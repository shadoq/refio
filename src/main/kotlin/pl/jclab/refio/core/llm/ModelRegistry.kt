package pl.jclab.refio.core.llm

import pl.jclab.refio.core.llm.adapters.AnthropicAdapter
import pl.jclab.refio.core.llm.adapters.GeminiAdapter
import pl.jclab.refio.core.llm.adapters.LMStudioAdapter
import pl.jclab.refio.core.llm.adapters.OllamaAdapter
import pl.jclab.refio.core.llm.adapters.OpenAIAdapter
import pl.jclab.refio.core.llm.adapters.OpenRouterAdapter
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.runBlocking

private val logger = dualLogger("ModelRegistry")

/**
 * Model capability types
 */
enum class ModelCapability {
    CHAT_COMPLETION,
    TEXT_COMPLETION,
    CODE_COMPLETION,
    EMBEDDINGS,
    IMAGE_GENERATION,
    VISION,
    AUDIO,
    TOOL_USE,
    REASONING,
}

/**
 * Model type categories
 */
enum class ModelType {
    TEXT,
    VISION,
    AUDIO,
    MULTIMODAL,
    EMBEDDING
}

/**
 * Comprehensive model definition with full configuration.
 * This is the static definition from the central registry.
 */
data class ModelDefinition(
    // Basic info
    val id: String,                    // "gpt-4.1-mini"
    val name: String,                  // "GPT-4.1 Mini"
    val provider: String,              // "openai"
    val description: String? = null,   // Human-readable description

    // Capabilities
    val capabilities: List<ModelCapability>,  // [CHAT, TEXT_COMPLETION, CODE_COMPLETION]
    val modelType: ModelType = ModelType.TEXT, // TEXT, VISION, AUDIO, etc.

    // Context & Token Limits
    val maxContext: Int,               // 128000 tokens
    val maxOutputTokens: Int? = null,  // 16384 tokens (if different from maxContext)

    // Pricing (USD per 1M tokens - industry standard)
    val costPer1MInput: Double,        // $0.15 / 1M input tokens
    val costPer1MOutput: Double,       // $0.60 / 1M output tokens

    // Features & Capabilities
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,  // o1-*, o3-* models
    val supportsStreaming: Boolean = true,
    val supportsFunctionCalling: Boolean = false,
    val supportsThinking: Boolean = false,   // Extended thinking mode

    // Reasoning Model Settings
    val reasoningTokensMultiplier: Double? = null,  // For reasoning models: multiply max_tokens by this factor (e.g., 2.5)

    // Provider-specific
    val endpointType: ApiEndpointType = ApiEndpointType.CHAT_COMPLETIONS,  // API endpoint to use (CHAT_COMPLETIONS, RESPONSES, etc.)
    val apiFormat: ApiFormat = ApiFormat.CHAT_COMPLETIONS,  // Request/response format (CHAT_COMPLETIONS, RESPONSES)
    val paramMappings: Map<String, String> = emptyMap(),  // max_tokens → max_completion_tokens
    val defaultParams: Map<String, Any> = emptyMap(),     // Default temperature, top_p, etc.
    val removeParams: List<String> = emptyList(),         // Params to remove for this model

    // Visibility & Status
    val active: Boolean = true,        // Show in UI
    val deprecated: Boolean = false,   // Mark as deprecated
    val replacedBy: String? = null     // "gpt-4.2-mini" if deprecated
)

/**
 * Model configuration with metadata
 *
 * This data class is used by LLM adapters to represent model information
 * fetched dynamically from provider APIs or converted from ModelDefinition.
 */
data class ModelConfig(
    val id: String,
    val name: String,
    val provider: String,
    val capabilities: List<String>,
    val maxContext: Int,
    val costPer1mInput: Double,  // USD per 1M input tokens
    val costPer1mOutput: Double,  // USD per 1M output tokens
    val supportsStreaming: Boolean = true  // Most models support streaming; reasoning models (o1-*, o3-*) don't
)

/**
 * Extension function to convert ModelDefinition → ModelConfig
 */
fun ModelDefinition.toModelConfig(): ModelConfig {
    return ModelConfig(
        id = this.id,
        name = this.name,
        provider = this.provider,
        capabilities = this.capabilities.map { it.name },
        maxContext = this.maxContext,
        costPer1mInput = this.costPer1MInput,
        costPer1mOutput = this.costPer1MOutput,
        supportsStreaming = this.supportsStreaming
    )
}

// Simple cache to avoid repeated API calls (cache expires on restart)
private var modelsCache: Map<String, List<ModelConfig>>? = null
private var cacheTimestamp: Long = 0L
private const val CACHE_TTL_MS = 300_000L // 5 minutes

/**
 * Infers provider from model name using common patterns.
 *
 * Examples:
 * - "gpt-4o-mini" -> "openai"
 * - "claude-3-5-sonnet" -> "anthropic"
 * - "qwen2.5:7b" -> "ollama"
 * - "anthropic/claude-3.5-sonnet" -> "openrouter"
 *
 * @param model Model identifier
 * @param default Default provider if inference fails (default: "ollama")
 * @return Inferred provider name
 */
fun inferProvider(model: String, default: String = "ollama"): String {
    return when {
        // OpenRouter format: "provider/model"
        model.contains("/") -> "openrouter"

        // OpenAI models
        model.startsWith("gpt-") || model.startsWith("o1") -> "openai"

        // Anthropic models
        model.startsWith("claude-") -> "anthropic"

        // Google Gemini models
        model.startsWith("gemini-") || model.contains("google/gemini") || model.contains("gemini") -> "gemini"

        // LM Studio models (local, OpenAI-compatible)
        model.startsWith("lmstudio/") -> "lmstudio"

        // Ollama format: "model:tag" or just "model"
        model.contains(":") -> "ollama"

        // Default to configured default
        else -> default
    }
}

