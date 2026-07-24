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
fun getModelPricing(provider: String, model: String): ModelPricing =
    resolveModelPricing(
        provider = provider,
        // ModelDefinitions: the coarse, family-level literal baseline (per 1M tokens).
        definition = ModelDefinitions.getDefinition(provider, model),
        // Live ModelRegistry cache, populated by adapter listModels(). OpenRouter's /models
        // endpoint reports accurate per-model prices.
        cached = getModelConfigFromCache(model),
    )

/**
 * Choose the effective pricing from the static [definition] baseline and the [cached] live price.
 *
 * Precedence:
 *  - **OpenRouter**: the live per-model `/models` price WINS over the literal baseline when present.
 *    The baseline is a coarse family-level fallback that ran ~10x low for some models (the Kimi
 *    family), and OpenRouter's own per-model price is authoritative - so a populated live price must
 *    not be shadowed by the literal. (The `usage.cost` returned per response is still the ultimate
 *    source of truth for BILLED cost; this precedence only governs pre-flight ESTIMATES, where no
 *    per-response cost exists yet.)
 *  - **Other providers**: unchanged - the literal baseline wins, live only fills a 0/0 baseline
 *    (this is how e.g. `anthropic/claude-haiku-4.5` overrides a family baseline).
 *  - A definition that exists but is priced 0/0 is an explicit "free" baseline and is returned last.
 *
 * Pure function of its inputs so the precedence is unit-testable without seeding the global cache.
 */
internal fun resolveModelPricing(
    provider: String,
    definition: ModelDefinition?,
    cached: ModelConfig?,
): ModelPricing {
    val liveHasPrice = cached != null && (cached.costPer1mInput > 0.0 || cached.costPer1mOutput > 0.0)
    val definitionHasPrice = definition != null &&
        (definition.costPer1MInput > 0.0 || definition.costPer1MOutput > 0.0)

    if (provider.equals("openrouter", ignoreCase = true) && liveHasPrice) {
        return pricingOf(cached!!.costPer1mInput, cached.costPer1mOutput, null)
    }
    if (definitionHasPrice) {
        return pricingOf(definition!!.costPer1MInput, definition.costPer1MOutput, definition.costPer1MCachedInput)
    }
    if (liveHasPrice) {
        return pricingOf(cached!!.costPer1mInput, cached.costPer1mOutput, null)
    }
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
