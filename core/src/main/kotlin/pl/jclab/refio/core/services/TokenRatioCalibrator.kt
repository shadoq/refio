package pl.jclab.refio.core.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Closed-loop chars/token calibration from real token counters.
 *
 * The flat [PromptTokenEstimator.CHARS_PER_TOKEN_BASE] = 3.5 ratio is only accurate for
 * cloud models; local code models (qwen/llama/mistral) pack ~3.2 chars/token, so a flat
 * estimate over-counts capacity and the budget lets through more text than the window
 * holds. Adapters already return real input-token counts (Ollama `prompt_eval_count`,
 * Anthropic `usage.input_tokens`, OpenAI `usage.prompt_tokens`) — this singleton folds
 * those observations into a per-model EMA so subsequent budget math self-corrects.
 *
 * Thread-safe process-global singleton keyed by `modelId`, matching the convention of
 * [pl.jclab.refio.core.services.monitoring.ModelUsageStats] and
 * [pl.jclab.refio.core.llm.GlobalMetrics]. Keying by model (not by session) means the
 * learned ratio is shared across sessions that use the same model, which is the right
 * granularity — the ratio is a property of the model's tokenizer, not the session.
 * Reset via [reset] (tests only).
 *
 * IMPORTANT: calibration only refines the *estimate*. It does NOT detect truncation —
 * when Ollama truncates an oversized prompt it reports the post-truncation length, so a
 * returned `inputTokens` can never exceed the window. Overflow detection lives on the
 * pre-send estimate (see [pl.jclab.refio.core.llm.adapters.OllamaAdapter] and
 * [pl.jclab.refio.core.debug.ContextOverflowTracker]).
 */
object TokenRatioCalibrator {

    /** EMA weight for the existing ratio; the new observation gets (1 - this). */
    private const val EMA_DECAY = 0.7

    private val ratios = ConcurrentHashMap<String, Double>()

    /**
     * Fold one real observation into the per-model EMA.
     *
     * @param modelId resolved model id (provider-qualified or bare — used verbatim as the key)
     * @param chars total prompt characters that were sent (system + messages)
     * @param realTokens the provider-reported input-token count for that prompt
     * @param truncationSuspected true when this request's prompt may have been cut to fit the
     *   window, which makes the pair unusable as evidence (see below)
     */
    fun observe(modelId: String, chars: Int, realTokens: Int, truncationSuspected: Boolean) {
        if (realTokens <= 0 || chars <= 0) {
            return
        }
        // A possibly-truncated request proves nothing about the tokenizer: a provider that truncates
        // reports the POST-truncation length, so the full char count paired with it reads as a
        // looser ratio than the model really has. Folding that in lowers every later estimate,
        // which blinds the pre-send overflow guard and at the same time tells the context budget it
        // can pack more text in — each truncation would make the next one harder to see.
        if (truncationSuspected) {
            return
        }
        val observed = chars.toDouble() / realTokens
        ratios.compute(modelId) { _, existing ->
            if (existing == null) observed else EMA_DECAY * existing + (1 - EMA_DECAY) * observed
        }
    }

    /**
     * Best current chars/token ratio for [modelId]: the calibrated EMA if we have observed
     * this model in-process, otherwise the Tier-1 family prior.
     */
    fun ratioFor(modelId: String): Double = ratios[modelId] ?: PromptTokenEstimator.charsPerToken(modelId)

    /** Test-only: drop all learned ratios. */
    fun reset() {
        ratios.clear()
    }
}
