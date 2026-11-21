package pl.jclab.refio.cli.tui.state

import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.core.services.turn.TurnJsonUtils

/**
 * State of one tool call row in the transcript. [NO_RESULT]: the call has no stored result and is not running,
 * because it was never run (a call batched with a rejected one) or the turn stopped before it finished.
 */
enum class ToolRowStatus { RUNNING, DONE, FAILED, RECOVERED, NO_RESULT }

/** One tool call as the transcript shows it: the call and its result paired into one row. */
data class ToolCallRow(
    /** Tool call id when known, otherwise the message id; stable while the call runs and after reload. */
    val key: String,
    val toolName: String,
    /** The argument that says what the call worked on (a path, a command, a query); may be empty. */
    val argument: String,
    /** Full result text as stored; empty while the call runs. */
    val result: String,
    val status: ToolRowStatus,
    val durationMs: Long?,
    /** Characters generated so far while an editing tool streams its content, else null. */
    val streamedChars: Int?,
    val timestamp: Long,
    val agentName: String?,
    /** For a failed call the agent got past: name of the call that followed it. */
    val retriedAs: String? = null,
) {
    /** First non-blank line of the result: what a collapsed row shows. */
    val shortResult: String get() = result.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
}

/** What the chat selection can point at: a message or one tool row. */
sealed interface TranscriptUnit {
    val key: String
    data class Message(val message: TuiChatMessage) : TranscriptUnit { override val key: String get() = message.id }
    data class Tool(val row: ToolCallRow) : TranscriptUnit { override val key: String get() = row.key }
}

sealed interface TranscriptItem {
    data class Message(val message: TuiChatMessage) : TranscriptItem
    /** Tool calls that followed each other without any message in between, shown as one group. */
    data class Tools(val rows: List<ToolCallRow>) : TranscriptItem
}

/**
 * Turns the chat message list into what the transcript shows. A tool call and its result are paired by
 * tool call id, so a turn looks the same while it runs (one message per call, updated in place) and after
 * it is reloaded from the database (the call and the result are separate rows there).
 */
object TranscriptBuilder {

    /** Metadata keys of a tool call message; written by [toolCallMessage] and [toolResultMessage]. */
    const val META_ARGUMENT = "arg"
    const val META_SUCCESS = "success"
    const val META_DURATION_MS = "durationMs"

    private val PATH_KEYS = listOf("path", "file", "file_path", "filepath", "target_path", "target_file")
    private val OTHER_KEYS = listOf("command", "query", "pattern", "url", "directory", "dir_path", "name", "subagent", "task")
    private const val ARGUMENT_MAX = 200

    fun isToolMessage(msg: TuiChatMessage): Boolean = msg.messageType == TuiMessageType.TOOL_CALL && msg.role == "tool"

    /** Message for a tool call that has started; the same shape live and when read back from the database. */
    fun toolCallMessage(
        id: String,
        toolCallId: String,
        toolName: String,
        argumentsJson: String,
        timestamp: Long,
        running: Boolean,
        agentName: String? = null,
    ) = TuiChatMessage(
        id = id,
        timestamp = timestamp,
        role = "tool",
        content = "",
        agentName = agentName,
        isStreaming = running,
        messageType = TuiMessageType.TOOL_CALL,
        toolName = toolName,
        toolCallId = toolCallId,
        metadata = mapOf(META_ARGUMENT to mainArgument(argumentsJson)),
    )

    /** Message for a stored tool result that did not come with its call (the call is a separate row). */
    fun toolResultMessage(id: String, toolCallId: String?, result: String, timestamp: Long, agentName: String? = null) = TuiChatMessage(
        id = id,
        timestamp = timestamp,
        role = "tool",
        content = result.trim(),
        agentName = agentName,
        messageType = TuiMessageType.TOOL_CALL,
        toolCallId = toolCallId,
        metadata = mapOf(META_SUCCESS to !isErrorResult(result)),
    )

    /** Tool errors are results that start with "Error"; the plugin reads stored results the same way. */
    fun isErrorResult(content: String): Boolean {
        val lower = content.trim().lowercase()
        return lower == "error" || lower.startsWith("error:") || lower.startsWith("error ")
    }

    /** The argument that tells what a call worked on: a path first, then a command, query or similar. */
    fun mainArgument(argumentsJson: String): String {
        val args = TurnJsonUtils.parseJsonToMap(argumentsJson)
        if (args.isEmpty()) return ""
        val value = firstOf(args, PATH_KEYS)
            ?: firstOf(args, OTHER_KEYS)
            ?: args.values.asSequence().mapNotNull { nestedPath(it) }.firstOrNull()
            ?: args.values.firstOrNull { it is String && it.isNotBlank() }?.toString()
            ?: ""
        val oneLine = value.lineSequence().firstOrNull()?.trim() ?: ""
        return if (oneLine.length <= ARGUMENT_MAX) oneLine else oneLine.take(ARGUMENT_MAX)
    }

