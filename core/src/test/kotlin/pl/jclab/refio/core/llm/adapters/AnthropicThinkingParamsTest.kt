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
    fun `model that thinks by default gets the effort level and asks for readable thinking summaries`() {
        // These models omit thinking text unless asked, so the UI would show a silent pause instead
        // of the reasoning; no thinking budget either, they reject it.
        val models = listOf(
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-sonnet-5", "claude-haiku-5-5",
            "claude-fable-5-2", "claude-mythos-5",
        )
        for (model in models) {
            val params = AnthropicAdapter(model = model).thinkingParams(ReasoningEffort.HIGH)

            assertEquals(
                mapOf(
                    "thinking" to mapOf("type" to "adaptive", "display" to "summarized"),
                    "output_config" to mapOf("effort" to "high"),
                ),
                params,
                "model $model",
            )
        }
    }

    @Test
    fun `opus 4_8 gets only the effort level, so a thinking mode it does not run today is not switched on`() {
        val params = AnthropicAdapter(model = "claude-opus-4-8").thinkingParams(ReasoningEffort.LOW)

        assertEquals(mapOf("output_config" to mapOf("effort" to "low")), params)
    }

    @Test
    fun `model that thinks only when asked sends nothing with reasoning off`() {
        // Opus 4.8 leaves thinking off unless the request enables it.
        val params = AnthropicAdapter(model = "claude-opus-4-8").thinkingParams(ReasoningEffort.OFF)

        assertTrue(params.isEmpty())
    }

    @Test
    fun `claude 5 models with reasoning off run at low effort and still show what they think`() {
        // Each of them thinks unless told otherwise; left alone they run at high (medium on Opus 5.5
        // and Haiku 5.5). Their thinking text is omitted by default, so an unbounded default shows
        // up in the UI as a long silent stream.
        val models = listOf(
            "claude-sonnet-5", "claude-sonnet-5-5", "claude-opus-5", "claude-haiku-5-5",
            "claude-fable-5", "claude-fable-5-1", "claude-fable-5-2", "claude-mythos-5",
        )
        for (model in models) {
            val params = AnthropicAdapter(model = model).thinkingParams(ReasoningEffort.OFF)

            // They still think at low effort, so without readable summaries the stream stays silent.
            assertEquals(
                mapOf(
                    "thinking" to mapOf("type" to "adaptive", "display" to "summarized"),
                    "output_config" to mapOf("effort" to "low"),
                ),
                params,
                "model $model",
            )
        }
    }

    @Test
    fun `older model still gets the extended thinking budget`() {
        val params = AnthropicAdapter(model = "claude-3-5-sonnet-20241022").thinkingParams(ReasoningEffort.LOW)

        assertEquals(mapOf("thinking" to mapOf("type" to "enabled", "budget_tokens" to 2048)), params)
    }
}
