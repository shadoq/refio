package pl.jclab.refio.cli.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("AcpAgent")

/**
 * Refio's side of the Agent Client Protocol (v1): turns IDE requests into Refio sessions and turns.
 *
 * Supported: `initialize`, `authenticate` (no-op, the agent is local), `session/new`,
 * `session/set_mode`, `session/prompt`, `session/cancel`. Everything else answers "method not
 * found", which the protocol treats as an unsupported optional feature.
 */
class AcpAgent(
    private val peer: AcpPeer,
    private val backend: AcpBackend,
    private val startMode: TaskMode,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, AcpSession>()
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bridgeJob: Job? = null

    fun install() {
        peer.requestHandler = { method, params -> handleRequest(method, params) }
        peer.notificationHandler = { method, params -> handleNotification(method, params) }
    }

    fun shutdown() {
        bridgeJob?.cancel()
        sessions.values.forEach { it.turn?.cancel() }
    }

    private suspend fun handleRequest(method: String, params: JsonObject): JsonElement {
        logger.info { "[ACP] <- $method" }
        return when (method) {
            "initialize" -> initialize(params)
            "authenticate" -> JsonObject(emptyMap())
            "session/new" -> newSession(params)
            "session/set_mode" -> setMode(params)
            "session/prompt" -> prompt(params)
            else -> throw AcpError(AcpError.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleNotification(method: String, params: JsonObject) {
        logger.info { "[ACP] <- $method (notification)" }
        if (method == "session/cancel") {
            val session = sessions[params.str("sessionId")] ?: return
            session.cancelRequested = true
            session.turn?.cancel()
        }
    }

    private fun initialize(params: JsonObject): JsonElement {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.intOrNull
        logger.info { "[ACP] client protocolVersion=$requested" }
        return buildJsonObject {
            // The client's version when we support it, otherwise the newest we do; the client
            // disconnects if it cannot speak that.
            put("protocolVersion", AcpMappings.PROTOCOL_VERSION)
            put("agentCapabilities", buildJsonObject {
                put("loadSession", false)
                put("promptCapabilities", buildJsonObject {
                    put("image", false)
                    put("audio", false)
                    put("embeddedContext", true)
                })
                put("mcpCapabilities", buildJsonObject {
                    put("http", false)
                    put("sse", false)
                })
            })
            put("authMethods", buildJsonArray {})
            put("agentInfo", buildJsonObject {
                put("name", "refio")
                put("title", "Refio")
                put("version", AcpAgent::class.java.`package`?.implementationVersion ?: "dev")
            })
        }
    }

    private suspend fun newSession(params: JsonObject): JsonElement {
        val cwdText = params.str("cwd") ?: throw AcpError(AcpError.INVALID_PARAMS, "cwd is required")
        val cwd = try {
            Paths.get(cwdText)
        } catch (e: Exception) {
            throw AcpError(AcpError.INVALID_PARAMS, "cwd is not a valid path: $cwdText")
        }
        if (!cwd.isAbsolute) throw AcpError(AcpError.INVALID_PARAMS, "cwd must be an absolute path")
        val root: Path = cwd.normalize()

        val mcpServers = AcpMappings.mcpServers(params["mcpServers"] as? JsonArray)
        if (!backend.open(root, mcpServers)) {
            throw AcpError(
                AcpError.INVALID_PARAMS,
                "This Refio process already serves another project; start a separate agent for $root"
            )
        }
        if (bridgeJob == null) {
            bridgeJob = AcpPermissionBridge(peer, backend.approvals, ::sessionForTask, scope).start()
        }

        val taskId = backend.createTask(startMode)
        sessions[taskId] = AcpSession(taskId, root, startMode, peer)
        logger.info { "[ACP] session $taskId in $root (mcp=${mcpServers.size})" }
        return buildJsonObject {
            put("sessionId", taskId)
            put("modes", AcpMappings.modeState(startMode))
        }
    }

    private fun setMode(params: JsonObject): JsonElement {
        val session = sessionOf(params)
        val mode = AcpMappings.modeFor(params.str("modeId") ?: "")
            ?: throw AcpError(AcpError.INVALID_PARAMS, "Unknown mode: ${params.str("modeId")}")
        session.mode = mode.taskMode
        session.update(buildJsonObject {
            put("sessionUpdate", "current_mode_update")
            put("currentModeId", mode.id)
        })
        return JsonObject(emptyMap())
    }

    private suspend fun prompt(params: JsonObject): JsonElement {
        val session = sessionOf(params)
        if (session.turn?.isActive == true) {
            throw AcpError(AcpError.INVALID_REQUEST, "A prompt is already running in this session")
        }
        val input = AcpMappings.promptToTurnInput(params["prompt"] as? JsonArray ?: JsonArray(emptyList()), session.cwd)
        input.skipped.forEach { logger.warn { "[ACP] prompt: skipped $it" } }
        if (input.text.isBlank() && input.contextRefs.isEmpty()) {
            throw AcpError(AcpError.INVALID_PARAMS, "The prompt is empty")
        }

        session.cancelRequested = false
        val turn = turnScope.async {
            backend.runTurn(session.id, session.mode, input.text, input.contextRefs, session.listener())
        }
        session.turn = turn

        val result = try {
            turn.await()
        } catch (e: CancellationException) {
            if (!session.cancelRequested) throw e
            null
        } catch (e: Exception) {
            if (!session.cancelRequested) {
                backend.markFinished(session.id, null)
                throw AcpError(AcpError.INTERNAL_ERROR, "The turn failed: ${e.message ?: e::class.simpleName}")
            }
            null
        }

        backend.markFinished(session.id, result)
        val stopReason = AcpMappings.stopReason(result, session.cancelRequested)
        if (result != null && stopReason != "cancelled") {
            val text = result.response.ifBlank {
                if (result.success) "" else "The turn ended without a result (${result.stopReason.name.lowercase()})."
            }
            if (text.isNotBlank()) session.sendText(text)
        }
        logger.info { "[ACP] session ${session.id} turn done: $stopReason" }
        return buildJsonObject { put("stopReason", stopReason) }
    }

    private fun sessionOf(params: JsonObject): AcpSession {
        val id = params.str("sessionId") ?: throw AcpError(AcpError.INVALID_PARAMS, "sessionId is required")
        return sessions[id] ?: throw AcpError(AcpError.INVALID_PARAMS, "Unknown session: $id")
    }

    /**
     * Approval requests carry the task id of the turn that raised them. A subagent may run under
     * its own task id; then the single session with a running turn is the one the user is watching.
     */
    private fun sessionForTask(taskId: String): AcpSession? =
        sessions[taskId] ?: sessions.values.filter { it.turn?.isActive == true }.singleOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
