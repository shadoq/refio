package pl.jclab.refio.core.services

import pl.jclab.refio.core.tools.base.ToolSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the native-tool-schema token reservation used to size the context budget.
 *
 * WHY it matters (Report 2 overflow): native tool schemas are sent in the request body's `tools`
 * array, NOT in the system-prompt text. Before this, the context budget counted only the prompt
 * text, so it packed RECENT_WORK / CONVERSATION up to the window and the real prompt (text +
 * schemas) blew past `num_ctx` — Ollama then silently truncates from the head, degrading output.
 * Reserving the schema cost lets the budget shrink the dynamic sections to actually fit.
 */
class PromptTokenEstimatorToolSchemaTest {

    private fun schema(name: String, props: Int): ToolSchema = ToolSchema(
        name = name,
        description = "Tool $name that does a thing with several documented parameters",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to (1..props).associate {
                "param_$it" to mapOf(
                    "type" to "string",
                    "description" to "Parameter number $it used by the $name tool"
                )
            },
            "required" to listOf("param_1")
        )
    )

    @Test
    fun `null or empty schema list reserves nothing`() {
        assertEquals(0, PromptTokenEstimator.estimateNativeToolSchemaTokens(null, "qwen3.5:9b"))
        assertEquals(0, PromptTokenEstimator.estimateNativeToolSchemaTokens(emptyList(), "qwen3.5:9b"))
    }

    @Test
    fun `a realistic native tool set reserves a non-trivial number of tokens`() {
        // ~28 schemas like the AGENT-mode tool set in Report 2 — must be a meaningful reservation,
        // not a rounding-error handful, or the budget would keep over-allocating the sections.
        val schemas = (1..28).map { schema("tool_$it", props = 3) }

        val reserved = PromptTokenEstimator.estimateNativeToolSchemaTokens(schemas, "qwen3.5:9b")

        assertTrue(
            reserved > 1000,
            "28 tool schemas must reserve >1000 tokens (was $reserved) — otherwise the budget under-counts the request"
        )
    }

    @Test
    fun `reservation grows with schema count`() {
        val few = PromptTokenEstimator.estimateNativeToolSchemaTokens((1..5).map { schema("t$it", 2) }, "qwen3.5:9b")
        val many = PromptTokenEstimator.estimateNativeToolSchemaTokens((1..25).map { schema("t$it", 2) }, "qwen3.5:9b")
        assertTrue(many > few, "more schemas must reserve more tokens (few=$few, many=$many)")
    }

    @Test
    fun `dense local tokenizer reserves at least as much as the flat default`() {
        // qwen/coder/llama tokenizers are denser (~3.2 chars/token) than the 3.5 flat default,
        // so the same schemas must not be under-counted for local models (docs/0057).
        val schemas = (1..10).map { schema("t$it", 3) }
        val qwen = PromptTokenEstimator.estimateNativeToolSchemaTokens(schemas, "qwen3.5:9b")
        val flat = PromptTokenEstimator.estimateNativeToolSchemaTokens(schemas, null)
        assertTrue(qwen >= flat, "dense local model must reserve >= flat estimate (qwen=$qwen, flat=$flat)")
    }
}
