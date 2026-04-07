package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for [OllamaAdapter.buildOllamaRequestBody].
 *
 * The bug we are guarding against: previously the adapter only set the `think` key when
 * `thinkingRequested == true`, leaving it unset otherwise. For thinking-capable models like
 * `qwen3.5:35b` Ollama then defaulted to `think=true`, which caused the model to consume its
 * `num_predict` budget on internal reasoning and emit empty `content`. AgentTurnLoop interpreted
 * that as "model returned nothing" and aborted after 3 retries — see the multi-iteration
 * `TURN_EMPTY_CONTENT` traces in docs/0107-multiagent.md.
 *
 * Fix: always set `think` explicitly so the request matches what Refio asked for.
 */
class OllamaAdapterRequestBodyTest {

    private val adapter = OllamaAdapter(model = "qwen3.5:35b")

    private val sampleMessages = listOf(
        mapOf("role" to "system", "content" to "You are a helpful agent."),
        mapOf("role" to "user", "content" to "List files in /tmp")
    )

    @Test
    fun `think key is always present and false when thinking not requested`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = true,
            maxTokens = 4096,
            temperature = 0.7
        )
        assertTrue(body.containsKey("think"), "think key must be present in request body")
        assertEquals(false, body["think"], "think must be explicitly false when not requested")
    }

    @Test
    fun `think key is true when thinking is requested`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = true,
            streaming = true,
            maxTokens = 4096,
            temperature = 0.7
        )
        assertEquals(true, body["think"])
    }

    @Test
    fun `json mode also sets think explicitly to false when thinking not requested`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = true,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 4096,
            temperature = 0.0
        )
        assertEquals("json", body["format"], "json mode must set format=json")
        assertTrue(body.containsKey("think"))
        assertEquals(false, body["think"], "think must be explicitly false even in json mode")
    }

    @Test
    fun `model and message list are propagated`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = true,
            maxTokens = 4096,
            temperature = 0.5
        )
        assertEquals("qwen3.5:35b", body["model"])
        @Suppress("UNCHECKED_CAST")
        val messages = body["messages"] as List<Map<String, String>>
        assertEquals(2, messages.size)
        assertEquals("user", messages[1]["role"])
    }

    @Test
    fun `streaming flag is honored`() {
        val streamingBody = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = true,
            maxTokens = 4096,
            temperature = 0.7
        )
        val nonStreamingBody = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 4096,
            temperature = 0.7
        )
        assertEquals(true, streamingBody["stream"])
        assertEquals(false, nonStreamingBody["stream"])
    }

    @Test
    fun `options block contains temperature and num_predict`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = true,
            maxTokens = 1024,
            temperature = 0.3
        )
        @Suppress("UNCHECKED_CAST")
        val options = body["options"] as Map<String, Any>
        assertEquals(0.3, options["temperature"])
        // requested 1024, configured limit is some default — expect num_predict to be the min(1024, default).
        val numPredict = options["num_predict"] as Int
        assertTrue(numPredict <= 1024, "num_predict ($numPredict) should not exceed requested 1024")
        assertTrue(numPredict > 0)
    }
}
