package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpRequestData
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.LLMContentPart
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.testutil.TestDatabase
import pl.jclab.refio.core.utils.GsonInstance.gson as appGson
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMAdapterSmokeTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb

    private val multimodalMessage = LLMMessage(
        role = "user",
        content = "Inspect attachment",
        parts = listOf(
            LLMContentPart.Text("Inspect attachment"),
            LLMContentPart.Image("image/png", "Zm9v")
        )
    )

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `openai smoke test should send multimodal request and parse response`() = runTest {
        val configService = mockProviderConfig(
            key = ConfigKeys.PROVIDER_OPENAI_API_KEY.key,
            apiKey = "test-openai-key"
        )

        var requestUrl = ""
        var requestJson = ""
        val adapter = OpenAIAdapter(
            model = "gpt-4o-mini",
            configService = configService,
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = mockHttpClient { request ->
                requestUrl = request.url.toString()
                requestJson = request.bodyText()
                respondJson(
                    """
                    {
                      "id": "chatcmpl_test",
                      "model": "gpt-4o-mini",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "OpenAI ok"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 11,
                        "completion_tokens": 7,
                        "total_tokens": 18
                      }
                    }
                    """.trimIndent()
                )
            }
        )

        val response = adapter.chat(
            messages = listOf(multimodalMessage),
            systemMessages = emptyList(),
            maxTokens = 128,
            temperature = 0.2,
            streaming = false,
            onStreamChunk = null,
            kwargs = emptyMap()
        )

        val requestBody = parseJsonMap(requestJson)
        @Suppress("UNCHECKED_CAST")
        val messages = requestBody["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.first()["content"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val imageUrl = content[1]["image_url"] as Map<String, Any?>

        assertEquals("https://mock.openai.test/v1/chat/completions", requestUrl)
        assertEquals("image_url", content[1]["type"])
        assertTrue((imageUrl["url"] as String).startsWith("data:image/png;base64,"))
        assertEquals("OpenAI ok", response.content)
        assertEquals(18, response.usage.totalTokens)
    }

    @Test
    fun `anthropic smoke test should send multimodal request and parse response`() = runTest {
        val configService = mockProviderConfig(
            key = ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key,
            apiKey = "test-anthropic-key"
        )

        var requestUrl = ""
        var requestJson = ""
        val adapter = AnthropicAdapter(
            model = "claude-3-5-sonnet-20241022",
            configService = configService,
            baseUrlOverride = "https://mock.anthropic.test",
            httpClientOverride = mockHttpClient { request ->
                requestUrl = request.url.toString()
                requestJson = request.bodyText()
                respondJson(
                    """
                    {
                      "id": "msg_test",
                      "model": "claude-3-5-sonnet-20241022",
                      "content": [
                        {
                          "type": "text",
                          "text": "Anthropic ok"
                        }
                      ],
                      "usage": {
                        "input_tokens": 12,
                        "output_tokens": 8
                      },
                      "stop_reason": "end_turn"
                    }
                    """.trimIndent()
                )
            }
        )

        val response = adapter.chat(
            messages = listOf(multimodalMessage),
            systemMessages = listOf("Follow repo rules"),
            maxTokens = 256,
            temperature = 0.1,
            streaming = false,
            onStreamChunk = null,
            kwargs = emptyMap()
        )

        val requestBody = parseJsonMap(requestJson)
        @Suppress("UNCHECKED_CAST")
        val messages = requestBody["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.first()["content"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val source = content[1]["source"] as Map<String, Any?>

        assertEquals("https://mock.anthropic.test/v1/messages", requestUrl)
        assertEquals("Follow repo rules", requestBody["system"])
        assertEquals("image", content[1]["type"])
        assertEquals("image/png", source["media_type"])
        assertEquals("Anthropic ok", response.content)
        assertEquals(20, response.usage.totalTokens)
    }

    @Test
    fun `gemini smoke test should send multimodal request and parse response`() = runTest {
        val configService = mockProviderConfig(
            key = ConfigKeys.PROVIDER_GEMINI_API_KEY.key,
            apiKey = "test-gemini-key"
        )

        var requestUrl = ""
        var requestJson = ""
        val adapter = GeminiAdapter(
            model = "gemini-2.5-flash",
            configService = configService,
            baseUrlOverride = "https://mock.gemini.test/v1beta",
            httpClientOverride = mockHttpClient { request ->
                requestUrl = request.url.toString()
                requestJson = request.bodyText()
                respondJson(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "Gemini ok" }
                            ]
                          },
                          "finishReason": "STOP"
                        }
                      ],
                      "usageMetadata": {
                        "promptTokenCount": 13,
                        "candidatesTokenCount": 9,
                        "totalTokenCount": 22
                      }
                    }
                    """.trimIndent()
                )
            }
        )

        val response = adapter.chat(
            messages = listOf(multimodalMessage),
            systemMessages = listOf("Answer precisely"),
            maxTokens = 256,
            temperature = 0.3,
            streaming = false,
            onStreamChunk = null,
            kwargs = emptyMap()
        )

        val requestBody = parseJsonMap(requestJson)
        @Suppress("UNCHECKED_CAST")
        val contents = requestBody["contents"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val parts = contents.first()["parts"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val inlineData = parts[1]["inlineData"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val systemInstruction = requestBody["system_instruction"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val systemParts = systemInstruction["parts"] as List<Map<String, Any?>>

        assertEquals(
            "https://mock.gemini.test/v1beta/models/gemini-2.5-flash:generateContent",
            requestUrl
        )
        assertEquals("Answer precisely", systemParts.first()["text"])
        assertEquals("image/png", inlineData["mimeType"])
        assertEquals("Zm9v", inlineData["data"])
        assertEquals("Gemini ok", response.content)
        assertEquals(22, response.usage.totalTokens)
    }

    private fun mockProviderConfig(key: String, apiKey: String): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.get(any(), any(), any(), any()) } returns null
        every { configService.get(key, ConfigScope.APP, any(), any()) } returns apiKey
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

    private fun parseJsonMap(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return appGson.fromJson(json, Map::class.java) as Map<String, Any?>
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
            is OutgoingContent.WriteChannelContent -> error("Unsupported request body type: ${content::class.simpleName}")
            is OutgoingContent.NoContent -> ""
            else -> error("Unsupported request body type: ${content::class.simpleName}")
        }
    }
}
