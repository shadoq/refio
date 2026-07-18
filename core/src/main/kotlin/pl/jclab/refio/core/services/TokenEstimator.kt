package pl.jclab.refio.core.services

import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.turn.TurnPrompt

private val logger = dualLogger("TokenEstimator")

/**
 * Estimates token count for prompts before sending to LLM.
 *
 * Uses tiktoken-compatible algorithm for accurate estimation.
 * Based on average characters per token (3.5) with provider-specific adjustments.
 *
 * **Single source of truth for chars/token ratio**: [CHARS_PER_TOKEN_BASE] in the companion
 * object below is referenced by [pl.jclab.refio.core.services.context.ContextTokenEstimator]
 * and [pl.jclab.refio.core.llm.TokenEstimator] so all three estimators agree on the base
 * ratio. PromptTokenEstimator adds optional code-block / JSON overhead and provider
 * multipliers on top — used only on the compaction hot path where provider is known.
 */
class PromptTokenEstimator {

    companion object {
        /** Base chars-per-token estimate, shared across all estimators in :core. */
        const val CHARS_PER_TOKEN_BASE: Double = 3.5

        /**
         * Downward safety margin applied to the chars/token ratio when computing *capacity*
         * (how many chars fit in a token budget). Under-estimating capacity is the safe
         * direction — better to leave a little window unused than to overflow it and have
         * Ollama silently truncate from the head.
         */
        private const val BUDGET_SAFETY_FACTOR = 0.9

        /**
         * Family-level chars/token prior keyed by model-name fragment.
         *
         * The ratio is dominated by *content* (code ~3.0, prose ~4.0) more than by the model,
         * but cloud tokenizers (gpt/claude BPE) and local code-model tokenizers (qwen/llama)
         * differ enough that a per-family cold-start prior beats one flat constant. Once real
         * usage is observed, [TokenRatioCalibrator] overrides this per model.
         */
        fun charsPerToken(modelId: String?): Double = when {
            modelId == null -> CHARS_PER_TOKEN_BASE
            modelId.contains("gpt") || modelId.contains("claude") -> 3.6
            modelId.contains("qwen") || modelId.contains("coder") ||
                modelId.contains("llama") || modelId.contains("mistral") -> 3.2
            else -> CHARS_PER_TOKEN_BASE // 3.5
        }

        /** Resolve the best chars/token ratio for [modelId]: calibrated EMA, then family prior, then base. */
        private fun resolveRatio(modelId: String?): Double =
            if (modelId == null) CHARS_PER_TOKEN_BASE else TokenRatioCalibrator.ratioFor(modelId)

        /**
         * Plain char/token estimate without code-block or JSON overhead.
         * Used by lightweight estimators (ContextTokenEstimator, llm.TokenEstimator)
         * that don't have provider context.
         */
        fun estimateBase(text: String): Int = estimateBase(text, null)

        /**
         * Model-aware variant of [estimateBase]. Uses the calibrated/family ratio for [modelId]
         * so dense local-model prompts are not under-counted. [modelId] = null keeps the legacy
         * flat-base behavior for callers without model context.
         */
        fun estimateBase(text: String, modelId: String?): Int {
            if (text.isBlank()) return 0
            return estimateTokensForChars(text.length, modelId)
        }

        /**
         * Token estimate from a precomputed character count, for callers that only have a length
         * (e.g. summed message sizes) and don't want to materialize the text. Same ratio and
         * rounding as [estimateBase] — the single chars→tokens conversion in :core, so every
         * estimator agrees instead of sprinkling ad-hoc `length / 4` divisions.
         */
        fun estimateTokensForChars(chars: Int, modelId: String? = null): Int {
            if (chars <= 0) return 0
            return kotlin.math.max(1, (chars / resolveRatio(modelId)).toInt())
        }

        /**
         * Estimate the token cost of native tool schemas as they are serialized into the request
         * body's `tools` array. These schemas are NOT part of the system-prompt text, so callers
         * that size the context budget against the system prompt alone (TurnPromptBuilder) miss
         * them — letting the dynamic sections over-allocate and push the real prompt past the
         * model's context window, where Ollama silently truncates from the head.
         *
         * The per-schema char measure (name + description + parameters JSON) mirrors the adapters'
         * own overflow guard (e.g. OllamaAdapter.estimateOllamaInputTokens) so the budget
         * reservation tracks what is actually sent on the wire.
         */
        fun estimateNativeToolSchemaTokens(
            schemas: List<pl.jclab.refio.core.tools.base.ToolSchema>?,
            modelId: String? = null,
        ): Int {
            if (schemas.isNullOrEmpty()) return 0
            val chars = schemas.sumOf { schema ->
                schema.name.length + schema.description.length + schema.parametersJsonSchema.toString().length
            }
            return estimateTokensForChars(chars, modelId)
        }

        /**
         * Inverse of [estimateBase] — how many characters fit in the given token budget.
         * Used by truncation helpers.
         */
        fun maxCharsForTokens(maxTokens: Int): Int = maxCharsForTokens(maxTokens, null)

        /**
         * Model-aware variant of [maxCharsForTokens]. Applies the [BUDGET_SAFETY_FACTOR] for
         * known models so capacity is under-estimated (never overflowed). [modelId] = null keeps
         * the legacy flat-base behavior with no margin for backward compatibility.
         */
        fun maxCharsForTokens(maxTokens: Int, modelId: String?): Int {
            if (maxTokens <= 0) return 0
            val ratio = if (modelId == null) CHARS_PER_TOKEN_BASE else resolveRatio(modelId) * BUDGET_SAFETY_FACTOR
            return (maxTokens * ratio).toInt()
        }

        // Provider-specific multipliers
        private val PROVIDER_MULTIPLIERS = mapOf(
            "anthropic" to 1.1,  // Claude tends to have more tokens
            "openai" to 1.0,
            "ollama" to 1.0,
            "gemini" to 1.05,
            "openrouter" to 1.0,
            "lmstudio" to 1.0,
            "generic_openai" to 1.0,
            "zai" to 1.0
        )

        // Compiled once - estimateString runs on the prompt hot path.
        private val CODE_FENCE_REGEX = Regex("```")
        private val JSON_OBJECT_REGEX = Regex("\\{[^}]+\\}")
    }

