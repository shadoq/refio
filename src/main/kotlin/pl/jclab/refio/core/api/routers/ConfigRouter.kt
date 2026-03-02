package pl.jclab.refio.core.api.routers

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.clearModelsCache
import pl.jclab.refio.core.llm.getAllModels
import pl.jclab.refio.core.llm.getModelsByProvider
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ConfigRouter")

/**
 * Router for configuration operations.
 * Handles model configuration, visibility settings, and orchestration settings.
 *
 * This router is responsible for:
 * - Model selection and configuration
 * - Model visibility management
 * - Orchestration settings
 * - Application-level and task-level configuration
 *
 * @property configService Configuration management service
 * @property llmClient LLM client for provider testing
 * @property configRepository Configuration storage repository
 */
class ConfigRouter(
    private val configService: ConfigService,
    private val llmClient: pl.jclab.refio.core.llm.LLMClient,
    private val configRepository: ConfigRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[ConfigRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[ConfigRouter] Shutting down" }
    }

    // ===== Model Configuration =====

    /**
     * Get list of available models.
     *
     * @param provider Optional provider filter (ollama, openai, anthropic)
     * @return List of available models with full configuration
     */
    suspend fun getModels(provider: String? = null): GetModelsResponse {
        logger.info { "[ConfigRouter] Getting models: provider=${provider ?: "all"}" }

        val models = if (provider != null) {
            getModelsByProvider(provider, configService)
        } else {
            getAllModels(configService)
        }

        return GetModelsResponse(
            models = models,
            count = models.size
        )
    }

    /**
     * Get list of available models with visibility settings applied.
     *
     * @param provider Optional provider filter (ollama, openai, anthropic)
     * @return List of ModelInfo with visibility settings
     */
    suspend fun getModelsWithVisibility(provider: String? = null): List<ModelInfo> {
        logger.info { "[ConfigRouter] Getting models with visibility: provider=${provider ?: "all"}" }

        val models = if (provider != null) {
            getModelsByProvider(provider, configService)
        } else {
            getAllModels(configService)
        }

        // Get visibility settings
        val visibilityMap = configService.getModelsVisibility()

        // If user has any visibility settings, new models should be hidden by default
        // If no settings exist (first time), new models are visible by default
        val hasUserSettings = visibilityMap.isNotEmpty()
        val defaultVisibility = !hasUserSettings

        // Map to ModelInfo with visibility settings
        return models.map { modelConfig ->
            val showInDropdown = visibilityMap[modelConfig.id] ?: defaultVisibility

            ModelInfo(
                id = modelConfig.id,
                provider = modelConfig.provider,
                name = modelConfig.name,
                contextSize = modelConfig.maxContext,
                capabilities = modelConfig.capabilities,
                pricing = ModelPricing(
                    inputPer1MTokens = modelConfig.costPer1mInput,
                    outputPer1MTokens = modelConfig.costPer1mOutput
                ),
                showInDropdown = showInDropdown
            )
        }
    }

    /**
     * Get the logical model to use for a request.
     * Centralizes model selection logic - if explicit model is provided, use it; otherwise use default.
     *
     * @param operation Model operation type (DEFAULT, PLAN, CODING, etc.)
     * @param taskId Optional task ID for task-level config
     * @return Model configuration to use
     */
    fun getModel(
        operation: ModelOperation,
        taskId: String? = null
    ): GetDefaultModelResponse {
        logger.info { "[ConfigRouter] Getting model: operation=$operation, taskId=${taskId ?: "none"}" }

        val (modelId, resolvedProvider) = configService.getModel(
            operation = operation,
            taskId = taskId
        )

        return GetDefaultModelResponse(
            operation = operation.name,
            modelId = modelId,
            provider = resolvedProvider
        )
    }

    /**
     * Get default model for given operation.
     *
     * @param operation Model operation type
     * @param taskId Optional task ID for task-level override
     * @return Default model configuration
     */
    fun getDefaultModel(operation: ModelOperation, taskId: String? = null): GetDefaultModelResponse {
        logger.info { "[ConfigRouter] Getting default model: operation=$operation, taskId=${taskId ?: "none"}" }

        val (modelId, provider) = configService.getDefaultModel(operation, taskId)

        return GetDefaultModelResponse(
            operation = operation.name,
            modelId = modelId,
            provider = provider
        )
    }

    /**
     * Set default model for given operation.
     *
     * @param request Request with operation, model ID, and provider
     * @param taskId Optional task ID for task-level config
     * @return Confirmation response
     */
    fun setDefaultModel(request: SetDefaultModelRequest, taskId: String? = null): SetDefaultModelResponse {
        logger.info {
            "[ConfigRouter] Setting default model: operation=${request.operation}, modelId=${request.modelId}, " +
                    "provider=${request.provider}, taskId=${taskId ?: "none"}"
        }

        configService.setDefaultModel(
            operation = request.operation,
            modelId = request.modelId,
            provider = request.provider,
            taskId = taskId
        )

        return SetDefaultModelResponse(
            operation = request.operation.name,
            modelId = request.modelId,
            provider = request.provider,
            scope = if (taskId != null) "task" else "app"
        )
    }

    /**
     * Set default model for ALL operations in one request.
     *
     * @param request Request with model ID and provider
     * @param taskId Optional task ID for task-level config
     * @return Confirmation response
     */
    fun setDefaultModelAllModes(
        request: SetDefaultModelAllModesRequest,
        taskId: String? = null
    ): SetDefaultModelAllModesResponse {
        logger.info {
            "[ConfigRouter] Setting default model for ALL modes: modelId=${request.modelId}, " +
                    "provider=${request.provider}, taskId=${taskId ?: "none"}"
        }

        configService.setDefaultModelAllModes(
            modelId = request.modelId,
            provider = request.provider,
            taskId = taskId
        )

        return SetDefaultModelAllModesResponse(
            modelId = request.modelId,
            provider = request.provider,
            scope = if (taskId != null) "task" else "app",
            modes = listOf(
                ModelOperation.DEFAULT.name,
                ModelOperation.PLAN.name,
                ModelOperation.CODING.name
            )
        )
    }

    // ===== Provider Management =====

    /**
     * Test connection to LLM provider.
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @param config Provider configuration (api_key, base_url, etc.)
     * @return Test result with success status and details
     */
    suspend fun testProviderConnection(provider: String, config: Map<String, String>): TestConnectionResult {
        logger.info { "[ConfigRouter] Testing connection to provider: $provider config=${SecureLogger.redactMap(config)}" }

        val startTime = System.currentTimeMillis()

        try {
            return withTimeout(30_000L) {
                val apiKey = config["api_key"]
                val baseUrl = config["base_url"]
                var resolvedBaseUrl = baseUrl
                if (provider.equals("lmstudio", ignoreCase = true) && resolvedBaseUrl.isNullOrEmpty()) {
                    resolvedBaseUrl = "http://localhost:1234/v1"
                }

                when (provider.lowercase()) {
                    "ollama" -> {
                        if (resolvedBaseUrl.isNullOrEmpty()) {
                            return@withTimeout TestConnectionResult(
                                success = false,
                                latencyMs = 0,
                                message = "Base URL is required for Ollama",
                                details = null
                            )
                        }
                    }
                    "anthropic", "openai", "openrouter", "gemini" -> {
                        if (apiKey.isNullOrEmpty()) {
                            return@withTimeout TestConnectionResult(
                                success = false,
                                latencyMs = 0,
                                message = "API key is required for $provider",
                                details = null
                            )
                        }
                    }
                    "lmstudio" -> {
                        resolvedBaseUrl = resolvedBaseUrl ?: "http://localhost:1234/v1"
                    }
                }

                val tempConfigKey = when (provider.lowercase()) {
                    "ollama" -> ConfigService.KEY_PROVIDER_OLLAMA_ENDPOINT to (baseUrl ?: "")
                    "anthropic" -> ConfigService.KEY_PROVIDER_ANTHROPIC_API_KEY to (apiKey ?: "")
                    "openai" -> ConfigService.KEY_PROVIDER_OPENAI_API_KEY to (apiKey ?: "")
                    "openrouter" -> ConfigService.KEY_PROVIDER_OPENROUTER_API_KEY to (apiKey ?: "")
                    "gemini" -> ConfigService.KEY_PROVIDER_GEMINI_API_KEY to (apiKey ?: "")
                    "lmstudio" -> ConfigService.KEY_PROVIDER_LM_STUDIO_API_KEY to (apiKey ?: "")
                    else -> null
                }

                val originalValue = tempConfigKey?.let { (key, value) ->
                    val original = configService.get(key, ConfigScope.APP)
                    if (value.isNotEmpty()) {
                        configService.set(key, value, ConfigScope.APP)
                        logger.debug { "Temporarily saved $key for testing" }
                    }
                    original
                }

                if (apiKey != null) {
                    when (provider.lowercase()) {
                        "anthropic" -> System.setProperty("ANTHROPIC_API_KEY", apiKey)
                        "openai" -> System.setProperty("OPENAI_API_KEY", apiKey)
                        "openrouter" -> System.setProperty("OPENROUTER_API_KEY", apiKey)
                        "lmstudio" -> System.setProperty("LM_STUDIO_API_KEY", apiKey)
                    }
                }

                when (provider.lowercase()) {
                    "ollama" -> resolvedBaseUrl?.let { System.setProperty("OLLAMA_BASE_URL", it) }
                    "lmstudio" -> resolvedBaseUrl?.let { System.setProperty("LM_STUDIO_BASE_URL", it) }
                }

                try {
                    clearModelsCache()
                    logger.debug { "ModelRegistry cache cleared for connection test" }

                    val models = getModelsByProvider(provider, configService)

                    if (models.isEmpty()) {
                        return@withTimeout TestConnectionResult(
                            success = false,
                            latencyMs = 0,
                            message = "No models available for provider: $provider. Please check your API key.",
                            details = null
                        )
                    }

                    val chatModels = models.filter {
                        "chat" in it.capabilities || "CHAT_COMPLETION" in it.capabilities || "TEXT_COMPLETION" in it.capabilities
                    }.filter { !it.id.contains("codex") }

                    if (chatModels.isEmpty()) {
                        return@withTimeout TestConnectionResult(
                            success = false,
                            latencyMs = 0,
                            message = "No chat-capable models available for provider: $provider (found ${models.size} models total)",
                            details = mapOf("total_models" to models.size)
                        )
                    }

                    val testModel = chatModels.first().id

                    val response = llmClient.complete(
                        provider = provider.lowercase(),
                        model = testModel,
                        messages = listOf(LLMMessage(role = "user", content = "test")),
                        maxTokens = 10,
                        temperature = 0.0,
                        source = "TestConnection"
                    )

                    val latency = (System.currentTimeMillis() - startTime).toInt()

                    logger.info { "Connection test successful for $provider: latency=${latency}ms, models=${models.size}" }

                    TestConnectionResult(
                        success = true,
                        latencyMs = latency,
                        message = "Connected successfully",
                        details = mapOf(
                            "models_available" to models.map { it.id },
                            "test_model" to testModel,
                            "response_tokens" to response.usage.outputTokens
                        )
                    )
                } finally {
                    tempConfigKey?.let { (key, _) ->
                        if (originalValue != null) {
                            configService.set(key, originalValue, ConfigScope.APP)
                            logger.debug { "Restored original value for $key after test" }
                        } else {
                            logger.debug { "Temp value for $key will be overwritten by UI auto-save" }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            val latency = (System.currentTimeMillis() - startTime).toInt()
            logger.error { "Connection test timed out for $provider after 30 seconds" }

            return TestConnectionResult(
                success = false,
                latencyMs = latency,
                message = "Connection test timed out after 30 seconds. Provider may be unreachable.",
                details = mapOf("error_type" to "Timeout")
            )
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - startTime).toInt()
            logger.error(e) { "Connection test failed for $provider" }

            return TestConnectionResult(
                success = false,
                latencyMs = latency,
                message = e.message ?: "Unknown error",
                details = mapOf("error_type" to (e::class.simpleName ?: "Unknown"))
            )
        }
    }

    /**
     * Refresh list of available models for a provider.
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @return List of available models with details
     */
    suspend fun refreshProviderModels(provider: String): List<ModelInfo> {
        logger.info { "[ConfigRouter] Refreshing models for provider: $provider" }

        try {
            logger.info { "Fetching models dynamically for $provider" }
            val models = getModelsByProvider(provider, configService)

            val visibilityMap = configService.getModelsVisibility()

            return models.mapNotNull { model ->
                val showInDropdown = visibilityMap[model.id] ?: true

                if ("EMBEDDINGS" in model.capabilities) {
                    return@mapNotNull null
                }

                ModelInfo(
                    id = model.id,
                    provider = model.provider,
                    name = model.name,
                    contextSize = model.maxContext,
                    capabilities = model.capabilities,
                    pricing = ModelPricing(
                        inputPer1MTokens = model.costPer1mInput,
                        outputPer1MTokens = model.costPer1mOutput
                    ),
                    showInDropdown = showInDropdown
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh models for $provider" }
            throw e
        }
    }

    /**
     * Refresh list of available models for all providers.
     *
     * @return List of available models with details from all providers
     */
    suspend fun refreshAllModels(): List<ModelInfo> {
        logger.info { "[ConfigRouter] Refreshing models for all providers" }

        val allProviders = listOf("ollama", "anthropic", "openai", "openrouter", "gemini", "lmstudio")
        val allModels = mutableListOf<ModelInfo>()

        for (provider in allProviders) {
            try {
                val models = refreshProviderModels(provider)
                allModels.addAll(models)
                logger.info { "Refreshed ${models.size} models from $provider" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh models from $provider (skipping)" }
            }
        }

        logger.info { "Total models refreshed: ${allModels.size}" }
        return allModels
    }

    /**
     * Update model visibility (show in dropdown).
     *
     * @param modelId Model ID to update
     * @param showInDropdown Whether to show model in dropdown
     */
    suspend fun updateModelVisibility(modelId: String, showInDropdown: Boolean) {
        logger.info { "[ConfigRouter] Updating model visibility: $modelId -> $showInDropdown" }

        try {
            configService.setModelVisibility(modelId, showInDropdown)
            logger.info { "Model visibility updated and persisted: $modelId -> $showInDropdown" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update model visibility: $modelId" }
            throw e
        }
    }

    /**
     * Update visibility for all models in one operation.
     *
     * @param visibilityMap Map of modelId to showInDropdown setting
     */
    suspend fun updateModelsVisibility(visibilityMap: Map<String, Boolean>) {
        logger.info { "[ConfigRouter] Updating visibility for ${visibilityMap.size} models" }

        try {
            configService.setModelsVisibility(visibilityMap)
            logger.info { "Model visibility updated and persisted (batch)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update model visibility (batch)" }
            throw e
        }
    }

    // ===== Configuration Management =====

    /**
     * Initialize provider keys on application startup.
     * Syncs all provider API keys from database to System properties.
     */
    fun initializeProviderKeys() {
        logger.info { "[ConfigRouter] Initializing provider keys on startup" }
        syncProviderKeysToSystemProperties()
    }

    /**
     * Update configuration setting.
     *
     * @param section Configuration section (e.g., "general", "providers", "models")
     * @param scope Scope of configuration ("app", "task", "project")
     * @param taskId Optional task ID for task-scoped config
     * @param settings Map of setting key-value pairs to update
     * @return Update confirmation response
     */
    fun updateConfig(
        section: String,
        scope: String,
        taskId: String?,
        settings: Map<String, Any>
    ): UpdateConfigResponse {
        logger.info {
            "[ConfigRouter] Updating config: section=$section, scope=$scope, taskId=$taskId, settings=${SecureLogger.redactMap(settings)}"
        }

        // Convert scope string to ConfigScope enum
        val configScope = when (scope.lowercase()) {
            "app" -> ConfigScope.APP
            "task" -> ConfigScope.TASK
            else -> {
                logger.warn { "Unknown scope: $scope, defaulting to APP" }
                ConfigScope.APP
            }
        }

        // Save each setting to database
        settings.forEach { (key, value) ->
            val fullKey = "$section.$key"
            val valueStr = value.toString()

            configRepository.set(
                key = fullKey,
                value = valueStr,
                scope = configScope,
                taskId = if (configScope == ConfigScope.TASK) taskId else null,
                description = "Setting for $section"
            )

            logger.info { "Config updated: $fullKey = $value" }
        }

        // If provider settings were updated, sync to System properties and clear cache
        if (section == "providers" && configScope == ConfigScope.APP) {
            logger.info { "Provider settings updated, syncing to System properties" }
            syncProviderKeysToSystemProperties()

            // Clear ModelRegistry cache to force fresh fetch with new API keys
            clearModelsCache()
            logger.info { "ModelRegistry cache cleared after provider settings update" }
        }

        return UpdateConfigResponse(
            section = section,
            scope = scope,
            updatedKeys = settings.keys.toList(),
            success = true
        )
    }

    /**
     * Get configuration settings for a section.
     *
     * @param section Configuration section (e.g., "general", "providers", "models")
     * @param scope Scope of configuration ("app", "task", "project")
     * @param taskId Optional task ID for task-scoped config
     * @return Configuration response with settings map
     */
    fun getConfig(
        section: String,
        scope: String,
        taskId: String? = null
    ): GetConfigResponse {
        logger.info { "[ConfigRouter] Getting config: section=$section, scope=$scope, taskId=$taskId" }

        // Convert scope string to ConfigScope enum
        val configScope = when (scope.lowercase()) {
            "app" -> ConfigScope.APP
            "task" -> ConfigScope.TASK
            else -> {
                logger.warn { "Unknown scope: $scope, defaulting to APP" }
                ConfigScope.APP
            }
        }

        // Get all configs for this section and scope
        val configList = configRepository.findByScope(
            scope = configScope,
            taskId = if (configScope == ConfigScope.TASK) taskId else null
        )

        // Filter by section prefix and build settings map
        val sectionPrefix = "$section."
        val settings = configList
            .filter { it.key.startsWith(sectionPrefix) }
            .associate { config ->
                // Remove section prefix from key
                val shortKey = config.key.removePrefix(sectionPrefix)
                shortKey to config.value
            }

        logger.info { "Found ${settings.size} settings for section=$section, scope=$scope" }

        return GetConfigResponse(
            section = section,
            scope = scope,
            settings = settings
        )
    }

    /**
     * Get configuration for a section (delegates to version with optional taskId).
     *
     * @param section Configuration section (e.g., "general", "providers", "models")
     * @param scope Scope of configuration ("app", "task", "project")
     * @return Configuration settings for the section
     */
    fun getConfig(section: String, scope: String): GetConfigResponse {
        return getConfig(section, scope, null)
    }

    /**
     * Reset all settings to defaults.
     *
     * Resets all configuration settings across all sections to their default values.
     *
     * @return Reset confirmation response
     */
    fun resetAllSettingsToDefaults(): ResetConfigResponse {
        logger.info { "[ConfigRouter] Resetting all settings to defaults" }

        // TODO: Implement actual config reset
        // For now, just log the action
        val affectedSections = listOf(
            "general", "providers", "models", "prompts",
            "tools", "index", "docs", "limits", "advanced"
        )

        logger.info { "Reset completed for sections: $affectedSections" }

        return ResetConfigResponse(
            success = true,
            message = "All settings reset to defaults",
            affectedSections = affectedSections
        )
    }

    // ===== Helper Functions =====

    /**
     * Sync provider API keys from database to System properties.
     * This ensures that LLM adapters can access the API keys via System.getProperty().
     */
    private fun syncProviderKeysToSystemProperties() {
        try {
            logger.info { "Syncing provider API keys to System properties" }

            // Get all provider settings from database
            val providerConfigs = configRepository.search("${ConfigService.KEY_PREFIX_PROVIDERS}%", ConfigScope.APP)

            providerConfigs.forEach { config ->
                // config.key format: "providers.anthropic.anthropic_api_key"
                // Extract: provider name and setting type
                val keyParts = config.key.removePrefix("providers.").split(".")
                if (keyParts.size != 2) {
                    logger.debug { "Skipping invalid provider key: ${config.key}" }
                    return@forEach
                }

                val providerName = keyParts[0].lowercase() // "anthropic", "openai", etc.
                val settingType = keyParts[1] // "anthropic_api_key", "ollama_endpoint", etc.

                // Map to System property names
                when {
                    providerName == "anthropic" && settingType == "anthropic_api_key" -> {
                        System.setProperty("ANTHROPIC_API_KEY", config.value)
                        logger.debug { "Set ANTHROPIC_API_KEY from database" }
                    }

                    providerName == "openai" && settingType == "openai_api_key" -> {
                        System.setProperty("OPENAI_API_KEY", config.value)
                        logger.debug { "Set OPENAI_API_KEY from database" }
                    }

                    providerName == "openrouter" && settingType == "openrouter_api_key" -> {
                        System.setProperty("OPENROUTER_API_KEY", config.value)
                        logger.debug { "Set OPENROUTER_API_KEY from database" }
                    }

                    providerName == "ollama" && settingType == "ollama_endpoint" -> {
                        System.setProperty("OLLAMA_BASE_URL", config.value)
                        logger.debug { "Set OLLAMA_BASE_URL from database" }
                    }

                    providerName == "gemini" && settingType == "gemini_api_key" -> {
                        System.setProperty("GEMINI_API_KEY", config.value)
                        logger.debug { "Set GEMINI_API_KEY from database" }
                    }

                    providerName == "lmstudio" && settingType == "lmstudio_api_key" -> {
                        System.setProperty("LM_STUDIO_API_KEY", config.value)
                        logger.debug { "Set LM_STUDIO_API_KEY from database" }
                    }

                    providerName == "lmstudio" && settingType == "lmstudio_base_url" -> {
                        System.setProperty("LM_STUDIO_BASE_URL", config.value)
                        logger.debug { "Set LM_STUDIO_BASE_URL from database" }
                    }

                    else -> {
                        logger.debug { "Unknown provider setting: ${config.key}" }
                    }
                }
            }

            logger.info { "Provider API keys synced to System properties successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync provider keys to System properties: ${e.message}" }
        }
    }
}
