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
 * Pricing per 1M tokens (USD)
 */
val PRICING: Map<String, Map<String, ModelPricing>> = mapOf(
    "openai" to mapOf(

        //GPT-5
        "gpt-5.1-codex" to ModelPricing(input = 1.25, output = 10.00),
        "gpt-5.1-codex-mini" to ModelPricing(input = 0.25, output = 2.00),
        "gpt-5.1-codex-nano" to ModelPricing(input = 0.05, output = 0.4),
        "gpt-5" to ModelPricing(input = 1.25, output = 10.00),
        "gpt-5-mini" to ModelPricing(input = 0.25, output = 2.00),
        "gpt-5-nano" to ModelPricing(input = 0.05, output = 0.4),
        //GPT-4.1
        "gpt-4.1" to ModelPricing(input = 2.00, output = 8.00),
        "gpt-4.1-mini" to ModelPricing(input = 0.4, output = 1.6),
        "gpt-4.1-nano" to ModelPricing(input = 0.1, output = 0.4),
        // GPT-4o models
        "gpt-4o" to ModelPricing(input = 2.50, output = 10.00),
        "gpt-4o-2024-11-20" to ModelPricing(input = 2.50, output = 10.00),
        "gpt-4o-2024-08-06" to ModelPricing(input = 2.50, output = 10.00),
        "gpt-4o-2024-05-13" to ModelPricing(input = 5.00, output = 15.00),
        // GPT-4o mini (cost-effective)
        "gpt-4o-mini" to ModelPricing(input = 0.15, output = 0.60),
        "gpt-4o-mini-2024-07-18" to ModelPricing(input = 0.15, output = 0.60),
        // GPT-4 Turbo
        "gpt-4-turbo" to ModelPricing(input = 10.00, output = 30.00),
        "gpt-4-turbo-2024-04-09" to ModelPricing(input = 10.00, output = 30.00),
        "gpt-4-turbo-preview" to ModelPricing(input = 10.00, output = 30.00),
        // GPT-4 (original)
        "gpt-4" to ModelPricing(input = 30.00, output = 60.00),
        "gpt-4-0613" to ModelPricing(input = 30.00, output = 60.00),
        "gpt-4-32k" to ModelPricing(input = 60.00, output = 120.00),
        // GPT-3.5 Turbo
        "gpt-3.5-turbo" to ModelPricing(input = 0.50, output = 1.50),
        "gpt-3.5-turbo-0125" to ModelPricing(input = 0.50, output = 1.50),
        "gpt-3.5-turbo-1106" to ModelPricing(input = 1.00, output = 2.00),
        "gpt-3.5-turbo-16k" to ModelPricing(input = 3.00, output = 4.00),
        // o1 models (reasoning)
        "o1" to ModelPricing(input = 15.00, output = 60.00),
        "o1-preview" to ModelPricing(input = 15.00, output = 60.00),
        "o1-mini" to ModelPricing(input = 3.00, output = 12.00)
    ),
    "anthropic" to mapOf(
        // Claude Opus
        "claude-opus-4-1-20250805" to ModelPricing(input = 15.00, output = 75.00),
        "claude-opus-4-1" to ModelPricing(input = 15.00, output = 75.00),

        // Claude Sonnet
        "claude-sonnet-4-5-20250929" to ModelPricing(input = 3.00, output = 15.00),
        "claude-sonnet-4-5" to ModelPricing(input = 3.00, output = 15.00),
        "claude-sonnet-4-20250514" to ModelPricing(input = 3.00, output = 15.00),
        "claude-sonnet-4-0" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-7-sonnet-20250219" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-7-sonnet-latest" to ModelPricing(input = 3.00, output = 15.00),
        // Claude Haiku
        "claude-haiku-4-5-20251001" to ModelPricing(input = 1.00, output = 5.00),
        "claude-haiku-4-5" to ModelPricing(input = 1.00, output = 5.00),
        "claude-3-5-haiku-20241022" to ModelPricing(input = 1.00, output = 5.00),
        "claude-3-5-haiku-latest" to ModelPricing(input = 1.00, output = 5.00),
        "claude-3-haiku-20240307" to ModelPricing(input = 1.00, output = 5.00),

        // Legacy
        "claude-3-5-sonnet" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-5-sonnet-20241022" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-5-sonnet-20240620" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-opus" to ModelPricing(input = 15.00, output = 75.00),
        "claude-3-opus-20240229" to ModelPricing(input = 15.00, output = 75.00),
        "claude-3-sonnet" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-sonnet-20240229" to ModelPricing(input = 3.00, output = 15.00),
        "claude-3-haiku" to ModelPricing(input = 0.25, output = 1.25),
        "claude-3-haiku-20240307" to ModelPricing(input = 0.25, output = 1.25),
        "claude-2.1" to ModelPricing(input = 8.00, output = 24.00),
        "claude-2.0" to ModelPricing(input = 8.00, output = 24.00),
        "claude-instant-1.2" to ModelPricing(input = 0.80, output = 2.40)
    ),
    "ollama" to mapOf(
        // Local models - free
        "default" to ModelPricing(input = 0.00, output = 0.00)
    ),
    "openrouter" to mapOf(
        // OpenRouter - unified API for multiple providers
        // Prices are dynamic and may vary. These are representative costs.

        // Anthropic models via OpenRouter
        "anthropic/claude-3.5-sonnet" to ModelPricing(input = 3.00, output = 15.00),
        "anthropic/claude-3.5-sonnet-20241022" to ModelPricing(input = 3.00, output = 15.00),
        "anthropic/claude-3.5-haiku" to ModelPricing(input = 0.80, output = 4.00),
        "anthropic/claude-3-opus" to ModelPricing(input = 15.00, output = 75.00),
        "anthropic/claude-3-sonnet" to ModelPricing(input = 3.00, output = 15.00),
        "anthropic/claude-3-haiku" to ModelPricing(input = 0.25, output = 1.25),

        // OpenAI models via OpenRouter
        "openai/gpt-4o" to ModelPricing(input = 2.50, output = 10.00),
        "openai/gpt-4o-mini" to ModelPricing(input = 0.15, output = 0.60),
        "openai/gpt-4-turbo" to ModelPricing(input = 10.00, output = 30.00),
        "openai/gpt-4" to ModelPricing(input = 30.00, output = 60.00),
        "openai/gpt-3.5-turbo" to ModelPricing(input = 0.50, output = 1.50),
        "openai/o1" to ModelPricing(input = 15.00, output = 60.00),
        "openai/o1-mini" to ModelPricing(input = 3.00, output = 12.00),

        // Google models via OpenRouter
        "google/gemini-pro" to ModelPricing(input = 0.50, output = 1.50),
        "google/gemini-pro-1.5" to ModelPricing(input = 1.25, output = 5.00),
        "google/gemini-flash-1.5-8b" to ModelPricing(input = 0.075, output = 0.30),
        "google/gemini-2.0-flash-exp" to ModelPricing(input = 0.0, output = 0.0), // Free experimental

        // Meta Llama models via OpenRouter
        "meta-llama/llama-3-70b-instruct" to ModelPricing(input = 0.52, output = 0.75),
        "meta-llama/llama-3-8b-instruct" to ModelPricing(input = 0.06, output = 0.06),

        // Mistral models via OpenRouter
        "mistralai/mistral-large" to ModelPricing(input = 2.00, output = 6.00),
        "mistralai/mistral-medium" to ModelPricing(input = 0.70, output = 2.10),
        "mistralai/mistral-small" to ModelPricing(input = 0.20, output = 0.60),

        // DeepSeek models via OpenRouter
        "deepseek/deepseek-chat" to ModelPricing(input = 0.14, output = 0.28),
        "deepseek/deepseek-coder" to ModelPricing(input = 0.14, output = 0.28)
    ),
    "gemini" to mapOf(
        "gemini-2.5-flash" to ModelPricing(input = 0.0, output = 0.0),
        "gemini-2.5-pro" to ModelPricing(input = 0.0, output = 0.0)
    ),
    "lmstudio" to mapOf(
        "default" to ModelPricing(input = 0.0, output = 0.0)
    )
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
    // 1. First check ModelDefinitions (single source of truth, per 1M tokens)
    val definition = ModelDefinitions.getDefinition(provider, model)
    if (definition != null) {
        return ModelPricing(
            input = definition.costPer1MInput,
            output = definition.costPer1MOutput
        )
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
