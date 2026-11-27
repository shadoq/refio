package pl.jclab.refio.core.llm.adapters

import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The reasoning-effort setting as it reaches OpenAI. Reasoning OFF arrives as no `thinking` kwarg;
 * OpenAI reasoning models then reason at their (costly) default unless told otherwise, so OFF sends
 * the model's lowest accepted level - which differs per model ("none", "minimal", "low", "medium").
 */
class OpenAIReasoningEffortTest {

    @Test
    fun `reasoning off on a chat-completions model that can switch reasoning off sends none`() {
        val body = requestBody(model = "gpt-5.5", thinking = null)

        assertEquals("none", body["reasoning_effort"])
    }

    @Test
    fun `reasoning off on a model whose floor is above none sends that floor, not a value it rejects`() {
        assertEquals("medium", requestBody(model = "gpt-5.5-pro", thinking = null)["reasoning_effort"])
    }

    @Test
    fun `a chosen level below the model's floor is raised to the floor instead of being rejected`() {
        assertEquals("medium", requestBody(model = "gpt-5.5-pro", thinking = "low")["reasoning_effort"])
    }

    @Test
    fun `reasoning off on a responses-api model with no known floor falls back to low`() {
        val body = requestBody(model = "gpt-5.1-codex", thinking = null, responses = true)

        assertEquals(mapOf("effort" to "low", "summary" to "auto"), body["reasoning"])
    }

    @Test
    fun `chosen level reaches chat completions as reasoning_effort, never as an unknown thinking field`() {
        val body = requestBody(model = "gpt-5.5", thinking = "high")

        assertEquals("high", body["reasoning_effort"])
        assertFalse(body.containsKey("thinking"), "OpenAI does not know a `thinking` field")
    }

    @Test
    fun `chosen level reaches the responses api as reasoning effort`() {
        val body = requestBody(model = "gpt-5.1-codex", thinking = "medium", responses = true)

        assertEquals(mapOf("effort" to "medium", "summary" to "auto"), body["reasoning"])
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun `model without reasoning gets no reasoning field at all`() {
        val body = requestBody(model = "gpt-4o", thinking = "high")

        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
        assertFalse(body.containsKey("thinking"))
    }

    private fun requestBody(model: String, thinking: String?, responses: Boolean = false): Map<*, *> = runBlocking {
        var captured: String? = null
        val reply = if (responses) RESPONSES_OK else CHAT_OK
        val adapter = OpenAIAdapter(
            model = model,
            configService = openAIConfig(),
            baseUrlOverride = "https://mock.openai.test/v1",
            httpClientOverride = HttpClient(MockEngine { request ->
                captured = when (val content = request.body) {
                    is TextContent -> content.text
                    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                    else -> error("unexpected body ${content::class}")
                }
                respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }) { install(ContentNegotiation) { gson() } },
        )

        adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 128,
            temperature = 0.2,
            streaming = false,
            onStreamChunk = null,
            kwargs = if (thinking != null) mapOf("thinking" to thinking) else emptyMap(),
        )
        Gson().fromJson(captured!!, Map::class.java)
    }

    private fun openAIConfig(): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.get(any(), any(), any(), any()) } returns null
        every { configService.get(ConfigKeys.PROVIDER_OPENAI_API_KEY.key, ConfigScope.APP, any(), any()) } returns "test-openai-key"
        every { configService.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        return configService
    }

    private companion object {
        val CHAT_OK = """
            {"id":"c1","model":"m","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimIndent()
        val RESPONSES_OK = """
            {"id":"r1","model":"m","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],
             "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
        """.trimIndent()
    }
}
