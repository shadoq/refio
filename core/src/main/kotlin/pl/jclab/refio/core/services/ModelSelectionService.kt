package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.getModelConfigFromCache
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_EMBEDDING_MODEL
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_EMBEDDING_PROVIDER
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_MODEL
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_PROVIDER
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_WEAK_MODEL
import pl.jclab.refio.core.services.ConfigService.Companion.FALLBACK_WEAK_PROVIDER
import pl.jclab.refio.core.services.ConfigService.Companion.INHERIT_MODEL_VALUE
import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * Model-selection logic extracted from [ConfigService].
 *
 * Owns:
 * - Logical-slot resolution (`getModel`, `getDefaultModel`, `getStrongModel`, `getWeakModel`, `getEmbeddingModel`).
 * - Slot writers (`setDefaultModel`, `setDefaultModelAllModes`, `setEmbeddingModel`).
 * - Model dropdown visibility (`getModel*Visibility`, `setModel*Visibility`).
 * - The small parsing/inheritance helpers for `provider/model` strings and `inherit` sentinels.
 *
 * Kept in the same package as [ConfigService] so it can use the `internal` access points
 * (`configRepository`, `yamlLoader`, `getConfigWithPrecedence`, `invalidateConfigCache`,
 * `setTyped`) without widening them to the whole module.
 */
internal class ModelSelectionService(private val configService: ConfigService) {
    private val logger = dualLogger("ModelSelectionService")

    fun getModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> {
        val selectedModel = configService.get(
            key = ConfigKeys.UI_SELECTED_MODEL.key,
            taskId = taskId,
            projectId = projectId
        )

        if (selectedModel != null && selectedModel.isNotBlank() && !selectedModel.equals("auto", ignoreCase = true)) {
            val (providerFromString, modelIdFromString) = parseModelString(selectedModel)
            logger.info { "Using user-selected model for $operation: $modelIdFromString (provider=$providerFromString)" }
            return Pair(modelIdFromString, providerFromString)
        }

        return getDefaultModel(operation, taskId, projectId)
    }

    fun getDefaultModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> {
        val key = configKeyForOperation(operation)
        val config = configService.getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
        val label = operation.name.lowercase()

        if (operation == ModelOperation.EMBEDDING) {
            if (config?.value != null) {
                val (provider, model) = parseModelString(config.value)
                logger.info { "Using embedding model from DB: $model (provider=$provider)" }
                return Pair(model, provider)
            }
            val yamlModel = configService.yamlLoader.getDefaultEmbeddingModel()
            if (yamlModel != null) {
                val (provider, model) = parseModelString(yamlModel)
                logger.info { "Using embedding model from YAML: $model (provider=$provider)" }
                return Pair(model, provider)
            }
            return fallbackModelForOperation(operation)
        }

        if (config != null) {
            val data = gson.fromJson(config.value, ModelConfigData::class.java)
            if (operation != ModelOperation.DEFAULT && isInheritedModelConfig(data)) {
                logger.info { "Using inherited $label model -> default model" }
                return getDefaultModel(ModelOperation.DEFAULT, taskId, projectId)
            }
            if (data.modelId != null && data.provider != null) {
                logger.info { "Using $label model from DB: ${data.modelId}" }
                return Pair(data.modelId, data.provider)
            }
        }

        val yamlModel = when (operation) {
            ModelOperation.DEFAULT -> configService.yamlLoader.getDefaultChatModel()
            ModelOperation.PLAN -> configService.yamlLoader.getDefaultPlanModel()
            ModelOperation.CODING -> configService.yamlLoader.getDefaultCodingModel()
            ModelOperation.WEAK -> configService.yamlLoader.getDefaultWeakModel()
            ModelOperation.STRONG -> configService.yamlLoader.getDefaultStrongModel()
            ModelOperation.EMBEDDING -> error("unreachable")
        }
        if (yamlModel != null) {
            val (provider, model) = parseModelString(yamlModel)
            logger.info { "Using $label model from YAML: $model (provider=$provider)" }
            return Pair(model, provider)
        }

        if (operation == ModelOperation.STRONG) {
            throw IllegalStateException("STRONG model not configured and has no fallback")
        }

        val fallback = fallbackModelForOperation(operation)
        logger.info { "No config found for $operation, using fallback: ${fallback.first}" }
        return fallback
    }

