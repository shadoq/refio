package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
