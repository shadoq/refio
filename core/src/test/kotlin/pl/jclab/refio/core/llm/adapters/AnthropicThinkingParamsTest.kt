package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.ReasoningEffort
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How [AnthropicAdapter] turns the configured reasoning level into request fields.
 *
 * Models from Opus 4.7 on reject the legacy `thinking: {type: enabled, budget_tokens}` block
 * and steer thinking depth through `output_config.effort` instead; older models only understand
 * the budget block.
 */
class AnthropicThinkingParamsTest {

    @Test
    fun `adaptive model gets the effort level instead of a thinking budget it would reject`() {
        val params = AnthropicAdapter(model = "claude-opus-5-5").thinkingParams(ReasoningEffort.LOW)

        assertEquals(mapOf("output_config" to mapOf("effort" to "low")), params)
    }

    @Test
    fun `adaptive model with reasoning off sends nothing and keeps the provider default`() {
        val params = AnthropicAdapter(model = "claude-sonnet-5").thinkingParams(ReasoningEffort.OFF)

        assertTrue(params.isEmpty())
    }

    @Test
    fun `older model still gets the extended thinking budget`() {
        val params = AnthropicAdapter(model = "claude-3-5-sonnet-20241022").thinkingParams(ReasoningEffort.LOW)

        assertEquals(mapOf("thinking" to mapOf("type" to "enabled", "budget_tokens" to 2048)), params)
    }
}
