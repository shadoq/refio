package pl.jclab.refio.core.services

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for model-aware token estimation (docs/0057).
 *
 * Why these matter (Rule 9): a flat 3.5 chars/token over-estimates capacity on local
 * code models (qwen/llama ~3.2), so the budget thinks more text fits than the model's
 * window allows — Ollama then silently truncates from the head and the agent hallucinates
 * success. The prior + calibration keep the budget honest per-model.
 */
class TokenRatioCalibratorTest {

    @BeforeTest
    @AfterTest
    fun cleanState() {
        TokenRatioCalibrator.reset()
    }

    @Test
    fun `Tier1 prior picks family-specific ratio, not the flat base`() {
        // qwen/coder/llama/mistral are denser than the 3.5 base
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("ollama/qwen2.5-coder:7b"))
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("llama3.1:8b"))
        // cloud families sit near base
        assertEquals(3.6, PromptTokenEstimator.charsPerToken("gpt-4o"))
        assertEquals(3.6, PromptTokenEstimator.charsPerToken("claude-3.7-sonnet"))
        // Only a caller with no model at all falls back to the shared base.
        assertEquals(PromptTokenEstimator.CHARS_PER_TOKEN_BASE, PromptTokenEstimator.charsPerToken(null))
    }

    /**
     * An unrecognized name must get the DENSE prior, not the loose base. Measured case: ollama's
     * own `/api/show` reports `ornith:35b` as architecture `qwen35moe`, a dense tokenizer (~3.2),
     * but the name carries no family fragment. Under the loose base the estimate ran ~9% low, so a
     * prompt that did overflow `num_ctx` looked like it fitted and Ollama truncated the head in
     * silence. Under-counting is the only direction that costs correctness; over-counting merely
     * leaves a sliver of the window unused, and the calibrator corrects it after the first call.
     */
    @Test
    fun `a model whose name names no family gets the dense prior, not the loose base`() {
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("ornith:35b"))
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("gemma3:12b"))
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("some-unknown-model"))
    }

    // gpt-oss runs locally on ollama, so matching the bare "gpt" fragment handed it the cloud BPE
    // ratio and the same silent-truncation exposure as the case above.
    @Test
    fun `a local model with a cloud-looking name is still treated as dense`() {
        assertEquals(3.2, PromptTokenEstimator.charsPerToken("gpt-oss:20b"))
    }

    // Cloud tokenizers really are looser, and a cloud provider errors on an oversized prompt
    // instead of truncating it, so there is no silent-truncation risk to protect against here.
    @Test
    fun `cloud BPE families keep the loose ratio`() {
        assertEquals(3.6, PromptTokenEstimator.charsPerToken("gemini-2.5-pro"))
        assertEquals(3.6, PromptTokenEstimator.charsPerToken("google/gemini-3-pro"))
        assertEquals(3.6, PromptTokenEstimator.charsPerToken("grok-4"))
    }

    @Test
    fun `long qwen prompt is budgeted with ~3,2 ratio not 3,5`() {
        val text = "x".repeat(32_000)
        val qwenTokens = PromptTokenEstimator.estimateBase(text, "qwen2.5-coder:7b")
        val baseTokens = PromptTokenEstimator.estimateBase(text)
        // A denser ratio => MORE estimated tokens for the same text => protects against
        // under-counting that leads to truncation.
        assertTrue(qwenTokens > baseTokens, "qwen estimate ($qwenTokens) should exceed flat-base ($baseTokens)")
        assertEquals((32_000 / 3.2).toInt(), qwenTokens)
    }

    @Test
    fun `observe twice converges ratioFor to the observed value`() {
        val model = "ollama/qwen2.5-coder:7b"
        // prior is 3.2; real usage says the text is denser (3.0 chars/token)
        TokenRatioCalibrator.observe(model, chars = 3000, realTokens = 1000, truncationSuspected = false)
        TokenRatioCalibrator.observe(model, chars = 3000, realTokens = 1000, truncationSuspected = false)
        // EMA: r1 = 3.0 (first), r2 = 0.7*3.0 + 0.3*3.0 = 3.0 -> converged to 3.0
        assertEquals(3.0, TokenRatioCalibrator.ratioFor(model), 0.0001)
    }

    @Test
    fun `ratioFor falls back to the Tier1 prior before any observation`() {
        assertEquals(3.2, TokenRatioCalibrator.ratioFor("qwen2.5-coder:7b"), 0.0001)
    }

    @Test
    fun `observe ignores non-positive token counts`() {
        val model = "gpt-4o"
        TokenRatioCalibrator.observe(model, chars = 100, realTokens = 0, truncationSuspected = false)
        TokenRatioCalibrator.observe(model, chars = 100, realTokens = -5, truncationSuspected = false)
        // still the prior, no NaN/divide-by-zero
        assertEquals(3.6, TokenRatioCalibrator.ratioFor(model), 0.0001)
    }

    /**
     * The poisoning this guard exists for. Ollama reports the prompt length AFTER it truncated, so
     * pairing the full character count with that number yields an inflated chars/token ratio. A
     * higher ratio lowers every later estimate, which blinds the very overflow guard that flagged
     * the truncation, and simultaneously tells the context budget it can pack more text in. Left
     * unguarded the loop reinforces itself: each truncation makes the next one harder to see.
     */
    @Test
    fun `a sample from a turn that may have been truncated is not folded into the ratio`() {
        val model = "ornith:35b"
        // 117565 chars really was ~36700 tokens at 3.2; ollama answered "32256" after cutting the
        // head, which reads as 3.65 chars/token.
        TokenRatioCalibrator.observe(model, chars = 117_565, realTokens = 32_256, truncationSuspected = true)

        assertEquals(3.2, TokenRatioCalibrator.ratioFor(model), 0.0001, "a truncated sample must leave the prior untouched")
    }

    @Test
    fun `a clean sample still teaches the ratio`() {
        val model = "ornith:35b"
        TokenRatioCalibrator.observe(model, chars = 96_000, realTokens = 32_000, truncationSuspected = false)

        assertEquals(3.0, TokenRatioCalibrator.ratioFor(model), 0.0001)
    }

    @Test
    fun `model-aware estimators stay backward compatible without modelId`() {
        val text = "hello world ".repeat(50)
        assertEquals(PromptTokenEstimator.estimateBase(text), PromptTokenEstimator.estimateBase(text, null))
        assertEquals(PromptTokenEstimator.maxCharsForTokens(100), PromptTokenEstimator.maxCharsForTokens(100, null))
    }

    @Test
    fun `estimateTokensForChars agrees with estimateBase - single source of truth`() {
        // Callers that only have a char count (ContextReferenceResolver, OpenAICompatibleAdapter
        // streaming fallback) must produce the same number as materializing the text and calling
        // estimateBase — no more ad-hoc `length / 4` divisions diverging from the shared ratio.
        for (n in intArrayOf(0, 1, 7, 350, 32_000)) {
            assertEquals(
                PromptTokenEstimator.estimateBase("x".repeat(n)),
                PromptTokenEstimator.estimateTokensForChars(n),
                "mismatch at $n chars",
            )
        }
    }

    @Test
    fun `maxCharsForTokens applies a downward safety margin for known models`() {
        // Budgeting must under-estimate capacity (ratio * 0.9) so we never overflow the window.
        val plain = PromptTokenEstimator.maxCharsForTokens(1000, "qwen2.5-coder:7b")
        val expected = (1000 * 3.2 * 0.9).toInt()
        assertEquals(expected, plain)
    }
}
