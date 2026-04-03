package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("PendingUserMessageQueue")

/**
 * Queues user messages sent while the agent is running.
 *
 * Messages are saved to ChatMessageRepository with "mid_execution_input" metadata.
 * TurnPromptBuilder reads the full conversation history, so these messages
 * automatically appear in the next iteration's prompt.
 */
class PendingUserMessageQueue(
    private val chatMessageRepository: ChatMessageRepository
) {
    companion object {
        const val META_TYPE = "mid_execution_input"
    }

    /** Tracks which tasks have pending mid-execution messages not yet consumed by the turn loop. */
    private val pendingTaskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Called by UI when user sends a message while the agent is running.
     * Saves the message to DB — it will be picked up by TurnPromptBuilder
     * in the next iteration.
     */
    fun enqueue(taskId: String, message: String) {
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.USER,
            content = message,
            metadata = """{"type":"$META_TYPE","timestamp":${System.currentTimeMillis()}}"""
        )
        pendingTaskIds.add(taskId)
        logger.info { "[MID_EXEC_INPUT] Queued user message for taskId=$taskId (${message.length} chars)" }
    }

    /**
     * Called by AgentTurnLoop between iterations to check if new user messages arrived.
     * Returns true (and clears the flag) if at least one message was enqueued since the last check.
     */
    fun consumePending(taskId: String): Boolean {
        return pendingTaskIds.remove(taskId)
    }
}
