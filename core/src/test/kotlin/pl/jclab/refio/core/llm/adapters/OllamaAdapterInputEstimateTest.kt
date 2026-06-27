package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.PromptTokenEstimator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the pre-send Ollama context-overflow estimate.
 *
 * Devil's-advocate review of branch `fix`: [OllamaAdapter.estimateOllamaInputTokens] estimated tokens
 * with the flat 3.5 chars/token default ([PromptTokenEstimator.estimateBase] with no model), ignoring
 * the model entirely. Dense local tokenizers (qwen/llama ~3.2 chars/token) were therefore UNDER-counted,
 * so a prompt that truly overflows num_ctx could slip past the [OLLAMA_CONTEXT_OVERFLOW] warning /
 * ContextOverflowTracker — the exact silent-truncation failure mode docs/0057 exists to surface. The
 * estimate must use the model's tokenizer ratio.
 */
class OllamaAdapterInputEstimateTest {

    @Test
    fun `input estimate uses the model tokenizer ratio, not the flat default`() {
        // Fixture model name hits the qwen/coder family prior (3.2) and is never observed by another
        // test, so the calibrator returns the clean prior — no cross-test contamination.
        val adapter = OllamaAdapter(model = "qwen-fixture-coder-7b")
        val messages = listOf(mapOf("role" to "user", "content" to "x".repeat(5000)))
        val messageChars = 5000 + 10 // estimateOllamaInputTokens adds 10 per message for role tokens

        val modelAware = PromptTokenEstimator.estimateBase("x".repeat(messageChars), "qwen-fixture-coder-7b")
        val flatDefault = PromptTokenEstimator.estimateBase("x".repeat(messageChars))

        // Precondition: the qwen ratio (3.2) genuinely differs from the flat 3.5 default, so the
        // assertion below actually distinguishes the buggy (flat) path from the fixed (model) path.
        assertTrue(modelAware > flatDefault, "qwen ratio must over-count vs the flat default")
        assertEquals(modelAware, adapter.estimateOllamaInputTokens(messages, null))
    }
}
