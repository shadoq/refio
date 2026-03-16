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
    fun `should preserve provider not configured`() {
        val error = LLMErrorMapper.missingConfig("openai", "api_key")

        assertIs<RefioError.ProviderNotConfigured>(error)
        assertTrue(error.message.orEmpty().contains("api_key"))
    }
}
