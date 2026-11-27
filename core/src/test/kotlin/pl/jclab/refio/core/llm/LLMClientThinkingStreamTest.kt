package pl.jclab.refio.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reasoning model can think for minutes before its first answer token. The thinking the adapter
 * streams must reach the caller's chunk callback, or the UI shows nothing but a silent stream.
 */
class LLMClientThinkingStreamTest {

    @Test
    fun `streamed thinking reaches the caller as a bounded tail of the reasoning so far`() = runBlocking { withTimeout(30_000) {
        val longThought = "step ".repeat(200) // 1000 chars, more than the preview keeps
        val sse = sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"${longThought}"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"done."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Answer"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )
        val client = LLMClient(
            configService = anthropicConfig(),
            httpClientOverride = HttpClient(MockEngine {
                respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }) { install(ContentNegotiation) { gson() } },
        )
        val chunks = mutableListOf<StreamChunk>()

        val response = client.complete(
            provider = "anthropic",
            model = "claude-haiku-5-5",
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            stream = true,
            onChunk = { chunks += it },
        )

        val previews = chunks.mapNotNull { it.thinkingPreview }
        assertEquals(2, previews.size, "one preview per thinking delta")
        assertTrue(previews.all { it.length <= StreamChunk.THINKING_PREVIEW_CHARS }, "preview stays bounded")
        assertTrue(previews.last().endsWith("step done."), "preview carries the newest reasoning: ${previews.last().takeLast(40)}")
        assertEquals("Answer", response.content, "thinking never leaks into the answer")
        assertTrue(chunks.filter { it.delta.isNotEmpty() }.all { it.thinkingPreview == null })
    } }

    private fun sse(vararg events: String): String =
        events.joinToString(separator = "") { "data: $it\n\n" }

    private fun anthropicConfig(): ConfigService {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.get(any(), any(), any(), any()) } returns null
        every { configService.get(ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key, ConfigScope.APP, any(), any()) } returns "test-anthropic-key"
        every { configService.getTyped(any<ConfigKey<Any?>>(), any()) } answers { firstArg<ConfigKey<Any?>>().default }
        every { configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns false
        return configService
    }
}
