package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.models.api.SummarizeResponse
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ChatRouter")

/**
 * Router for chat-related operations.
 * Handles conversation management, message streaming, and conversation summarization.
 *
 * This router is responsible for:
 * - Sending chat messages (streaming and non-streaming)
 * - Retrieving conversation history
 * - Managing message lifecycle (delete, clear)
 * - Generating conversation summaries
 *
 * @property chatService Chat conversation management service
 * @property chatMessageRepository Chat message storage repository
 * @property taskRepository Task management repository (for validation)
 */
class ChatRouter(
    private val chatService: ChatService,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[ChatRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[ChatRouter] Shutting down" }
    }

    // ===== Chat Operations =====

    /**
     * Send chat message and get LLM response (RFC 0032: unified streaming/non-streaming).
     *
     * This endpoint handles conversational interactions with LLM providers.
     * Must be called with an existing task in CHAT mode.
     *
     * @param request Chat request with task ID, input message, and parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Chat response with assistant message and metadata
     * @throws IllegalArgumentException If task not found or mode is not CHAT
     * @throws Exception On LLM API errors
     */
    suspend fun chat(
        request: ChatRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ChatResponse {
        logger.info { "[ChatRouter] chat: taskId=${request.taskId}, stream=$stream" }
        return chatService.chat(request, stream, onChunk)
    }

    /**
     * Generate a conversation summary (new system message) for the given task.
     * Reduces context size by 50-70% while preserving essential information.
     *
     * @param taskId Task ID to summarize
     * @return Summary response with generated summary text
     * @throws IllegalArgumentException If task not found
     */
    suspend fun summarizeConversation(
        taskId: String,
        streamCallback: StreamCallback? = null
    ): SummarizeResponse {
        logger.info { "[ChatRouter] summarizeConversation: taskId=$taskId" }
        return chatService.summarizeConversation(taskId, streamCallback)
    }

    /**
     * Generate a short session title for the given user message.
     */
    suspend fun generateSessionTitle(taskId: String, userMessage: String): String {
        logger.info { "[ChatRouter] generateSessionTitle: taskId=$taskId" }
        return chatService.generateSessionTitle(taskId, userMessage)
    }

    /**
     * Get all messages for a task.
     *
     * @param taskId Task ID to get messages for
     * @return List of messages ordered by creation time
     */
    fun getMessages(taskId: String): GetMessagesResponse {
        logger.info { "[ChatRouter] Getting messages for task: taskId=$taskId" }

        val messages = chatMessageRepository.findByTaskId(taskId)

        return GetMessagesResponse(
            messages = messages.map { msg ->
                MessageResponse(
                    id = msg.id,
                    taskId = msg.taskId,
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    thinking = msg.thinking,  // Reasoning process (gpt-oss, Claude)
                    metadata = msg.metadata,
                    toolCallsJson = pl.jclab.refio.core.db.ToolCallData.toJsonList(msg.toolCalls),
                    toolCallId = msg.toolCallId,
                    tokensIn = msg.tokensIn,
                    tokensOut = msg.tokensOut,
                    cost = msg.cost,
                    createdAt = msg.createdAt,
                    isSummarized = msg.isSummarized,
                    rawOutput = msg.rawOutput
                )
            },
            count = messages.size
        )
    }

    /**
     * Delete a single chat message.
     *
     * @param messageId Message ID to delete
     * @return true if deleted successfully, false if message not found
     */
    fun deleteMessage(messageId: String): Boolean {
        logger.info { "[ChatRouter] Deleting message: messageId=$messageId" }
        return chatMessageRepository.delete(messageId)
    }

    /**
     * Clear all messages for a task.
     * Deletes all messages in the conversation history.
     *
     * @param taskId Task ID to clear history for
     * @return true if all messages deleted successfully
     */
    fun clearHistory(taskId: String): Boolean {
        logger.info { "[ChatRouter] Clearing history for task: taskId=$taskId" }
        val messages = chatMessageRepository.findByTaskId(taskId)
        return messages.all { chatMessageRepository.delete(it.id) }
    }

    /**
     * Truncate conversation history starting from the given message (inclusive).
     * Intended for "rewind conversation" UX: remove a tail of history and continue from earlier context.
     *
     * @throws IllegalArgumentException If message is not found or does not belong to task
     */
    fun truncateHistoryFromMessage(taskId: String, fromMessageId: String): TruncateHistoryResponse {
        logger.info { "[ChatRouter] Truncating history from message: taskId=$taskId, fromMessageId=$fromMessageId" }
        val deletedCount = chatMessageRepository.deleteFromMessageInclusive(taskId, fromMessageId)
        return TruncateHistoryResponse(
            taskId = taskId,
            fromMessageId = fromMessageId,
            deletedCount = deletedCount
        )
    }
}

data class TruncateHistoryResponse(
    val taskId: String,
    val fromMessageId: String,
    val deletedCount: Int
)
