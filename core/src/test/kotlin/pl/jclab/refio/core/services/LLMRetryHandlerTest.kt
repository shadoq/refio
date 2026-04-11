package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LLMRetryHandlerTest {

    private lateinit var llmClient: LLMClient
    private lateinit var retryHandler: LLMRetryHandler

    private val testMessages = listOf(LLMMessage(role = "user", content = "Hello"))

    private fun successResponse() = LLMResponse(
        content = "response",
        usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
        model = "test-model",
        provider = "test",
        cost = 0.001
    )

    @BeforeEach
    fun setup() {
        llmClient = mockk()
        retryHandler = LLMRetryHandler(llmClient)
    }

    @Nested
    inner class SuccessfulCalls {

        @Test
        fun `should return response on first success`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns successResponse()

            val response = retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test"
            )

            assertEquals("response", response.content)
            coVerify(exactly = 1) { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class RetryableErrors {

        @Test
        fun `should retry on rate limit error`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("rate limit exceeded") andThen successResponse()

            val response = retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1  // minimize delay in tests
            )

            assertEquals("response", response.content)
            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry on 429 error`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("HTTP 429 Too Many Requests") andThen successResponse()

            val response = retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals("response", response.content)
        }

        @Test
        fun `should retry on timeout`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Connection timed out") andThen successResponse()

            val response = retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals("response", response.content)
        }

        @Test
        fun `should retry on 502 bad gateway`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("502 bad gateway") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry on 503 service unavailable`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("503 service unavailable") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry on overloaded error`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Server is overloaded") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry on connection refused`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Connection refused") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry when Ollama stream ends before done=true`() = runTest {
            // OllamaAdapter throws this when the NDJSON channel closes without a final done chunk
            // (remote Ollama restart, idle proxy timeout, mid-generation network drop).
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Ollama stream ended before done=true final chunk (contentBytes=512, thinkingBytes=0, durationMs=29051)") andThen successResponse()

            val response = retryHandler.callWithRetry(
                provider = "ollama",
                model = "qwen3.5:122b",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals("response", response.content)
            assertEquals(1, retryHandler.getStats().totalRetries)
        }

        @Test
        fun `should retry on unexpected end of stream`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Unexpected end of stream") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertEquals(1, retryHandler.getStats().totalRetries)
        }
    }

    @Nested
    inner class NonRetryableErrors {

        @Test
        fun `should not retry CancellationException`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("User cancelled")

            assertFailsWith<CancellationException> {
                retryHandler.callWithRetry(
                    provider = "test",
                    model = "test-model",
                    messages = testMessages,
                    taskId = "task-1",
                    source = "test",
                    baseDelayMs = 1
                )
            }

            assertEquals(0, retryHandler.getStats().totalRetries)
            assertEquals(1, retryHandler.getStats().totalFailures)
        }

        @Test
        fun `should not retry authentication error`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Invalid API key")

            assertFailsWith<RuntimeException> {
                retryHandler.callWithRetry(
                    provider = "test",
                    model = "test-model",
                    messages = testMessages,
                    taskId = "task-1",
                    source = "test",
                    baseDelayMs = 1
                )
            }

            assertEquals(0, retryHandler.getStats().totalRetries)
            assertEquals(1, retryHandler.getStats().totalFailures)
        }

        @Test
        fun `should not retry unknown errors`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("Some unknown error")

            assertFailsWith<RuntimeException> {
                retryHandler.callWithRetry(
                    provider = "test",
                    model = "test-model",
                    messages = testMessages,
                    taskId = "task-1",
                    source = "test",
                    baseDelayMs = 1
                )
            }

            assertEquals(0, retryHandler.getStats().totalRetries)
        }
    }

    @Nested
    inner class MaxRetriesAndStats {

        @Test
        fun `should throw after max retries exhausted`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("rate limit exceeded")

            assertFailsWith<RuntimeException> {
                retryHandler.callWithRetry(
                    provider = "test",
                    model = "test-model",
                    messages = testMessages,
                    taskId = "task-1",
                    source = "test",
                    maxRetries = 3,
                    baseDelayMs = 1
                )
            }

            // 2 retries (first call + 2 retries = 3 total attempts, but only 2 are "retries")
            assertEquals(2, retryHandler.getStats().totalRetries)
            assertEquals(1, retryHandler.getStats().totalFailures)
        }

        @Test
        fun `should reset stats`() = runTest {
            coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("rate limit") andThen successResponse()

            retryHandler.callWithRetry(
                provider = "test",
                model = "test-model",
                messages = testMessages,
                taskId = "task-1",
                source = "test",
                baseDelayMs = 1
            )

            assertTrue(retryHandler.getStats().totalRetries > 0)

            retryHandler.resetStats()
            assertEquals(0, retryHandler.getStats().totalRetries)
            assertEquals(0, retryHandler.getStats().totalFailures)
        }
    }
}
