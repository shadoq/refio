package pl.jclab.refio.core.services

import kotlinx.coroutines.delay
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.CancellationException

private val logger = dualLogger("LLMRetryHandler")

/**
 * Handles LLM API calls with retry logic and exponential backoff.
 *
 * Retries on transient errors:
 * - Rate limits (429)
 * - Timeouts
 * - 502/503 errors
 * - Overloaded messages
 *
 * Does NOT retry on:
 * - CancellationException (user cancelled)
 * - Authentication errors
 * - Invalid requests
 *
 * Reference: ADR-0028 - Retry Logic
 */
class LLMRetryHandler(
    private val llmClient: LLMClient
) {
    // Track retry statistics
    private var totalRetries = 0
    private var totalFailures = 0

    /**
     * Call LLM with automatic retry on transient errors.
     *
     * @param provider LLM provider name
     * @param model Model name
     * @param messages Conversation messages
     * @param systemPrompt Optional system prompt
     * @param taskId Task ID for tracking
     * @param source Source identifier for logging
     * @param maxRetries Maximum retry attempts
     * @param baseDelayMs Base delay for exponential backoff (ms)
     * @param responseFormat Optional response format (e.g., JSON mode)
     * @param stream Whether to stream response
     * @param onChunk Optional streaming callback
     * @return LLM response
     */
    suspend fun callWithRetry(
        provider: String,
        model: String,
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        taskId: String,
        source: String,
        maxRetries: Int = 3,
        baseDelayMs: Long = 1000,
        responseFormat: Map<String, Any>? = null,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): LLMResponse {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return llmClient.complete(
                    provider = provider,
                    model = model,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    taskId = taskId,
                    source = source,
                    responseFormat = responseFormat,
                    stream = stream,
                    onChunk = onChunk
                )
            } catch (e: Exception) {
                lastException = e

                val shouldRetry = shouldRetryException(e)

                if (!shouldRetry) {
                    logger.error(e) { "[RETRY] Non-retryable error, giving up: ${e.message}" }
                    totalFailures++
                    throw e
                }

                if (attempt < maxRetries - 1) {
                    val delayMs = baseDelayMs * (1 shl attempt)  // Exponential: 1s, 2s, 4s
                    totalRetries++

                    logger.warn {
                        "[RETRY] Attempt ${attempt + 1}/$maxRetries failed: ${e.message}. " +
                        "Retrying in ${delayMs}ms..."
                    }

                    delay(delayMs)
                } else {
                    totalFailures++
                    logger.error(e) { "[RETRY] All $maxRetries attempts failed" }
                }
            }
        }

        throw lastException ?: RuntimeException("LLM call failed after $maxRetries retries")
    }

    /**
     * Determine if an exception should trigger a retry.
     *
     * @param e Exception to check
     * @return True if exception is retryable
     */
    private fun shouldRetryException(e: Exception): Boolean {
        // Don't retry if user cancelled
        if (e is CancellationException) {
            return false
        }

        val message = e.message?.lowercase() ?: return false

        // Check for retryable error patterns
        return when {
            // Rate limits
            message.contains("rate limit") -> true
            message.contains("too many requests") -> true
            message.contains("429") -> true

            // Timeouts
            message.contains("timeout") -> true
            message.contains("timed out") -> true

            // Server errors
            message.contains("503") -> true
            message.contains("502") -> true
            message.contains("service unavailable") -> true
            message.contains("bad gateway") -> true

            // Overloaded
            message.contains("overloaded") -> true
            message.contains("server is busy") -> true

            // Network errors (often transient)
            message.contains("connection refused") -> true
            message.contains("connection reset") -> true
            message.contains("connection timed out") -> true

            // Default: don't retry
            else -> false
        }
    }

    /**
     * Get retry statistics.
     */
    fun getStats(): RetryStats = RetryStats(
        totalRetries = totalRetries,
        totalFailures = totalFailures
    )

    /**
     * Reset statistics.
     */
    fun resetStats() {
        totalRetries = 0
        totalFailures = 0
        logger.info { "[RETRY] Statistics reset" }
    }
}

/**
 * Retry statistics.
 */
data class RetryStats(
    val totalRetries: Int,
    val totalFailures: Int
)
