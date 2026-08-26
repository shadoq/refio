package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.StreamFinishReason
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stream that dies mid-answer must be distinguishable from one that finished cleanly.
 *
 * When a server closes the connection after some content but before its terminator
 * (`[DONE]` / `message_stop`), the loops simply fell out of `while (!channel.isClosedForRead)`
 * and the half-written answer was returned as if complete. Downstream that reads as "the model
 * replied in prose" instead of "the reply was cut off", so the turn draws the wrong conclusion
 * from an unparseable envelope.
 *
 * The cut-off is recorded as a finish reason. It stays a soft signal on purpose: partial output is
 * still returned rather than turned into a hard failure, matching the salvage rule the Ollama
 * adapter already follows.
 */
class StreamTruncationTest {

    @Test
    fun `SSE cut off after partial content is reported as truncated`() = runTest {
        val received = StringBuilder()
        val finishReason = OpenAICompatibleHelpers.consumeChatCompletionsSSE(
            channel = ByteReadChannel(
                """data: {"choices":[{"delta":{"content":"partial ans"}}]}""" + "\n"
            ),
            toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator(),
            onContent = { received.append(it) },
        )

        assertEquals("partial ans", received.toString())
        assertEquals(StreamFinishReason.TRUNCATED, finishReason)
    }

    @Test
    fun `SSE closed by DONE is not reported as truncated`() = runTest {
        val finishReason = OpenAICompatibleHelpers.consumeChatCompletionsSSE(
            channel = ByteReadChannel(
                """data: {"choices":[{"delta":{"content":"full answer"}}]}""" + "\n" +
                    "data: [DONE]\n"
            ),
            toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator(),
            onContent = { },
        )

        assertNull(finishReason, "a clean end without finish_reason must stay null, not become a truncation marker")
    }

    @Test
    fun `SSE that reported a finish_reason keeps the provider value`() = runTest {
        // A provider that states why it stopped has terminated the stream on purpose, even
        // without a trailing [DONE]. Its own reason must survive untouched.
        val finishReason = OpenAICompatibleHelpers.consumeChatCompletionsSSE(
            channel = ByteReadChannel(
                """data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""" + "\n"
            ),
            toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator(),
            onContent = { },
        )

        assertEquals("stop", finishReason)
    }

    @Test
    fun `OpenAI stream cut off after partial content is reported as truncated`() = runTest {
        val adapter = OpenAIAdapter(
            model = "gpt-4o-mini",
            configService = mockOpenAIConfig(),
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient {
                respondSse("""data: {"choices":[{"delta":{"content":"half of an ans"}}]}""" + "\n")
            }
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 64,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = {},
            kwargs = emptyMap(),
        )

        assertEquals("half of an ans", response.content, "partial output must still be returned")
        assertEquals(StreamFinishReason.TRUNCATED, response.finishReason)
    }

    @Test
    fun `Anthropic stream cut off before message_stop is reported as truncated`() = runTest {
        val adapter = AnthropicAdapter(
            model = "claude-3-5-sonnet-20241022",
            configService = mockAnthropicConfig(),
            baseUrlOverride = "https://mock.anthropic.test",
            httpClientOverride = mockHttpClient {
                respondSse(
                    "event: message_start\n" +
                        """data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}""" + "\n" +
                        "event: content_block_delta\n" +
                        """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"half of an ans"}}""" + "\n"
                )
            }
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 64,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = {},
            kwargs = emptyMap(),
        )

        assertEquals("half of an ans", response.content, "partial output must still be returned")
        assertEquals(StreamFinishReason.TRUNCATED, response.finishReason)
    }

    @Test
    fun `Anthropic stream closed by message_stop keeps the provider stop reason`() = runTest {
        val adapter = AnthropicAdapter(
            model = "claude-3-5-sonnet-20241022",
            configService = mockAnthropicConfig(),
            baseUrlOverride = "https://mock.anthropic.test",
            httpClientOverride = mockHttpClient {
                respondSse(
                    """data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}""" + "\n" +
                        """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"complete"}}""" + "\n" +
                        """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""" + "\n" +
                        """data: {"type":"message_stop"}""" + "\n"
                )
            }
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 64,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = {},
            kwargs = emptyMap(),
        )

        assertEquals("end_turn", response.finishReason)
    }

    private fun mockOpenAIConfig(): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.get(any(), any(), any(), any()) } returns null
        every {
            configService.get(ConfigKeys.PROVIDER_OPENAI_API_KEY.key, ConfigScope.APP, any(), any())
        } returns "test-openai-key"
        every { configService.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        return configService
    }

    private fun mockAnthropicConfig(): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.get(any(), any(), any(), any()) } returns null
        every {
            configService.get(ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key, ConfigScope.APP, any(), any())
        } returns "test-anthropic-key"
        every { configService.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        return configService
    }

    private fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClient {
        return HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                gson {
                    serializeNulls()
                }
            }
        }
    }

    private fun MockRequestHandleScope.respondSse(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType("text", "event-stream").toString())
        )
}
