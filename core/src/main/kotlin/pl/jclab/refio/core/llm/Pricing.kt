package pl.jclab.refio.core.llm

/**
 * Pricing information for LLM providers.
 *
 * All prices are per 1 million tokens (USD).
 * Updated: 2024-01-15
 */

data class ModelPricing(
    val input: Double,
    val output: Double,
    // Cache-read / cache-write rates per 1M tokens. Populated by getModelPricing(); when a model
    // has no known cache price they fall back to the full input rate (no discount), so an unknown
    // cache never lowers the estimate. Constructors that only pass input/output (e.g. API display)
    // leave these at 0.0 and are not used for cost calculation.
    val cachedInput: Double = 0.0,
    val cacheWriteInput: Double = 0.0
)

/**
 * Get pricing for a specific model.
 *
 * Priority: ModelDefinitions (single source of truth) -> PRICING fallback -> fuzzy match
 *
 * @param provider Provider name (openai, anthropic, ollama)
 * @param model Model identifier
 * @return ModelPricing with input and output prices per 1M tokens
 * @throws IllegalArgumentException if provider is unknown
 *
 * Examples:
 * ```
 * getModelPricing("openai", "gpt-4o-mini")  // ModelPricing(0.15, 0.60)
 * getModelPricing("anthropic", "claude-3-5-sonnet-20241022")  // ModelPricing(3.00, 15.00)
 * getModelPricing("ollama", "qwen2.5:7b")  // ModelPricing(0.00, 0.00)
 * ```
 */
fun getModelPricing(provider: String, model: String): ModelPricing {
    // 1. First check ModelDefinitions (single source of truth, per 1M tokens).
    val definition = ModelDefinitions.getDefinition(provider, model)
    if (definition != null && (definition.costPer1MInput > 0.0 || definition.costPer1MOutput > 0.0)) {
        return pricingOf(definition.costPer1MInput, definition.costPer1MOutput, definition.costPer1MCachedInput)
    }

    // 2. Fall back to live ModelRegistry cache (populated by adapter listModels()).
    // OpenRouter's /models endpoint reports accurate per-model prices; this lets
    // specific models (e.g. anthropic/claude-haiku-4.5) override the family-level
    // baseline configured in ModelDefinitions.
    val cached = getModelConfigFromCache(model)
    if (cached != null && (cached.costPer1mInput > 0.0 || cached.costPer1mOutput > 0.0)) {
        return pricingOf(cached.costPer1mInput, cached.costPer1mOutput, null)
    }

    // 3. If ModelDefinitions had a hit (even with 0/0), return it — explicit "free" baseline.
    if (definition != null) {
        return pricingOf(definition.costPer1MInput, definition.costPer1MOutput, definition.costPer1MCachedInput)
    }

    return ModelPricing(0.00, 0.00)
}

/**
 * Build a ModelPricing, deriving the cache rates. Cache-read uses the explicit per-model price
 * when known, otherwise the full input rate (no discount). Cache-write (Anthropic) has no per-model
 * price here, so it uses the full input rate too - conservative: an unknown cache never lowers cost.
 */
private fun pricingOf(input: Double, output: Double, cachedInput: Double?): ModelPricing {
    return ModelPricing(
        input = input,
        output = output,
        cachedInput = cachedInput ?: input,
        cacheWriteInput = input
    )
}

/**
 * Calculate cost for a completion.
 *
 * @param provider Provider name
 * @param model Model identifier
 * @param inputTokens Number of input tokens
 * @param outputTokens Number of output tokens
 * @return Total cost in USD
 *
 * Examples:
 * ```
 * calculateCost("openai", "gpt-4o-mini", 1000, 500)  // 0.00045
 * calculateCost("anthropic", "claude-3-5-sonnet-20241022", 2000, 1000)  // 0.021
 * ```
 */
fun calculateCost(
    provider: String,
    model: String,
    inputTokens: Int,
    outputTokens: Int,
    cachedInputTokens: Int = 0,
    cacheWriteInputTokens: Int = 0
): Double {
    val pricing = getModelPricing(provider, model)

    // inputTokens is the full input count; the cached/write parts are subsets of it, each priced
    // at its own rate, and the remainder ("fresh") at the full input rate.
    val freshInputTokens = (inputTokens - cachedInputTokens - cacheWriteInputTokens).coerceAtLeast(0)
    val inputCost = (freshInputTokens / 1_000_000.0) * pricing.input +
        (cachedInputTokens / 1_000_000.0) * pricing.cachedInput +
        (cacheWriteInputTokens / 1_000_000.0) * pricing.cacheWriteInput
    val outputCost = (outputTokens / 1_000_000.0) * pricing.output

    return inputCost + outputCost
}
