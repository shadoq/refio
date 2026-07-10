package pl.jclab.refio.core.errors

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LLMErrorMapperTest {

    @Test
    fun `should map timeout exception to Refio timeout`() {
        val error = LLMErrorMapper.fromThrowable(
            provider = "openai",
            model = "gpt-4o-mini",
            timeoutMs = 30_000,
            throwable = HttpRequestTimeoutException(HttpRequestBuilder())
        )

        assertIs<RefioError.LLMTimeout>(error)
    }

    @Test
    fun `should map 429 to rate limit`() {
        val error = LLMErrorMapper.fromHttpStatus(
            provider = "openrouter",
            model = "anthropic/claude-3.5-sonnet",
            statusCode = 429,
            message = "too many requests"
        )

        assertIs<RefioError.LLMRateLimit>(error)
    }

    @Test
    fun `connection refused wrapped by http client maps to actionable is-Ollama-running message with endpoint`() {
        // Ktor wraps connection failures, so the ConnectException sits deeper in the cause chain.
        val wrapped = RuntimeException(
            "request failed",
            java.io.IOException("transport error", java.net.ConnectException("Connection refused"))
        )

        val error = LLMErrorMapper.fromThrowable(
            provider = "ollama",
            model = "qwen2.5:7b",
            timeoutMs = 30_000,
            throwable = wrapped,
            endpoint = "http://localhost:11434"
        )

        assertIs<RefioError.LLMConnectionFailed>(error)
        assertTrue(error.message.orEmpty().contains("http://localhost:11434"))
        assertTrue(error.message.orEmpty().contains("ollama serve"))
    }

    @Test
    fun `ollama 404 model not found maps to ready-to-run ollama pull command`() {
        val error = LLMErrorMapper.fromHttpStatus(
            provider = "ollama",
            model = "qwen2.5:7b",
            statusCode = 404,
            message = "model \"qwen2.5:7b\" not found, try pulling it first"
        )

        assertIs<RefioError.LLMModelNotFound>(error)
        assertTrue(error.message.orEmpty().contains("ollama pull qwen2.5:7b"))
    }

    @Test
    fun `context length error maps to reduce-context-or-increase-window guidance`() {
        val error = LLMErrorMapper.fromThrowable(
            provider = "ollama",
            model = "qwen2.5:7b",
            timeoutMs = 30_000,
            throwable = IllegalStateException("prompt exceeds the maximum context length of 4096 tokens")
        )

        assertIs<RefioError.LLMContextOverflow>(error)
        assertTrue(error.message.orEmpty().contains("context window"))
    }

    @Test
    fun `should preserve provider not configured`() {
        val error = LLMErrorMapper.missingConfig("openai", "api_key")

        assertIs<RefioError.ProviderNotConfigured>(error)
        assertTrue(error.message.orEmpty().contains("api_key"))
    }
}
