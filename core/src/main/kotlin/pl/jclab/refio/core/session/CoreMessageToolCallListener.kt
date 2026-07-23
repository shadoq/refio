package pl.jclab.refio.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallResult
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.core.db.ToolCallData
import java.util.concurrent.ConcurrentHashMap

/**
 * [AbstractToolCallLifecycleListener] implementation backed by [SessionStateManager].
 *
 * Used by [CoreSessionService] (and any other caller that renders tool-call
 * progress through the canonical [Message] stream) to create / update / finalize
 * temporary assistant messages per tool invocation.
 */
class CoreMessageToolCallListener(
    scope: CoroutineScope,
    private val stateManager: SessionStateManager,
    private val onReloadSubtasks: suspend () -> Unit,
    private val onReloadMessages: suspend () -> Unit = {},
    private val resolveToolDisplayType: (String) -> ToolDisplayType,
    private val parseToolParameters: (String) -> Map<String, String>,
) : AbstractToolCallLifecycleListener(scope) {

    private val scopeRef = scope

    /**
     * Length of the text last pushed into the message list, per streaming tool message.
     *
     * Adapters emit a steady stream of deltas whose accumulated text has not grown (empty deltas).
     * Pushing those would launch a coroutine, take the messages mutex and rebuild the whole list only
     * to produce an identical result that the StateFlow then discards as equal. Skipping them cannot
     * lose the final frame: the accumulated text only ever grows by appending, so an unchanged length
     * means unchanged content - there is nothing left to render.
     */
    private val lastPushedLength = ConcurrentHashMap<String, Int>()

    override fun onCreateTempMessage(taskId: String, toolCall: ToolCallData): String {
        val tempId = "temp-${toolCall.id}"
        val toolInfo = ToolCallDisplayInfo(
            toolName = toolCall.name,
            toolCallId = toolCall.id,
            displayType = resolveToolDisplayType(toolCall.name),
            parameters = parseToolParameters(toolCall.arguments),
            status = ToolCallStatus.EXECUTING,
        )
        val tempMessage = Message(
            id = tempId,
            taskId = taskId,
            role = "assistant",
            content = "",
            toolCallInfo = toolInfo,
            createdAt = System.currentTimeMillis(),
            // Live from creation, not from the first delta. Reconciliation on a mid-turn reload keeps
            // an in-memory message only while it is streaming; a tool bubble that is still marked
            // non-streaming is dropped from the list. Every later delta then maps over a list that no
            // longer holds this id, producing an unchanged list, so the StateFlow (which compares by
            // equality) stops emitting entirely - the bubble never streams and the char counter never
            // moves, while the persisted display row shows "Generating..." with empty content.
            // onFinalizeTempMessage clears both flags when the call ends.
            isStreaming = true,
            isToolStreaming = true,
        )
        scopeRef.launch { stateManager.appendMessage(tempMessage) }
        return tempId
    }

    override fun onUpdateTempMessage(
        messageId: String,
        toolCallId: String,
        delta: String,
        accumulated: String,
    ) {
        if (lastPushedLength.put(messageId, accumulated.length) == accumulated.length) return

        scopeRef.launch {
            stateManager.updateMessages { messages ->
                messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            content = accumulated,
                            isStreaming = true,
                            isToolStreaming = true,
                            lastChunkAt = System.currentTimeMillis(),
                        )
                    } else msg
                }
            }
        }
    }

    override fun onFinalizeTempMessage(
        messageId: String,
        toolCall: ToolCallData,
        result: String,
        success: Boolean,
    ) {
        lastPushedLength.remove(messageId)

        val resultSummary = if (result.isNotBlank()) {
            val trimmed = result.trim()
            if (trimmed.length <= 120) trimmed else "${trimmed.take(120)}..."
        } else null

        scopeRef.launch {
            stateManager.updateMessages { messages ->
                messages.map { msg ->
                    if (msg.id == messageId) {
                        val updatedToolInfo = msg.toolCallInfo?.copy(
                            status = if (success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                            result = if (resultSummary != null) ToolCallResult(
                                success = success,
                                summary = resultSummary,
                            ) else null,
                        )
                        msg.copy(
                            toolCallInfo = updatedToolInfo,
                            isStreaming = false,
                            isToolStreaming = false,
                            lastChunkAt = System.currentTimeMillis(),
                        )
                    } else msg
                }
            }
        }
    }

    override suspend fun onAfterToolLifecycleEvent(taskId: String) {
        onReloadSubtasks()
        // Refresh chat messages so ChatView footer (token counts, generation time)
        // updates after each AgentTurnLoop iteration, not only at turn end.
        onReloadMessages()
    }
}