/**
 * Gets all models from all providers dynamically.
 * Results are cached for 5 minutes to avoid excessive API calls.
 *
 * @param configService Optional ConfigService for API keys (uses env vars as fallback)
 * @return List of all available models from all providers
 */
suspend fun getAllModels(
    configService: pl.jclab.refio.core.services.ConfigService? = null
): List<ModelConfig> {
    // Check cache
    val now = System.currentTimeMillis()
    if (modelsCache != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
        return modelsCache!!.values.flatten()
    }

    logger.info { "[ModelRegistry] Fetching models from all providers" }

    val allModels = mutableMapOf<String, List<ModelConfig>>()

    // Fetch from each provider (failures are logged but don't block others)

    // Ollama (always available, no API key needed)
    try {
        val ollamaAdapter = OllamaAdapter(
            configService = configService
        )
        allModels["ollama"] = ollamaAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["ollama"]?.size ?: 0} models from Ollama" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch Ollama models: ${e.message}" }
        allModels["ollama"] = emptyList()
    }

    // OpenAI (requires API key)
    try {
        val openaiAdapter = OpenAIAdapter(configService = configService)
        allModels["openai"] = openaiAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["openai"]?.size ?: 0} models from OpenAI" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch OpenAI models: ${e.message}" }
        allModels["openai"] = emptyList()
    }

    // Anthropic (requires API key)
    try {
        val anthropicAdapter = AnthropicAdapter(configService = configService)
        allModels["anthropic"] = anthropicAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["anthropic"]?.size ?: 0} models from Anthropic" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch Anthropic models: ${e.message}" }
        allModels["anthropic"] = emptyList()
    }

    // OpenRouter (requires API key)
    try {
        val openrouterAdapter = OpenRouterAdapter(configService = configService)
        allModels["openrouter"] = openrouterAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["openrouter"]?.size ?: 0} models from OpenRouter" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch OpenRouter models: ${e.message}" }
        allModels["openrouter"] = emptyList()
    }

    // Gemini (requires API key)
    try {
        val geminiAdapter = GeminiAdapter(configService = configService)
        allModels["gemini"] = geminiAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["gemini"]?.size ?: 0} models from Gemini" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch Gemini models: ${e.message}" }
        allModels["gemini"] = emptyList()
    }

    // LM Studio (local, OpenAI-compatible)
    try {
        val lmStudioAdapter = LMStudioAdapter(configService = configService)
        allModels["lmstudio"] = lmStudioAdapter.listModels()
        logger.info { "[ModelRegistry] Fetched ${allModels["lmstudio"]?.size ?: 0} models from LM Studio" }
    } catch (e: Exception) {
        logger.warn { "[ModelRegistry] Failed to fetch LM Studio models: ${e.message}" }
        allModels["lmstudio"] = emptyList()
    }

    // Update cache
    modelsCache = allModels
    cacheTimestamp = now

    val totalModels = allModels.values.flatten()
    logger.info { "[ModelRegistry] Total ${totalModels.size} models available" }

    return totalModels
}

/**
 * Gets models from a specific provider dynamically.
 *
 * @param provider Provider name (ollama, openai, anthropic, openrouter)
 * @param configService Optional ConfigService for API keys
 * @return List of models from the specified provider
 */
suspend fun getModelsByProvider(
    provider: String,
    configService: pl.jclab.refio.core.services.ConfigService? = null
): List<ModelConfig> {
    // Check cache first
    val now = System.currentTimeMillis()
    if (modelsCache != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
        return modelsCache!![provider] ?: emptyList()
    }

    logger.info { "[ModelRegistry] Fetching models from provider: $provider" }

    return try {
        when (provider.lowercase()) {
            "ollama" -> {
                val adapter = OllamaAdapter(
                    configService = configService
                )
                adapter.listModels()
            }
            "openai" -> {
                val adapter = OpenAIAdapter(configService = configService)
                adapter.listModels()
            }
            "anthropic" -> {
                val adapter = AnthropicAdapter(configService = configService)
                adapter.listModels()
            }
            "openrouter" -> {
                val adapter = OpenRouterAdapter(configService = configService)
                adapter.listModels()
            }
            "gemini" -> {
                val adapter = GeminiAdapter(configService = configService)
                adapter.listModels()
            }
            "lmstudio" -> {
                val adapter = LMStudioAdapter(configService = configService)
                adapter.listModels()
            }
            else -> {
                logger.error { "[ModelRegistry] Unknown provider: $provider" }
                emptyList()
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "[ModelRegistry] Failed to fetch models from $provider: ${e.message}" }
        emptyList()
    }
}

/**
 * Gets configuration for a specific model by ID.
 * Searches across all providers to find the model.
 *
 * @param modelId Model identifier
 * @param configService Optional ConfigService for API keys
 * @return ModelConfig if found, null otherwise
 */
suspend fun getModelConfig(
    modelId: String,
    configService: pl.jclab.refio.core.services.ConfigService? = null
): ModelConfig? {
    val allModels = getAllModels(configService)
    return allModels.find { it.id == modelId }
}

/**
 * Clears the models cache. Call this when you want to force refresh from APIs.
 */
fun clearModelsCache() {
    modelsCache = null
    cacheTimestamp = 0L
    logger.info { "[ModelRegistry] Cache cleared" }
}
