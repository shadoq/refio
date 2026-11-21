package pl.jclab.refio.cli.acp

import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ProposedChange
import pl.jclab.refio.core.services.turn.ProposedChangeBuilder
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.turn.TurnJsonUtils
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One ACP session = one Refio task. */
class AcpSession(
    val id: String,
    val cwd: Path,
    @Volatile var mode: TaskMode,
    private val peer: AcpPeer,
) {
    @Volatile var turn: Deferred<TurnResult>? = null
    @Volatile var cancelRequested: Boolean = false

    /** Tool calls the IDE already knows about, so a later report updates them instead of adding a duplicate. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val diffs = ConcurrentHashMap<String, JsonObject>()
    private val diffBuilder = ProposedChangeBuilder(cwd)

    fun update(update: JsonObject) {
        peer.notify("session/update", buildJsonObject {
            put("sessionId", id)
            put("update", update)
        })
    }

    fun sendText(text: String) {
        update(buildJsonObject {
            put("sessionUpdate", "agent_message_chunk")
            put("content", AcpMappings.textBlock(text))
        })
    }

    /**
     * Reports a tool call the first time Refio mentions it (approval prompt or execution start);
     * returns the `ToolCall` body so a permission request can reuse it.
     */
    fun announceToolCall(
        toolCallId: String,
        toolName: String,
        arguments: Map<String, Any>,
        status: String,
        knownChange: ProposedChange? = null,
    ): JsonObject {
        val change = knownChange ?: diffBuilder.build(toolName, arguments)
        val diff = change?.let { AcpMappings.diffContent(it, cwd) }?.also { diffs[toolCallId] = it }
        val body = buildJsonObject {
            put("toolCallId", toolCallId)
            put("title", AcpMappings.toolTitle(toolName, arguments))
            put("kind", AcpMappings.toolKind(toolName))
            put("status", status)
            put("rawInput", rawInput(arguments))
            put("locations", buildJsonArray {
                AcpMappings.toolLocations(arguments, cwd).forEach { path -> add(buildJsonObject { put("path", path) }) }
            })
            if (diff != null) put("content", buildJsonArray { add(diff) })
        }
        if (announced.add(toolCallId)) {
            update(buildJsonObject {
                put("sessionUpdate", "tool_call")
                body.forEach { (k, v) -> put(k, v) }
            })
        } else {
            updateToolCall(toolCallId, status, null)
        }
        return body
    }

    fun updateToolCall(toolCallId: String, status: String, text: String?) {
        update(buildJsonObject {
            put("sessionUpdate", "tool_call_update")
            put("toolCallId", toolCallId)
            put("status", status)
            if (text != null) {
                put("content", buildJsonArray {
                    diffs[toolCallId]?.let { add(it) }
                    add(AcpMappings.textContent(AcpMappings.clip(text)))
                })
            }
        })
    }

    fun isAnnounced(toolCallId: String): Boolean = toolCallId in announced

    /** Bridges Refio's turn events to `session/update` notifications for this session. */
    fun listener(): TurnEventListener = object : TurnEventListener {
        override fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData) {
            if (isAnnounced(toolCall.id)) {
                updateToolCall(toolCall.id, "in_progress", null)
            } else {
                announceToolCall(toolCall.id, toolCall.name, TurnJsonUtils.parseJsonToMap(toolCall.arguments), "in_progress")
            }
        }

        override fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean) {
            // Some calls fail before they ever start (unknown tool, blocked by a rule).
            if (!isAnnounced(toolCall.id)) {
                announceToolCall(toolCall.id, toolCall.name, TurnJsonUtils.parseJsonToMap(toolCall.arguments), "in_progress")
            }
            updateToolCall(toolCall.id, if (success) "completed" else "failed", result)
        }

        override fun onTurnCompleted(taskId: String, result: TurnResult, runId: String, parentRunId: String?, depth: Int) {}
    }

    private fun rawInput(arguments: Map<String, Any>): JsonElement = TurnJsonUtils.parseMapToJsonElement(arguments)
}
