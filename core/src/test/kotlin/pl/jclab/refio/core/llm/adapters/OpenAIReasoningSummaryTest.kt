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
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * OpenAI keeps its reasoning hidden unless the request asks for a summary of it. Without one the
 * panel shows a silent stream while a Responses-API model thinks.
 */
class OpenAIReasoningSummaryTest {

    @AfterEach
    fun reset() = OpenAIAdapter.resetReasoningSummaryRejection()

    @Test
    fun `a thinking responses-api model is asked for a summary of its reasoning`() {
        val bodies = run(model = "gpt-5.1-codex", thinking = "medium", replies = listOf(ok(STREAM_WITH_SUMMARY)))

        assertEquals(mapOf("effort" to "medium", "summary" to "auto"), bodies.single()["reasoning"])
    }

    @Test
    fun `the streamed reasoning summary reaches the caller as thinking, never as answer text`() {
        val chunks = mutableListOf<StreamChunk>()
        run(model = "gpt-5.1-codex", thinking = "medium", replies = listOf(ok(STREAM_WITH_SUMMARY)), chunks = chunks)

        assertEquals("Checking the file. Done.", chunks.mapNotNull { it.thinking }.joinToString(""))
        assertEquals("Answer", chunks.joinToString("") { it.delta })
    }

    @Test
    fun `reasoning switched off to none asks for no summary`() {
        val bodies = run(model = "gpt-6.1-terra", thinking = null, replies = listOf(ok(STREAM_WITH_SUMMARY)))

        assertEquals(mapOf("effort" to "none"), bodies.single()["reasoning"])
    }

    @Test
    fun `an account not allowed reasoning summaries still gets its answer, and is not asked again`() {
        val rejected = HttpStatusCode.BadRequest to
            """{"error":{"message":"Your organization must be verified to generate reasoning summaries.","param":"reasoning.summary"}}"""

        val first = run(model = "gpt-5.1-codex", thinking = "low", replies = listOf(rejected, ok(STREAM_WITH_SUMMARY)))
        assertEquals(2, first.size, "retried once without the summary")
        assertEquals(mapOf("effort" to "low"), first[1]["reasoning"])

        val next = run(model = "gpt-5.1-codex", thinking = "low", replies = listOf(ok(STREAM_WITH_SUMMARY)))
        assertEquals(mapOf("effort" to "low"), next.single()["reasoning"])
    }

    @Test
    fun `chat completions models get no summary field`() {
        val bodies = run(model = "gpt-5.5", thinking = "high", replies = listOf(ok(CHAT_STREAM)))

        assertNull(bodies.single()["reasoning"])
        assertFalse(bodies.single().containsKey("summary"))
    }

    private fun ok(body: String) = HttpStatusCode.OK to body

    private fun run(
        model: String,
        thinking: String?,
        replies: List<Pair<HttpStatusCode, String>>,
        chunks: MutableList<StreamChunk> = mutableListOf(),
    ): List<Map<*, *>> = runBlocking {
        withTimeout(30_000) {
            val bodies = mutableListOf<Map<*, *>>()
            val adapter = OpenAIAdapter(
                model = model,
                configService = openAIConfig(),
                baseUrlOverride = "https://mock.openai.test/v1",
                httpClientOverride = HttpClient(MockEngine { request ->
                    val text = when (val content = request.body) {
                        is TextContent -> content.text
                        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                        else -> error("unexpected body ${content::class}")
                    }
                    bodies += Gson().fromJson(text, Map::class.java)
                    val (status, reply) = replies[bodies.size - 1]
                    val type = if (status == HttpStatusCode.OK) "text/event-stream" else ContentType.Application.Json.toString()
                    respond(reply, status, headersOf(HttpHeaders.ContentType, type))
                }) { install(ContentNegotiation) { gson() } },
            )
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 128,
                temperature = 0.2,
                streaming = true,
                onStreamChunk = { chunks += it },
                kwargs = if (thinking != null) mapOf("thinking" to thinking) else emptyMap(),
            )
            bodies
        }
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
        fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

        val STREAM_WITH_SUMMARY = sse(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Checking the file. "}""",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Done."}""",
            """{"type":"response.output_text.delta","output_index":1,"delta":"Answer"}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Answer"}]}],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
        )

        val CHAT_STREAM = sse(
            """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""",
            """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
        )
    }
}
