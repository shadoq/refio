package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [ZAIAdapter.buildRequestBody] thinking handling.
 *
 * GLM 4.5+ models think by default unless the request carries `thinking.type=disabled`. The
 * adapter used to send no `thinking` field at all, so the user's "thinking OFF" toggle was a
 * silent no-op: on a long single-file task glm-5.3-flash spent its whole output budget
 * (32649 of 32684 completion tokens) on reasoning and ended the turn without a single tool call.
 */
class ZAIAdapterThinkingTest {

    /** Exposes the protected [ZAIAdapter.buildRequestBody] to the test. */
    private class Testable : ZAIAdapter(model = "glm-5.3") {
        fun body(kwargs: Map<String, Any>): Map<String, Any> = buildRequestBody(
            requestMessages = listOf(mapOf("role" to "user", "content" to "hi")),
            effectiveMaxTokens = 1024,
            temperature = 0.7,
            streaming = false,
            kwargs = kwargs,
            requestId = "req-1",
        )
    }

    private fun thinkingType(kwargs: Map<String, Any>): Any? {
        @Suppress("UNCHECKED_CAST")
        val thinking = Testable().body(kwargs)["thinking"] as? Map<String, Any>
        return thinking?.get("type")
    }

    @Test
    fun `thinking OFF disables GLM reasoning`() {
        assertEquals("disabled", thinkingType(mapOf("thinking" to false)))
    }

    @Test
    fun `no thinking kwarg also disables GLM reasoning instead of falling back to the provider default`() {
        assertEquals("disabled", thinkingType(emptyMap()))
    }

    @Test
    fun `thinking ON enables GLM reasoning`() {
        assertEquals("enabled", thinkingType(mapOf("thinking" to true)))
        assertEquals("enabled", thinkingType(mapOf("thinking" to "high")))
    }
}
