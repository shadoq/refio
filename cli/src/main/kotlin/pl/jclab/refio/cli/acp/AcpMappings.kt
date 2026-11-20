package pl.jclab.refio.cli.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.context.mcp.MCPEnvVariable
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ProposedChange
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure translations between Agent Client Protocol (v1) values and Refio's own types.
 * Kept free of I/O so each rule can be tested on its own.
 */
object AcpMappings {

    const val PROTOCOL_VERSION = 1

    // ---- Session modes ----

    data class Mode(val id: String, val name: String, val description: String, val taskMode: TaskMode)

    val MODES = listOf(
        Mode("agent", "Agent", "Reads, edits files and runs commands", TaskMode.AGENT),
        Mode("plan", "Plan", "Read-only: investigates and proposes, never writes", TaskMode.PLAN),
        Mode("chat", "Chat", "Conversation only, no tools", TaskMode.CHAT),
    )

    fun modeFor(id: String): Mode? = MODES.firstOrNull { it.id == id }

    fun modeOf(taskMode: TaskMode): Mode = MODES.first { it.taskMode == taskMode }

    fun modeState(current: TaskMode): JsonObject = buildJsonObject {
        put("currentModeId", modeOf(current).id)
        put("availableModes", buildJsonArray {
            MODES.forEach { mode ->
                add(buildJsonObject {
                    put("id", mode.id)
                    put("name", mode.name)
                    put("description", mode.description)
                })
            }
        })
    }

    // ---- Tool calls ----

    /** ACP `ToolKind`: lets the IDE pick an icon and group the call. Unknown and MCP tools are `other`. */
    fun toolKind(toolName: String): String = when (toolName) {
        "read_file", "read_directory", "view_diff" -> "read"
        "grep_search", "file_search", "find_usages", "rag_search", "code_intelligence" -> "search"
        "code_editing", "advance_code_editing", "multi_edit", "multi_line_editor", "create_new_file", "rename_symbol" -> "edit"
        "run_terminal_command", "run_code", "run_process_background", "monitor_process" -> "execute"
        "fetch_webpage", "web_search", "http_request" -> "fetch"
        "think", "tasks" -> "think"
        else -> "other"
    }

    private val TITLE_KEYS = listOf("path", "file_path", "directory", "query", "pattern", "command", "url")
    private val PATH_KEYS = listOf("path", "file_path", "directory", "target_file")

    /** Short human title, e.g. `read_file src/Main.kt`. */
    fun toolTitle(toolName: String, arguments: Map<String, Any>): String {
        val detail = TITLE_KEYS.firstNotNullOfOrNull { key -> (arguments[key] as? String)?.takeIf { it.isNotBlank() } }
            ?: return toolName
        val oneLine = detail.replace('\n', ' ')
        return "$toolName ${if (oneLine.length > MAX_TITLE_DETAIL) oneLine.take(MAX_TITLE_DETAIL) + "..." else oneLine}"
    }

    /** Absolute paths the call touches, so the IDE can follow the agent through the files. */
    fun toolLocations(arguments: Map<String, Any>, cwd: Path): List<String> =
        PATH_KEYS.mapNotNull { key -> (arguments[key] as? String)?.takeIf { it.isNotBlank() } }
            .mapNotNull { raw ->
                try {
                    val p = Paths.get(raw)
                    (if (p.isAbsolute) p else cwd.resolve(p)).normalize().toString()
                } catch (e: Exception) {
                    null
                }
            }
            .distinct()

    fun diffContent(change: ProposedChange, cwd: Path): JsonObject = buildJsonObject {
        put("type", "diff")
        val p = Paths.get(change.filePath)
        put("path", (if (p.isAbsolute) p else cwd.resolve(p)).normalize().toString())
        change.oldContent?.let { put("oldText", it) }
        put("newText", change.newContent ?: change.unifiedDiff)
    }

    fun textContent(text: String): JsonObject = buildJsonObject {
        put("type", "content")
        put("content", textBlock(text))
    }

    fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun clip(text: String, max: Int = MAX_TOOL_OUTPUT_CHARS): String =
        if (text.length <= max) text else text.take(max) + "\n... (${text.length - max} more characters)"

    // ---- Turn outcome ----

    /**
     * ACP `StopReason`. Refio has about 25 ways for a turn to end; ACP has five. Anything that is
     * not a cancel, the iteration cap or a truncated output is an ordinary end of turn - the reason
     * itself reaches the user as the turn's final text.
     */
    fun stopReason(result: TurnResult?, cancelRequested: Boolean): String = when {
        cancelRequested || result == null -> "cancelled"
        result.stopReason == TurnStopReason.CANCELLED -> "cancelled"
        result.stopReason == TurnStopReason.MAX_ITERATIONS -> "max_turn_requests"
        result.stopReason == TurnStopReason.OUTPUT_TRUNCATED -> "max_tokens"
        else -> "end_turn"
    }

