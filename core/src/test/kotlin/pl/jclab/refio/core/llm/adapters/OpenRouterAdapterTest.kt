package pl.jclab.refio.core.llm.adapters

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/**
 * Regression tests for [OpenRouterAdapter.buildRequestBody] reasoning/thinking handling.
 *
 * The bug we guard against (session a9cd298e, minimax-m3): with the user's "thinking" toggle
 * OFF the adapter sent nothing to control reasoning, so reasoning-capable models routed through
 * OpenRouter defaulted to reasoning ON and burned thousands of hidden completion tokens (observed
 * 23.7k reasoning tokens, zero tool calls, turn ended on intent prose). The toggle was a silent
 * no-op for OpenRouter. Fix: OFF now sends OpenRouter's unified `reasoning.enabled=false`.
 */
class OpenRouterAdapterTest {

    /** Exposes the protected [OpenRouterAdapter.buildRequestBody] to the test. */
    private class Testable(model: String) : OpenRouterAdapter(model = model) {
        fun body(kwargs: Map<String, Any>): Map<String, Any> = buildRequestBody(
            requestMessages = listOf(mapOf("role" to "user", "content" to "hi")),
            effectiveMaxTokens = 1024,
            temperature = 0.7,
            streaming = false,
            kwargs = kwargs,
            requestId = "req-1",
        )

        /** Exposes the protected mid-stream error handler to the test. */
        fun streamError(json: String) =
            onStreamRawChunk(JsonParser.parseString(json).asJsonObject)
    }

    @Test
    fun `thinking OFF suppresses reasoning via reasoning enabled=false`() {
        val body = Testable("minimax/minimax-m3").body(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val reasoning = body["reasoning"] as? Map<String, Any>
        assertEquals(false, reasoning?.get("enabled"), "thinking OFF must send reasoning.enabled=false")
        assertTrue(!body.containsKey("thinking"), "no Anthropic thinking block when thinking is OFF")
    }

    @Test
    fun `thinking ON for a non-claude model sends neither reasoning nor thinking`() {
        val body = Testable("minimax/minimax-m3").body(mapOf("thinking" to true))
        assertTrue(!body.containsKey("reasoning"), "must NOT suppress reasoning when thinking is ON")
        assertTrue(!body.containsKey("thinking"), "non-claude model gets no Anthropic thinking block")
    }

    @Test
    fun `thinking ON for a claude model enables the thinking block, not reasoning-off`() {
        val body = Testable("anthropic/claude-opus-4-7").body(mapOf("thinking" to true))
        @Suppress("UNCHECKED_CAST")
        val thinking = body["thinking"] as? Map<String, Any>
        assertEquals("enabled", thinking?.get("type"))
        assertTrue(!body.containsKey("reasoning"), "claude thinking-ON must not also disable reasoning")
    }

    @Test
    fun `an effort string sets OpenRouter unified reasoning effort for a non-claude model`() {
        // reasoningEffort ("low"/"medium"/"high") arrives via kwargs["thinking"] as a String and
        // must translate to OpenRouter's unified reasoning.effort (not a suppression, not ignored).
        val body = Testable("minimax/minimax-m3").body(mapOf("thinking" to "high"))
        @Suppress("UNCHECKED_CAST")
        val reasoning = body["reasoning"] as? Map<String, Any>
        assertEquals("high", reasoning?.get("effort"), "effort string must set reasoning.effort")
    }

    @Test
    fun `an effort string scales the claude thinking budget on OpenRouter`() {
        // Claude on OpenRouter uses a token budget rather than an effort enum; HIGH must map to
        // a larger budget than LOW so the level is actually honored.
        @Suppress("UNCHECKED_CAST")
        fun budget(effort: String): Int =
            (Testable("anthropic/claude-opus-4-7").body(mapOf("thinking" to effort))["thinking"]
                as Map<String, Any>)["budget_tokens"] as Int
        assertTrue(budget("high") > budget("low"), "HIGH budget must exceed LOW budget")
    }

    @Test
    fun `thinking OFF does not suppress reasoning for mandatory-reasoning Kimi endpoints`() {
        // The Kimi family rejects reasoning.enabled=false ("Reasoning is mandatory for this
        // endpoint and cannot be disabled"), so thinking OFF must NOT emit the suppression key
        // for any kimi variant — both the 1M-context k3 and the family fallback (e.g. k2.7-code).
        for (model in listOf("moonshotai/kimi-k3", "moonshotai/kimi-k2.7-code")) {
            val body = Testable(model).body(emptyMap())
            assertTrue(
                !body.containsKey("reasoning"),
                "mandatory-reasoning model $model must not receive reasoning.enabled=false"
            )
        }
    }

    @Test
    fun `a mid-stream 429 error envelope surfaces as a retryable rate limit`() {
        // OpenRouter delivers upstream provider errors inside the SSE body (HTTP 200 outer,
        // {"error":{...}} chunk inner). A 429 there must keep its rate-limit classification so
        // the retry handler backs off and retries instead of failing the whole turn - the streaming
        // path previously threw a bare exception, which the classifier treated as non-retryable.
        val error = assertFailsWith<RefioError.LLMRateLimit> {
            Testable("tencent/hy3").streamError(
                """{"error":{"message":"Provider returned error","code":429,"metadata":{"provider_name":"GMICloud"}}}"""
            )
        }
        val detail = error.message.orEmpty() + (error.cause?.message.orEmpty())
        assertTrue(detail.contains("429"), "429 context must be preserved: $detail")
    }

    @Test
    fun `a mid-stream 500 error envelope is not misclassified as a rate limit`() {
        // Guard the other direction: routing through mapHttpError must not turn every upstream
        // failure into a rate limit - only 429 is retryable-as-rate-limit.
        val error = assertFailsWith<RefioError> {
            Testable("tencent/hy3").streamError(
                """{"error":{"message":"Provider returned error","code":500,"metadata":{"provider_name":"GMICloud"}}}"""
            )
        }
        assertIsNot<RefioError.LLMRateLimit>(error, "a 500 is not a rate limit")
    }

    @Test
    fun `a mid-stream 429 envelope aborts the real SSE stream instead of being swallowed`() = runTest {
        // The classification above only helps if the SSE loop actually lets the exception out.
        // When it is swallowed the stream reads on to [DONE] and the caller gets the partial
        // text as if it were the whole answer, while the retry handler never sees the 429 and
        // cannot back off. Drive the real stream here, not the raw-chunk hook.
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"partial \"}}]}\n\n")
            append(
                "data: {\"error\":{\"message\":\"Provider returned error\",\"code\":429," +
                    "\"metadata\":{\"provider_name\":\"GMICloud\"}}}\n\n"
            )
            append("data: [DONE]\n\n")
        }
        val adapter = OpenRouterAdapter(
            model = "tencent/hy3",
            configService = mockConfig(),
            httpClientOverride = mockSseClient(sse),
        )

