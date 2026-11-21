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
 * Each call is one message in the shape [TranscriptBuilder.toolCallMessage] defines (the same shape the
 * database reload builds), updated in place when the result arrives. How long each call took is recorded
 * in [toolDurations] by call id, so the times survive the reload at the end of the turn.
 */
class TuiToolCallListener(
    scope: CoroutineScope,
    private val messagesState: MutableStateFlow<List<TuiChatMessage>>,
    private val onToolStarted: (String) -> Unit,
    private val onReloadSubtasks: suspend () -> Unit,
    private val toolDurations: MutableMap<String, Long> = mutableMapOf(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AbstractToolCallLifecycleListener(scope) {

    override fun onCreateTempMessage(taskId: String, toolCall: ToolCallData): String {
        onToolStarted(toolCall.name)

        val tempId = "temp-${toolCall.id}"
        messagesState.update { messages ->
            messages + TranscriptBuilder.toolCallMessage(
                id = tempId,
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                argumentsJson = toolCall.arguments,
                timestamp = clock(),
                running = true,
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
                    msg.copy(content = accumulated, isStreaming = true, isToolStreaming = true)
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
        val now = clock()
        messagesState.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    val durationMs = (now - msg.timestamp).coerceAtLeast(0)
                    toolDurations[toolCall.id] = durationMs
                    msg.copy(
                        content = result.trim(),
                        isStreaming = false,
                        isToolStreaming = false,
                        metadata = msg.metadata + mapOf(
                            TranscriptBuilder.META_SUCCESS to success,
                            TranscriptBuilder.META_DURATION_MS to durationMs,
                        ),
                    )
                } else msg
            }
        }
    }

    override suspend fun onAfterToolLifecycleEvent(taskId: String) {
        onReloadSubtasks()
    }
}