    // ---- Prompt ----

    data class TurnInput(
        val text: String,
        val contextRefs: List<ContextReference>,
        /** What was left out and why, for the log. */
        val skipped: List<String>,
    )

    /**
     * Turns ACP prompt blocks into Refio's turn input.
     *
     * A `resource_link` to a file becomes a context reference only when it lies inside [cwd]: the
     * project root is the sandbox boundary, and a link must not let the IDE widen it.
     */
    fun promptToTurnInput(blocks: JsonArray, cwd: Path): TurnInput {
        val root = cwd.toAbsolutePath().normalize()
        val text = StringBuilder()
        val refs = mutableListOf<ContextReference>()
        val skipped = mutableListOf<String>()

        fun appendPart(part: String) {
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(part)
        }

        for (element in blocks) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.let { appendPart(it) }
                "resource_link" -> {
                    val uri = block.str("uri") ?: continue
                    val path = fileUriToPath(uri)
                    when {
                        path == null -> appendPart("[Referenced resource: ${block.str("name") ?: uri} <$uri>]")
                        path.startsWith(root) -> refs += ContextReference.file(path.toString(), block.str("name"))
                        else -> skipped += "resource_link outside project: $uri"
                    }
                }
                "resource" -> {
                    val resource = block["resource"] as? JsonObject ?: continue
                    val uri = resource.str("uri") ?: "resource"
                    val body = resource.str("text")
                    if (body == null) {
                        skipped += "binary resource: $uri"
                    } else {
                        appendPart("Context from $uri:\n```\n$body\n```")
                    }
                }
                else -> skipped += "unsupported content block: ${block.str("type")}"
            }
        }
        return TurnInput(text.toString(), refs, skipped)
    }

    private fun fileUriToPath(uri: String): Path? = try {
        val parsed = URI(uri)
        if (parsed.scheme.equals("file", ignoreCase = true)) Paths.get(parsed).toAbsolutePath().normalize() else null
    } catch (e: Exception) {
        null
    }

    // ---- MCP servers handed over by the IDE ----

    /** Stdio servers only: the agent advertises no HTTP/SSE MCP capability, so the IDE sends none. */
    fun mcpServers(servers: JsonArray?): List<MCPServerConfig> =
        servers.orEmpty().mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            if (server.str("type") != null) return@mapNotNull null
            val name = server.str("name") ?: return@mapNotNull null
            val command = server.str("command") ?: return@mapNotNull null
            MCPServerConfig(
                id = "acp-" + name.lowercase().replace(Regex("[^a-z0-9_-]"), "-"),
                displayName = name,
                type = MCPServerType.STDIO,
                command = command,
                args = (server["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                env = (server["env"] as? JsonArray).orEmpty().mapNotNull { env ->
                    val obj = env as? JsonObject ?: return@mapNotNull null
                    val key = obj.str("name") ?: return@mapNotNull null
                    MCPEnvVariable(key, obj.str("value") ?: "", isSecret = true)
                },
            )
        }

    // ---- Permissions ----

    const val ALLOW_ONCE = "allow_once"
    const val ALLOW_ALWAYS = "allow_always"
    const val REJECT_ONCE = "reject_once"
    const val REJECT_ALWAYS = "reject_always"

    fun permissionOptions(toolName: String): JsonArray = buildJsonArray {
        fun option(id: String, name: String) = add(buildJsonObject {
            put("optionId", id)
            put("name", name)
            put("kind", id)
        })
        option(ALLOW_ONCE, "Allow once")
        option(ALLOW_ALWAYS, "Always allow $toolName in this session")
        option(REJECT_ONCE, "Reject this call")
        option(REJECT_ALWAYS, "Reject and stop")
    }

    /**
     * Maps the IDE's answer to Refio's decision.
     *
     * `reject_once` lets the turn go on: the model hears "not that way" and picks another route.
     * `reject_always` ends the turn, since Refio has no session-wide "always deny" rule; a
     * cancelled or unreadable answer ends it as well.
     */
    fun permissionDecision(response: JsonElement?, toolName: String): ApprovalDecision {
        val outcome = (response as? JsonObject)?.get("outcome") as? JsonObject
            ?: return ApprovalDecision.Rejected("No answer from the IDE")
        if (outcome.str("outcome") != "selected") return ApprovalDecision.Rejected(null)
        return when (outcome.str("optionId")) {
            ALLOW_ONCE -> ApprovalDecision.Approved
            ALLOW_ALWAYS -> ApprovalDecision.Trusted(toolName, null)
            REJECT_ONCE -> ApprovalDecision.NotPermitted("The user rejected this call in the IDE")
            else -> ApprovalDecision.Rejected("The user rejected this call in the IDE")
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val MAX_TITLE_DETAIL = 80
    const val MAX_TOOL_OUTPUT_CHARS = 4000
}
