package pl.jclab.refio.core.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.llm.adapters.AnthropicAdapter
import pl.jclab.refio.core.llm.adapters.GenericOpenAIAdapter
import pl.jclab.refio.core.llm.adapters.GeminiAdapter
import pl.jclab.refio.core.llm.adapters.LMStudioAdapter
import pl.jclab.refio.core.llm.adapters.OllamaAdapter
import pl.jclab.refio.core.llm.adapters.OpenAIAdapter
import pl.jclab.refio.core.llm.adapters.OpenRouterAdapter
import pl.jclab.refio.core.llm.adapters.ZAIAdapter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.logging.dualLogger

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
private const val LIST_MODELS_TIMEOUT_MS = 15_000L // 15s per-provider timeout for cloud providers
private const val LIST_MODELS_TIMEOUT_LOCAL_MS = 3_000L // 3s for local providers (ollama, lmstudio)
private val modelsCacheMutex = Mutex()

private fun listModelsTimeoutFor(provider: String): Long = when (provider) {
    "ollama", "lmstudio" -> LIST_MODELS_TIMEOUT_LOCAL_MS
    else -> LIST_MODELS_TIMEOUT_MS
}

private fun getCachedModelsIfFresh(now: Long = System.currentTimeMillis()): List<ModelConfig>? {
    val cached = modelsCache ?: return null
    if ((now - cacheTimestamp) >= CACHE_TTL_MS) return null
    return cached.values.flatten()
}

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

        // Z.AI models
        model.startsWith("glm-") -> "zai"

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
 * Returns the last cached model snapshot regardless of TTL freshness, or empty if
 * nothing has ever been fetched. Never triggers remote calls. Use this for read-only
 * UI listings that should not block on slow providers.
 */
fun getCachedModelsSnapshot(): List<ModelConfig> {
    return modelsCache?.values?.flatten() ?: emptyList()
}

/**
 * Gets all models from all providers dynamically.
 * Results are cached for 5 minutes to avoid excessive API calls.
 *
 * @param configService Optional ConfigService for API keys (uses env vars as fallback)
 * @param fetchIfMissing When false, returns the current cache (even if stale or empty)
 *                      without performing any remote calls. UI screens that just want
 *                      to display the last known state should pass false.
 * @return List of all available models from all providers
 */
suspend fun getAllModels(
    configService: pl.jclab.refio.core.services.ConfigService? = null,
    fetchIfMissing: Boolean = true
): List<ModelConfig> {
    getCachedModelsIfFresh()?.let {
        GlobalMetrics.recordCacheAccess("model_registry", hit = true)
        return it
    }

    if (!fetchIfMissing) {
        GlobalMetrics.recordCacheAccess("model_registry", hit = false)
        return getCachedModelsSnapshot()
    }

    return modelsCacheMutex.withLock {
        getCachedModelsIfFresh()?.let {
            GlobalMetrics.recordCacheAccess("model_registry", hit = true)
            return@withLock it
        }
        GlobalMetrics.recordCacheAccess("model_registry", hit = false)

        val now = System.currentTimeMillis()
        logger.info { "[ModelRegistry] Fetching models from all providers (single-flight, parallel providers)" }

        data class ProviderFetch(val name: String, val models: List<ModelConfig>)

        val providerNames = listOf("ollama", "openai", "anthropic", "openrouter", "gemini", "lmstudio", "generic_openai", "zai")

        val results = coroutineScope {
            providerNames.map { name ->
                async {
                    try {
                        val models = withTimeoutOrNull(listModelsTimeoutFor(name)) {
                            when (name) {
                                "ollama" -> OllamaAdapter(configService = configService).listModels()
                                "openai" -> OpenAIAdapter(configService = configService).listModels()
                                "anthropic" -> AnthropicAdapter(configService = configService).listModels()
                                "openrouter" -> OpenRouterAdapter(configService = configService).listModels()
                                "gemini" -> GeminiAdapter(configService = configService).listModels()
                                "lmstudio" -> LMStudioAdapter(configService = configService).listModels()
                                "generic_openai" -> GenericOpenAIAdapter(
                                    model = configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_MODEL) ?: "custom-openai",
                                    providerName = "generic_openai",
                                    configService = configService
                                ).listModels()
                                "zai" -> ZAIAdapter(
                                    model = "glm-4.5",
                                    configService = configService
                                ).listModels()
                                else -> emptyList()
                            }
                        }
                        if (models == null) {
                            logger.warn { "[ModelRegistry] Timeout fetching $name models (${listModelsTimeoutFor(name)}ms)" }
                            ProviderFetch(name, emptyList())
                        } else {
                            logger.info { "[ModelRegistry] Fetched ${models.size} models from $name" }
                            ProviderFetch(name, models)
                        }
                    } catch (e: Exception) {
                        logger.warn { "[ModelRegistry] Failed to fetch $name models: ${e.message}" }
                        ProviderFetch(name, emptyList())
                    }
                }
            }.awaitAll()
        }

        val allModels = results.associate { it.name to it.models }
        modelsCache = allModels
        cacheTimestamp = now

        val totalModels = allModels.values.flatten()
        logger.info { "[ModelRegistry] Total ${totalModels.size} models available" }
        totalModels
    }
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
        val cached = modelsCache!![provider]
        if (cached != null) return cached
    }

    logger.info { "[ModelRegistry] Fetching models from provider: $provider" }

    return try {
        val models = withTimeoutOrNull(listModelsTimeoutFor(provider.lowercase())) {
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
                "generic_openai" -> {
                    val adapter = GenericOpenAIAdapter(
                        model = configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_MODEL) ?: "custom-openai",
                        providerName = "generic_openai",
                        configService = configService
                    )
                    adapter.listModels()
                }
                "zai" -> {
                    val adapter = ZAIAdapter(
                        model = "glm-4.5",
                        configService = configService
                    )
                    adapter.listModels()
                }
                else -> {
                    logger.error { "[ModelRegistry] Unknown provider: $provider" }
                    emptyList()
                }
            }
        }
        val fetched = if (models == null) {
            logger.warn { "[ModelRegistry] Timeout fetching $provider models (${listModelsTimeoutFor(provider.lowercase())}ms)" }
            emptyList()
        } else {
            models
        }

        // Merge result into shared cache so subsequent cache-only reads
        // (e.g. StatusBar via getModelConfigFromCache) see fresh data.
        val existing = modelsCache ?: emptyMap()
        modelsCache = existing + (provider to fetched)
        if (cacheTimestamp == 0L) {
            cacheTimestamp = System.currentTimeMillis()
        }

        fetched
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
 * Gets model config from cache only, without triggering network requests.
 * Returns null if model not found in cache or cache is empty.
 * Safe to call from any thread (including AWT/EDT).
 */
fun getModelConfigFromCache(modelId: String): ModelConfig? {
    return modelsCache?.values?.flatten()?.find { it.id == modelId }
}

/**
 * Clears the models cache. Call this when you want to force refresh from APIs.
 */
fun clearModelsCache() {
    modelsCache = null
    cacheTimestamp = 0L
    logger.info { "[ModelRegistry] Cache cleared" }
}

/**
 * Clears the cache entry for a single provider, leaving other providers' entries intact.
 * Used when a single provider's config changes (e.g. Ollama context size) so we don't
 * evict fresh data for unrelated providers.
 */
fun clearModelsCacheForProvider(provider: String) {
    val existing = modelsCache ?: return
    val key = existing.keys.firstOrNull { it.equals(provider, ignoreCase = true) } ?: return
    modelsCache = existing - key
    logger.info { "[ModelRegistry] Cache cleared for provider: $key" }
}
