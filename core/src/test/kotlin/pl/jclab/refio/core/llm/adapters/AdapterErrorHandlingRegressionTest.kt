package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.tools.base.ToolSchema
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdapterErrorHandlingRegressionTest {

    private fun mockHttpClient(status: HttpStatusCode, body: String, contentType: ContentType = ContentType.Application.Json): HttpClient {
        return HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType.toString())
            )
        }) {
            install(ContentNegotiation) { gson() }
        }
    }

    private val dummyTools = listOf(
        ToolSchema(name = "read_file", description = "Read a file", parametersJsonSchema = mapOf("type" to "object"))
    )

    @Test
    fun `OllamaAdapter propagates ToolsNotSupportedException instead of wrapping it into LLMError`() = runBlocking {
        val client = mockHttpClient(
            HttpStatusCode.BadRequest,
            """{"error":"registry.ollama.ai/library/tiny:latest does not support tools"}"""
        )
        val adapter = OllamaAdapter(model = "tiny:latest", httpClientOverride = client)
        assertFailsWith<ToolsNotSupportedException> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                streaming = false,
                kwargs = mapOf("native_tools" to dummyTools)
            )
        }
        client.close()
    }

    @Test
    fun `OllamaAdapter preserves HTTP 502 status on non-JSON proxy error page`() = runBlocking {
        val client = mockHttpClient(
            HttpStatusCode.BadGateway,
            "<html><body>502 Bad Gateway</body></html>",
            ContentType.Text.Html
        )
        val adapter = OllamaAdapter(model = "tiny:latest", httpClientOverride = client)
        val err = assertFailsWith<RefioError.LLMError> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                streaming = false
            )
        }
        assertEquals(true, err.message?.contains("502") == true)
        client.close()
    }

    @Test
    fun `GeminiAdapter skips thought parts when extracting final text answer`() = runBlocking {
        val responseJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"thought": true, "text": "Internal chain of thought"},
                      {"text": "Final answer"}
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5, "totalTokenCount": 15}
            }
        """.trimIndent()
        val client = mockHttpClient(HttpStatusCode.OK, responseJson)
        val adapter = GeminiAdapter(model = "gemini-2.5-flash", httpClientOverride = client)
        // Supply key via system property for test
        val prev = System.getProperty("GEMINI_API_KEY")
        System.setProperty("GEMINI_API_KEY", "test-key")
        try {
            val response = adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                streaming = false
            )
            assertEquals("Final answer", response.content)
        } finally {
            if (prev == null) System.clearProperty("GEMINI_API_KEY") else System.setProperty("GEMINI_API_KEY", prev)
            client.close()
        }
    }

    @Test
    fun `OpenAIAdapter checks HTTP 401 before Responses API transform instead of throwing MalformedResponse`() = runBlocking {
        val client = mockHttpClient(
            HttpStatusCode.Unauthorized,
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
        )
        val adapter = OpenAIAdapter(model = "gpt-5.2", httpClientOverride = client)
        val prev = System.getProperty("OPENAI_API_KEY")
        System.setProperty("OPENAI_API_KEY", "sk-invalid")
        try {
            assertFailsWith<RefioError.LLMAuthentication> {
                adapter.chat(
                    messages = listOf(LLMMessage(role = "user", content = "hi")),
                    systemMessages = emptyList(),
                    streaming = false
                )
            }
        } finally {
            if (prev == null) System.clearProperty("OPENAI_API_KEY") else System.setProperty("OPENAI_API_KEY", prev)
            client.close()
        }
    }

    @Test
    fun `OpenAICompatibleAdapter propagates ToolsNotSupportedException on 400 tools unsupported`() = runBlocking {
        val client = mockHttpClient(
            HttpStatusCode.BadRequest,
            """{"error":{"message":"No endpoints found that support tool use"}}"""
        )
        val adapter = LMStudioAdapter(model = "local-model", httpClientOverride = client)
        assertFailsWith<ToolsNotSupportedException> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                streaming = false,
                kwargs = mapOf("native_tools" to dummyTools)
            )
        }
        client.close()
    }
}
