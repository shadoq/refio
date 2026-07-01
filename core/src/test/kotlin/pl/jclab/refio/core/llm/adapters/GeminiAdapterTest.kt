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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.tools.base.ToolSchema
import pl.jclab.refio.core.utils.GsonInstance.gson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [GeminiAdapter] over its `httpClientOverride` / `baseUrlOverride` seams,
 * so the full chat() path (request construction + response parsing + error mapping) is exercised
 * with a MockEngine and no real network. These are the Gemini analogue of the Ollama/OpenAI
 * *ToolsTest suites - the adapter's rich, provider-specific shaping was previously only smoke-tested.
 */
class GeminiAdapterTest {

    @BeforeEach
    fun setup() {
        // chat() resolves the API key from configService (null here) then System property.
        System.setProperty("GEMINI_API_KEY", "test-key")
    }

    @AfterEach
    fun teardown() {
        System.clearProperty("GEMINI_API_KEY")
    }

    private val readFileSchema = ToolSchema(
        name = "read_file",
        description = "Read a file from disk",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string", "description" to "Absolute path")),
            "required" to listOf("path")
        )
    )

    private val textResponse =
        """{"candidates":[{"content":{"parts":[{"text":"Hello from Gemini"}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":2,"totalTokenCount":17}}"""

    private val functionCallResponse =
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"README.md"}}}]},""" +
            """"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":13}}"""

    private fun adapter(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): GeminiAdapter =
        GeminiAdapter(
            model = "gemini-2.5-flash",
            baseUrlOverride = "http://mock.gemini.test",
            httpClientOverride = mockHttpClient(handler)
        )

    private suspend fun GeminiAdapter.ask(
        messages: List<LLMMessage> = listOf(LLMMessage(role = "user", content = "hi")),
        systemMessages: List<String> = emptyList(),
        kwargs: Map<String, Any> = emptyMap()
    ) = chat(
        messages = messages,
        systemMessages = systemMessages,
        maxTokens = 256,
        temperature = 0.3,
        streaming = false,
        onStreamChunk = null,
        kwargs = kwargs
    )

    // ---- request construction ----

    @Test
    fun `request carries combined system_instruction, mapped roles and thinking disabled by default`() = runTest {
        var captured: String? = null
        val gemini = adapter { req -> captured = req.bodyText(); respondJson(textResponse) }

        gemini.ask(
            messages = listOf(
                LLMMessage(role = "user", content = "hi"),
                LLMMessage(role = "assistant", content = "earlier reply")
            ),
            systemMessages = listOf("You are a coding agent.", "Be terse.")
        )

        val body = gson.fromJson(captured, Map::class.java)
        val systemInstruction = body["system_instruction"] as Map<*, *>
        val systemParts = systemInstruction["parts"] as List<*>
        assertEquals("You are a coding agent.\n\nBe terse.", (systemParts[0] as Map<*, *>)["text"])

        val generationConfig = body["generationConfig"] as Map<*, *>
        assertEquals(0.3, (generationConfig["temperature"] as Number).toDouble(), 1e-6)
        assertTrue((generationConfig["maxOutputTokens"] as Number).toInt() > 0)
        val thinkingConfig = generationConfig["thinkingConfig"] as Map<*, *>
        assertEquals(0, (thinkingConfig["thinkingBudget"] as Number).toInt(), "thinking off must pin the budget to 0")

        // Gemini only accepts user/model: assistant maps to model, user stays user.
        val contents = body["contents"] as List<*>
        assertEquals("user", (contents[0] as Map<*, *>)["role"])
        assertEquals("model", (contents[1] as Map<*, *>)["role"])
    }

    @Test
    fun `native tool schemas are sent as functionDeclarations`() = runTest {
        var captured: String? = null
        val gemini = adapter { req -> captured = req.bodyText(); respondJson(textResponse) }

        gemini.ask(kwargs = mapOf("native_tools" to listOf(readFileSchema)))

        val body = gson.fromJson(captured, Map::class.java)
        val tools = body["tools"] as List<*>
        val declarations = (tools[0] as Map<*, *>)["functionDeclarations"] as List<*>
        assertEquals("read_file", (declarations[0] as Map<*, *>)["name"])
    }

    @Test
    fun `thinking ON drops the thinkingBudget=0 override`() = runTest {
        var captured: String? = null
        val gemini = adapter { req -> captured = req.bodyText(); respondJson(textResponse) }

        gemini.ask(kwargs = mapOf("thinking" to true))

        val body = gson.fromJson(captured, Map::class.java)
        val generationConfig = body["generationConfig"] as Map<*, *>
        assertTrue(!generationConfig.containsKey("thinkingConfig"), "thinking ON must not force thinkingBudget=0")
    }

    // ---- response parsing ----

    @Test
    fun `parses text content, finish reason and usage with thoughts folded into output`() = runTest {
        val response = adapter { respondJson(textResponse) }.ask()

        assertEquals("Hello from Gemini", response.content)
        assertEquals("STOP", response.finishReason)
        assertEquals(10, response.usage.inputTokens)
        assertEquals(7, response.usage.outputTokens, "candidatesTokenCount(5) + thoughtsTokenCount(2)")
        assertEquals(17, response.usage.totalTokens)
    }

    @Test
    fun `functionCall parts surface as native tool calls when tools were requested`() = runTest {
        val response = adapter { respondJson(functionCallResponse) }
            .ask(kwargs = mapOf("native_tools" to listOf(readFileSchema)))

        val calls = response.nativeToolCalls
        assertNotNull(calls, "a functionCall part must surface as a native tool call")
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("README.md"))
    }

    @Test
    fun `functionCall is not parsed as a tool call when no tools were requested`() = runTest {
        // Without native_tools, functionCall parts are normalized into content, not nativeToolCalls.
        val response = adapter { respondJson(functionCallResponse) }.ask()
        assertTrue(response.nativeToolCalls.isNullOrEmpty(), "no tools requested -> no native tool calls")
    }

    // ---- error mapping ----

    @Test
    fun `missing candidates raises MalformedResponse`() = runTest {
        val gemini = adapter { respondJson("""{"usageMetadata":{"promptTokenCount":1}}""") }
        assertFailsWith<RefioError.MalformedResponse> { gemini.ask() }
    }

    @Test
    fun `a 400 tooling-unsupported error with tools requested maps to ToolsNotSupportedException`() = runTest {
        val gemini = adapter {
            respond(
                content = """{"error":{"message":"Function calling is not supported for this model"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        assertFailsWith<ToolsNotSupportedException> {
            gemini.ask(kwargs = mapOf("native_tools" to listOf(readFileSchema)))
        }
    }

    // ---- helpers (mirror the other adapter MockEngine tests) ----

    private fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                gson { serializeNulls() }
            }
        }

    private fun MockRequestHandleScope.respondJson(json: String) =
        respond(
            content = json,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )

    private fun HttpRequestData.bodyText(): String =
        when (val content = body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> runBlocking { content.readFrom().readRemaining().readText() }
            is OutgoingContent.NoContent -> ""
            else -> error("Unsupported request body type: ${content::class.simpleName}")
        }
}