    /**
     * Estimate token count for a prompt.
     *
     * @param prompt The TurnPrompt to estimate
     * @param provider LLM provider name for adjustment
     * @return Estimated token count
     */
    fun estimate(prompt: TurnPrompt, provider: String = "openai"): Int {
        val systemTokens = estimateString(prompt.systemPrompt)
        val messageTokens = prompt.messages.sumOf { msg ->
            estimateString(msg.content) + 4  // Message overhead
        }

        val multiplier = PROVIDER_MULTIPLIERS[provider] ?: 1.0
        return ((systemTokens + messageTokens) * multiplier).toInt()
    }

    /**
     * Estimate tokens for a single string.
     *
     * @param text Text to estimate
     * @return Estimated token count
     */
    fun estimateString(text: String): Int {
        if (text.isBlank()) return 0

        // Count special patterns that typically use more tokens
        val codeBlockCount = CODE_FENCE_REGEX.findAll(text).count()
        val jsonObjectCount = JSON_OBJECT_REGEX.findAll(text).count()

        val baseEstimate = estimateBase(text)
        val overhead = codeBlockCount * 3 + jsonObjectCount * 2

        return baseEstimate + overhead
    }

    /**
     * Check if prompt fits within context window.
     *
     * @param prompt The prompt to check
     * @param maxTokens Maximum allowed tokens
     * @param reserveForOutput Tokens to reserve for model output
     * @param provider LLM provider name
     * @return Pair of (fits, estimatedTokens)
     */
    fun checkFits(
        prompt: TurnPrompt,
        maxTokens: Int,
        reserveForOutput: Int = 4096,
        provider: String = "openai"
    ): Pair<Boolean, Int> {
        val estimated = estimate(prompt, provider)
        val available = maxTokens - reserveForOutput
        return (estimated <= available) to estimated
    }

    /**
     * Get safe token limit for a provider.
     *
     * Returns conservative context window size for the given provider.
     *
     * @param provider LLM provider name
     * @param model Model name (optional, for model-specific limits)
     * @return Safe token limit
     */
    fun getSafeTokenLimit(provider: String, model: String? = null): Int {
        // Model-specific limits
        if (model != null) {
            return when {
                // GPT-4o and GPT-4o-mini
                model.startsWith("gpt-4o") && !model.contains("mini") -> 128000
                model.contains("mini") -> 128000
                // GPT-4-turbo
                model.startsWith("gpt-4-turbo") -> 128000
                // o1, o3 models
                model.startsWith("o1") || model.startsWith("o3") -> 200000
                // Claude 3.5/3.7 Sonnet
                model.contains("claude-3.5") || model.contains("claude-3.7") -> 200000
                model.contains("sonnet") -> 200000
                // Claude Opus
                model.contains("opus") -> 200000
                // Gemini
                model.contains("gemini-2.5") -> 1000000
                model.contains("gemini-2.0") -> 1000000
                model.contains("flash") -> 1000000
                // Default for known models
                else -> 128000
            }
        }

        // Provider defaults
        return when (provider) {
            "anthropic" -> 200000
            "openai" -> 128000
            "gemini" -> 1000000
            "zai" -> 128000
            "ollama", "lmstudio", "openrouter", "generic_openai" -> 32768
            else -> 128000
        }
    }
}
