package pl.jclab.refio.core.llm.adapters

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.base.ToolSchema
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Users running their own OpenAI-compatible server pin generation settings server-side, so the
 * parameters Refio always added (`temperature`, `max_tokens`, and the non-standard `request_id`)
 * overrode their configuration. `max_tokens` was the sharp edge: it is clamped to
 * `limits.max_output_size`, so a server allowed to generate more was capped at 16384 by default.
 *
 * These tests pin what raw request mode removes and, just as importantly, what it must keep.
 */
class GenericOpenAIRawRequestTest {

    @Test
    fun `raw request mode sends no sampling parameters of ours`() = runTest {
        val body = captureRequestBody(rawRequest = true)

        assertFalse(body.has("temperature"), "temperature must be left to the server")
        assertFalse(body.has("max_tokens"), "max_tokens must be left to the server")
        assertFalse(body.has("request_id"), "the non-standard request_id must not be sent")
        assertEquals("qwen3-coder-local", body.get("model").asString, "the model still has to be named")
    }

    @Test
    fun `raw request mode keeps the tool schemas, so AGENT mode still has tools to call`() = runTest {
        val body = captureRequestBody(rawRequest = true, tools = listOf(readFileSchema()))

        assertTrue(body.has("tools"), "dropping tools would silently disable AGENT mode")
        assertEquals(1, body.getAsJsonArray("tools").size())
        assertEquals("auto", body.get("tool_choice").asString)
    }

    @Test
    fun `without the flag the sampling parameters are sent as before`() = runTest {
        val body = captureRequestBody(rawRequest = false)

        assertTrue(body.has("temperature"))
        assertTrue(body.has("max_tokens"))
        assertTrue(body.has("request_id"))
    }

    @Test
    fun `the flag does not leak to other providers sharing this adapter`() = runTest {
        // ZAIAdapter extends GenericOpenAIAdapter but talks to a hosted API that expects these
        // parameters, so a flag meant for a self-hosted server must not reach it.
        val body = captureRequestBody(rawRequest = true, providerName = "zai")

        assertTrue(body.has("max_tokens"), "Z.AI must keep receiving max_tokens")
        assertTrue(body.has("temperature"))
    }

    private suspend fun captureRequestBody(
        rawRequest: Boolean,
        providerName: String = "generic_openai",
        tools: List<ToolSchema> = emptyList(),
    ): JsonObject {
        var captured: String? = null
        val client = HttpClient(MockEngine { request ->
            captured = (request.body as TextContent).text
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { gson { serializeNulls() } }
        }

        GenericOpenAIAdapter(
            model = "qwen3-coder-local",
            providerName = providerName,
            configService = mockConfig(rawRequest),
            baseUrlOverride = "https://mock.test/v1",
            httpClientOverride = client,
        ).chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 64,
            temperature = 0.3,
            streaming = false,
            onStreamChunk = null,
            kwargs = if (tools.isEmpty()) emptyMap() else mapOf("native_tools" to tools),
        )

        return JsonParser.parseString(requireNonNull(captured)).asJsonObject
    }

    private fun requireNonNull(body: String?): String =
        body ?: error("the adapter did not send a request body")

    private fun readFileSchema(): ToolSchema = ToolSchema(
        name = "read_file",
        description = "Read a file",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string")),
        ),
    )

    private fun mockConfig(rawRequest: Boolean): ConfigService {
        val config = mockk<ConfigService>()
        every { config.get(any(), any(), any(), any()) } returns null
        every { config.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { config.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL) } returns "https://mock.test/v1"
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY) } returns "test-key"
        every { config.getTyped(ConfigKeys.PROVIDER_ZAI_BASE_URL) } returns "https://mock.test/v1"
        every { config.getTyped(ConfigKeys.PROVIDER_ZAI_API_KEY) } returns "test-key"
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_RAW_REQUEST, any()) } returns rawRequest
        return config
    }
}
