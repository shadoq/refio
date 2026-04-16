package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("UserQuestionService")

/**
 * Suspends the agent loop while waiting for a user answer to a question.
 *
 * Usage:
 * 1. Tool calls ask(question) -> gets back a requestId
 * 2. Service emits AskUserRequest to listener (UI shows the question)
 * 3. UI calls resolve(requestId, answer) when user responds
 * 4. ask() returns the answer
 */
class UserQuestionService(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }

    data class AskUserRequest(
        val requestId: String,
        val taskId: String,
        val question: String,
        val options: List<String>?
    )

    interface Listener {
        fun onAskUserRequest(request: AskUserRequest)
    }

    private data class PendingEntry(
        val request: AskUserRequest,
        val deferred: CompletableDeferred<String>
    )

    private val pending = ConcurrentHashMap<String, PendingEntry>()
    var listener: Listener? = null

    suspend fun ask(taskId: String, question: String, options: List<String>?): Result<String> {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        val request = AskUserRequest(requestId, taskId, question, options)
        pending[requestId] = PendingEntry(request, deferred)

        listener?.onAskUserRequest(request)
            ?: return Result.failure(IllegalStateException("No UI listener — ask_user not supported in this context"))

        return try {
            val answer = withTimeout(timeoutMs) { deferred.await() }
            Result.success(answer)
        } catch (e: TimeoutCancellationException) {
            pending.remove(requestId)
            Result.failure(RuntimeException("User did not respond within ${timeoutMs / 1000}s"))
        } finally {
            pending.remove(requestId)
        }
    }

    fun resolve(requestId: String, answer: String) {
        val entry = pending[requestId]
        if (entry == null) {
            logger.warn { "No pending question for requestId=$requestId" }
            return
        }
        entry.deferred.complete(answer)
    }

    fun cancel(requestId: String, reason: String = "Cancelled by user") {
        val entry = pending[requestId]
        entry?.deferred?.completeExceptionally(RuntimeException(reason))
    }

    fun getPendingRequests(): List<AskUserRequest> =
        pending.values.map { it.request }
}
