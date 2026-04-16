package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.session.AbstractToolCallLifecycleListener

/**
 * [AbstractToolCallLifecycleListener] variant that renders tool-call lifecycle
 * as [TuiChatMessage] entries in the TUI chat stream.
 *
 * Keeps TUI-specific concerns (role="tool" messages, `TOOL_CALL` message type,
 * `Running ...` placeholder copy, success/failure metadata) out of the
 * shared base class.
 */
class TuiToolCallListener(
    scope: CoroutineScope,
    private val messagesState: MutableStateFlow<List<TuiChatMessage>>,
    private val onToolStarted: (String) -> Unit,
    private val onReloadSubtasks: suspend () -> Unit,
) : AbstractToolCallLifecycleListener(scope) {

    override fun onCreateTempMessage(taskId: String, toolCall: ToolCallData): String {
        onToolStarted(toolCall.name)

        val tempId = "temp-${toolCall.id}"
        val argsSummary = try {
            val args = toolCall.arguments
            if (args.length <= 120) args else "${args.take(120)}..."
        } catch (_: Exception) { "" }

        messagesState.update { messages ->
            messages + TuiChatMessage(
                id = tempId,
                timestamp = System.currentTimeMillis(),
                role = "tool",
                content = "Running ${toolCall.name}...",
                messageType = TuiMessageType.TOOL_CALL,
                toolName = toolCall.name,
                isStreaming = true,
                metadata = mapOf("args" to argsSummary),
            )
        }
        return tempId
    }

    override fun onUpdateTempMessage(
        messageId: String,
        toolCallId: String,
        delta: String,
        accumulated: String,
    ) {
        messagesState.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(content = accumulated, isStreaming = true)
                } else msg
            }
        }
    }

    override fun onFinalizeTempMessage(
        messageId: String,
        toolCall: ToolCallData,
        result: String,
        success: Boolean,
    ) {
        val resultSummary = if (result.isNotBlank()) {
            val trimmed = result.trim()
            if (trimmed.length <= 200) trimmed else "${trimmed.take(200)}..."
        } else if (success) "Done" else "Failed"

        messagesState.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(
                        content = resultSummary,
                        isStreaming = false,
                        metadata = msg.metadata + ("success" to success),
                    )
                } else msg
            }
        }
    }

    override suspend fun onAfterToolLifecycleEvent(taskId: String) {
        onReloadSubtasks()
    }
}
