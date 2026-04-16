package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.subagents.BuiltinSubagentOverrides
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

/**
 * Service for managing application configuration.
 *
 * Configuration hierarchy (from lowest to highest priority):
 * 1. Built-in defaults (hardcoded in this class)
 * 2. User config file (~/.refio/config.yaml)
 * 3. Project config file (<project>/.refio/config.yaml)
 * 4. Database overrides (settings changed via Settings UI)
 *
 * Handles default model selection per logical operation slot.
 */
class ConfigService(
    internal val configRepository: ConfigRepository,
    private val defaultProjectId: String? = null,
    private val projectRoot: Path? = null
) {
    private val logger = dualLogger("ConfigService")
    private val configCache = ConfigCache()

    /**
     * Hierarchical config loader for YAML files.
     * Handles user and project config files.
     */
    internal val yamlLoader: HierarchicalConfigLoader by lazy {
        HierarchicalConfigLoader.getInstance(projectRoot)
    }

    private val resolver = ConfigResolver(
        configRepository = configRepository,
        yamlLoader = yamlLoader,
        cache = configCache,
        defaultProjectId = defaultProjectId,
    )

    private val modelSelectionService = ModelSelectionService(this)

    fun <T> getTyped(configKey: ConfigKey<T>, taskId: String? = null): T =
        resolver.getTyped(configKey, taskId)

    fun <T> setTyped(configKey: ConfigKey<T>, value: T, scope: ConfigScope = ConfigScope.APP, taskId: String? = null) =
        resolver.setTyped(configKey, value, scope, taskId)

    companion object {
        const val INHERIT_MODEL_VALUE = "inherit"

        /** Context sizes at or below this threshold trigger compact (shorter) prompts. */
        const val COMPACT_PROMPT_THRESHOLD = 48_000

        /** Default assumed context size when model metadata is unavailable. */
        const val DEFAULT_CONTEXT_SIZE = 32768

        // Prefixes for dynamic key searches (not single keys, can't live in ConfigKeys registry).
        const val KEY_PREFIX_PROVIDERS = "providers."
        const val KEY_CONTEXT_BUDGET_SECTION_PREFIX = "context.budget.section."

        // Hard fallbacks used when neither DB nor YAML provide a value (last resort).
        const val FALLBACK_MODEL = "qwen2.5:7b"
        const val FALLBACK_PROVIDER = "ollama"
        const val FALLBACK_WEAK_MODEL = "qwen2.5:7b"
        const val FALLBACK_WEAK_PROVIDER = "ollama"
        const val FALLBACK_EMBEDDING_MODEL = "nomic-embed-text"
        const val FALLBACK_EMBEDDING_PROVIDER = "ollama"
        val KEY_JSON_THINKING_XML_TAGS get() = ConfigKeys.JSON_THINKING_XML_TAGS.key
    }

    /**
     * Get the logical model to use for a request.
     *
     * Delegates to [ModelSelectionService].
     */
    fun getModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> = modelSelectionService.getModel(operation, taskId, projectId)

    fun getDefaultModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> = modelSelectionService.getDefaultModel(operation, taskId, projectId)

    fun getStrongModel(
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String>? = modelSelectionService.getStrongModel(taskId, projectId)

    @Suppress("UNUSED_PARAMETER")
    fun setDefaultModel(
        operation: ModelOperation,
        modelId: String,
        provider: String,
        taskId: String? = null,
        _userId: String? = null
    ) = modelSelectionService.setDefaultModel(operation, modelId, provider, taskId, _userId)

    fun setDefaultModelAllModes(
        modelId: String,
        provider: String,
        taskId: String? = null,
        userId: String? = null
    ) = modelSelectionService.setDefaultModelAllModes(modelId, provider, taskId, userId)

    fun getModelVisibility(modelId: String): Boolean =
        modelSelectionService.getModelVisibility(modelId)

    fun setModelVisibility(modelId: String, showInDropdown: Boolean) =
        modelSelectionService.setModelVisibility(modelId, showInDropdown)

    fun setModelsVisibility(visibilityMap: Map<String, Boolean>) =
        modelSelectionService.setModelsVisibility(visibilityMap)

    fun getModelsVisibility(): Map<String, Boolean> =
        modelSelectionService.getModelsVisibility()

    fun get(
        key: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null
    ): String? = resolver.get(key, scope, taskId, projectId)

    fun getFromYaml(key: String): String? = resolver.getFromYaml(key)

    fun set(
        key: String,
        value: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null
    ) = resolver.set(key, value, scope, taskId, projectId)

    // ==================== UI CONFIGURATION ====================

    fun setThinkingEnabled(enabled: Boolean, taskId: String? = null) {
        setTyped(ConfigKeys.UI_THINKING_ENABLED, enabled, taskScope(taskId), taskId)
    }

    fun setNoEgressEnabled(enabled: Boolean, taskId: String? = null) {
        setTyped(ConfigKeys.UI_NO_EGRESS_ENABLED, enabled, taskScope(taskId), taskId)
    }

    fun setExecutionMode(mode: String, taskId: String? = null) {
        setTyped(ConfigKeys.UI_EXECUTION_MODE, mode, taskScope(taskId), taskId)
    }

    fun setSelectedModel(model: String, taskId: String? = null) {
        setTyped(ConfigKeys.UI_SELECTED_MODEL, model, taskScope(taskId), taskId)
    }

    // ==================== MODELS CONFIGURATION ====================

    fun getWeakModel(): Pair<String, String> = modelSelectionService.getWeakModel()

    fun getEmbeddingModel(): String = modelSelectionService.getEmbeddingModel()

    fun setEmbeddingModel(model: String) = modelSelectionService.setEmbeddingModel(model)

    fun getYamlConfig(): ConfigYaml {
        return yamlLoader.getConfig()
    }

    /**
     * Export current configuration to a YAML file.
     *
     * @param file Target file to write
     * @param includeApiKeys If true, includes API keys (masked for security)
     */
    fun exportToYaml(file: java.io.File, includeApiKeys: Boolean = false) {
        val config = ConfigYamlBuilder(this, configRepository).build(includeApiKeys)
        ConfigYaml.saveToFile(config, file, withComments = true)
        logger.info { "Exported configuration to: ${file.absolutePath}" }
    }

    private val builtinSubagentOverrides = BuiltinSubagentOverrides(
        configRepository = configRepository,
        invalidate = ::invalidateConfigCache,
    )

    fun getBuiltinSubagentEnabledOverrides(): Map<String, Boolean> =
        builtinSubagentOverrides.getAll()

    fun setBuiltinSubagentEnabledOverride(name: String, enabled: Boolean) =
        builtinSubagentOverrides.setOverride(name, enabled)

    // ==================== CONTEXT CONFIGURATION (ADR 0017) ====================
    // Delegated to ContextBudgetResolver to keep budget math out of this class.

    private val contextBudgetResolver = ContextBudgetResolver(this)

    fun getContextBudget(taskId: String? = null, operation: ModelOperation? = null): ContextBudget =
        contextBudgetResolver.getContextBudget(taskId, operation)

    fun isCompactPrompts(operation: ModelOperation? = null, taskId: String? = null): Boolean =
        contextBudgetResolver.isCompactPrompts(operation, taskId)

    private val defaultsInitializer = ConfigDefaultsInitializer(
        configRepository = configRepository,
        applyYaml = ::applyYaml,
        invalidateAllCaches = configCache::invalidateAll,
    )

    private val validator = ConfigValidator(this)

    fun loadFromYamlIfMissing() {
        defaultsInitializer.loadFromYamlIfMissing()
        validator.validateAll()
    }

    fun initializeDefaults() {
        defaultsInitializer.initializeDefaults()
        validator.validateAll()
    }

    fun reloadFromYaml(): Int {
        val updated = defaultsInitializer.reloadFromYaml()
        validator.validateAll()
        return updated
    }

    /**
     * Walk a [ConfigYaml] snapshot and apply it to DB.
     * @param overwrite true = `reload` semantics (overwrite all, count updates);
     *                  false = `load-if-missing` semantics (skip keys already present).
     * @return Number of keys written.
     */
    private fun applyYaml(yamlConfig: pl.jclab.refio.core.config.ConfigYaml, overwrite: Boolean): Int {
        return yamlApplier.apply(yamlConfig, overwrite)
    }

    private val yamlApplier = ConfigYamlApplier(
        configRepository = configRepository,
        setter = ::set,
        modelsVisibilityGetter = ::getModelsVisibility,
        defaultModelSetter = ::setDefaultModel,
        modelStringParser = modelSelectionService::parseModelString,
    )

    private fun taskScope(taskId: String?): ConfigScope =
        if (taskId != null) ConfigScope.TASK else ConfigScope.APP

    internal fun invalidateConfigCache(key: String) = resolver.invalidate(key)

    internal fun getConfigWithPrecedence(
        key: String,
        taskId: String? = null,
        projectId: String? = null,
    ) = resolver.getConfigWithPrecedence(key, taskId, projectId)

    /**
     * Check if intelligent orchestration is enabled (US-028).
     *
     * @param taskId Optional task ID for task-level override
     * @return true if orchestration is enabled (default: true)
     */
    /**
     * Check if task verification should run for current turn.
     * ADR 0019 P13: Auto-enable verification for longer turns (>5 iterations) to catch hallucinations.
     *
     * @param taskId Task ID (for config override)
     * @param iterationCount Current iteration count in turn
     * @return true if verification should run
     */
    fun shouldVerifyTask(taskId: String? = null, iterationCount: Int = 0, writeToolsExecutedInTurn: Int = 0): Boolean {
        val explicitSetting = getConfigWithPrecedence(ConfigKeys.TASK_VERIFICATION_ENABLED.key, taskId)
        if (explicitSetting != null) {
            // User explicitly configured it - respect that
            return explicitSetting.value.toBoolean()
        }
        if (writeToolsExecutedInTurn > 0) {
            return true
        }
        // Auto-enable for longer turns (>5 iterations) where hallucinations are more likely
        return iterationCount >= 5
    }
}
