package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.config.TerminalCommandConfig
import pl.jclab.refio.core.config.TerminalConfig
import pl.jclab.refio.core.config.TerminalWhitelistConfig
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.getModelConfig
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.services.context.ContextSection
import pl.jclab.refio.core.tools.security.AllowedCommand
import pl.jclab.refio.core.tools.security.CommandWhitelistConfig
import pl.jclab.refio.core.tools.security.CommandWhitelistDefaults
import pl.jclab.refio.core.tools.security.WhitelistMode
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.runBlocking
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
    private val configRepository: ConfigRepository,
    private val defaultProjectId: String? = null,
    private val projectRoot: Path? = null
) {
    private val logger = dualLogger("ConfigService")

    /**
     * Hierarchical config loader for YAML files.
     * Handles user and project config files.
     */
    private val yamlLoader: HierarchicalConfigLoader by lazy {
        HierarchicalConfigLoader.getInstance(projectRoot)
    }

    companion object {
        // Configuration keys
        const val KEY_DEFAULT_MODEL_CHAT = "default_model.chat"
        const val KEY_DEFAULT_MODEL_PLAN = "default_model.plan"
        const val KEY_DEFAULT_MODEL_AGENT = "default_model.agent"
        const val KEY_WEAK_MODEL = "default_model.weak"  // Cheap model for auxiliary operations (summaries, etc.)
        const val KEY_MODELS_VISIBILITY = "models.visibility"

        // Limits configuration keys
        const val KEY_API_CALL_TIMEOUT = "limits.api_call_timeout"
        const val KEY_STREAMING_READ_TIMEOUT = "limits.streaming_read_timeout_sec"
        const val KEY_STREAMING_REQUEST_TIMEOUT = "limits.streaming_request_timeout_sec"
        const val KEY_TOOL_EXECUTION_TIMEOUT = "limits.tool_execution_timeout"
        const val KEY_MAX_CONTEXT_SIZE = "limits.max_context_size"
        const val KEY_MAX_OUTPUT_SIZE = "limits.max_output_size"
        const val KEY_MAX_FILE_SIZE = "limits.max_file_size"
        const val KEY_MAX_RETRIES = "limits.max_retries"
        const val KEY_RATE_LIMIT_RPM = "limits.rate_limit_rpm"
        const val KEY_RETRY_DELAY_MS = "limits.retry_delay_ms"

        // Orchestration configuration keys (US-028)
        const val KEY_ORCHESTRATION_ENABLED = "orchestration.enabled"

        // UI configuration keys
        const val KEY_UI_THINKING_ENABLED = "ui.thinking_enabled"
        const val KEY_UI_NO_EGRESS_ENABLED = "ui.no_egress_enabled"
        const val KEY_UI_ORCHESTRATION_ENABLED = "ui.orchestration_enabled"
        const val KEY_UI_INTENT_CLASSIFICATION_ENABLED = "ui.intent_classification_enabled"
        const val KEY_UI_EXECUTION_MODE = "ui.execution_mode"
        const val KEY_UI_SELECTED_MODE = "ui.selected_mode"
        const val KEY_UI_SELECTED_MODEL = "ui.selected_model"

        // Models configuration keys
        const val KEY_EMBEDDING_MODEL = "models.embedding_model"

        // RAG configuration keys
        const val KEY_RAG_ENABLED = "rag.enabled"
        const val KEY_RAG_AUTO_INDEX_ON_CONTEXT = "rag.auto_index_on_context_build"
        const val KEY_RAG_INDEX_ON_STARTUP = "rag.index_on_startup"
        const val KEY_RAG_MAX_FILE_SIZE_MB = "rag.max_file_size_mb"
        const val KEY_RAG_CACHE_TTL_MS = "rag.cache_ttl_ms"
        const val KEY_RAG_MAX_CONCURRENT_JOBS = "rag.max_concurrent_jobs"
        const val KEY_RAG_MAX_CHUNKS_PER_FILE = "rag.max_chunks_per_file"
        const val KEY_RAG_INDEX_BATCH_SIZE = "rag.index_batch_size"
        const val KEY_RAG_EMBEDDINGS_BATCH_SIZE = "rag.embeddings_batch_size"
        const val KEY_RAG_IGNORED_DIRECTORIES = "rag.ignored_directories"
        const val KEY_RAG_SEARCH_SIMILARITY_THRESHOLD = "rag.search_similarity_threshold"
        const val KEY_RAG_SEARCH_TOP_K = "rag.search_top_k"
        const val KEY_RAG_SEARCH_HYBRID_ENABLED = "rag.search_hybrid_enabled"
        const val KEY_RAG_SEARCH_SEMANTIC_WEIGHT = "rag.search_semantic_weight"
        const val KEY_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS = "rag.search_include_context_chunks"
        const val KEY_PROJECT_ANALYSIS_MAX_FILES = "project_analysis.max_files"
        const val KEY_PROJECT_ANALYSIS_FINGERPRINT_LIMIT = "project_analysis.fingerprint_limit"
        const val KEY_PROJECT_ANALYSIS_CACHE_TTL_MS = "project_analysis.cache_ttl_ms"

        // General configuration keys
        const val KEY_FORMAT_MARKDOWN = "general.format_markdown"
        const val KEY_STREAMING_ENABLED = "general.streaming_enabled"
        const val KEY_ADVANCED_VIEW = "general.advanced_view"

        // Advanced configuration keys
        const val KEY_AUTO_OPTIMIZE_PERCENTAGE = "advanced.auto_optimize_percentage"
        const val KEY_NO_EGRESS_DEFAULT = "advanced.no_egress_default"
        const val KEY_READ_ONLY_MODE = "advanced.read_only_mode"

        // Tools configuration keys
        const val KEY_TOOLS_PERMISSIONS = "tools.permissions"
        const val KEY_TOOL_PERMISSION_RUN_TERMINAL = "tools.permission_run_terminal_command"
        const val KEY_TERMINAL_WHITELIST = "terminal.whitelist"
        const val KEY_TERMINAL_WHITELIST_ENABLED = "terminal.whitelist.enabled"
        const val KEY_TERMINAL_WHITELIST_MODE = "terminal.whitelist.mode"

        // Tool result summarization configuration keys
        const val KEY_TOOL_SUMMARY_ENABLED = "tool_summary.enabled"
        const val KEY_TOOL_SUMMARY_MIN_LENGTH = "tool_summary.min_length"

        // Context configuration keys (ADR 0017)
        const val KEY_RECENT_WORK_FULL_DATA_LIMIT = "context.recent_work.full_data_limit"
        const val KEY_RECENT_WORK_SUMMARY_MAX_LENGTH = "context.recent_work.summary_max_length"
        const val KEY_CONTEXT_BUDGET_TOTAL_TOKENS = "context.budget.total_tokens"
        const val KEY_CONTEXT_BUDGET_INPUT_RATIO = "context.budget.input_ratio"
        const val KEY_CONTEXT_BUDGET_SECTION_PREFIX = "context.budget.section."

        // Subagents configuration keys
        const val KEY_SUBAGENTS_BUILTIN_ENABLED = "subagents.builtin_enabled"

        // Provider configuration key prefixes (dynamic keys with provider name)
        const val KEY_PREFIX_PROVIDERS = "providers."
        const val KEY_PROVIDER_OLLAMA_ENDPOINT = "providers.ollama.ollama_endpoint"
        const val KEY_PROVIDER_OLLAMA_CONTEXT_SIZE = "providers.ollama.ollama_context_size"
        const val KEY_PROVIDER_OLLAMA_KEEP_ALIVE = "providers.ollama.ollama_keep_alive"
        const val KEY_PROVIDER_ANTHROPIC_API_KEY = "providers.anthropic.anthropic_api_key"
        const val KEY_PROVIDER_OPENAI_API_KEY = "providers.openai.openai_api_key"
        const val KEY_PROVIDER_OPENROUTER_API_KEY = "providers.openrouter.openrouter_api_key"
        const val KEY_PROVIDER_GEMINI_API_KEY = "providers.gemini.gemini_api_key"
        const val KEY_PROVIDER_LM_STUDIO_API_KEY = "providers.lmstudio.lmstudio_api_key"
        const val KEY_PROVIDER_LM_STUDIO_BASE_URL = "providers.lmstudio.lmstudio_base_url"
        const val KEY_PROVIDER_LM_STUDIO_CONTEXT_SIZE = "providers.lmstudio.lmstudio_context_size"

        // Fallback defaults (used when no config exists)
        const val FALLBACK_MODEL = "qwen2.5:7b"
        const val FALLBACK_PROVIDER = "ollama"
        const val FALLBACK_WEAK_MODEL = "qwen2.5:7b"
        const val FALLBACK_WEAK_PROVIDER = "ollama"
        const val FALLBACK_EMBEDDING_MODEL = "nomic-embed-text"
        const val FALLBACK_EMBEDDING_PROVIDER = "ollama"

        // Limit defaults
        const val DEFAULT_API_CALL_TIMEOUT = 360 // seconds
        const val DEFAULT_STREAMING_READ_TIMEOUT = 360
        const val DEFAULT_STREAMING_REQUEST_TIMEOUT = 1800
        const val DEFAULT_TOOL_EXECUTION_TIMEOUT = 360 // seconds
        const val DEFAULT_CONTEXT_SIZE = 32768 // tokens
        const val DEFAULT_MAX_CONTEXT_SIZE = 128000 // tokens
        const val DEFAULT_MAX_OUTPUT_SIZE = 16384 // tokens
        const val DEFAULT_MAX_FILE_SIZE = 10 // MB
        const val DEFAULT_ORCHESTRATION_ENABLED = true
        const val DEFAULT_RAG_MAX_FILE_SIZE_MB = 2L
        const val DEFAULT_RAG_INDEX_ON_STARTUP = true
        const val DEFAULT_RAG_INDEX_BATCH_SIZE = 10
        const val DEFAULT_RAG_EMBEDDING_BATCH_SIZE = 50
        const val DEFAULT_RAG_CACHE_TTL_MS = 300_000L
        const val DEFAULT_RAG_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_RAG_MAX_CHUNKS_PER_FILE = 100
        const val DEFAULT_RAG_SEARCH_SIMILARITY_THRESHOLD = 0.5f
        const val DEFAULT_RAG_SEARCH_TOP_K = 5
        const val DEFAULT_RAG_SEARCH_HYBRID_ENABLED = false
        const val DEFAULT_RAG_SEARCH_SEMANTIC_WEIGHT = 0.7f
        const val DEFAULT_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS = false
        val DEFAULT_RAG_IGNORED_DIRECTORIES = listOf(
            ".git",
            ".idea",
            ".vscode",
            ".gradle",
            ".claude",
            ".continue",
            ".github",
            ".refio",
            ".codex",
            ".junie",
            ".husky",
            ".vscode",
            "node_modules",
            "build",
            "dist",
            "out",
            "target",
            "__pycache__",
            ".venv",
            "*.log",
            "*.tmp",
            "Agents.md",
            "CLAUDE.md",
            "GEMINI.md",
            ".gitignore",
            ".aiignore",
        )
        const val DEFAULT_PROJECT_ANALYSIS_MAX_FILES = 400
        const val DEFAULT_PROJECT_ANALYSIS_FINGERPRINT_LIMIT = 2000
        const val DEFAULT_PROJECT_ANALYSIS_CACHE_TTL_MS = 600_000L

        // Tool result summarization defaults
        const val DEFAULT_TOOL_SUMMARY_ENABLED = true
        const val DEFAULT_TOOL_SUMMARY_MIN_LENGTH = 500

        // Context defaults (ADR 0017)
        const val DEFAULT_RECENT_WORK_FULL_DATA_LIMIT = 5
        const val DEFAULT_RECENT_WORK_SUMMARY_MAX_LENGTH = 1000
        const val DEFAULT_CONTEXT_BUDGET_INPUT_RATIO = 0.85

        // Agent flow defaults (ADR 0019)
        const val DEFAULT_TASK_VERIFICATION_ENABLED = false
        const val DEFAULT_MAX_CONSECUTIVE_TOOL_ERRORS = 3
        const val DEFAULT_MAX_ITERATIONS = 50
        const val DEFAULT_JSON_THINKING_XML_TAGS = "thinking,think"

        // Agent flow configuration keys (ADR 0019)
        const val KEY_TASK_VERIFICATION_ENABLED = "agent.task_verification_enabled"
        const val KEY_MAX_CONSECUTIVE_TOOL_ERRORS = "agent.max_consecutive_tool_errors"
        const val KEY_MAX_ITERATIONS = "agent.max_iterations"
        const val KEY_JSON_THINKING_XML_TAGS = "agent.json_thinking_xml_tags"
    }

    /**
     * Get the logical model to use for a request.
     *
     * This method centralizes model selection logic:
     * 1. Check if user selected a specific model in UI (ui.selected_model)
     * 2. If yes and not "Auto" -> return that model for ALL operations
     * 3. If "Auto" or not set -> return operation-specific default model
     *
     * @param operation Model operation type (DEFAULT, PLAN, CODING, WEAK, EMBEDDING)
     * @param taskId Optional task ID for task-level override
     * @return Pair of (model_id, provider) to use for the request
     */
    fun getModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> {
        // Check if user selected a specific model in UI
        val selectedModel = get(
            key = KEY_UI_SELECTED_MODEL,
            taskId = taskId,
            projectId = projectId
        )

        if (selectedModel != null && selectedModel.isNotBlank() && !selectedModel.equals("auto", ignoreCase = true)) {
            // User selected a specific model -> use it for ALL operations
            val (providerFromString, modelIdFromString) = parseModelString(selectedModel)

            logger.info { "Using user-selected model for $operation: $modelIdFromString (provider=$providerFromString)" }
            return Pair(modelIdFromString, providerFromString)
        }

        // User selected "Auto" or no selection -> use operation-specific default
        return getDefaultModel(operation, taskId, projectId)
    }

    /**
     * Parse model string that might be in format "provider/model" or just "model".
     *
     * Examples:
     * - "ollama/qwen2.5:7b" -> ("ollama", "qwen2.5:7b")
     * - "qwen2.5:7b" -> ("ollama", "qwen2.5:7b") // fallback to ollama
     * - "gpt-4.1-mini" -> ("openai", "gpt-4.1-mini") // fallback to openai
     *
     * @param modelString Model identifier, optionally prefixed with provider
     * @return Pair of (provider, model_id)
     */
    private fun parseModelString(modelString: String): Pair<String, String> {
        if (modelString.contains("/")) {
            val parts = modelString.split("/", limit = 2)
            return Pair(parts[0], parts[1])
        }

        // Infer provider from model name patterns
        val provider = when {
            modelString.startsWith("gpt-") -> "openai"
            modelString.startsWith("claude-") -> "anthropic"
            else -> FALLBACK_PROVIDER // Default to ollama for unknown models
        }

        return Pair(provider, modelString)
    }

    private fun configKeyForOperation(operation: ModelOperation): String = when (operation) {
        ModelOperation.DEFAULT -> KEY_DEFAULT_MODEL_CHAT
        ModelOperation.PLAN -> KEY_DEFAULT_MODEL_PLAN
        ModelOperation.CODING -> KEY_DEFAULT_MODEL_AGENT
        ModelOperation.WEAK -> KEY_WEAK_MODEL
        ModelOperation.EMBEDDING -> KEY_EMBEDDING_MODEL
    }

    private fun fallbackModelForOperation(operation: ModelOperation): Pair<String, String> = when (operation) {
        ModelOperation.DEFAULT,
        ModelOperation.PLAN,
        ModelOperation.CODING -> Pair(FALLBACK_MODEL, FALLBACK_PROVIDER)
        ModelOperation.WEAK -> Pair(FALLBACK_WEAK_MODEL, FALLBACK_WEAK_PROVIDER)
        ModelOperation.EMBEDDING -> Pair(FALLBACK_EMBEDDING_MODEL, FALLBACK_EMBEDDING_PROVIDER)
    }

    /**
     * Get default model_id and provider for given mode.
     *
     * Configuration hierarchy:
     * 1. Database value (highest priority)
     * 2. YAML config (user + project)
     * 3. Built-in fallback
     *
     * @param mode Task mode (chat, plan, agent)
     * @param taskId Optional task ID for task-level override
     * @return Pair of (model_id, provider)
     */
    fun getDefaultModel(
        operation: ModelOperation,
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String> {
        val key = configKeyForOperation(operation)

        // 1. Check database first
        val config = getConfigWithPrecedence(
            key = key,
            taskId = taskId,
            projectId = projectId
        )

        when (operation) {
            ModelOperation.EMBEDDING -> {
                // Check DB first
                if (config?.value != null) {
                    val (provider, model) = parseModelString(config.value)
                    logger.info { "Using embedding model from DB: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
                // Check YAML
                val yamlModel = yamlLoader.getDefaultEmbeddingModel()
                if (yamlModel != null) {
                    val (provider, model) = parseModelString(yamlModel)
                    logger.info { "Using embedding model from YAML: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
                return fallbackModelForOperation(operation)
            }

            ModelOperation.WEAK -> {
                // Check DB first
                if (config != null) {
                    val data = gson.fromJson(config.value, ModelConfigData::class.java)
                    if (data.modelId != null && data.provider != null) {
                        logger.info { "Using weak model from DB: ${data.modelId}" }
                        return Pair(data.modelId, data.provider)
                    }
                }
                // Check YAML
                val yamlModel = yamlLoader.getDefaultWeakModel()
                if (yamlModel != null) {
                    val (provider, model) = parseModelString(yamlModel)
                    logger.info { "Using weak model from YAML: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
            }

            ModelOperation.DEFAULT -> {
                if (config != null) {
                    val data = gson.fromJson(config.value, ModelConfigData::class.java)
                    if (data.modelId != null && data.provider != null) {
                        logger.info { "Using chat model from DB: ${data.modelId}" }
                        return Pair(data.modelId, data.provider)
                    }
                }
                // Check YAML
                val yamlModel = yamlLoader.getDefaultChatModel()
                if (yamlModel != null) {
                    val (provider, model) = parseModelString(yamlModel)
                    logger.info { "Using chat model from YAML: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
            }

            ModelOperation.PLAN -> {
                if (config != null) {
                    val data = gson.fromJson(config.value, ModelConfigData::class.java)
                    if (data.modelId != null && data.provider != null) {
                        logger.info { "Using plan model from DB: ${data.modelId}" }
                        return Pair(data.modelId, data.provider)
                    }
                }
                // Check YAML
                val yamlModel = yamlLoader.getDefaultPlanModel()
                if (yamlModel != null) {
                    val (provider, model) = parseModelString(yamlModel)
                    logger.info { "Using plan model from YAML: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
            }

            ModelOperation.CODING -> {
                if (config != null) {
                    val data = gson.fromJson(config.value, ModelConfigData::class.java)
                    if (data.modelId != null && data.provider != null) {
                        logger.info { "Using coding model from DB: ${data.modelId}" }
                        return Pair(data.modelId, data.provider)
                    }
                }
                // Check YAML
                val yamlModel = yamlLoader.getDefaultCodingModel()
                if (yamlModel != null) {
                    val (provider, model) = parseModelString(yamlModel)
                    logger.info { "Using coding model from YAML: $model (provider=$provider)" }
                    return Pair(model, provider)
                }
            }
        }

        val fallback = fallbackModelForOperation(operation)
        logger.info { "No config found for $operation, using fallback: ${fallback.first}" }
        return fallback
    }

    /**
     * Set default model for given mode.
     *
     * @param mode Task mode (chat, plan, agent)
     * @param modelId Model identifier (e.g., "qwen2.5:7b")
     * @param provider Provider name (e.g., "ollama")
     * @param taskId Optional task ID for task-level config
     * @param userId Optional user ID for audit
     * @throws IllegalArgumentException If model_id doesn't exist in model_registry or provider mismatch
     */
    fun setDefaultModel(
        operation: ModelOperation,
        modelId: String,
        provider: String,
        taskId: String? = null,
        userId: String? = null
    ) {
        // Validate model exists by fetching from providers
        if (operation != ModelOperation.EMBEDDING) {
            val modelConfig = runBlocking {
                getModelConfig(modelId, this@ConfigService)
            }
            if (modelConfig==null || modelConfig.provider != provider) {
                return
            }
        }

        when (operation) {
            ModelOperation.WEAK -> {
                setWeakModel(modelId, provider)
                logger.info { "Set weak model to $provider/$modelId" }
                return
            }
            ModelOperation.EMBEDDING -> {
                setEmbeddingModel("$provider/$modelId")
                logger.info { "Set embedding model to $provider/$modelId" }
                return
            }
            else -> {
                val key = configKeyForOperation(operation)
                val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
                val valueJson = gson.toJson(ModelConfigData(modelId, provider))

                configRepository.set(
                    key = key,
                    value = valueJson,
                    scope = scope,
                    taskId = taskId,
                    description = "Default model for $operation operation"
                )

                logger.info { "Set ${scope.name} config $key = $modelId" }
            }
        }
    }

    /**
     * Set default model for ALL modes (chat, plan, agent) in one operation.
     *
     * @param modelId Model identifier (e.g., "qwen2.5:7b")
     * @param provider Provider name (e.g., "ollama")
     * @param taskId Optional task ID for task-level config
     * @param userId Optional user ID for audit
     * @throws IllegalArgumentException If model_id doesn't exist in model_registry or provider mismatch
     */
    fun setDefaultModelAllModes(
        modelId: String,
        provider: String,
        taskId: String? = null,
        userId: String? = null
    ) {
        // Validate once by fetching from providers
        val modelConfig = runBlocking {
            getModelConfig(modelId, this@ConfigService)
        }
        if (modelConfig==null || modelConfig.provider != provider) {
            return
        }

        // Set for all modes
        for (operation in listOf(ModelOperation.DEFAULT, ModelOperation.PLAN, ModelOperation.CODING)) {
            setDefaultModel(
                operation = operation,
                modelId = modelId,
                provider = provider,
                taskId = taskId,
                userId = userId
            )
        }

        logger.info { "Set default model for ALL modes: $modelId (provider=$provider)" }
    }

    /**
     * Get visibility setting for a specific model.
     *
     * @param modelId Model identifier (e.g., "ollama/qwen2.5:14b")
     * @return true if model should be shown in dropdown (default), false otherwise
     */
    fun getModelVisibility(modelId: String): Boolean {
        val config = configRepository.get(KEY_MODELS_VISIBILITY, ConfigScope.APP)
        if (config != null) {
            val visibilityMap = gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>
            return visibilityMap?.get(modelId) ?: true  // Default to visible
        }
        return true  // Default to visible if no config
    }

    /**
     * Set visibility setting for a specific model.
     *
     * @param modelId Model identifier (e.g., "ollama/qwen2.5:14b")
     * @param showInDropdown true to show model in dropdown, false to hide
     */
    fun setModelVisibility(modelId: String, showInDropdown: Boolean) {
        // Get current visibility map
        val config = configRepository.get(KEY_MODELS_VISIBILITY, ConfigScope.APP)
        val visibilityMap = if (config != null) {
            gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean> ?: emptyMap()
        } else {
            emptyMap()
        }

        // Update map with new value
        val updatedMap = visibilityMap.toMutableMap()
        updatedMap[modelId] = showInDropdown

        // Save back to config
        val valueJson = gson.toJson(updatedMap)
        configRepository.set(
            key = KEY_MODELS_VISIBILITY,
            value = valueJson,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Model visibility settings"
        )

        logger.info { "Updated model visibility: $modelId -> $showInDropdown" }
    }

    /**
     * Replace all model visibility settings in one write.
     *
     * @param visibilityMap Map of modelId to showInDropdown setting
     */
    fun setModelsVisibility(visibilityMap: Map<String, Boolean>) {
        val valueJson = gson.toJson(visibilityMap)
        configRepository.set(
            key = KEY_MODELS_VISIBILITY,
            value = valueJson,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Model visibility settings"
        )

        logger.info { "Updated model visibility for ${visibilityMap.size} models" }
    }

    /**
     * Get all model visibility settings.
     *
     * Configuration hierarchy:
     * 1. Database value (highest priority)
     * 2. YAML config (user + project merged)
     *
     * @return Map of modelId to showInDropdown setting
     */
    fun getModelsVisibility(): Map<String, Boolean> {
        // 1. Check database first
        val config = configRepository.get(KEY_MODELS_VISIBILITY, ConfigScope.APP)
        if (config != null) {
            val visibilityMap = gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>
            if (visibilityMap != null && visibilityMap.isNotEmpty()) {
                return visibilityMap
            }
        }

        // 2. Fall back to YAML config
        val yamlVisibility = yamlLoader.getModelsVisibility()
        if (yamlVisibility != null && yamlVisibility.isNotEmpty()) {
            return yamlVisibility
        }

        return emptyMap()
    }

    /**
     * Get config value by key with hierarchical lookup.
     *
     * Lookup order (highest priority first):
     * 1. Database value (if exists)
     * 2. Project YAML config (<project>/.refio/config.yaml)
     * 3. User YAML config (~/.refio/config.yaml)
     * 4. Built-in default (returned as null here, caller uses default)
     *
     * @param key Configuration key
     * @param scope Configuration scope (APP or TASK)
     * @param taskId Optional task ID for TASK scope
     * @return Config value or null if not found
     */
    fun get(
        key: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null
    ): String? {
        // 1. First check database (highest priority)
        val dbConfig = when {
            taskId != null -> getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
            scope == ConfigScope.PROJECT -> {
                val resolvedProjectId = resolveProjectId(projectId)
                resolvedProjectId?.let { configRepository.get(key, ConfigScope.PROJECT, projectId = it) }
            }
            else -> configRepository.get(key, scope)
        }

        if (dbConfig?.value != null) {
            return dbConfig.value
        }

        // 2. Fall back to YAML config (user + project merged)
        return getFromYaml(key)
    }

    /**
     * Get configuration value from YAML files only.
     * Useful when you want to explicitly read from file-based config.
     *
     * @param key Configuration key in dot notation (e.g., "general.format_markdown")
     * @return Value from YAML config or null if not found
     */
    fun getFromYaml(key: String): String? {
        return when (key) {
            // General settings
            KEY_FORMAT_MARKDOWN -> yamlLoader.getFormatMarkdown()?.toString()
            KEY_STREAMING_ENABLED -> yamlLoader.getStreamingEnabled()?.toString()
            KEY_ADVANCED_VIEW -> yamlLoader.getAdvancedView()?.toString()

            // Limits
            KEY_API_CALL_TIMEOUT -> yamlLoader.getApiCallTimeout()?.toString()
            KEY_TOOL_EXECUTION_TIMEOUT -> yamlLoader.getToolExecutionTimeout()?.toString()
            KEY_STREAMING_READ_TIMEOUT -> yamlLoader.getStreamingReadTimeout()?.toString()
            KEY_STREAMING_REQUEST_TIMEOUT -> yamlLoader.getStreamingRequestTimeout()?.toString()
            KEY_MAX_CONTEXT_SIZE -> yamlLoader.getMaxContextSize()?.toString()
            KEY_MAX_OUTPUT_SIZE -> yamlLoader.getMaxOutputSize()?.toString()
            KEY_MAX_FILE_SIZE -> yamlLoader.getMaxFileSize()?.toString()

            // Advanced
            KEY_NO_EGRESS_DEFAULT -> yamlLoader.getNoEgressDefault()?.toString()
            KEY_READ_ONLY_MODE -> yamlLoader.getReadOnlyMode()?.toString()
            KEY_AUTO_OPTIMIZE_PERCENTAGE -> yamlLoader.getAutoOptimizePercentage()?.toString()

            // Terminal whitelist
            KEY_TERMINAL_WHITELIST_ENABLED -> yamlLoader.getTerminalWhitelistEnabled()?.toString()
            KEY_TERMINAL_WHITELIST_MODE -> yamlLoader.getTerminalWhitelistMode()?.trim()?.uppercase()
            KEY_TERMINAL_WHITELIST -> yamlLoader.getTerminalWhitelist()?.let { gson.toJson(it) }

            // RAG
            KEY_RAG_ENABLED -> yamlLoader.getRagEnabled()?.toString()
            KEY_RAG_INDEX_ON_STARTUP -> yamlLoader.getRagIndexOnStartup()?.toString()
            KEY_RAG_AUTO_INDEX_ON_CONTEXT -> yamlLoader.getRagAutoIndexOnContextBuild()?.toString()
            KEY_RAG_MAX_FILE_SIZE_MB -> yamlLoader.getRagMaxFileSizeMB()?.toString()
            KEY_RAG_MAX_CHUNKS_PER_FILE -> yamlLoader.getRagMaxChunksPerFile()?.toString()
            KEY_RAG_INDEX_BATCH_SIZE -> yamlLoader.getRagIndexBatchSize()?.toString()
            KEY_RAG_EMBEDDINGS_BATCH_SIZE -> yamlLoader.getRagEmbeddingsBatchSize()?.toString()
            KEY_RAG_CACHE_TTL_MS -> yamlLoader.getRagCacheTtlMs()?.toString()
            KEY_RAG_MAX_CONCURRENT_JOBS -> yamlLoader.getRagMaxConcurrentJobs()?.toString()
            KEY_RAG_IGNORED_DIRECTORIES -> yamlLoader.getRagIgnoredDirectories()?.joinToString(",")
            KEY_RAG_SEARCH_SIMILARITY_THRESHOLD -> yamlLoader.getRagSearchSimilarityThreshold()?.toString()
            KEY_RAG_SEARCH_TOP_K -> yamlLoader.getRagSearchTopK()?.toString()
            KEY_RAG_SEARCH_HYBRID_ENABLED -> yamlLoader.getRagSearchHybridEnabled()?.toString()
            KEY_RAG_SEARCH_SEMANTIC_WEIGHT -> yamlLoader.getRagSearchSemanticWeight()?.toString()
            KEY_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS -> yamlLoader.getRagSearchIncludeContextChunks()?.toString()

            // UI
            KEY_UI_THINKING_ENABLED -> yamlLoader.getUiThinkingEnabled()?.toString()
            KEY_UI_NO_EGRESS_ENABLED -> yamlLoader.getUiNoEgressEnabled()?.toString()
            KEY_UI_EXECUTION_MODE -> yamlLoader.getUiExecutionMode()?.trim()?.uppercase()
            KEY_UI_SELECTED_MODE -> yamlLoader.getUiSelectedMode()?.trim()?.uppercase()
            KEY_UI_SELECTED_MODEL -> yamlLoader.getUiSelectedModel()?.trim()?.lowercase()

            // Provider endpoints
            KEY_PROVIDER_OLLAMA_ENDPOINT -> yamlLoader.getOllamaEndpoint()
            KEY_PROVIDER_OLLAMA_CONTEXT_SIZE -> yamlLoader.getOllamaContextSize()?.toString()
            KEY_PROVIDER_OLLAMA_KEEP_ALIVE -> yamlLoader.getOllamaKeepAlive()?.toString()
            KEY_PROVIDER_ANTHROPIC_API_KEY -> yamlLoader.getAnthropicApiKey()
            KEY_PROVIDER_OPENAI_API_KEY -> yamlLoader.getOpenAIApiKey()
            KEY_PROVIDER_OPENROUTER_API_KEY -> yamlLoader.getOpenRouterApiKey()
            KEY_PROVIDER_GEMINI_API_KEY -> yamlLoader.getGeminiApiKey()
            KEY_PROVIDER_LM_STUDIO_API_KEY -> yamlLoader.getLMStudioApiKey()
            KEY_PROVIDER_LM_STUDIO_BASE_URL -> yamlLoader.getLMStudioBaseUrl()
            KEY_PROVIDER_LM_STUDIO_CONTEXT_SIZE -> yamlLoader.getLMStudioContextSize()?.toString()

            // Models (visibility is handled separately)
            KEY_EMBEDDING_MODEL -> yamlLoader.getDefaultEmbeddingModel()

            else -> null
        }
    }

    /**
     * Set config value by key (simple helper for APP scope).
     *
     * @param key Configuration key
     * @param value Configuration value
     * @param scope Configuration scope (default: APP)
     * @param taskId Optional task ID for TASK scope
     */
    fun set(
        key: String,
        value: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null
    ) {
        val resolvedProjectId = resolveProjectId(projectId)
        configRepository.set(
            key = key,
            value = value,
            scope = scope,
            projectId = if (scope == ConfigScope.PROJECT) resolvedProjectId else null,
            taskId = taskId,
            description = null
        )
    }

    /**
     * Get API call timeout in milliseconds.
     *
     * @param taskId Optional task ID for task-level override
     * @return Timeout in milliseconds (default: 120000ms = 120s)
     */
    fun getApiCallTimeoutMs(taskId: String? = null): Long {
        val config = getConfigWithPrecedence(
            key = KEY_API_CALL_TIMEOUT,
            taskId = taskId
        )
        val seconds = config?.value?.toIntOrNull() ?: DEFAULT_API_CALL_TIMEOUT
        return seconds * 1000L
    }

    /**
     * Get tool execution timeout in milliseconds.
     *
     * @param taskId Optional task ID for task-level override
     * @return Timeout in milliseconds (default: 120000ms = 120s)
     */
    fun getToolExecutionTimeoutMs(taskId: String? = null): Long {
        val config = getConfigWithPrecedence(
            key = KEY_TOOL_EXECUTION_TIMEOUT,
            taskId = taskId
        )
        val seconds = config?.value?.toIntOrNull() ?: DEFAULT_TOOL_EXECUTION_TIMEOUT
        return seconds * 1000L
    }

    /**
     * Get maximum context size in tokens.
     *
     * @param taskId Optional task ID for task-level override
     * @return Max context size in tokens (default: 128000)
     */
    fun getMaxContextSize(taskId: String? = null): Int {
        val config = getConfigWithPrecedence(
            key = KEY_MAX_CONTEXT_SIZE,
            taskId = taskId
        )
        return config?.value?.toIntOrNull() ?: DEFAULT_MAX_CONTEXT_SIZE
    }

    /**
     * Get maximum output size in tokens.
     *
     * @param taskId Optional task ID for task-level override
     * @return Max output size in tokens (default: 8192)
     */
    fun getMaxOutputTokens(taskId: String? = null): Int {
        val config = getConfigWithPrecedence(
            key = KEY_MAX_OUTPUT_SIZE,
            taskId = taskId
        )
        return config?.value?.toIntOrNull() ?: DEFAULT_MAX_OUTPUT_SIZE
    }

    /**
     * Get maximum file size in MB.
     *
     * @param taskId Optional task ID for task-level override
     * @return Max file size in MB (default: 10)
     */
    fun getMaxFileSizeMB(taskId: String? = null): Int {
        val config = getConfigWithPrecedence(
            key = KEY_MAX_FILE_SIZE,
            taskId = taskId
        )
        return config?.value?.toIntOrNull() ?: DEFAULT_MAX_FILE_SIZE
    }

    // ==================== UI CONFIGURATION ====================

    /**
     * Get thinking enabled setting.
     *
     * @param taskId Optional task ID for task-level override
     * @return true if thinking is enabled (default: false)
     */
    fun isThinkingEnabled(taskId: String? = null): Boolean {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        return get(
            key = KEY_UI_THINKING_ENABLED,
            scope = scope,
            taskId = taskId
        )?.toBoolean() ?: false
    }

    /**
     * Set thinking enabled setting.
     *
     * @param enabled true to enable thinking display
     * @param taskId Optional task ID for task-level config
     */
    fun setThinkingEnabled(enabled: Boolean, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        configRepository.set(
            key = KEY_UI_THINKING_ENABLED,
            value = enabled.toString(),
            scope = scope,
            taskId = taskId,
            description = "Show LLM thinking process in UI"
        )
    }

    /**
     * Get no-egress enabled setting.
     *
     * @param taskId Optional task ID for task-level override
     * @return true if no-egress is enabled (default: false)
     */
    fun isNoEgressEnabled(taskId: String? = null): Boolean {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        return get(
            key = KEY_UI_NO_EGRESS_ENABLED,
            scope = scope,
            taskId = taskId
        )?.toBoolean() ?: false
    }

    /**
     * Set no-egress enabled setting.
     *
     * @param enabled true to block external network calls
     * @param taskId Optional task ID for task-level config
     */
    fun setNoEgressEnabled(enabled: Boolean, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        configRepository.set(
            key = KEY_UI_NO_EGRESS_ENABLED,
            value = enabled.toString(),
            scope = scope,
            taskId = taskId,
            description = "Block external network calls"
        )
    }

    /**
     * Get execution mode setting.
     *
     * @param taskId Optional task ID for task-level override
     * @return Execution mode (AUTO/INTERACTIVE, default: AUTO)
     */
    fun getExecutionMode(taskId: String? = null): String {
        val config = getConfigWithPrecedence(
            key = KEY_UI_EXECUTION_MODE,
            taskId = taskId
        )
        return config?.value ?: "AUTO"
    }

    /**
     * Set execution mode setting.
     *
     * @param mode Execution mode (AUTO/INTERACTIVE)
     * @param taskId Optional task ID for task-level config
     */
    fun setExecutionMode(mode: String, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        configRepository.set(
            key = KEY_UI_EXECUTION_MODE,
            value = mode,
            scope = scope,
            taskId = taskId,
            description = "Execution mode (AUTO/INTERACTIVE)"
        )
    }

    /**
     * Get selected mode setting.
     *
     * @param taskId Optional task ID for task-level override
     * @return Selected mode (CHAT/PLAN/AGENT, default: CHAT)
     */
    fun getSelectedMode(taskId: String? = null): String {
        val config = getConfigWithPrecedence(
            key = KEY_UI_SELECTED_MODE,
            taskId = taskId
        )
        return config?.value ?: "CHAT"
    }

    /**
     * Set selected mode setting.
     *
     * @param mode Selected mode (CHAT/PLAN/AGENT)
     * @param taskId Optional task ID for task-level config
     */
    fun setSelectedMode(mode: String, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        configRepository.set(
            key = KEY_UI_SELECTED_MODE,
            value = mode,
            scope = scope,
            taskId = taskId,
            description = "Selected task mode"
        )
    }

    /**
     * Get selected model setting.
     *
     * @param taskId Optional task ID for task-level override
     * @return Selected model (default: empty string)
     */
    fun getSelectedModel(taskId: String? = null): String? {
        val config = getConfigWithPrecedence(
            key = KEY_UI_SELECTED_MODEL,
            taskId = taskId
        )
        return config?.value
    }

    /**
     * Set selected model setting.
     *
     * @param model Selected model (provider/model format)
     * @param taskId Optional task ID for task-level config
     */
    fun setSelectedModel(model: String, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP
        configRepository.set(
            key = KEY_UI_SELECTED_MODEL,
            value = model,
            scope = scope,
            taskId = taskId,
            description = "Selected model in UI"
        )
    }

    // ==================== MODELS CONFIGURATION ====================

    /**
     * Get weak model for auxiliary operations.
     *
     * @return Pair of (model_id, provider) for weak model
     */
    fun getWeakModel(): Pair<String, String> = getDefaultModel(ModelOperation.WEAK)

    /**
     * Set weak model for auxiliary operations.
     *
     * @param modelId Model identifier
     * @param provider Provider name
     */
    fun setWeakModel(modelId: String, provider: String) {
        val valueJson = gson.toJson(ModelConfigData(modelId, provider))
        configRepository.set(
            key = KEY_WEAK_MODEL,
            value = valueJson,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Cheap model for auxiliary operations"
        )
    }

    /**
     * Get embedding model.
     *
     * @return Embedding model (default: "ollama/nomic-embed-text")
     */
    fun getEmbeddingModel(): String {
        val (modelId, provider) = getDefaultModel(ModelOperation.EMBEDDING)
        return "$provider/$modelId"
    }

    /**
     * Set embedding model.
     *
     * @param model Embedding model (provider/model format)
     */
    fun setEmbeddingModel(model: String) {
        configRepository.set(
            key = KEY_EMBEDDING_MODEL,
            value = model,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Model for embeddings"
        )
    }

    /**
     * Get configured Ollama endpoint with hierarchical lookup.
     *
     * Priority:
     * 1. Database value
     * 2. YAML config
     * 3. System property OLLAMA_BASE_URL
     * 4. System property OLLAMA_ENDPOINT (legacy)
     * 5. Default: http://localhost:11434
     */
    fun getOllamaEndpoint(): String {
        // 1. Check database
        val configured = configRepository.get(KEY_PROVIDER_OLLAMA_ENDPOINT, ConfigScope.APP)?.value?.takeIf { it.isNotBlank() }
        if (configured != null) return configured

        // 2. Check YAML
        val yamlEndpoint = yamlLoader.getOllamaEndpoint()?.takeIf { it.isNotBlank() }
        if (yamlEndpoint != null) return yamlEndpoint

        // 3-4. Check system properties
        val systemBase = System.getProperty("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        val systemLegacy = System.getProperty("OLLAMA_ENDPOINT")?.takeIf { it.isNotBlank() }

        return systemBase ?: systemLegacy ?: "http://localhost:11434"
    }

    /**
     * Reload configuration from YAML files.
     * Call this when you need to refresh cached config.
     */
    fun reloadYamlConfig(): ConfigYaml {
        return yamlLoader.reloadConfig()
    }

    /**
     * Get the merged YAML configuration.
     * Useful for debugging or exporting current config state.
     */
    fun getYamlConfig(): ConfigYaml {
        return yamlLoader.getConfig()
    }

    /**
     * Build a ConfigYaml object from current database values.
     * Used for exporting current configuration to YAML file.
     *
     * @param includeApiKeys If true, includes API keys (masked). If false, omits them.
     * @return ConfigYaml object with current settings
     */
    fun buildConfigYamlFromCurrentSettings(includeApiKeys: Boolean = false): ConfigYaml {
        return ConfigYaml(
            general = buildGeneralConfig(),
            providers = buildProvidersConfig(includeApiKeys),
            models = buildModelsConfig(),
            limits = buildLimitsConfig(),
            advanced = buildAdvancedConfig(),
            tools = buildToolsConfig(),
            terminal = buildTerminalConfig(),
            rag = buildRagConfig(),
            ui = buildUiConfig()
        )
    }

    private fun buildGeneralConfig(): pl.jclab.refio.core.config.GeneralConfig {
        return pl.jclab.refio.core.config.GeneralConfig(
            formatMarkdown = isFormatMarkdownEnabled(),
            streamingEnabled = isStreamingEnabled(),
            advancedView = isAdvancedViewEnabled()
        )
    }

    private fun buildProvidersConfig(includeApiKeys: Boolean): pl.jclab.refio.core.config.ProvidersConfig {
        val ollamaEndpoint = get(KEY_PROVIDER_OLLAMA_ENDPOINT)
        val lmstudioBaseUrl = get(KEY_PROVIDER_LM_STUDIO_BASE_URL)

        return pl.jclab.refio.core.config.ProvidersConfig(
            ollama = pl.jclab.refio.core.config.OllamaConfig(
                endpoint = ollamaEndpoint ?: getOllamaEndpoint(),
                contextSize = getOllamaContextSize(),
                keepAlive = getOllamaKeepAlive()
            ),
            anthropic = if (includeApiKeys) {
                pl.jclab.refio.core.config.AnthropicConfig(
                    apiKey = get(KEY_PROVIDER_ANTHROPIC_API_KEY)
                )
            } else null,
            openai = if (includeApiKeys) {
                pl.jclab.refio.core.config.OpenAIConfig(
                    apiKey = get(KEY_PROVIDER_OPENAI_API_KEY)
                )
            } else null,
            openrouter = if (includeApiKeys) {
                pl.jclab.refio.core.config.OpenRouterConfig(
                    apiKey = get(KEY_PROVIDER_OPENROUTER_API_KEY)
                )
            } else null,
            gemini = if (includeApiKeys) {
                pl.jclab.refio.core.config.GeminiConfig(
                    apiKey = get(KEY_PROVIDER_GEMINI_API_KEY)
                )
            } else null,
            lmstudio = pl.jclab.refio.core.config.LMStudioConfig(
                apiKey = if (includeApiKeys) get(KEY_PROVIDER_LM_STUDIO_API_KEY) else null,
                baseUrl = lmstudioBaseUrl,
                contextSize = getLMStudioContextSize()
            )
        )
    }

    private fun buildModelsConfig(): pl.jclab.refio.core.config.ModelsConfig {
        val (chatModel, chatProvider) = getDefaultModel(ModelOperation.DEFAULT)
        val (planModel, planProvider) = getDefaultModel(ModelOperation.PLAN)
        val (codingModel, codingProvider) = getDefaultModel(ModelOperation.CODING)
        val (weakModel, weakProvider) = getDefaultModel(ModelOperation.WEAK)
        val (embeddingModel, embeddingProvider) = getDefaultModel(ModelOperation.EMBEDDING)

        return pl.jclab.refio.core.config.ModelsConfig(
            defaults = pl.jclab.refio.core.config.ModelDefaultsConfig(
                chat = "$chatProvider/$chatModel",
                plan = "$planProvider/$planModel",
                coding = "$codingProvider/$codingModel",
                weak = "$weakProvider/$weakModel",
                embedding = "$embeddingProvider/$embeddingModel"
            ),
            visibility = getModelsVisibility()
        )
    }

    private fun buildLimitsConfig(): pl.jclab.refio.core.config.LimitsConfig {
        return pl.jclab.refio.core.config.LimitsConfig(
            apiCallTimeout = (getApiCallTimeoutMs() / 1000).toInt(),
            toolExecutionTimeout = (getToolExecutionTimeoutMs() / 1000).toInt(),
            streamingReadTimeout = (getStreamingReadTimeoutMs() / 1000).toInt(),
            streamingRequestTimeout = (getStreamingRequestTimeoutMs() / 1000).toInt(),
            maxContextSize = getMaxContextSize(),
            maxOutputSize = getMaxOutputTokens(),
            maxFileSize = getMaxFileSizeMB()
        )
    }

    private fun buildAdvancedConfig(): pl.jclab.refio.core.config.AdvancedConfig {
        return pl.jclab.refio.core.config.AdvancedConfig(
            noEgressDefault = isNoEgressDefault(),
            readOnlyMode = isReadOnlyMode(),
            autoOptimizePercentage = getAutoOptimizePercentage()
        )
    }

    private fun buildToolsConfig(): pl.jclab.refio.core.config.ToolsConfig {
        val permissions = getToolsPermissions()
        if (permissions.isEmpty()) return pl.jclab.refio.core.config.ToolsConfig()

        val yamlPermissions = permissions.mapValues { (_, enabled) ->
            pl.jclab.refio.core.config.ToolPermissionConfig(
                planMode = if (enabled) "ON" else "OFF",
                agentMode = if (enabled) "ON" else "OFF"
            )
        }

        return pl.jclab.refio.core.config.ToolsConfig(permissions = yamlPermissions)
    }

    private fun buildTerminalConfig(): TerminalConfig {
        val whitelist = getTerminalWhitelistConfig()
        val yamlCommands = whitelist.allowedCommands.map { command ->
            TerminalCommandConfig(
                program = command.program,
                description = command.description.ifBlank { null },
                aliases = command.aliases.ifEmpty { null },
                blockedFlags = command.blockedFlags.ifEmpty { null },
                blockedSubcommands = command.blockedSubcommands.ifEmpty { null },
                blockedArgPatterns = command.blockedArgPatterns.ifEmpty { null },
                allowedSubcommands = command.allowedSubcommands.ifEmpty { null },
                maxArgs = command.maxArgs,
                requireConfirmation = command.requireConfirmation
            )
        }

        return TerminalConfig(
            whitelist = TerminalWhitelistConfig(
                enabled = whitelist.enabled,
                mode = whitelist.mode.name,
                globalBlockedPatterns = whitelist.globalBlockedPatterns,
                commands = yamlCommands
            )
        )
    }

    private fun buildRagConfig(): pl.jclab.refio.core.config.RagConfig {
        return pl.jclab.refio.core.config.RagConfig(
            enabled = isRagEnabled(),
            indexOnStartup = shouldIndexRagOnStartup(),
            autoIndexOnContextBuild = shouldAutoIndexOnContextBuild(),
            maxFileSizeMB = getRagMaxFileSizeBytes() / (1024 * 1024),
            maxChunksPerFile = getRagMaxChunksPerFile(),
            indexBatchSize = getRagIndexBatchSize(),
            embeddingsBatchSize = getRagEmbeddingBatchSize(),
            cacheTtlMs = getRagCacheTtlMs(),
            maxConcurrentJobs = getRagMaxConcurrentJobs(),
            ignoredDirectories = getRagIgnoredDirectories().toList(),
            searchSimilarityThreshold = getRagSearchSimilarityThreshold(),
            searchTopK = getRagSearchTopK(),
            searchHybridEnabled = getRagSearchHybridEnabled(),
            searchSemanticWeight = getRagSearchSemanticWeight(),
            searchIncludeContextChunks = getRagSearchIncludeContextChunks()
        )
    }

    private fun buildUiConfig(): pl.jclab.refio.core.config.UiConfig {
        return pl.jclab.refio.core.config.UiConfig(
            thinkingEnabled = isThinkingEnabled(),
            noEgressEnabled = isNoEgressEnabled(),
            executionMode = getExecutionMode(),
            selectedMode = getSelectedMode(),
            selectedModel = getSelectedModel()
        )
    }

    /**
     * Export current configuration to a YAML file.
     *
     * @param file Target file to write
     * @param includeApiKeys If true, includes API keys (masked for security)
     */
    fun exportToYaml(file: java.io.File, includeApiKeys: Boolean = false) {
        val config = buildConfigYamlFromCurrentSettings(includeApiKeys)
        ConfigYaml.saveToFile(config, file, withComments = true)
        logger.info { "Exported configuration to: ${file.absolutePath}" }
    }

    // ==================== GENERAL CONFIGURATION ====================

    /**
     * Get format markdown setting.
     *
     * @return true if markdown formatting is enabled (default: true)
     */
    fun isFormatMarkdownEnabled(): Boolean {
        val config = configRepository.get(KEY_FORMAT_MARKDOWN, ConfigScope.APP)
        return config?.value?.toBoolean() ?: true
    }

    /**
     * Get streaming enabled setting.
     *
     * @return true if streaming is enabled (default: true)
     */
    fun isStreamingEnabled(): Boolean {
        val config = configRepository.get(KEY_STREAMING_ENABLED, ConfigScope.APP)
        return config?.value?.toBoolean() ?: true
    }

    // ==================== RAG CONFIGURATION ====================

    fun isRagEnabled(): Boolean {
        val config = configRepository.get(KEY_RAG_ENABLED, ConfigScope.APP)
        return config?.value?.toBoolean() ?: true
    }

    fun shouldAutoIndexOnContextBuild(): Boolean {
        val config = configRepository.get(KEY_RAG_AUTO_INDEX_ON_CONTEXT, ConfigScope.APP)
        return config?.value?.toBoolean() ?: true
    }

    fun shouldIndexRagOnStartup(): Boolean {
        val config = configRepository.get(KEY_RAG_INDEX_ON_STARTUP, ConfigScope.APP)
        return config?.value?.toBoolean() ?: DEFAULT_RAG_INDEX_ON_STARTUP
    }

    fun getRagMaxFileSizeBytes(): Long {
        val config = configRepository.get(KEY_RAG_MAX_FILE_SIZE_MB, ConfigScope.APP)
        val megabytes = config?.value?.toLongOrNull() ?: DEFAULT_RAG_MAX_FILE_SIZE_MB
        return megabytes * 1024L * 1024L
    }

    fun getRagCacheTtlMs(): Long {
        val config = configRepository.get(KEY_RAG_CACHE_TTL_MS, ConfigScope.APP)
        return config?.value?.toLongOrNull() ?: DEFAULT_RAG_CACHE_TTL_MS
    }

    fun getRagMaxConcurrentJobs(): Int {
        val config = configRepository.get(KEY_RAG_MAX_CONCURRENT_JOBS, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RAG_MAX_CONCURRENT_JOBS
    }

    fun getRagMaxChunksPerFile(): Int {
        val config = configRepository.get(KEY_RAG_MAX_CHUNKS_PER_FILE, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RAG_MAX_CHUNKS_PER_FILE
    }

    fun getRagIndexBatchSize(): Int {
        val config = configRepository.get(KEY_RAG_INDEX_BATCH_SIZE, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RAG_INDEX_BATCH_SIZE
    }

    fun getRagEmbeddingBatchSize(): Int {
        val config = configRepository.get(KEY_RAG_EMBEDDINGS_BATCH_SIZE, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RAG_EMBEDDING_BATCH_SIZE
    }

    fun getRagIgnoredDirectories(): Set<String> {
        val config = configRepository.get(KEY_RAG_IGNORED_DIRECTORIES, ConfigScope.APP)
        val raw = config?.value ?: DEFAULT_RAG_IGNORED_DIRECTORIES.joinToString(",")
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun getRagSearchSimilarityThreshold(): Float {
        val value = get(KEY_RAG_SEARCH_SIMILARITY_THRESHOLD)?.toFloatOrNull()
            ?: DEFAULT_RAG_SEARCH_SIMILARITY_THRESHOLD
        return value.coerceIn(0.0f, 1.0f)
    }

    fun getRagSearchTopK(): Int {
        return get(KEY_RAG_SEARCH_TOP_K)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RAG_SEARCH_TOP_K
    }

    fun getRagSearchHybridEnabled(): Boolean {
        return get(KEY_RAG_SEARCH_HYBRID_ENABLED)?.toBoolean() ?: DEFAULT_RAG_SEARCH_HYBRID_ENABLED
    }

    fun getRagSearchSemanticWeight(): Float {
        val value = get(KEY_RAG_SEARCH_SEMANTIC_WEIGHT)?.toFloatOrNull()
            ?: DEFAULT_RAG_SEARCH_SEMANTIC_WEIGHT
        return value.coerceIn(0.0f, 1.0f)
    }

    fun getRagSearchIncludeContextChunks(): Boolean {
        return get(KEY_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)?.toBoolean()
            ?: DEFAULT_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS
    }

    fun getProjectAnalysisMaxFiles(): Int {
        val config = configRepository.get(KEY_PROJECT_ANALYSIS_MAX_FILES, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(10) ?: DEFAULT_PROJECT_ANALYSIS_MAX_FILES
    }

    fun getProjectAnalysisFingerprintLimit(): Int {
        val config = configRepository.get(KEY_PROJECT_ANALYSIS_FINGERPRINT_LIMIT, ConfigScope.APP)
        return config?.value?.toIntOrNull()?.coerceAtLeast(100) ?: DEFAULT_PROJECT_ANALYSIS_FINGERPRINT_LIMIT
    }

    fun getProjectAnalysisCacheTtlMs(): Long {
        val config = configRepository.get(KEY_PROJECT_ANALYSIS_CACHE_TTL_MS, ConfigScope.APP)
        return config?.value?.toLongOrNull() ?: DEFAULT_PROJECT_ANALYSIS_CACHE_TTL_MS
    }

    /**
     * Get advanced view setting.
     *
     * @return true if advanced view is enabled (default: false)
     */
    fun isAdvancedViewEnabled(): Boolean {
        val config = configRepository.get(KEY_ADVANCED_VIEW, ConfigScope.APP)
        return config?.value?.toBoolean() ?: false
    }

    // ==================== ADVANCED CONFIGURATION ====================

    /**
     * Get auto-optimize percentage.
     *
     * @return Auto-optimize percentage (default: 85)
     */
    fun getAutoOptimizePercentage(): Int {
        val config = configRepository.get(KEY_AUTO_OPTIMIZE_PERCENTAGE, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: 85
    }

    /**
     * Get no-egress default setting.
     *
     * @return true if no-egress is default (default: false)
     */
    fun isNoEgressDefault(): Boolean {
        val config = configRepository.get(KEY_NO_EGRESS_DEFAULT, ConfigScope.APP)
        return config?.value?.toBoolean() ?: false
    }

    /**
     * Get read-only mode setting.
     *
     * @return true if read-only mode is enabled (default: false)
     */
    fun isReadOnlyMode(): Boolean {
        val config = configRepository.get(KEY_READ_ONLY_MODE, ConfigScope.APP)
        return config?.value?.toBoolean() ?: false
    }

    // ==================== PROVIDER CONFIGURATION ====================

    /**
     * Get Ollama context size.
     *
     * @return Ollama context size in tokens (default: 32768)
     */
    fun getOllamaContextSize(): Int {
        val config = configRepository.get(KEY_PROVIDER_OLLAMA_CONTEXT_SIZE, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: DEFAULT_CONTEXT_SIZE
    }

    /**
     * Get Ollama keep_alive duration.
     *
     * @return Ollama keep_alive in seconds (default: 1800 = 30 minutes)
     */
    fun getOllamaKeepAlive(): Int {
        val config = configRepository.get(KEY_PROVIDER_OLLAMA_KEEP_ALIVE, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: 1800
    }

    /**
     * Set Ollama context size.
     *
     * @param contextSize Context size in tokens
     */
    fun setOllamaContextSize(contextSize: Int) {
        configRepository.set(
            key = KEY_PROVIDER_OLLAMA_CONTEXT_SIZE,
            value = contextSize.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Ollama context size in tokens"
        )
    }

    /**
     * Get LM Studio context size.
     *
     * @return LM Studio context size in tokens (default: 32768)
     */
    fun getLMStudioContextSize(): Int {
        val config = configRepository.get(KEY_PROVIDER_LM_STUDIO_CONTEXT_SIZE, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: DEFAULT_CONTEXT_SIZE
    }

    /**
     * Set LM Studio context size.
     *
     * @param contextSize Context size in tokens
     */
    fun setLMStudioContextSize(contextSize: Int) {
        configRepository.set(
            key = KEY_PROVIDER_LM_STUDIO_CONTEXT_SIZE,
            value = contextSize.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "LM Studio context size in tokens"
        )
    }

    // ==================== TOOLS CONFIGURATION ====================

    /**
     * Get tool permissions map.
     *
     * @return Map of tool_name -> enabled
     */
    fun getToolsPermissions(): Map<String, Boolean> {
        val config = configRepository.get(KEY_TOOLS_PERMISSIONS, ConfigScope.APP)
        if (config != null) {
            val permissionsMap = gson.fromJson(config.value, Map::class.java) as? Map<String, Boolean>
            return permissionsMap ?: emptyMap()
        }
        return emptyMap()
    }

    /**
     * Set tool permissions map.
     *
     * @param permissions Map of tool_name -> enabled
     */
    fun setToolsPermissions(permissions: Map<String, Boolean>) {
        val valueJson = gson.toJson(permissions)
        configRepository.set(
            key = KEY_TOOLS_PERMISSIONS,
            value = valueJson,
            scope = ConfigScope.APP,
            taskId = null,
            description = "Tool permissions"
        )
    }

    /**
     * Get run terminal command permission.
     *
     * @return true if run_terminal_command is allowed (default: true)
     */
    fun isRunTerminalCommandAllowed(): Boolean {
        val config = configRepository.get(KEY_TOOL_PERMISSION_RUN_TERMINAL, ConfigScope.APP)
        return config?.value?.toBoolean() ?: true
    }

    /**
     * Set run terminal command permission.
     *
     * @param allowed true to allow run_terminal_command tool
     */
    fun setRunTerminalCommandAllowed(allowed: Boolean) {
        configRepository.set(
            key = KEY_TOOL_PERMISSION_RUN_TERMINAL,
            value = allowed.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Allow run_terminal_command tool"
        )
    }

    fun getTerminalWhitelistConfig(): CommandWhitelistConfig {
        val defaults = CommandWhitelistConfig(
            enabled = true,
            mode = WhitelistMode.WHITELIST_ONLY,
            allowedCommands = CommandWhitelistDefaults.DEFAULT_COMMANDS,
            globalBlockedPatterns = CommandWhitelistDefaults.DEFAULT_BLOCKED_PATTERNS
        )

        val fromYaml = yamlLoader.getTerminalWhitelist()?.let { yaml ->
            CommandWhitelistConfig(
                enabled = yaml.enabled ?: defaults.enabled,
                mode = parseWhitelistMode(yaml.mode) ?: defaults.mode,
                allowedCommands = yaml.commands?.map { toDomainAllowedCommand(it) } ?: emptyList(),
                globalBlockedPatterns = yaml.globalBlockedPatterns ?: emptyList()
            )
        }

        var merged = mergeTerminalWhitelistConfigs(defaults, fromYaml)

        val dbConfig = getConfigWithPrecedence(KEY_TERMINAL_WHITELIST)
        if (dbConfig != null) {
            val parsed = runCatching { gson.fromJson(dbConfig.value, CommandWhitelistConfig::class.java) }.getOrNull()
            if (parsed != null) {
                merged = mergeTerminalWhitelistConfigs(merged, parsed)
            } else {
                logger.warn { "Failed to parse DB terminal whitelist config JSON" }
            }
        }

        getConfigWithPrecedence(KEY_TERMINAL_WHITELIST_ENABLED)?.value?.toBooleanStrictOrNull()?.let { enabled ->
            merged = merged.copy(enabled = enabled)
        }
        parseWhitelistMode(getConfigWithPrecedence(KEY_TERMINAL_WHITELIST_MODE)?.value)?.let { mode ->
            merged = merged.copy(mode = mode)
        }

        return merged
    }

    fun setTerminalWhitelistConfig(config: CommandWhitelistConfig, scope: ConfigScope) {
        require(scope != ConfigScope.TASK) { "TASK scope is not supported for terminal whitelist config" }

        val projectId = if (scope == ConfigScope.PROJECT) {
            resolveProjectId(null)
                ?: throw IllegalArgumentException("PROJECT scope requires default projectId")
        } else {
            null
        }

        configRepository.set(
            key = KEY_TERMINAL_WHITELIST,
            value = gson.toJson(config),
            scope = scope,
            projectId = projectId,
            taskId = null,
            description = "Terminal whitelist configuration"
        )
        configRepository.set(
            key = KEY_TERMINAL_WHITELIST_ENABLED,
            value = config.enabled.toString(),
            scope = scope,
            projectId = projectId,
            taskId = null,
            description = "Terminal whitelist enabled"
        )
        configRepository.set(
            key = KEY_TERMINAL_WHITELIST_MODE,
            value = config.mode.name,
            scope = scope,
            projectId = projectId,
            taskId = null,
            description = "Terminal whitelist mode"
        )
    }

    fun addAllowedCommand(command: AllowedCommand, scope: ConfigScope) {
        val current = getTerminalWhitelistConfig()
        val programKey = command.program.lowercase()
        val updatedCommands = current.allowedCommands
            .filterNot { it.program.lowercase() == programKey } + command
        setTerminalWhitelistConfig(current.copy(allowedCommands = updatedCommands), scope)
    }

    fun removeAllowedCommand(program: String, scope: ConfigScope) {
        val current = getTerminalWhitelistConfig()
        val targetKey = program.lowercase()
        val updatedCommands = current.allowedCommands.filterNot { it.program.lowercase() == targetKey }
        setTerminalWhitelistConfig(current.copy(allowedCommands = updatedCommands), scope)
    }

    /**
     * Get enabled overrides for builtin subagents.
     *
     * @return Map of subagent name -> enabled flag
     */
    fun getBuiltinSubagentEnabledOverrides(): Map<String, Boolean> {
        val config = configRepository.get(KEY_SUBAGENTS_BUILTIN_ENABLED, ConfigScope.APP)
        if (config != null) {
            val raw = gson.fromJson(config.value, Map::class.java) as? Map<*, *>
            if (raw != null) {
                return raw.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    val enabled = when (value) {
                        is Boolean -> value
                        is String -> value.toBoolean()
                        else -> null
                    } ?: return@mapNotNull null
                    name to enabled
                }.toMap()
            }
        }
        return emptyMap()
    }

    /**
     * Set enabled override for a builtin subagent.
     *
     * @param name Subagent name
     * @param enabled Enabled flag
     */
    fun setBuiltinSubagentEnabledOverride(name: String, enabled: Boolean) {
        val current = getBuiltinSubagentEnabledOverrides().toMutableMap()
        current[name.lowercase()] = enabled
        configRepository.set(
            key = KEY_SUBAGENTS_BUILTIN_ENABLED,
            value = gson.toJson(current),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Builtin subagent enabled overrides"
        )
    }

    // ==================== TOOL RESULT SUMMARIZATION ====================

    /**
     * Check if tool result summarization is enabled.
     *
     * @return true if tool summarization is enabled (default: true)
     */
    fun isToolSummaryEnabled(): Boolean {
        return get(KEY_TOOL_SUMMARY_ENABLED)?.toBoolean() ?: DEFAULT_TOOL_SUMMARY_ENABLED
    }

    /**
     * Set tool result summarization enabled setting.
     *
     * @param enabled true to enable tool summarization
     */
    fun setToolSummaryEnabled(enabled: Boolean) {
        configRepository.set(
            key = KEY_TOOL_SUMMARY_ENABLED,
            value = enabled.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Enable tool result summarization"
        )
    }

    /**
     * Get minimum output length for summarization.
     * Tool outputs shorter than this will not be summarized.
     *
     * @return Minimum length in characters (default: 500)
     */
    fun getToolSummaryMinLength(): Int {
        return get(KEY_TOOL_SUMMARY_MIN_LENGTH)?.toIntOrNull() ?: DEFAULT_TOOL_SUMMARY_MIN_LENGTH
    }

    /**
     * Set minimum output length for summarization.
     *
     * @param length Minimum length in characters
     */
    fun setToolSummaryMinLength(length: Int) {
        configRepository.set(
            key = KEY_TOOL_SUMMARY_MIN_LENGTH,
            value = length.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Minimum tool output length for summarization"
        )
    }

    // ==================== CONTEXT CONFIGURATION (ADR 0017) ====================

    /**
     * Get recent work full data limit.
     * Number of recent tool executions to show with full data.
     *
     * @return Full data limit (default: 2)
     */
    fun getRecentWorkFullDataLimit(): Int {
        val config = configRepository.get(KEY_RECENT_WORK_FULL_DATA_LIMIT, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: DEFAULT_RECENT_WORK_FULL_DATA_LIMIT
    }

    /**
     * Set recent work full data limit.
     *
     * @param limit Number of recent tool executions with full data
     */
    fun setRecentWorkFullDataLimit(limit: Int) {
        configRepository.set(
            key = KEY_RECENT_WORK_FULL_DATA_LIMIT,
            value = limit.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Number of recent tool executions with full data"
        )
    }

    /**
     * Get recent work summary max length.
     * Maximum length for truncated tool outputs.
     *
     * @return Summary max length in characters (default: 150)
     */
    fun getRecentWorkSummaryMaxLength(): Int {
        val config = configRepository.get(KEY_RECENT_WORK_SUMMARY_MAX_LENGTH, ConfigScope.APP)
        return config?.value?.toIntOrNull() ?: DEFAULT_RECENT_WORK_SUMMARY_MAX_LENGTH
    }

    /**
     * Set recent work summary max length.
     *
     * @param length Maximum length in characters
     */
    fun setRecentWorkSummaryMaxLength(length: Int) {
        configRepository.set(
            key = KEY_RECENT_WORK_SUMMARY_MAX_LENGTH,
            value = length.toString(),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Maximum length for truncated tool outputs"
        )
    }

    /**
     * Resolve context budget for prompt building.
     */
    fun getContextBudget(taskId: String? = null, operation: ModelOperation? = null): ContextBudget {
        val inputRatio = getContextBudgetInputRatio(taskId)
        val contextSize = resolveContextSizeForBudget(operation, taskId)
        val totalOverride = getContextBudgetTotalTokens(taskId)
        val overrides = getContextBudgetSectionOverrides(taskId)

        return ContextBudget.forContextSize(
            contextSize = contextSize,
            inputRatio = inputRatio,
            overrides = overrides,
            totalTokensOverride = totalOverride
        )
    }

    private fun getContextBudgetInputRatio(taskId: String? = null): Double {
        val config = getConfigWithPrecedence(KEY_CONTEXT_BUDGET_INPUT_RATIO, taskId)
        return config?.value?.toDoubleOrNull() ?: DEFAULT_CONTEXT_BUDGET_INPUT_RATIO
    }

    private fun getContextBudgetTotalTokens(taskId: String? = null): Int? {
        val config = getConfigWithPrecedence(KEY_CONTEXT_BUDGET_TOTAL_TOKENS, taskId)
        return config?.value?.toIntOrNull()
    }

    private fun getContextBudgetSectionOverrides(taskId: String? = null): Map<ContextSection, Int> {
        val overrides = mutableMapOf<ContextSection, Int>()
        ContextSection.values().forEach { section ->
            val key = "$KEY_CONTEXT_BUDGET_SECTION_PREFIX${section.name.lowercase()}"
            val config = getConfigWithPrecedence(key, taskId)
            val value = config?.value?.toIntOrNull()
            if (value != null && value > 0) {
                overrides[section] = value
            }
        }
        return overrides
    }

    private fun resolveContextSizeForBudget(operation: ModelOperation?, taskId: String?): Int {
        val fallback = getMaxContextSize(taskId)
        if (operation == null) return fallback

        val (_, provider) = getModel(operation, taskId)
        return when (provider.lowercase()) {
            "ollama" -> getOllamaContextSize()
            "lmstudio" -> getLMStudioContextSize()
            else -> fallback
        }
    }

    /**
     * Get streaming read timeout (time between chunks) in milliseconds.
     * Used to detect stalled streaming connections.
     */
    fun getStreamingReadTimeoutMs(): Long {
        val seconds = get(KEY_STREAMING_READ_TIMEOUT)?.toLongOrNull()
            ?: DEFAULT_STREAMING_READ_TIMEOUT.toLong()
        return seconds * 1000L
    }

    /**
     * Get streaming request timeout (total time) in milliseconds.
     * Used as maximum total duration for streaming requests.
     */
    fun getStreamingRequestTimeoutMs(): Long {
        val seconds = get(KEY_STREAMING_REQUEST_TIMEOUT)?.toLongOrNull()
            ?: DEFAULT_STREAMING_REQUEST_TIMEOUT.toLong()
        return seconds * 1000L
    }

    /**
     * Load configuration from YAML file, but only for keys that don't exist in DB.
     * Called on plugin startup to initialize config from user's config file.
     *
     * Location: ~/.refio/config.yaml (Linux/macOS) or %USERPROFILE%\.refio\config.yaml (Windows)
     *
     * This method is idempotent - it won't overwrite existing config values.
     */
    fun loadFromYamlIfMissing() {
        val yamlConfig = pl.jclab.refio.core.config.ConfigYaml.load()
        if (yamlConfig == null) {
            logger.info { "No YAML config file found or failed to parse, skipping" }
            return
        }

        logger.info { "Loading configuration from YAML file (only missing keys)" }

        // Load default models (only if not set)
        yamlConfig.models?.defaults?.let { defaults ->
            defaults.chat?.let { model ->
                if (configRepository.get(KEY_DEFAULT_MODEL_CHAT, ConfigScope.APP) == null) {
                    val (provider, modelId) = parseModelString(model)
                    try {
                        setDefaultModel(ModelOperation.DEFAULT, modelId, provider)
                        logger.info { "Loaded default chat model from YAML: $modelId" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to set chat model from YAML: ${e.message}" }
                    }
                }
            }

            defaults.plan?.let { model ->
                if (configRepository.get(KEY_DEFAULT_MODEL_PLAN, ConfigScope.APP) == null) {
                    val (provider, modelId) = parseModelString(model)
                    try {
                        setDefaultModel(ModelOperation.PLAN, modelId, provider)
                        logger.info { "Loaded default plan model from YAML: $modelId" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to set plan model from YAML: ${e.message}" }
                    }
                }
            }

            defaults.coding?.let { model ->
                if (configRepository.get(KEY_DEFAULT_MODEL_AGENT, ConfigScope.APP) == null) {
                    val (provider, modelId) = parseModelString(model)
                    try {
                        setDefaultModel(ModelOperation.CODING, modelId, provider)
                        logger.info { "Loaded default coding model from YAML: $modelId" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to set coding model from YAML: ${e.message}" }
                    }
                }
            }
        }

        // Load model visibility (merge with existing)
        yamlConfig.models?.visibility?.let { visibility ->
            val existingVisibility = getModelsVisibility().toMutableMap()
            var hasChanges = false

            visibility.forEach { (modelId, show) ->
                if (!existingVisibility.containsKey(modelId)) {
                    existingVisibility[modelId] = show
                    hasChanges = true
                    logger.info { "Loaded model visibility from YAML: $modelId -> $show" }
                }
            }

            if (hasChanges) {
                val valueJson = gson.toJson(existingVisibility)
                configRepository.set(
                    key = KEY_MODELS_VISIBILITY,
                    value = valueJson,
                    scope = ConfigScope.APP,
                    taskId = null,
                    description = "Model visibility settings"
                )
            }
        }

        // Load provider configs (only if not set)
        yamlConfig.providers?.let { providers ->
            providers.ollama?.endpoint?.let { endpoint ->
                if (configRepository.get(KEY_PROVIDER_OLLAMA_ENDPOINT, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_OLLAMA_ENDPOINT, endpoint)
                    logger.info { "Loaded Ollama endpoint from YAML: $endpoint" }
                }
            }

            providers.anthropic?.apiKey?.let { apiKey ->
                if (configRepository.get(KEY_PROVIDER_ANTHROPIC_API_KEY, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_ANTHROPIC_API_KEY, apiKey)
                    logger.info { "Loaded Anthropic API key from YAML" }
                }
            }

            providers.openai?.apiKey?.let { apiKey ->
                if (configRepository.get(KEY_PROVIDER_OPENAI_API_KEY, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_OPENAI_API_KEY, apiKey)
                    logger.info { "Loaded OpenAI API key from YAML" }
                }
            }

            providers.openrouter?.apiKey?.let { apiKey ->
                if (configRepository.get(KEY_PROVIDER_OPENROUTER_API_KEY, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_OPENROUTER_API_KEY, apiKey)
                    logger.info { "Loaded OpenRouter API key from YAML" }
                }
            }

            providers.gemini?.apiKey?.let { apiKey ->
                if (configRepository.get(KEY_PROVIDER_GEMINI_API_KEY, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_GEMINI_API_KEY, apiKey)
                    logger.info { "Loaded Gemini API key from YAML" }
                }
            }

            providers.lmstudio?.apiKey?.let { apiKey ->
                if (configRepository.get(KEY_PROVIDER_LM_STUDIO_API_KEY, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_LM_STUDIO_API_KEY, apiKey)
                    logger.info { "Loaded LM Studio API key from YAML" }
                }
            }

            providers.lmstudio?.baseUrl?.let { baseUrl ->
                if (configRepository.get(KEY_PROVIDER_LM_STUDIO_BASE_URL, ConfigScope.APP) == null) {
                    set(KEY_PROVIDER_LM_STUDIO_BASE_URL, baseUrl)
                    logger.info { "Loaded LM Studio base URL from YAML: $baseUrl" }
                }
            }
        }

        // Load limits (only if not set)
        yamlConfig.limits?.let { limits ->
            limits.apiCallTimeout?.let { timeout ->
                if (configRepository.get(KEY_API_CALL_TIMEOUT, ConfigScope.APP) == null) {
                    set(KEY_API_CALL_TIMEOUT, timeout.toString())
                    logger.info { "Loaded API call timeout from YAML: $timeout" }
                }
            }

            limits.toolExecutionTimeout?.let { timeout ->
                if (configRepository.get(KEY_TOOL_EXECUTION_TIMEOUT, ConfigScope.APP) == null) {
                    set(KEY_TOOL_EXECUTION_TIMEOUT, timeout.toString())
                    logger.info { "Loaded tool execution timeout from YAML: $timeout" }
                }
            }

            limits.maxContextSize?.let { size ->
                if (configRepository.get(KEY_MAX_CONTEXT_SIZE, ConfigScope.APP) == null) {
                    set(KEY_MAX_CONTEXT_SIZE, size.toString())
                    logger.info { "Loaded max context size from YAML: $size" }
                }
            }

            limits.maxOutputSize?.let { size ->
                if (configRepository.get(KEY_MAX_OUTPUT_SIZE, ConfigScope.APP) == null) {
                    set(KEY_MAX_OUTPUT_SIZE, size.toString())
                    logger.info { "Loaded max output size from YAML: $size" }
                }
            }

            limits.maxFileSize?.let { size ->
                if (configRepository.get(KEY_MAX_FILE_SIZE, ConfigScope.APP) == null) {
                    set(KEY_MAX_FILE_SIZE, size.toString())
                    logger.info { "Loaded max file size from YAML: $size" }
                }
            }
        }

        yamlConfig.terminal?.whitelist?.let { whitelist ->
            if (configRepository.get(KEY_TERMINAL_WHITELIST, ConfigScope.APP) == null) {
                val domainConfig = CommandWhitelistConfig(
                    enabled = whitelist.enabled ?: true,
                    mode = parseWhitelistMode(whitelist.mode) ?: WhitelistMode.WHITELIST_ONLY,
                    allowedCommands = whitelist.commands?.map { toDomainAllowedCommand(it) } ?: emptyList(),
                    globalBlockedPatterns = whitelist.globalBlockedPatterns ?: emptyList()
                )
                configRepository.set(
                    key = KEY_TERMINAL_WHITELIST,
                    value = gson.toJson(domainConfig),
                    scope = ConfigScope.APP,
                    taskId = null,
                    description = "Terminal whitelist configuration"
                )
                logger.info { "Loaded terminal whitelist config from YAML" }
            }
            if (configRepository.get(KEY_TERMINAL_WHITELIST_ENABLED, ConfigScope.APP) == null && whitelist.enabled != null) {
                set(KEY_TERMINAL_WHITELIST_ENABLED, whitelist.enabled.toString())
                logger.info { "Loaded terminal whitelist enabled from YAML: ${whitelist.enabled}" }
            }
            if (configRepository.get(KEY_TERMINAL_WHITELIST_MODE, ConfigScope.APP) == null && whitelist.mode != null) {
                val mode = parseWhitelistMode(whitelist.mode)
                if (mode != null) {
                    set(KEY_TERMINAL_WHITELIST_MODE, mode.name)
                    logger.info { "Loaded terminal whitelist mode from YAML: ${mode.name}" }
                }
            }
        }

        logger.info { "Finished loading configuration from YAML" }
    }

    /**
     * Initialize default configuration values if they don't exist in the database.
     * Called on plugin startup to ensure all required config keys have values.
     *
     * This method is idempotent - it won't overwrite existing config values.
     */
    fun initializeDefaults() {
        logger.info { "Initializing default configuration values (only missing keys)" }

        migrateProviderKeysToLowercase()

        val defaults = listOf(
            Triple(KEY_UI_THINKING_ENABLED, "false", "Show LLM thinking process in UI"),
            Triple(KEY_UI_NO_EGRESS_ENABLED, "false", "Block external network calls"),
            Triple(KEY_UI_ORCHESTRATION_ENABLED, "true", "Enable orchestration UI toggle"),
            Triple(KEY_UI_INTENT_CLASSIFICATION_ENABLED, "false", "Enable LLM intent classification"),
            Triple(KEY_UI_EXECUTION_MODE, "AUTO", "Execution mode (AUTO/INTERACTIVE)"),
            Triple(KEY_UI_SELECTED_MODE, "CHAT", "Selected task mode (CHAT/PLAN/AGENT)"),
            Triple(KEY_EMBEDDING_MODEL, "ollama/nomic-embed-text", "Model for embeddings"),
            Triple(KEY_FORMAT_MARKDOWN, "true", "Format responses as markdown"),
            Triple(KEY_STREAMING_ENABLED, "true", "Enable streaming responses"),
            Triple(KEY_ADVANCED_VIEW, "false", "Show advanced UI options"),
            Triple(KEY_TOOL_SUMMARY_ENABLED, "true", "Enable tool result summarization"),
            Triple(KEY_TOOL_SUMMARY_MIN_LENGTH, "500", "Minimum tool output length for summarization"),
            Triple(KEY_TASK_VERIFICATION_ENABLED, "false", "Enable task completion verification for AGENT mode"),
            Triple(KEY_MAX_CONSECUTIVE_TOOL_ERRORS, "3", "Max consecutive tool errors before failing the turn"),
            Triple(KEY_JSON_THINKING_XML_TAGS, DEFAULT_JSON_THINKING_XML_TAGS, "Comma-separated XML tags stripped before JSON extraction (e.g., thinking,think)")
        )

        var initializedCount = 0
        for ((key, value, description) in defaults) {
            if (configRepository.get(key, ConfigScope.APP) == null) {
                configRepository.set(
                    key = key,
                    value = value,
                    scope = ConfigScope.APP,
                    taskId = null,
                    description = description
                )
                logger.info { "Initialized default: $key = $value" }
                initializedCount++
            }
        }

        if (configRepository.get(KEY_TERMINAL_WHITELIST_ENABLED, ConfigScope.APP) == null) {
            configRepository.set(
                key = KEY_TERMINAL_WHITELIST_ENABLED,
                value = "true",
                scope = ConfigScope.APP,
                taskId = null,
                description = "Terminal whitelist enabled"
            )
            initializedCount++
        }
        if (configRepository.get(KEY_TERMINAL_WHITELIST_MODE, ConfigScope.APP) == null) {
            configRepository.set(
                key = KEY_TERMINAL_WHITELIST_MODE,
                value = WhitelistMode.WHITELIST_ONLY.name,
                scope = ConfigScope.APP,
                taskId = null,
                description = "Terminal whitelist mode"
            )
            initializedCount++
        }

        logger.info { "Finished initializing defaults: $initializedCount keys set" }
    }

    private fun migrateProviderKeysToLowercase() {
        val legacyToCanonical = listOf(
            "providers.Ollama.ollama_endpoint" to KEY_PROVIDER_OLLAMA_ENDPOINT,
            "providers.Anthropic.anthropic_api_key" to KEY_PROVIDER_ANTHROPIC_API_KEY,
            "providers.OpenAI.openai_api_key" to KEY_PROVIDER_OPENAI_API_KEY,
            "providers.OpenRouter.openrouter_api_key" to KEY_PROVIDER_OPENROUTER_API_KEY,
            "providers.Gemini.gemini_api_key" to KEY_PROVIDER_GEMINI_API_KEY,
            "providers.LMStudio.lmstudio_api_key" to KEY_PROVIDER_LM_STUDIO_API_KEY,
            "providers.LMStudio.lmstudio_base_url" to KEY_PROVIDER_LM_STUDIO_BASE_URL,
            "ollama_endpoint" to KEY_PROVIDER_OLLAMA_ENDPOINT,
            "anthropic_api_key" to KEY_PROVIDER_ANTHROPIC_API_KEY,
            "openai_api_key" to KEY_PROVIDER_OPENAI_API_KEY,
            "openrouter_api_key" to KEY_PROVIDER_OPENROUTER_API_KEY,
            "gemini_api_key" to KEY_PROVIDER_GEMINI_API_KEY,
            "lmstudio_api_key" to KEY_PROVIDER_LM_STUDIO_API_KEY,
            "lmstudio_base_url" to KEY_PROVIDER_LM_STUDIO_BASE_URL
        )

        legacyToCanonical.forEach { (legacyKey, canonicalKey) ->
            val legacyConfig = configRepository.get(legacyKey, ConfigScope.APP) ?: return@forEach
            val canonicalConfig = configRepository.get(canonicalKey, ConfigScope.APP)

            if (canonicalConfig == null) {
                configRepository.set(
                    key = canonicalKey,
                    value = legacyConfig.value,
                    scope = ConfigScope.APP,
                    taskId = null,
                    description = legacyConfig.description
                )
                logger.info { "Migrated provider key: $legacyKey -> $canonicalKey" }
            } else {
                logger.info { "Skipping provider key migration for $legacyKey (canonical already set)" }
            }

            configRepository.delete(legacyKey, ConfigScope.APP)
        }
    }

    /**
     * Reload all configuration from YAML file, overwriting existing DB values.
     * Called manually via Settings UI "Reload from Local Config" button.
     *
     * This method is NOT idempotent - it will overwrite all config values with YAML values.
     *
     * @return Number of config keys updated
     */
    fun reloadFromYaml(): Int {
        val yamlConfig = pl.jclab.refio.core.config.ConfigYaml.load()
            ?: throw IllegalStateException("No YAML config file found at ${pl.jclab.refio.core.config.ConfigYaml.getConfigPath().absolutePath}")

        logger.info { "Reloading all configuration from YAML file (overwriting DB)" }
        var updatedCount = 0

        // Reload default models
        yamlConfig.models?.defaults?.let { defaults ->
            defaults.chat?.let { model ->
                val (provider, modelId) = parseModelString(model)
                try {
                    setDefaultModel(ModelOperation.DEFAULT, modelId, provider)
                    updatedCount++
                    logger.info { "Reloaded default chat model from YAML: $modelId" }
                } catch (e: Exception) {
                    logger.warn { "Failed to set chat model from YAML: ${e.message}" }
                }
            }

            defaults.plan?.let { model ->
                val (provider, modelId) = parseModelString(model)
                try {
                    setDefaultModel(ModelOperation.PLAN, modelId, provider)
                    updatedCount++
                    logger.info { "Reloaded default plan model from YAML: $modelId" }
                } catch (e: Exception) {
                    logger.warn { "Failed to set plan model from YAML: ${e.message}" }
                }
            }

            defaults.coding?.let { model ->
                val (provider, modelId) = parseModelString(model)
                try {
                    setDefaultModel(ModelOperation.CODING, modelId, provider)
                    updatedCount++
                    logger.info { "Reloaded default coding model from YAML: $modelId" }
                } catch (e: Exception) {
                    logger.warn { "Failed to set coding model from YAML: ${e.message}" }
                }
            }
        }

        // Reload model visibility (replace existing)
        yamlConfig.models?.visibility?.let { visibility ->
            val valueJson = gson.toJson(visibility)
            configRepository.set(
                key = KEY_MODELS_VISIBILITY,
                value = valueJson,
                scope = ConfigScope.APP,
                taskId = null,
                description = "Model visibility settings"
            )
            updatedCount++
            logger.info { "Reloaded model visibility from YAML: ${visibility.size} entries" }
        }

        // Reload provider configs
        yamlConfig.providers?.let { providers ->
            providers.ollama?.endpoint?.let { endpoint ->
                set(KEY_PROVIDER_OLLAMA_ENDPOINT, endpoint)
                updatedCount++
                logger.info { "Reloaded Ollama endpoint from YAML: $endpoint" }
            }

            providers.anthropic?.apiKey?.let { apiKey ->
                set(KEY_PROVIDER_ANTHROPIC_API_KEY, apiKey)
                updatedCount++
                logger.info { "Reloaded Anthropic API key from YAML" }
            }

            providers.openai?.apiKey?.let { apiKey ->
                set(KEY_PROVIDER_OPENAI_API_KEY, apiKey)
                updatedCount++
                logger.info { "Reloaded OpenAI API key from YAML" }
            }

            providers.openrouter?.apiKey?.let { apiKey ->
                set(KEY_PROVIDER_OPENROUTER_API_KEY, apiKey)
                updatedCount++
                logger.info { "Reloaded OpenRouter API key from YAML" }
            }

            providers.gemini?.apiKey?.let { apiKey ->
                set(KEY_PROVIDER_GEMINI_API_KEY, apiKey)
                updatedCount++
                logger.info { "Reloaded Gemini API key from YAML" }
            }

            providers.lmstudio?.apiKey?.let { apiKey ->
                set(KEY_PROVIDER_LM_STUDIO_API_KEY, apiKey)
                updatedCount++
                logger.info { "Reloaded LM Studio API key from YAML" }
            }

            providers.lmstudio?.baseUrl?.let { baseUrl ->
                set(KEY_PROVIDER_LM_STUDIO_BASE_URL, baseUrl)
                updatedCount++
                logger.info { "Reloaded LM Studio base URL from YAML: $baseUrl" }
            }
        }

        // Reload limits
        yamlConfig.limits?.let { limits ->
            limits.apiCallTimeout?.let { timeout ->
                set(KEY_API_CALL_TIMEOUT, timeout.toString())
                updatedCount++
                logger.info { "Reloaded API call timeout from YAML: $timeout" }
            }

            limits.toolExecutionTimeout?.let { timeout ->
                set(KEY_TOOL_EXECUTION_TIMEOUT, timeout.toString())
                updatedCount++
                logger.info { "Reloaded tool execution timeout from YAML: $timeout" }
            }

            limits.maxContextSize?.let { size ->
                set(KEY_MAX_CONTEXT_SIZE, size.toString())
                updatedCount++
                logger.info { "Reloaded max context size from YAML: $size" }
            }

            limits.maxOutputSize?.let { size ->
                set(KEY_MAX_OUTPUT_SIZE, size.toString())
                updatedCount++
                logger.info { "Reloaded max output size from YAML: $size" }
            }

            limits.maxFileSize?.let { size ->
                set(KEY_MAX_FILE_SIZE, size.toString())
                updatedCount++
                logger.info { "Reloaded max file size from YAML: $size" }
            }
        }

        yamlConfig.terminal?.whitelist?.let { whitelist ->
            val domainConfig = CommandWhitelistConfig(
                enabled = whitelist.enabled ?: true,
                mode = parseWhitelistMode(whitelist.mode) ?: WhitelistMode.WHITELIST_ONLY,
                allowedCommands = whitelist.commands?.map { toDomainAllowedCommand(it) } ?: emptyList(),
                globalBlockedPatterns = whitelist.globalBlockedPatterns ?: emptyList()
            )
            setTerminalWhitelistConfig(domainConfig, ConfigScope.APP)
            updatedCount++
            logger.info { "Reloaded terminal whitelist from YAML" }
        }

        logger.info { "Finished reloading configuration from YAML: $updatedCount keys updated" }
        return updatedCount
    }

    private fun mergeTerminalWhitelistConfigs(
        base: CommandWhitelistConfig,
        override: CommandWhitelistConfig?
    ): CommandWhitelistConfig {
        if (override == null) {
            return base
        }

        val commandsByProgram = linkedMapOf<String, AllowedCommand>()
        base.allowedCommands.forEach { command ->
            commandsByProgram[command.program.lowercase()] = command
        }
        override.allowedCommands.forEach { command ->
            commandsByProgram[command.program.lowercase()] = command
        }

        return base.copy(
            enabled = override.enabled,
            mode = override.mode,
            allowedCommands = commandsByProgram.values.toList(),
            globalBlockedPatterns = (base.globalBlockedPatterns + override.globalBlockedPatterns).distinct()
        )
    }

    private fun toDomainAllowedCommand(command: TerminalCommandConfig): AllowedCommand {
        return AllowedCommand(
            program = command.program,
            description = command.description.orEmpty(),
            aliases = command.aliases ?: emptyList(),
            blockedFlags = command.blockedFlags ?: emptyList(),
            blockedSubcommands = command.blockedSubcommands ?: emptyList(),
            blockedArgPatterns = command.blockedArgPatterns ?: emptyList(),
            allowedSubcommands = command.allowedSubcommands ?: emptyList(),
            maxArgs = command.maxArgs ?: 50,
            requireConfirmation = command.requireConfirmation ?: false
        )
    }

    private fun parseWhitelistMode(value: String?): WhitelistMode? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { WhitelistMode.valueOf(value.trim().uppercase()) }.getOrNull()
    }

    private fun resolveProjectId(projectId: String?): String? = projectId ?: defaultProjectId

    private fun getConfigWithPrecedence(
        key: String,
        taskId: String? = null,
        projectId: String? = null
    ) = configRepository.getWithPrecedence(
        key = key,
        taskId = taskId,
        projectId = resolveProjectId(projectId)
    )

    /**
     * Check if intelligent orchestration is enabled (US-028).
     *
     * @param taskId Optional task ID for task-level override
     * @return true if orchestration is enabled (default: true)
     */
    fun isOrchestrationEnabled(taskId: String? = null): Boolean {
        val config = getConfigWithPrecedence(
            key = KEY_ORCHESTRATION_ENABLED,
            taskId = taskId
        )
        val result = config?.value?.toBoolean() ?: DEFAULT_ORCHESTRATION_ENABLED
        logger.info { "isOrchestrationEnabled(taskId=$taskId): config=${config?.value}, result=$result, default=$DEFAULT_ORCHESTRATION_ENABLED" }
        return result
    }

    fun isTaskVerificationEnabled(taskId: String? = null): Boolean {
        val config = getConfigWithPrecedence(
            key = KEY_TASK_VERIFICATION_ENABLED,
            taskId = taskId
        )
        val result = config?.value?.toBoolean() ?: DEFAULT_TASK_VERIFICATION_ENABLED
        logger.info { "isTaskVerificationEnabled(taskId=$taskId): config=${config?.value}, result=$result, default=$DEFAULT_TASK_VERIFICATION_ENABLED" }
        return result
    }

    /**
     * Check if task verification should run for current turn.
     * ADR 0019 P13: Auto-enable verification for longer turns (>5 iterations) to catch hallucinations.
     *
     * @param taskId Task ID (for config override)
     * @param iterationCount Current iteration count in turn
     * @return true if verification should run
     */
    fun shouldVerifyTask(taskId: String? = null, iterationCount: Int = 0): Boolean {
        val explicitSetting = getConfigWithPrecedence(KEY_TASK_VERIFICATION_ENABLED, taskId)
        if (explicitSetting != null) {
            // User explicitly configured it - respect that
            return explicitSetting.value.toBoolean()
        }
        // Auto-enable for longer turns (>5 iterations) where hallucinations are more likely
        return iterationCount >= 5
    }

    fun getMaxConsecutiveToolErrors(taskId: String? = null): Int {
        val config = getConfigWithPrecedence(
            key = KEY_MAX_CONSECUTIVE_TOOL_ERRORS,
            taskId = taskId
        )
        val parsed = config?.value?.toIntOrNull()
        val result = if (parsed != null && parsed > 0) {
            parsed
        } else {
            DEFAULT_MAX_CONSECUTIVE_TOOL_ERRORS
        }
        logger.info {
            "getMaxConsecutiveToolErrors(taskId=$taskId): config=${config?.value}, result=$result, default=$DEFAULT_MAX_CONSECUTIVE_TOOL_ERRORS"
        }
        return result
    }

    fun getMaxIterations(taskId: String? = null): Int {
        val config = getConfigWithPrecedence(
            key = KEY_MAX_ITERATIONS,
            taskId = taskId
        )
        val parsed = config?.value?.toIntOrNull()
        val result = if (parsed != null) {
            parsed.coerceIn(10, 200)  // Enforce range 10-200
        } else {
            DEFAULT_MAX_ITERATIONS
        }
        logger.info {
            "getMaxIterations(taskId=$taskId): config=${config?.value}, result=$result, default=$DEFAULT_MAX_ITERATIONS"
        }
        return result
    }

    fun getJsonThinkingXmlTags(taskId: String? = null): List<String> {
        val raw = getConfigWithPrecedence(
            key = KEY_JSON_THINKING_XML_TAGS,
            taskId = taskId
        )?.value ?: DEFAULT_JSON_THINKING_XML_TAGS

        return raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Set orchestration enabled setting (US-028).
     *
     * @param enabled true to enable orchestration, false to disable
     * @param taskId Optional task ID for task-level config
     */
    fun setOrchestrationEnabled(enabled: Boolean, taskId: String? = null) {
        val scope = if (taskId != null) ConfigScope.TASK
                    else ConfigScope.APP
        configRepository.set(
            key = KEY_ORCHESTRATION_ENABLED,
            value = enabled.toString(),
            scope = scope,
            taskId = taskId,
            description = "Enable intelligent orchestration with reflection and plan adaptation"
        )

        logger.info { "Set orchestration_enabled = $enabled (scope=${scope.name})" }
    }

    /**
     * Data class for model configuration JSON storage.
     */
    private data class ModelConfigData(
        val modelId: String? = null,
        val provider: String? = null
    )
}
