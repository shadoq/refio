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
import kotlin.test.assertTrue

/**
 * Regression for the Report-1 hard failure: Ollama streamed a single chunk carrying a COMPLETE
 * native tool call (content empty, `done:false`) and then closed the NDJSON channel WITHOUT the
 * trailing `done:true` sentinel. The old guard treated any missing sentinel as a fatal
 * "stream ended before done=true" error and discarded the already-captured tool call, failing the
 * whole turn.
 *
 * WHY it matters: Ollama emits whole tool calls in one chunk (not incrementally — see
 * [OllamaAdapter] streaming loop), so a captured call is usable. Throwing it away turns a usable
 * turn into a hard failure, and a retry pays the full (often 30 s+) model latency again for the
 * same likely-truncated stream. The adapter must finalize gracefully when usable output was
 * already captured, and only hard-fail when nothing at all arrived.
 */
class OllamaAdapterStreamSalvageTest {

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
    fun `stream ending without done sentinel still surfaces a captured tool call`() = runTest {
        // One NDJSON chunk: complete tool_call, empty content, done=false. No done=true line.
        val ndjsonNoDone =
            """{"model":"qwen3.5:9b","message":{"role":"assistant","content":"",""" +
                """"tool_calls":[{"function":{"name":"read_file","arguments":{"path":"README.md"}}}]},""" +
                """"done":false}""" + "\n"

        val adapter = OllamaAdapter(
            model = "qwen3.5:9b",
            baseUrlOverride = "http://mock.ollama.test",
            httpClientOverride = mockHttpClient { respondNdjson(ndjsonNoDone) }
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "Co to za projekt?")),
            systemMessages = listOf("You are an autonomous coding agent."),
            maxTokens = 256,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = {},
            kwargs = mapOf("native_tools" to listOf(readFileSchema))
        )

        val calls = response.nativeToolCalls
        assertNotNull(calls, "captured native tool call must be surfaced, not discarded")
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("README.md"))
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

    private fun MockRequestHandleScope.respondNdjson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType("application", "x-ndjson").toString())
        )
}
