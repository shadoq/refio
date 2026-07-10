package pl.jclab.refio.core.services

import kotlinx.coroutines.delay
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    // Track retry statistics (thread-safe)
    private val totalRetries = AtomicInteger(0)
    private val totalFailures = AtomicInteger(0)

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
        temperature: Double = 0.7,
        responseFormat: Map<String, Any>? = null,
        thinking: Boolean = false,
        reasoningEffort: String? = null,
        noEgressEnabled: Boolean = false,
        stream: Boolean = false,
        onChunk: StreamCallback? = null,
        kwargs: Map<String, Any> = emptyMap()
    ): LLMResponse {
        var lastException: Exception? = null

        // Track whether any chunk has already been pushed to the live consumer. A streamed
        // call that partially streamed before failing must NOT be retried: consumers append
        // deltas with no reset semantics, so a second full stream would be concatenated onto
        // the partial first one (duplicated/garbled output to the UI).
        val streamedToUi = AtomicBoolean(false)
        val guardedOnChunk: StreamCallback? = onChunk?.let { delegate ->
            { chunk ->
                streamedToUi.set(true)
                delegate(chunk)
            }
        }

        repeat(maxRetries) { attempt ->
            try {
                return llmClient.complete(
                    provider = provider,
                    model = model,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    taskId = taskId,
                    source = source,
                    temperature = temperature,
                    responseFormat = responseFormat,
                    thinking = thinking,
                    reasoningEffort = reasoningEffort,
                    noEgressEnabled = noEgressEnabled,
                    stream = stream,
                    onChunk = guardedOnChunk,
                    kwargs = kwargs
                )
            } catch (e: Exception) {
                lastException = e

                val shouldRetry = shouldRetryException(e)

                if (!shouldRetry) {
                    logger.error(e) { "[RETRY] Non-retryable error, giving up: ${e.message}" }
                    totalFailures.incrementAndGet()
                    throw e
                }

                if (stream && streamedToUi.get()) {
                    logger.error(e) {
                        "[RETRY] Streamed call already emitted chunks to the UI; not retrying " +
                        "to avoid duplicated output: ${e.message}"
                    }
                    totalFailures.incrementAndGet()
                    throw e
                }

                if (attempt < maxRetries - 1) {
                    val delayMs = baseDelayMs * (1 shl attempt)  // Exponential: 1s, 2s, 4s
                    totalRetries.incrementAndGet()

                    logger.warn {
                        "[RETRY] Attempt ${attempt + 1}/$maxRetries failed: ${e.message}. " +
                        "Retrying in ${delayMs}ms..."
                    }

                    delay(delayMs)
                } else {
                    totalFailures.incrementAndGet()
                    logger.error(e) { "[RETRY] All $maxRetries attempts failed" }
                }
            }
        }

        throw lastException ?: RuntimeException("LLM call failed after $maxRetries retries")
    }

    /**
     * Determine if an exception should trigger a retry.
     *
     * Uses typed RefioError subclasses first, then falls back to message matching
     * only for non-RefioError exceptions (e.g. raw Ktor/IO exceptions).
     *
     * @param e Exception to check
     * @return True if exception is retryable
     */
    private fun shouldRetryException(e: Exception): Boolean {
        // Never retry cancellation
        if (e is CancellationException) return false

        // Never retry authentication errors
        if (e is RefioError.LLMAuthentication) return false
        if (e is RefioError.ProviderNotConfigured) return false

        // Always retry rate limits and timeouts (typed)
        if (e is RefioError.LLMRateLimit) return true
        if (e is RefioError.LLMTimeout) return true

        // For generic LLM errors, check the underlying cause
        if (e is RefioError.LLMError) {
            return shouldRetryByMessage(e)
        }

        // For non-RefioError exceptions (raw IO/network), check message
        return shouldRetryByMessage(e)
    }

    /**
     * Fallback: check exception message for retryable patterns.
     * Used for non-typed exceptions (Ktor, IO, etc).
     */
    private fun shouldRetryByMessage(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return when {
            message.contains("rate limit") || message.contains("too many requests") -> true
            message.contains("timeout") || message.contains("timed out") -> true
            message.contains("service unavailable") || message.contains("bad gateway") -> true
            message.contains("overloaded") || message.contains("server is busy") -> true
            message.contains("connection refused") || message.contains("connection reset") -> true
            // Streaming NDJSON cut off mid-flight (Ollama remote, flaky proxy, server restart).
            // The server closed the channel before sending the final done=true chunk — transient,
            // safe to retry from scratch since no tool side-effects ran on this turn yet.
            message.contains("stream ended before") -> true
            message.contains("unexpected end of stream") -> true
            else -> false
        }
    }

    /**
     * Get retry statistics.
     */
    fun getStats(): RetryStats = RetryStats(
        totalRetries = totalRetries.get(),
        totalFailures = totalFailures.get()
    )

    /**
     * Reset statistics.
     */
    fun resetStats() {
        totalRetries.set(0)
        totalFailures.set(0)
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
