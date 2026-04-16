package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.errors.LLMErrorMapper
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.SupportedModels
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.services.ConfigService

/**
 * LM Studio adapter for OpenAI-compatible local endpoints (defaults to
 * `http://localhost:1234/v1`). Filters `listModels()` through [SupportedModels]
 * and applies the user-configured context size when the model's own metadata
 * does not include one.
 */
class LMStudioAdapter(
    model: String = "local",
    private val baseUrlOverride: String? = null,
    configService: ConfigService? = null,
    taskId: String? = null,
    subtaskId: String? = null,
    source: String? = null,
    httpClientOverride: HttpClient? = null,
) : OpenAICompatibleAdapter(
    model = model,
    providerName = "lmstudio",
    configService = configService,
    taskId = taskId,
    subtaskId = subtaskId,
    source = source,
    httpClientOverride = httpClientOverride,
) {

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:1234/v1"
    }

    override fun resolveBaseUrl(): String {
        return baseUrlOverride
            ?: configService?.get(ConfigKeys.PROVIDER_LM_STUDIO_BASE_URL.key, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_BASE_URL")
            ?: System.getenv("LM_STUDIO_BASE_URL")
            ?: DEFAULT_BASE_URL
    }

    override fun resolveApiKey(): String? {
        return configService?.get(ConfigKeys.PROVIDER_LM_STUDIO_API_KEY.key, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_API_KEY")
            ?: System.getenv("LM_STUDIO_API_KEY")
    }

    override fun logUnsupportedThinking(logPrefix: String) {
        logger.info { "$logPrefix Thinking mode requested but not supported by OpenAI-compatible API - parameter ignored" }
    }

    override suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val resolvedBaseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        try {
            val response = client.get("$resolvedBaseUrl$MODELS_ENDPOINT") {
                apiKey?.let { header("Authorization", "Bearer $it") }
            }

            val body: Map<String, Any?> = response.body()
            @Suppress("UNCHECKED_CAST")
            val modelsData = body["data"] as? List<Map<String, Any?>> ?: emptyList()

            val contextSize = configService?.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE)
                ?: ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE.default

            modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                if (!SupportedModels.isSupported("lmstudio", modelId)) {
                    return@mapNotNull null
                }

                val modelContextLength = (modelData["context_length"] as? Number)?.toInt() ?: contextSize
                val baseDefinition = ModelDefinitions.getDefinition("lmstudio", modelId)
                    ?: run {
                        logger.warn {
                            "[LMSTUDIO] Model $modelId not in registry — using synthetic definition (context=$modelContextLength)"
                        }
                        ModelDefinitions.syntheticDefinitionFor(
                            provider = "lmstudio",
                            modelId = modelId,
                            maxContext = modelContextLength,
                        )
                    }

                val definition = baseDefinition.copy(maxContext = modelContextLength)
                definition.toModelConfig()
            }
        } catch (e: Exception) {
            logger.error(e) { "[LMStudio] Failed to fetch models: ${e.message}" }
            throw LLMErrorMapper.listModelsFailure(provider, e)
        }
    }
}
