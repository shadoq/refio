package pl.jclab.refio.core.services.orchestration

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("UserInteraction")

/**
 * User Interaction - manages user questions during execution.
 *
 * Allows orchestrator to:
 * - Ask user questions
 * - Wait for responses
 * - Resume execution with answer
 */
class UserInteraction(
    private val chatMessageRepository: ChatMessageRepository
) {

    private val awaitingResponse = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // Public state for UI to detect waiting state
    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse

    private val _currentQuestionId = MutableStateFlow<String?>(null)
    val currentQuestionId: StateFlow<String?> = _currentQuestionId

    /**
     * Ask user a question and pause execution.
     *
     * @param taskId Task ID
     * @param question Question text
     * @param options Optional list of choices
     * @return Question ID for tracking
     */
    suspend fun askQuestion(
        taskId: String,
        question: String,
        options: List<String>? = null
    ): String {
        logger.info { "[USER_INTERACTION] Asking question: $question" }

        val questionId = UUID.randomUUID().toString()

        // Format question with options
        val questionText = if (options != null && options.isNotEmpty()) {
            buildString {
                append("❓ **Question:**\n\n")
                append(question)
                append("\n\n**Options:**\n")
                options.forEachIndexed { index, option ->
                    append("${('A' + index)}. $option\n\n")
                }
            }
        } else {
            "❓ **Question:**\n\n$question\n\n**Question ID:** `$questionId`"
        }

        // Save question to chat
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.ASSISTANT,
            content = questionText,
            metadata = gson.toJson(mapOf(
                "type" to "orchestrator_question",
                "question_id" to questionId,
                "awaiting_response" to true,
                "options" to (options ?: emptyList())
            ))
        )

        // Create deferred for waiting
        val deferred = CompletableDeferred<String>()
        awaitingResponse[questionId] = deferred

        // Update state for UI
        _isWaitingForResponse.value = true
        _currentQuestionId.value = questionId

        return questionId
    }

    /**
     * Wait for user response to question.
     *
     * Suspends until user provides answer.
     */
    suspend fun waitForResponse(questionId: String): String {
        logger.info { "[USER_INTERACTION] Waiting for response to question: $questionId" }

        val deferred = awaitingResponse[questionId]
            ?: throw IllegalArgumentException("Question not found: $questionId")

        return deferred.await()
    }

    /**
     * Provide user response to question.
     *
     * Called by SessionManager when user responds.
     */
    fun provideResponse(questionId: String, response: String) {
        logger.info { "[USER_INTERACTION] Received response for question: $questionId" }

        val deferred = awaitingResponse[questionId]
            ?: throw IllegalArgumentException("Question not found: $questionId")

        deferred.complete(response)
        awaitingResponse.remove(questionId)

        // Update state for UI (clear waiting state)
        _isWaitingForResponse.value = awaitingResponse.isNotEmpty()
        _currentQuestionId.value = awaitingResponse.keys.firstOrNull()
    }

    /**
     * Cancel question (execution aborted).
     */
    fun cancelQuestion(questionId: String) {
        logger.info { "[USER_INTERACTION] Cancelling question: $questionId" }

        val deferred = awaitingResponse[questionId]
        deferred?.cancel()
        awaitingResponse.remove(questionId)

        // Update state for UI
        _isWaitingForResponse.value = awaitingResponse.isNotEmpty()
        _currentQuestionId.value = awaitingResponse.keys.firstOrNull()
    }

    /**
     * Check if there are any pending questions waiting for response.
     */
    fun hasPendingQuestions(): Boolean {
        return awaitingResponse.isNotEmpty()
    }

    /**
     * Get all pending question IDs.
     */
    fun getPendingQuestionIds(): List<String> {
        return awaitingResponse.keys.toList()
    }
}