    fun getStrongModel(
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String>? {
        val config = configService.getConfigWithPrecedence(key = ConfigKeys.STRONG_MODEL.key, taskId = taskId, projectId = projectId)
        if (config != null) {
            val data = gson.fromJson(config.value, ModelConfigData::class.java)
            if (isInheritedModelConfig(data)) {
                logger.info { "Using inherited strong model -> default model" }
                return getDefaultModel(ModelOperation.DEFAULT, taskId, projectId)
            }
            if (data.modelId != null && data.provider != null) {
                logger.info { "Using strong model from DB: ${data.modelId}" }
                return Pair(data.modelId, data.provider)
            }
        }

        val yamlModel = configService.yamlLoader.getDefaultStrongModel()
        if (yamlModel != null) {
            val (provider, model) = parseModelString(yamlModel)
            logger.info { "Using strong model from YAML: $model (provider=$provider)" }
            return Pair(model, provider)
        }

        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun setDefaultModel(
        operation: ModelOperation,
        modelId: String,
        provider: String,
        taskId: String? = null,
        _userId: String? = null
    ) {
        if (operation != ModelOperation.EMBEDDING) {
            val modelConfig = getModelConfigFromCache(modelId)
            if (modelConfig != null && modelConfig.provider != provider) {
                return
            }
        }

        if (operation == ModelOperation.EMBEDDING) {
            setEmbeddingModel("$provider/$modelId")
            logger.info { "Set embedding model to $provider/$modelId" }
            return
        }

        val key = configKeyForOperation(operation)
        val scope = if (operation != ModelOperation.WEAK && taskId != null) ConfigScope.TASK else ConfigScope.APP
        val effectiveTaskId = if (scope == ConfigScope.TASK) taskId else null
        val description = if (operation == ModelOperation.WEAK) {
            "Cheap model for auxiliary operations"
        } else {
            "Default model for $operation operation"
        }
        configService.configRepository.set(
            key = key,
            value = gson.toJson(ModelConfigData(modelId, provider)),
            scope = scope,
            taskId = effectiveTaskId,
            description = description
        )
        configService.invalidateConfigCache(key)
        logger.info { "Set ${scope.name} config $key = $modelId" }
    }

    fun setDefaultModelAllModes(
        modelId: String,
        provider: String,
        taskId: String? = null,
        userId: String? = null
    ) {
        val modelConfig = getModelConfigFromCache(modelId)
        if (modelConfig != null && modelConfig.provider != provider) {
            return
        }

        for (operation in listOf(ModelOperation.DEFAULT, ModelOperation.PLAN, ModelOperation.CODING)) {
            setDefaultModel(
                operation = operation,
                modelId = modelId,
                provider = provider,
                taskId = taskId,
                _userId = userId
            )
        }

        logger.info { "Set default model for ALL modes: $modelId (provider=$provider)" }
    }

    fun getModelVisibility(modelId: String): Boolean {
        val config = configService.configRepository.get(ConfigKeys.MODELS_VISIBILITY.key, ConfigScope.APP)
        if (config != null) {
            @Suppress("UNCHECKED_CAST")
            val visibilityMap = gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>
            return visibilityMap?.get(modelId) ?: true
        }
        return true
    }

    fun setModelVisibility(modelId: String, showInDropdown: Boolean) {
        setModelsVisibility(getModelsVisibility().toMutableMap().apply { put(modelId, showInDropdown) })
        logger.info { "Updated model visibility: $modelId -> $showInDropdown" }
    }

    fun setModelsVisibility(visibilityMap: Map<String, Boolean>) {
        val valueJson = gson.toJson(visibilityMap)
        configService.configRepository.set(
            key = ConfigKeys.MODELS_VISIBILITY.key,
            value = valueJson,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Model visibility settings"
        )

        logger.info { "Updated model visibility for ${visibilityMap.size} models" }
        configService.invalidateConfigCache(ConfigKeys.MODELS_VISIBILITY.key)
    }

    fun getModelsVisibility(): Map<String, Boolean> {
        val config = configService.configRepository.get(ConfigKeys.MODELS_VISIBILITY.key, ConfigScope.APP)
        if (config != null) {
            @Suppress("UNCHECKED_CAST")
            val visibilityMap = gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>
            if (visibilityMap != null && visibilityMap.isNotEmpty()) {
                return visibilityMap
            }
        }

        val yamlVisibility = configService.yamlLoader.getModelsVisibility()
        if (yamlVisibility != null && yamlVisibility.isNotEmpty()) {
            return yamlVisibility
        }

        return emptyMap()
    }

    fun getWeakModel(): Pair<String, String> = getDefaultModel(ModelOperation.WEAK)

    fun getEmbeddingModel(): String {
        val (modelId, provider) = getDefaultModel(ModelOperation.EMBEDDING)
        return "$provider/$modelId"
    }

    fun setEmbeddingModel(model: String) {
        configService.setTyped(ConfigKeys.EMBEDDING_MODEL, model)
    }

    /**
     * Parse model string that might be in format "provider/model" or just "model".
     *
     * Examples:
     * - "ollama/qwen2.5:7b" -> ("ollama", "qwen2.5:7b")
     * - "qwen2.5:7b" -> ("ollama", "qwen2.5:7b") // fallback to ollama
     * - "gpt-4.1-mini" -> ("openai", "gpt-4.1-mini") // fallback to openai
     */
    fun parseModelString(modelString: String): Pair<String, String> {
        if (modelString.contains("/")) {
            val parts = modelString.split("/", limit = 2)
            return Pair(parts[0], parts[1])
        }

        val provider = when {
            modelString.startsWith("gpt-") -> "openai"
            modelString.startsWith("glm-") -> "zai"
            modelString.startsWith("claude-") -> "anthropic"
            else -> FALLBACK_PROVIDER
        }

        return Pair(provider, modelString)
    }

    private fun configKeyForOperation(operation: ModelOperation): String = when (operation) {
        ModelOperation.DEFAULT -> ConfigKeys.DEFAULT_MODEL_CHAT.key
        ModelOperation.PLAN -> ConfigKeys.DEFAULT_MODEL_PLAN.key
        ModelOperation.CODING -> ConfigKeys.DEFAULT_MODEL_AGENT.key
        ModelOperation.WEAK -> ConfigKeys.WEAK_MODEL.key
        ModelOperation.EMBEDDING -> ConfigKeys.EMBEDDING_MODEL.key
        ModelOperation.STRONG -> ConfigKeys.STRONG_MODEL.key
    }

    private fun isInheritedModelConfig(data: ModelConfigData): Boolean {
        return data.modelId.equals(INHERIT_MODEL_VALUE, ignoreCase = true) &&
                data.provider.equals(INHERIT_MODEL_VALUE, ignoreCase = true)
    }

    private fun fallbackModelForOperation(operation: ModelOperation): Pair<String, String> = when (operation) {
        ModelOperation.DEFAULT,
        ModelOperation.PLAN,
        ModelOperation.CODING -> Pair(FALLBACK_MODEL, FALLBACK_PROVIDER)
        ModelOperation.WEAK -> Pair(FALLBACK_WEAK_MODEL, FALLBACK_WEAK_PROVIDER)
        ModelOperation.EMBEDDING -> Pair(FALLBACK_EMBEDDING_MODEL, FALLBACK_EMBEDDING_PROVIDER)
        ModelOperation.STRONG -> throw IllegalStateException("STRONG model has no fallback — must be explicitly configured")
    }

    /** JSON storage format for model selection entries in DB. */
    internal data class ModelConfigData(
        val modelId: String? = null,
        val provider: String? = null
    )
}
