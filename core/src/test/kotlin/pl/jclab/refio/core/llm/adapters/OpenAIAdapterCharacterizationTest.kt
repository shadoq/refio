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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.gson.gson
import io.ktor.utils.io.core.readText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization tests dla OpenAIAdapter — fixują obecne zachowanie przed Sprint 1 #2.
 *
 * Po Sprint 1 testy oznaczone `// CHANGES AFTER SPRINT 1` będą musiały zostać
 * zaktualizowane (expected exception zamiast obecnego silent behavior).
 */
class OpenAIAdapterCharacterizationTest {

    @Test
    fun `Responses API with blank output throws MalformedResponse even when top-level text present`() = runTest {
        val configService = mockOpenAIConfig()

        val fixture = """
            {
              "id": "resp_test",
              "model": "gpt-5.1-codex",
              "output": [],
              "text": "would be used as fallback pre-Sprint-1",
              "usage": {
                "input_tokens": 10,
                "output_tokens": 5,
                "total_tokens": 15
              }
            }
        """.trimIndent()

        val adapter = OpenAIAdapter(
            model = "gpt-5.1-codex",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { respondJson(fixture) }
        )

        val err = assertThrows<RefioError.MalformedResponse> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 128,
                temperature = 0.2,
                streaming = false,
                onStreamChunk = null,
                kwargs = emptyMap()
            )
        }
        assertEquals("openai", err.provider)
        assertTrue(err.message!!.contains("no content"), "message should explain what was missing: ${err.message}")
    }

    @Test
    fun `Responses API with blank output and no text field throws MalformedResponse`() = runTest {
        val configService = mockOpenAIConfig()

        val fixture = """
            {
              "id": "resp_test",
              "model": "gpt-5.1-codex",
              "output": [],
              "usage": { "input_tokens": 1, "output_tokens": 1, "total_tokens": 2 }
            }
        """.trimIndent()

        val adapter = OpenAIAdapter(
            model = "gpt-5.1-codex",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { respondJson(fixture) }
        )

        assertThrows<RefioError.MalformedResponse> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 128,
                temperature = 0.2,
                streaming = false,
                onStreamChunk = null,
                kwargs = emptyMap()
            )
        }
    }

    @Test
    fun `Responses API extracts text from message item in output array`() = runTest {
        val configService = mockOpenAIConfig()

        val fixture = """
            {
              "id": "resp_test",
              "model": "gpt-5.1-codex",
              "output": [
                {
                  "type": "message",
                  "content": [
                    { "type": "output_text", "text": "proper output" }
                  ]
                }
              ],
              "usage": { "input_tokens": 3, "output_tokens": 2, "total_tokens": 5 }
            }
        """.trimIndent()

        val adapter = OpenAIAdapter(
            model = "gpt-5.1-codex",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { respondJson(fixture) }
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 128,
            temperature = 0.2,
            streaming = false,
            onStreamChunk = null,
            kwargs = emptyMap()
        )

        assertEquals("proper output", response.content)
    }

    @Test
    fun `Responses API with Int thinking param fails via LLMError wrapping IllegalArgumentException`() = runTest {
        val configService = mockOpenAIConfig()

        val adapter = OpenAIAdapter(
            model = "gpt-5.1-codex",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { respondJson("""{"output":[]}""") }
        )

        val err = assertThrows<RefioError.LLMError> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 128,
                temperature = 0.2,
                streaming = false,
                onStreamChunk = null,
                kwargs = mapOf("thinking" to 42)
            )
        }
        val root = err.originalCause as? IllegalArgumentException
            ?: error("Expected IllegalArgumentException as root cause, got: ${err.originalCause}")
        assertTrue(root.message!!.contains("Boolean or String"), "unexpected: ${root.message}")
    }

    @Test
    fun `Responses API with invalid thinking string fails via LLMError wrapping IllegalArgumentException`() = runTest {
        val configService = mockOpenAIConfig()

        val adapter = OpenAIAdapter(
            model = "gpt-5.1-codex",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { respondJson("""{"output":[]}""") }
        )

        val err = assertThrows<RefioError.LLMError> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 128,
                temperature = 0.2,
                streaming = false,
                onStreamChunk = null,
                kwargs = mapOf("thinking" to "ultra-max")
            )
        }
        val root = err.originalCause as? IllegalArgumentException
            ?: error("Expected IllegalArgumentException as root cause, got: ${err.originalCause}")
        assertTrue(root.message!!.contains("low, medium, high"), "unexpected: ${root.message}")
    }

    private fun mockOpenAIConfig(): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.get(any(), any(), any(), any()) } returns null
        every {
            configService.get(ConfigService.KEY_PROVIDER_OPENAI_API_KEY, ConfigScope.APP, any(), any())
        } returns "test-openai-key"
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

    private suspend fun MockRequestHandleScope.respondJson(json: String) =
        respond(
            content = json,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )

    private fun HttpRequestData.bodyText(): String {
        return when (val content = body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> runBlocking { content.readFrom().readRemaining().readText() }
            is OutgoingContent.NoContent -> ""
            else -> error("Unsupported request body type: ${content::class.simpleName}")
        }
    }
}
