package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.*
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_ZAI_BASE_URL
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_ANTHROPIC_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_CUSTOM_OPENAI_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_CUSTOM_OPENAI_BASE_URL
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_CUSTOM_OPENAI_MODEL
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_GEMINI_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_LM_STUDIO_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_LM_STUDIO_BASE_URL
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_OLLAMA_ENDPOINT
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_OPENAI_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_OPENROUTER_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_ZAI_API_KEY
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_PROVIDER_ZAI_BASE_URL
import pl.jclab.refio.core.services.ConfigService.Companion.KEY_TOOLS_PERMISSIONS
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.config.ConfigKeys

/**
 * Builds a [ConfigYaml] snapshot from the current database values.
 *
 * Extracted from [ConfigService] to keep responsibilities narrow:
 * ConfigService handles reads/writes + caching; ConfigYamlBuilder composes
 * the cross-section YAML representation used by `exportToYaml`.
 */
internal class ConfigYamlBuilder(
    private val configService: ConfigService,
    private val configRepository: ConfigRepository
) {
    fun build(includeApiKeys: Boolean): ConfigYaml = ConfigYaml(
        general = buildGeneral(),
        providers = buildProviders(includeApiKeys),
        models = buildModels(),
        limits = buildLimits(),
        advanced = buildAdvanced(),
        tools = buildTools(),
        rag = buildRag(),
        ui = buildUi()
    )

    private fun buildGeneral() = GeneralConfig(
        formatMarkdown = configService.getTyped(ConfigKeys.FORMAT_MARKDOWN),
        streamingEnabled = configService.getTyped(ConfigKeys.STREAMING_ENABLED),
        advancedView = configService.getTyped(ConfigKeys.ADVANCED_VIEW)
    )

    private fun buildProviders(includeApiKeys: Boolean): ProvidersConfig {
        val ollamaEndpoint = configService.get(KEY_PROVIDER_OLLAMA_ENDPOINT)
        val lmstudioBaseUrl = configService.get(KEY_PROVIDER_LM_STUDIO_BASE_URL)
        val customOpenAIBaseUrl = configService.get(KEY_PROVIDER_CUSTOM_OPENAI_BASE_URL)

        return ProvidersConfig(
            ollama = OllamaConfig(
                endpoint = ollamaEndpoint ?: configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT),
                contextSize = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE),
                keepAlive = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_KEEP_ALIVE)
            ),
            anthropic = if (includeApiKeys) AnthropicConfig(apiKey = configService.get(KEY_PROVIDER_ANTHROPIC_API_KEY)) else null,
            openai = if (includeApiKeys) OpenAIConfig(apiKey = configService.get(KEY_PROVIDER_OPENAI_API_KEY)) else null,
            openrouter = if (includeApiKeys) OpenRouterConfig(apiKey = configService.get(KEY_PROVIDER_OPENROUTER_API_KEY)) else null,
            gemini = if (includeApiKeys) GeminiConfig(apiKey = configService.get(KEY_PROVIDER_GEMINI_API_KEY)) else null,
            lmstudio = LMStudioConfig(
                apiKey = if (includeApiKeys) configService.get(KEY_PROVIDER_LM_STUDIO_API_KEY) else null,
                baseUrl = lmstudioBaseUrl,
                contextSize = configService.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE)
            ),
            genericOpenai = GenericOpenAIConfig(
                apiKey = if (includeApiKeys) configService.get(KEY_PROVIDER_CUSTOM_OPENAI_API_KEY) else null,
                baseUrl = customOpenAIBaseUrl,
                model = configService.get(KEY_PROVIDER_CUSTOM_OPENAI_MODEL)
            ),
            zai = ZAIConfig(
                apiKey = if (includeApiKeys) configService.get(KEY_PROVIDER_ZAI_API_KEY) else null,
                baseUrl = configService.get(KEY_PROVIDER_ZAI_BASE_URL) ?: DEFAULT_ZAI_BASE_URL
            )
        )
    }

    private fun buildModels(): ModelsConfig {
        val (chatModel, chatProvider) = configService.getDefaultModel(ModelOperation.DEFAULT)
        val (planModel, planProvider) = configService.getDefaultModel(ModelOperation.PLAN)
        val (codingModel, codingProvider) = configService.getDefaultModel(ModelOperation.CODING)
        val (weakModel, weakProvider) = configService.getDefaultModel(ModelOperation.WEAK)
        val (embeddingModel, embeddingProvider) = configService.getDefaultModel(ModelOperation.EMBEDDING)

        return ModelsConfig(
            defaults = ModelDefaultsConfig(
                chat = "$chatProvider/$chatModel",
                plan = "$planProvider/$planModel",
                coding = "$codingProvider/$codingModel",
                weak = "$weakProvider/$weakModel",
                embedding = "$embeddingProvider/$embeddingModel"
            ),
            visibility = configService.getModelsVisibility()
        )
    }

    private fun buildLimits() = LimitsConfig(
        apiCallTimeout = configService.getTyped(ConfigKeys.API_CALL_TIMEOUT),
        toolExecutionTimeout = configService.getTyped(ConfigKeys.TOOL_EXECUTION_TIMEOUT),
        streamingReadTimeout = configService.getTyped(ConfigKeys.STREAMING_READ_TIMEOUT),
        streamingRequestTimeout = configService.getTyped(ConfigKeys.STREAMING_REQUEST_TIMEOUT),
        maxContextSize = configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE),
        maxOutputSize = configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE),
        maxFileSize = configService.getTyped(ConfigKeys.MAX_FILE_SIZE)
    )

    private fun buildAdvanced() = AdvancedConfig(
        noEgressDefault = configService.getTyped(ConfigKeys.NO_EGRESS_DEFAULT),
        readOnlyMode = configService.getTyped(ConfigKeys.READ_ONLY_MODE),
        autoOptimizePercentage = configService.getTyped(ConfigKeys.AUTO_OPTIMIZE_PERCENTAGE)
    )

    private fun buildTools(): ToolsConfig {
        val config = configRepository.get(KEY_TOOLS_PERMISSIONS, ConfigScope.APP)
        @Suppress("UNCHECKED_CAST")
        val permissions: Map<String, Boolean> = if (config != null) {
            (gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>) ?: emptyMap()
        } else emptyMap()
        if (permissions.isEmpty()) return ToolsConfig()

        val yamlPermissions = permissions.mapValues { (_, enabled) ->
            ToolPermissionConfig(
                planMode = if (enabled) "ON" else "OFF",
                agentMode = if (enabled) "ON" else "OFF"
            )
        }
        return ToolsConfig(permissions = yamlPermissions)
    }

    private fun buildRag() = RagConfig(
        enabled = configService.getTyped(ConfigKeys.RAG_ENABLED),
        indexOnStartup = configService.getTyped(ConfigKeys.RAG_INDEX_ON_STARTUP),
        autoIndexOnContextBuild = configService.getTyped(ConfigKeys.RAG_AUTO_INDEX_ON_CONTEXT),
        maxFileSizeMB = configService.getTyped(ConfigKeys.RAG_MAX_FILE_SIZE_MB),
        maxChunksPerFile = configService.getTyped(ConfigKeys.RAG_MAX_CHUNKS_PER_FILE),
        indexBatchSize = configService.getTyped(ConfigKeys.RAG_INDEX_BATCH_SIZE),
        embeddingsBatchSize = configService.getTyped(ConfigKeys.RAG_EMBEDDINGS_BATCH_SIZE),
        cacheTtlMs = configService.getTyped(ConfigKeys.RAG_CACHE_TTL_MS),
        maxConcurrentJobs = configService.getTyped(ConfigKeys.RAG_MAX_CONCURRENT_JOBS),
        ignoredDirectories = configService.getTyped(ConfigKeys.RAG_IGNORED_DIRECTORIES),
        searchSimilarityThreshold = configService.getTyped(ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD),
        searchTopK = configService.getTyped(ConfigKeys.RAG_SEARCH_TOP_K),
        searchHybridEnabled = configService.getTyped(ConfigKeys.RAG_SEARCH_HYBRID_ENABLED),
        searchSemanticWeight = configService.getTyped(ConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT),
        searchIncludeContextChunks = configService.getTyped(ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)
    )

    private fun buildUi() = UiConfig(
        thinkingEnabled = configService.getTyped(ConfigKeys.UI_THINKING_ENABLED),
        noEgressEnabled = configService.getTyped(ConfigKeys.UI_NO_EGRESS_ENABLED),
        executionMode = configService.getTyped(ConfigKeys.UI_EXECUTION_MODE),
        selectedMode = configService.getTyped(ConfigKeys.UI_SELECTED_MODE),
        selectedModel = configService.getTyped(ConfigKeys.UI_SELECTED_MODEL)
    )
}
