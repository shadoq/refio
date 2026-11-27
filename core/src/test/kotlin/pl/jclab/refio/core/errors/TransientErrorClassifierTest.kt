package pl.jclab.refio.core.errors

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The classifier is the single gate deciding "retry vs give up" for every LLM call site. These
 * cases pin the exact failure strings seen in production (bare 5xx, Cloudflare edge codes,
 * Anthropic overload) so a future refactor cannot silently narrow retryability back to prose-only.
 */
class TransientErrorClassifierTest {

    @Test
    fun `bare HTTP 5xx status is transient even with an empty body`() {
        // Anthropic returns "HTTP 500" with no prose — only the status code identifies it.
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 500): ")))
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 503): ")))
    }

    @Test
    fun `Cloudflare edge codes and Anthropic overload are transient`() {
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 520): error code: 520")))
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 529): ")))
    }

    @Test
    fun `prose-only transient signals still match`() {
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Server is overloaded")))
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Connection reset by peer")))
        assertTrue(TransientErrorClassifier.isTransient(RuntimeException("Ollama stream ended before done=true")))
    }

    @Test
    fun `client errors and unrelated 3-digit numbers are not transient`() {
        // A 400/404 is a client error a retry cannot fix.
        assertFalse(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 400): invalid request")))
        assertFalse(TransientErrorClassifier.isTransient(RuntimeException("Anthropic API error (HTTP 404): not found")))
        // A stray 3-digit number not anchored to an http/code/status marker must not trip the matcher.
        assertFalse(TransientErrorClassifier.isTransient(RuntimeException("generated 500 lines of output")))
    }

    @Test
    fun `a Gemini model out of capacity is not worth retrying, a 503 elsewhere still is`() {
        val geminiBusy = LLMErrorMapper.fromHttpStatus(
            "gemini", "gemini-3.8-flash", 503,
            """Gemini streaming error (HTTP 503): {"error":{"code":503,"message":"This model is currently experiencing high demand.","status":"UNAVAILABLE"}}""",
        )
        assertFalse(TransientErrorClassifier.isTransient(geminiBusy))

        val ollamaBusy = LLMErrorMapper.fromHttpStatus("ollama", "qwen3.5:9b", 503, "Ollama API error (HTTP 503): service unavailable")
        assertTrue(TransientErrorClassifier.isTransient(ollamaBusy))
    }
}