    private fun firstOf(args: Map<String, Any>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> (args[key] as? String)?.takeIf { it.isNotBlank() } }

    /** A path inside a list of objects, as in a batch edit: `edits=[{path=...}]`. */
    private fun nestedPath(value: Any): String? {
        val list = value as? List<*> ?: return null
        return list.firstNotNullOfOrNull { item ->
            @Suppress("UNCHECKED_CAST")
            (item as? Map<String, Any>)?.let { firstOf(it, PATH_KEYS) }
        }
    }

    /** Messages the transcript shows under the agent filter (user and system messages always stay). */
    fun visibleMessages(messages: List<TuiChatMessage>, agentFilter: String?): List<TuiChatMessage> =
        if (agentFilter == null) messages
        else messages.filter { it.agentName == agentFilter || it.role == "user" || it.role == "system" }

    fun build(messages: List<TuiChatMessage>): List<TranscriptItem> {
        val rows = LinkedHashMap<String, ToolCallRow>()
        // Position of each paired row in the output, so a result fills the row its call opened.
        val items = mutableListOf<Any>() // TuiChatMessage or the key of a tool row
        for (msg in messages) {
            if (!isToolMessage(msg)) {
                items += msg
                continue
            }
            val key = msg.toolCallId ?: msg.id
            val existing = rows[key]
            if (existing == null) {
                rows[key] = rowOf(msg)
                items += key
            } else {
                rows[key] = merge(existing, msg)
            }
        }

        val result = mutableListOf<TranscriptItem>()
        var group = mutableListOf<ToolCallRow>()
        fun closeGroup() {
            if (group.isNotEmpty()) {
                result += TranscriptItem.Tools(markRecovered(group))
                group = mutableListOf()
            }
        }
        for (item in items) {
            if (item is TuiChatMessage) {
                closeGroup()
                result += TranscriptItem.Message(item)
            } else {
                val row = rows.getValue(item as String)
                if (group.isNotEmpty() && group.last().agentName != row.agentName) closeGroup()
                group += row
            }
        }
        closeGroup()
        return result
    }

    /** What the selection moves over: every message and every tool row, top to bottom. */
    fun units(items: List<TranscriptItem>): List<TranscriptUnit> = items.flatMap { item ->
        when (item) {
            is TranscriptItem.Message -> listOf(TranscriptUnit.Message(item.message))
            is TranscriptItem.Tools -> item.rows.map { TranscriptUnit.Tool(it) }
        }
    }

    private fun rowOf(msg: TuiChatMessage): ToolCallRow = ToolCallRow(
        key = msg.toolCallId ?: msg.id,
        toolName = msg.toolName ?: "tool",
        argument = TuiRenderBuffer.cleanText(msg.metadata[META_ARGUMENT] as? String ?: ""),
        result = if (msg.isToolStreaming) "" else TuiRenderBuffer.cleanText(msg.content.trim()),
        status = statusOf(msg),
        durationMs = (msg.metadata[META_DURATION_MS] as? Number)?.toLong(),
        streamedChars = if (msg.isToolStreaming) msg.content.length else null,
        timestamp = msg.timestamp,
        agentName = msg.agentName,
    )

    /** A later message with the same call id carries the result (database) or a newer state (live). */
    private fun merge(row: ToolCallRow, msg: TuiChatMessage): ToolCallRow {
        val update = rowOf(msg)
        return row.copy(
            toolName = msg.toolName ?: row.toolName,
            argument = update.argument.ifEmpty { row.argument },
            result = update.result.ifEmpty { row.result },
            status = if (msg.metadata.containsKey(META_SUCCESS)) update.status else row.status,
            durationMs = update.durationMs ?: row.durationMs,
            streamedChars = update.streamedChars,
        )
    }

    private fun statusOf(msg: TuiChatMessage): ToolRowStatus = when (msg.metadata[META_SUCCESS]) {
        true -> ToolRowStatus.DONE
        false -> ToolRowStatus.FAILED
        // Messages read back from the database are never running: they are loaded after a turn or round ends.
        else -> if (msg.isStreaming) ToolRowStatus.RUNNING else ToolRowStatus.NO_RESULT
    }

    /**
     * A failed call followed in the same group by a successful retry (the same tool, or another tool on the same
     * argument) was got past: one dim line, not an error. Any other later success does not hide the failure.
     */
    private fun markRecovered(rows: List<ToolCallRow>): List<ToolCallRow> = rows.mapIndexed { i, row ->
        if (row.status != ToolRowStatus.FAILED) return@mapIndexed row
        val retry = rows.subList(i + 1, rows.size).firstOrNull { later ->
            later.status == ToolRowStatus.DONE && (later.toolName == row.toolName || (row.argument.isNotBlank() && later.argument == row.argument))
        }
        if (retry == null) row else row.copy(status = ToolRowStatus.RECOVERED, retriedAs = retry.toolName)
    }
}
