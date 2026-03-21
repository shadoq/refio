package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo

/**
 * Handles turn completion and cleanup.
 *
 * Responsibilities:
 * - Persist assistant message if needed
 * - Update metrics to idle state
 * - Notify listeners of completion
 */
class TurnFinalizer(
    private val chatMessageRepository: ChatMessageRepository
) {
    /**
     * Complete a turn with optional message persistence and listener notification.
     *
     * @param listener Optional listener with onTurnCompleted method
     */
    fun completeTurn(
        taskId: String,
        result: TurnResult,
        listener: TurnCompletionListener?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        persistAssistantMessage: Boolean,
        metadata: String? = null
    ): TurnResult {
        if (persistAssistantMessage) {
            val content = result.response.ifBlank {
                if (result.success) "Task completed." else "Task finished without a final response."
            }
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = content,
                metadata = metadata
            )
        }

        GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
        listener?.onTurnCompleted(taskId, result, runId, parentRunId, depth)
        return result
    }
}

/**
 * Minimal interface for turn completion notifications.
 */
interface TurnCompletionListener {
    fun onTurnCompleted(
        taskId: String,
        result: TurnResult,
        runId: String,
        parentRunId: String?,
        depth: Int
    ) {}
}
