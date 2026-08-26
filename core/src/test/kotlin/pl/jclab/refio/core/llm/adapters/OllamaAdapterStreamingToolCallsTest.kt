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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.tools.base.ToolSchema
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Accumulation rules for tool calls arriving on the Ollama NDJSON stream.
 *
 * The streaming loop used to reset its buffer on every chunk that carried `tool_calls`, so only
 * the LAST chunk survived. That is invisible while a server packs every call into one chunk, but
 * silently drops work as soon as parallel calls are split across chunks — the model asks for two
 * files and the turn reads one. Calls must therefore accumulate across chunks, while a call that
 * was already captured is never collected twice: a repeated call would be EXECUTED twice, and for
 * a writing tool that means the edit is applied twice.
 */
class OllamaAdapterStreamingToolCallsTest {

    private val readFileSchema = ToolSchema(
        name = "read_file",
        description = "Read a file from disk",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Absolute path")
            ),
            "required" to listOf("path")
        )
    )

    @Test
    fun `tool calls split across chunks are all surfaced`() = runTest {
        val ndjson = buildString {
            append(toolCallChunk("README.md"))
            append(toolCallChunk("CLAUDE.md"))
            append(doneChunk())
        }

        val calls = streamToolCalls(ndjson)

        assertNotNull(calls)
        assertEquals(2, calls.size, "a call captured in an earlier chunk must not be dropped by a later one")
        assertEquals(listOf("read_file", "read_file"), calls.map { it.name })
        assertEquals(
            listOf(true, true),
            listOf(calls[0].argumentsJson.contains("README.md"), calls[1].argumentsJson.contains("CLAUDE.md"))
        )
    }

    @Test
    fun `parallel tool calls inside a single chunk are all surfaced`() = runTest {
        val ndjson =
            """{"model":"qwen3.5:9b","message":{"role":"assistant","content":"",""" +
                """"tool_calls":[""" +
                """{"function":{"name":"read_file","arguments":{"path":"README.md"}}},""" +
                """{"function":{"name":"read_file","arguments":{"path":"CLAUDE.md"}}}""" +
                """]},"done":false}""" + "\n" + doneChunk()

        val calls = streamToolCalls(ndjson)

        assertNotNull(calls)
        assertEquals(2, calls.size)
    }

    @Test
    fun `a call repeated in a later chunk is not collected twice`() = runTest {
        // Defensive: if a server ever re-sends the cumulative tool_calls array on each chunk,
        // accumulating blindly would run the same tool twice.
        val ndjson = buildString {
            append(toolCallChunk("README.md"))
            append(toolCallChunk("README.md"))
            append(doneChunk())
        }

        val calls = streamToolCalls(ndjson)

        assertNotNull(calls)
        assertEquals(1, calls.size, "the same call must never be executed twice because of stream repetition")
    }

    private suspend fun streamToolCalls(ndjson: String) =
        OllamaAdapter(
            model = "qwen3.5:9b",
            baseUrlOverride = "http://mock.ollama.test",
            httpClientOverride = mockHttpClient { respondNdjson(ndjson) }
        ).chat(
            messages = listOf(LLMMessage(role = "user", content = "Read the docs")),
            systemMessages = listOf("You are an autonomous coding agent."),
            maxTokens = 256,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = {},
            kwargs = mapOf("native_tools" to listOf(readFileSchema))
        ).nativeToolCalls

    private fun toolCallChunk(path: String) =
        """{"model":"qwen3.5:9b","message":{"role":"assistant","content":"",""" +
            """"tool_calls":[{"function":{"name":"read_file","arguments":{"path":"$path"}}}]},""" +
            """"done":false}""" + "\n"

    private fun doneChunk() =
        """{"model":"qwen3.5:9b","message":{"role":"assistant","content":""},""" +
            """"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5}""" + "\n"

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

    private fun MockRequestHandleScope.respondNdjson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType("application", "x-ndjson").toString())
        )
}