        assertFailsWith<RefioError.LLMRateLimit> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 64,
                temperature = 0.0,
                streaming = true,
                onStreamChunk = { },
                kwargs = emptyMap(),
            )
        }
    }

    @Test
    fun `an undecodable SSE line is skipped and the rest of the stream still arrives`() = runTest {
        // The other half of the contract: only a deliberate abort ends the stream. A line we
        // cannot decode must not fail the turn, because the provider still delivers the answer
        // in the surrounding chunks.
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"before \"}}]}\n\n")
            append("data: {not json at all\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"after\"},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val adapter = OpenRouterAdapter(
            model = "tencent/hy3",
            configService = mockConfig(),
            httpClientOverride = mockSseClient(sse),
        )

        val response = adapter.chat(
            messages = listOf(LLMMessage(role = "user", content = "hi")),
            systemMessages = emptyList(),
            maxTokens = 64,
            temperature = 0.0,
            streaming = true,
            onStreamChunk = { },
            kwargs = emptyMap(),
        )

        assertEquals("before after", response.content)
        assertEquals("stop", response.finishReason)
    }

    private fun mockConfig(): ConfigService {
        val config = mockk<ConfigService>()
        every { config.get(any(), any(), any(), any()) } returns "test-key"
        every { config.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        return config
    }

    private fun mockSseClient(sseBody: String): HttpClient = HttpClient(MockEngine { _ ->
        respond(
            content = sseBody,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
        )
    }) {
        install(ContentNegotiation) {
            gson {
                serializeNulls()
            }
        }
    }
}
