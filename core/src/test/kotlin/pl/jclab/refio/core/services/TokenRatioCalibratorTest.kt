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
        // unknown / null fall back to the shared base
        assertEquals(PromptTokenEstimator.CHARS_PER_TOKEN_BASE, PromptTokenEstimator.charsPerToken("some-unknown-model"))
        assertEquals(PromptTokenEstimator.CHARS_PER_TOKEN_BASE, PromptTokenEstimator.charsPerToken(null))
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
        TokenRatioCalibrator.observe(model, chars = 3000, realTokens = 1000) // observed 3.0
        TokenRatioCalibrator.observe(model, chars = 3000, realTokens = 1000) // observed 3.0
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
        TokenRatioCalibrator.observe(model, chars = 100, realTokens = 0)
        TokenRatioCalibrator.observe(model, chars = 100, realTokens = -5)
        // still the prior, no NaN/divide-by-zero
        assertEquals(3.6, TokenRatioCalibrator.ratioFor(model), 0.0001)
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
