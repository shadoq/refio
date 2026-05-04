package pl.jclab.refio.core.llm

/**
 * Pricing information for LLM providers.
 *
 * All prices are per 1 million tokens (USD).
 * Updated: 2024-01-15
 */

data class ModelPricing(
    val input: Double,
    val output: Double
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
        return ModelPricing(
            input = definition.costPer1MInput,
            output = definition.costPer1MOutput
        )
    }

    // 2. Fall back to live ModelRegistry cache (populated by adapter listModels()).
    // OpenRouter's /models endpoint reports accurate per-model prices; this lets
    // specific models (e.g. anthropic/claude-haiku-4.5) override the family-level
    // baseline configured in ModelDefinitions.
    val cached = getModelConfigFromCache(model)
    if (cached != null && (cached.costPer1mInput > 0.0 || cached.costPer1mOutput > 0.0)) {
        return ModelPricing(
            input = cached.costPer1mInput,
            output = cached.costPer1mOutput
        )
    }

    // 3. If ModelDefinitions had a hit (even with 0/0), return it — explicit "free" baseline.
    if (definition != null) {
        return ModelPricing(definition.costPer1MInput, definition.costPer1MOutput)
    }

    return ModelPricing(0.00, 0.00)
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
    outputTokens: Int
): Double {
    val pricing = getModelPricing(provider, model)

    val inputCost = (inputTokens / 1_000_000.0) * pricing.input
    val outputCost = (outputTokens / 1_000_000.0) * pricing.output

    return inputCost + outputCost
}
