package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.getModelConfigFromCache
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.services.context.ContextSection
import pl.jclab.refio.core.utils.GsonInstance.gson
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
    private val configRepository: ConfigRepository,
    private val defaultProjectId: String? = null,
    private val projectRoot: Path? = null
) {
    private val logger = dualLogger("ConfigService")
    private val configCache = ConfigCache()

    /**
     * Hierarchical config loader for YAML files.
     * Handles user and project config files.
     */
    private val yamlLoader: HierarchicalConfigLoader by lazy {
        HierarchicalConfigLoader.getInstance(projectRoot)
    }

    /**
     * Get a typed configuration value using a [ConfigKey] descriptor.
     *
     * Lookup order (highest priority first):
     * 1. Database value (task-scoped, then project-scoped, then app-scoped)
     * 2. YAML config value via the key's yamlAccessor
     * 3. The key's built-in default
     *
     * @param configKey Typed key descriptor containing key, parser, default, and yaml accessor
     * @param taskId Optional task ID for task-level override
     * @return Parsed value of type T, or the key's default if not found / unparseable
     */
    fun <T> getTyped(configKey: ConfigKey<T>, taskId: String? = null): T {
        val cacheKey = "typed:${configKey.key}:task=${taskId.orEmpty()}"
        return configCache.getOrCompute(cacheKey) {
            val dbConfig = getConfigWithPrecedence(key = configKey.key, taskId = taskId)
            if (dbConfig?.value != null) {
                val parsed = configKey.parser(dbConfig.value)
                if (parsed != null) return@getOrCompute parsed
            }

            val yamlValue = configKey.yamlAccessor?.invoke(yamlLoader)
            if (yamlValue != null) {
                val parsed = configKey.parser(yamlValue.toString())
                if (parsed != null) return@getOrCompute parsed
            }

            configKey.default
        }
    }

    /**
     * Set a typed configuration value using a [ConfigKey] descriptor.
     *
     * @param configKey Typed key descriptor containing key and serializer
     * @param value The value to store
     * @param scope Configuration scope (default: APP)
     * @param taskId Optional task ID for TASK scope
     */
    fun <T> setTyped(configKey: ConfigKey<T>, value: T, scope: ConfigScope = ConfigScope.APP, taskId: String? = null) {
        val serialized = configKey.serializer(value)
        configRepository.set(
            key = configKey.key,
            value = serialized,
            scope = scope,
            taskId = taskId,
            description = null
        )
        invalidateConfigCache(configKey.key)
    }

    companion object {
        const val INHERIT_MODEL_VALUE = "inherit"

        /** Context sizes at or below this threshold trigger compact (shorter) prompts. */
        const val COMPACT_PROMPT_THRESHOLD = 48_000

        // Configuration keys
        const val KEY_DEFAULT_MODEL_CHAT = "default_model.chat"
        const val KEY_DEFAULT_MODEL_PLAN = "default_model.plan"
        const val KEY_DEFAULT_MODEL_AGENT = "default_model.agent"
        const val KEY_WEAK_MODEL = "default_model.weak"  // Cheap model for auxiliary operations (summaries, etc.)
        const val KEY_STRONG_MODEL = "default_model.strong"  // Powerful model for complex delegation
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
        const val KEY_UI_MULTI_AGENT_STRATEGY = "ui.multi_agent_strategy"
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
        const val KEY_OLLAMA_MAX_CONCURRENT = "providers.ollama_max_concurrent"
        const val KEY_RAG_INDEX_BATCH_SIZE = "rag.index_batch_size"
        const val KEY_RAG_EMBEDDINGS_BATCH_SIZE = "rag.embeddings_batch_size"
        const val KEY_RAG_EMBEDDING_CACHE_SIZE = "rag.embedding_cache_size"
        const val KEY_RAG_IGNORED_DIRECTORIES = "rag.ignored_directories"
        const val KEY_RAG_CHUNKING_MODE = "rag.chunking_mode"
        const val KEY_RAG_SEARCH_SIMILARITY_THRESHOLD = "rag.search_similarity_threshold"
        const val KEY_RAG_SEARCH_TOP_K = "rag.search_top_k"
        const val KEY_RAG_SEARCH_CACHE_TTL_SECONDS = "rag.search.cache_ttl_seconds"
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

        // Tool result summarization configuration keys
        const val KEY_TOOL_SUMMARY_ENABLED = "tool_summary.enabled"
        const val KEY_TOOL_SUMMARY_MIN_LENGTH = "tool_summary.min_length"
        const val KEY_SECURITY_ALLOW_SYMLINKS = "security.allow_symlinks"

        // Context configuration keys (ADR 0017)
        const val KEY_RECENT_WORK_FULL_DATA_LIMIT = "context.recent_work.full_data_limit"
        const val KEY_RECENT_WORK_SUMMARY_MAX_LENGTH = "context.recent_work.summary_max_length"
        const val KEY_CONTEXT_BUDGET_TOTAL_TOKENS = "context.budget.total_tokens"
        const val KEY_CONTEXT_BUDGET_INPUT_RATIO = "context.budget.input_ratio"
        const val KEY_CONTEXT_BUDGET_SECTION_PREFIX = "context.budget.section."
        const val KEY_WORKING_MEMORY_MAX_FACTS = "working_memory.max_facts"

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
        const val KEY_PROVIDER_CUSTOM_OPENAI_API_KEY = "providers.generic_openai.generic_openai_api_key"
        const val KEY_PROVIDER_CUSTOM_OPENAI_BASE_URL = "providers.generic_openai.generic_openai_base_url"
        const val KEY_PROVIDER_CUSTOM_OPENAI_MODEL = "providers.generic_openai.generic_openai_model"
        const val KEY_PROVIDER_ZAI_API_KEY = "providers.zai.zai_api_key"
        const val KEY_PROVIDER_ZAI_BASE_URL = "providers.zai.zai_base_url"

        // Fallback defaults (used when no config exists)
        const val FALLBACK_MODEL = "qwen2.5:7b"
        const val FALLBACK_PROVIDER = "ollama"
        const val FALLBACK_WEAK_MODEL = "qwen2.5:7b"
        const val FALLBACK_WEAK_PROVIDER = "ollama"
        const val FALLBACK_EMBEDDING_MODEL = "nomic-embed-text"
        const val FALLBACK_EMBEDDING_PROVIDER = "ollama"
        const val DEFAULT_ZAI_BASE_URL = "https://api.z.ai/api/coding/paas/v4"
        const val LEGACY_ZAI_BASE_URL = "https://api.z.ai/v1"
        const val GENERAL_ZAI_BASE_URL = "https://api.z.ai/api/paas/v4"

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
        const val DEFAULT_RAG_EMBEDDING_CACHE_SIZE = 2_000
        const val DEFAULT_RAG_CACHE_TTL_MS = 300_000L
        const val DEFAULT_RAG_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_RAG_MAX_CHUNKS_PER_FILE = 100
        const val DEFAULT_RAG_CHUNKING_MODE = "semantic"
        const val DEFAULT_RAG_SEARCH_SIMILARITY_THRESHOLD = 0.65f
        const val DEFAULT_RAG_SEARCH_TOP_K = 5
        const val DEFAULT_RAG_SEARCH_CACHE_TTL_SECONDS = 60L
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
        const val DEFAULT_WORKING_MEMORY_MAX_FACTS = 20

        // Agent flow defaults (ADR 0019) — single source of truth is ConfigKeys
        val DEFAULT_TASK_VERIFICATION_ENABLED get() = ConfigKeys.TASK_VERIFICATION_ENABLED.default
        val DEFAULT_MAX_CONSECUTIVE_TOOL_ERRORS get() = ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.default
        val DEFAULT_MAX_ITERATIONS get() = ConfigKeys.MAX_ITERATIONS.default
        val DEFAULT_JSON_THINKING_XML_TAGS get() = ConfigKeys.JSON_THINKING_XML_TAGS.default.joinToString(",")

        // Agent flow configuration keys (ADR 0019) — single source of truth is ConfigKeys
        val KEY_TASK_VERIFICATION_ENABLED get() = ConfigKeys.TASK_VERIFICATION_ENABLED.key
        val KEY_MAX_CONSECUTIVE_TOOL_ERRORS get() = ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key
        val KEY_MAX_ITERATIONS get() = ConfigKeys.MAX_ITERATIONS.key
        val KEY_JSON_THINKING_XML_TAGS get() = ConfigKeys.JSON_THINKING_XML_TAGS.key
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
            modelString.startsWith("glm-") -> "zai"
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
        ModelOperation.STRONG -> KEY_STRONG_MODEL
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
        val config = getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
        val label = operation.name.lowercase()

        // Embedding uses plain "provider/model" string format in DB, unlike the others (JSON ModelConfigData).
        if (operation == ModelOperation.EMBEDDING) {
            if (config?.value != null) {
                val (provider, model) = parseModelString(config.value)
                logger.info { "Using embedding model from DB: $model (provider=$provider)" }
                return Pair(model, provider)
            }
            val yamlModel = yamlLoader.getDefaultEmbeddingModel()
            if (yamlModel != null) {
                val (provider, model) = parseModelString(yamlModel)
                logger.info { "Using embedding model from YAML: $model (provider=$provider)" }
                return Pair(model, provider)
            }
            return fallbackModelForOperation(operation)
        }

        // All non-embedding operations: JSON ModelConfigData in DB, optional inheritance → DEFAULT.
        if (config != null) {
            val data = gson.fromJson(config.value, ModelConfigData::class.java)
            // DEFAULT cannot inherit (would recurse forever).
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
            ModelOperation.DEFAULT -> yamlLoader.getDefaultChatModel()
            ModelOperation.PLAN -> yamlLoader.getDefaultPlanModel()
            ModelOperation.CODING -> yamlLoader.getDefaultCodingModel()
            ModelOperation.WEAK -> yamlLoader.getDefaultWeakModel()
            ModelOperation.STRONG -> yamlLoader.getDefaultStrongModel()
            ModelOperation.EMBEDDING -> error("unreachable")
        }
        if (yamlModel != null) {
            val (provider, model) = parseModelString(yamlModel)
            logger.info { "Using $label model from YAML: $model (provider=$provider)" }
            return Pair(model, provider)
        }

        if (operation == ModelOperation.STRONG) {
            // No fallback for STRONG — callers should use getStrongModel() which returns null.
            throw IllegalStateException("STRONG model not configured and has no fallback")
        }

        val fallback = fallbackModelForOperation(operation)
        logger.info { "No config found for $operation, using fallback: ${fallback.first}" }
        return fallback
    }

    /**
     * Get the configured strong model, or null if not configured.
     * Unlike other operations, STRONG has no fallback.
     */
    fun getStrongModel(
        taskId: String? = null,
        projectId: String? = null
    ): Pair<String, String>? {
        val key = KEY_STRONG_MODEL

        // 1. Check database
        val config = getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
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

        // 2. Check YAML
        val yamlModel = yamlLoader.getDefaultStrongModel()
        if (yamlModel != null) {
            val (provider, model) = parseModelString(yamlModel)
            logger.info { "Using strong model from YAML: $model (provider=$provider)" }
            return Pair(model, provider)
        }

        // 3. No fallback — return null
        return null
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
    @Suppress("UNUSED_PARAMETER")
    fun setDefaultModel(
        operation: ModelOperation,
        modelId: String,
        provider: String,
        taskId: String? = null,
        _userId: String? = null
    ) {
        // Validate model exists using cached model registry (no suspend/runBlocking needed)
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
        // WEAK is app-scoped (no per-task weak model); other operations honour taskId.
        val scope = if (operation != ModelOperation.WEAK && taskId != null) ConfigScope.TASK else ConfigScope.APP
        val effectiveTaskId = if (scope == ConfigScope.TASK) taskId else null
        val description = if (operation == ModelOperation.WEAK) {
            "Cheap model for auxiliary operations"
        } else {
            "Default model for $operation operation"
        }
        configRepository.set(
            key = key,
            value = gson.toJson(ModelConfigData(modelId, provider)),
            scope = scope,
            taskId = effectiveTaskId,
            description = description
        )
        invalidateConfigCache(key)
        logger.info { "Set ${scope.name} config $key = $modelId" }
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
        // Validate once using cached model registry (no suspend/runBlocking needed)
        val modelConfig = getModelConfigFromCache(modelId)
        if (modelConfig != null && modelConfig.provider != provider) {
            return
        }

        // Set for all modes
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

    /**
     * Get visibility setting for a specific model.
     *
     * @param modelId Model identifier (e.g., "ollama/qwen2.5:14b")
     * @return true if model should be shown in dropdown (default), false otherwise
     */
    fun getModelVisibility(modelId: String): Boolean {
        val config = configRepository.get(KEY_MODELS_VISIBILITY, ConfigScope.APP)
        if (config != null) {
            @Suppress("UNCHECKED_CAST")
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
        setModelsVisibility(getModelsVisibility().toMutableMap().apply { put(modelId, showInDropdown) })
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
        invalidateConfigCache(KEY_MODELS_VISIBILITY)
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
            @Suppress("UNCHECKED_CAST")
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
        val cacheKey = "raw:$key:scope=${scope.name}:task=${taskId.orEmpty()}:project=${resolveProjectId(projectId).orEmpty()}"
        return configCache.getOrCompute(cacheKey) {
            val dbConfig = when {
                taskId != null -> getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
                scope == ConfigScope.PROJECT -> {
                    val resolvedProjectId = resolveProjectId(projectId)
                    resolvedProjectId?.let { configRepository.get(key, ConfigScope.PROJECT, projectId = it) }
                }
                else -> configRepository.get(key, scope)
            }
            dbConfig?.value ?: getFromYaml(key)
        }
    }

    /**
     * Get configuration value from YAML files only.
     * Useful when you want to explicitly read from file-based config.
     *
     * Uses [ConfigKeys.byKey] to find the registered [ConfigKey] and its yamlAccessor.
     *
     * @param key Configuration key in dot notation (e.g., "general.format_markdown")
     * @return Value from YAML config or null if not found
     */
    fun getFromYaml(key: String): String? {
        val cacheKey = "yaml:$key"
        return configCache.getOrCompute(cacheKey) {
            val configKey = ConfigKeys.byKey(key)
                ?: return@getOrCompute null
            configKey.yamlAccessor?.invoke(yamlLoader)?.toString()
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
        invalidateConfigCache(key)
    }

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

    fun getWeakModel(): Pair<String, String> = getDefaultModel(ModelOperation.WEAK)

    fun getEmbeddingModel(): String {
        val (modelId, provider) = getDefaultModel(ModelOperation.EMBEDDING)
        return "$provider/$modelId"
    }

    fun setEmbeddingModel(model: String) {
        setTyped(ConfigKeys.EMBEDDING_MODEL, model)
    }

    fun getYamlConfig(): ConfigYaml {
        return yamlLoader.getConfig()
    }

    fun normalizeZAIBaseUrl(baseUrl: String?): String {
        val normalized = baseUrl?.trim()?.trimEnd('/')
        return when {
            normalized.isNullOrEmpty() -> DEFAULT_ZAI_BASE_URL
            normalized.equals(LEGACY_ZAI_BASE_URL, ignoreCase = true) -> DEFAULT_ZAI_BASE_URL
            normalized.equals(GENERAL_ZAI_BASE_URL, ignoreCase = true) -> DEFAULT_ZAI_BASE_URL
            else -> normalized
        }
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

    fun getBuiltinSubagentEnabledOverrides(): Map<String, Boolean> {
        val config = configRepository.get(KEY_SUBAGENTS_BUILTIN_ENABLED, ConfigScope.APP)
        if (config != null) {
            val raw = gson.fromJson(config.value, Map::class.java)
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
        invalidateConfigCache(KEY_SUBAGENTS_BUILTIN_ENABLED)
    }

    // ==================== CONTEXT CONFIGURATION (ADR 0017) ====================

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
        val fallback = getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId)
        if (operation == null) return fallback

        val (_, provider) = getModel(operation, taskId)
        return when (provider.lowercase()) {
            "ollama" -> getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE)
            "lmstudio" -> getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE)
            else -> fallback
        }
    }

    /**
     * Whether compact (shorter) system prompts should be used.
     * Auto-detects based on resolved context size for the operation:
     * context <= 48000 tokens → compact mode (saves ~40% prompt tokens).
     */
    fun isCompactPrompts(operation: ModelOperation? = null, taskId: String? = null): Boolean {
        val contextSize = resolveContextSizeForBudget(operation, taskId)
        return contextSize <= COMPACT_PROMPT_THRESHOLD
    }

    /**
     * Get streaming read timeout (time between chunks) in milliseconds.
     * Used to detect stalled streaming connections.
     */
    /**
     * Get streaming request timeout (total time) in milliseconds.
     * Used as maximum total duration for streaming requests.
     */
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
        applyYaml(yamlConfig, overwrite = false)
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

        val defaults = listOf(
            Triple(KEY_UI_THINKING_ENABLED, "false", "Show LLM thinking process in UI"),
            Triple(KEY_UI_NO_EGRESS_ENABLED, "false", "Block external network calls"),
            Triple(KEY_UI_ORCHESTRATION_ENABLED, "true", "Enable orchestration UI toggle"),
            Triple(KEY_UI_MULTI_AGENT_STRATEGY, "SINGLE", "Multi-agent orchestration strategy: SINGLE, PARALLEL, PIPELINE, ORCHESTRATOR"),
            Triple(KEY_UI_INTENT_CLASSIFICATION_ENABLED, "false", "Enable LLM intent classification"),
            Triple(KEY_UI_EXECUTION_MODE, "AUTO", "Execution mode (AUTO/INTERACTIVE)"),
            Triple(KEY_UI_SELECTED_MODE, "CHAT", "Selected task mode (CHAT/PLAN/AGENT)"),
            Triple(KEY_EMBEDDING_MODEL, "ollama/nomic-embed-text", "Model for embeddings"),
            Triple(KEY_FORMAT_MARKDOWN, "true", "Format responses as markdown"),
            Triple(KEY_STREAMING_ENABLED, "true", "Enable streaming responses"),
            Triple(KEY_ADVANCED_VIEW, "false", "Show advanced UI options"),
            Triple(KEY_TOOL_SUMMARY_ENABLED, "true", "Enable tool result summarization"),
            Triple(KEY_TOOL_SUMMARY_MIN_LENGTH, "500", "Minimum tool output length for summarization"),
            Triple(KEY_SECURITY_ALLOW_SYMLINKS, "false", "Allow symbolic links in PathSandbox (unsafe, opt-in)"),
            Triple(KEY_PROVIDER_ZAI_BASE_URL, DEFAULT_ZAI_BASE_URL, "Base URL for Z.AI provider"),
            Triple(KEY_RAG_EMBEDDING_CACHE_SIZE, DEFAULT_RAG_EMBEDDING_CACHE_SIZE.toString(), "Maximum embedding cache entries"),
            Triple(KEY_RAG_CHUNKING_MODE, DEFAULT_RAG_CHUNKING_MODE, "RAG chunking mode (semantic or line_based)"),
            Triple(KEY_RAG_SEARCH_CACHE_TTL_SECONDS, DEFAULT_RAG_SEARCH_CACHE_TTL_SECONDS.toString(), "TTL for cached @codebase search results in seconds"),
            Triple(KEY_WORKING_MEMORY_MAX_FACTS, DEFAULT_WORKING_MEMORY_MAX_FACTS.toString(), "Maximum working memory facts stored per task"),
            Triple(KEY_TASK_VERIFICATION_ENABLED, "false", "Enable task completion verification for AGENT mode"),
            Triple(KEY_MAX_CONSECUTIVE_TOOL_ERRORS, DEFAULT_MAX_CONSECUTIVE_TOOL_ERRORS.toString(), "Max consecutive failures of the same tool+args before aborting (definitive loop). Varied args reset the counter."),
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

        logger.info { "Finished initializing defaults: $initializedCount keys set" }
        configCache.invalidateAll()
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
        val updatedCount = applyYaml(yamlConfig, overwrite = true)
        logger.info { "Finished reloading configuration from YAML: $updatedCount keys updated" }
        configCache.invalidateAll()
        return updatedCount
    }

    /**
     * Walk a [ConfigYaml] snapshot and apply it to DB.
     * @param overwrite true = `reload` semantics (overwrite all, count updates);
     *                  false = `load-if-missing` semantics (skip keys already present).
     * @return Number of keys written.
     */
    private fun applyYaml(yamlConfig: pl.jclab.refio.core.config.ConfigYaml, overwrite: Boolean): Int {
        val verb = if (overwrite) "Reloaded" else "Loaded"
        var count = 0

        fun apply(key: String, value: String?, label: String) {
            if (value == null) return
            if (!overwrite && configRepository.get(key, ConfigScope.APP) != null) return
            set(key, value)
            count++
            logger.info { "$verb $label from YAML: $value" }
        }

        fun applyDefaultModel(key: String, op: ModelOperation, model: String?, label: String) {
            if (model == null) return
            if (!overwrite && configRepository.get(key, ConfigScope.APP) != null) return
            val (provider, modelId) = parseModelString(model)
            try {
                setDefaultModel(op, modelId, provider)
                count++
                logger.info { "$verb default $label model from YAML: $modelId" }
            } catch (e: Exception) {
                logger.warn { "Failed to set $label model from YAML: ${e.message}" }
            }
        }

        yamlConfig.models?.defaults?.let { d ->
            applyDefaultModel(KEY_DEFAULT_MODEL_CHAT, ModelOperation.DEFAULT, d.chat, "chat")
            applyDefaultModel(KEY_DEFAULT_MODEL_PLAN, ModelOperation.PLAN, d.plan, "plan")
            applyDefaultModel(KEY_DEFAULT_MODEL_AGENT, ModelOperation.CODING, d.coding, "coding")
        }

        yamlConfig.models?.visibility?.let { visibility ->
            val finalMap = if (overwrite) {
                visibility
            } else {
                // load-if-missing: only add entries not yet present
                val existing = getModelsVisibility().toMutableMap()
                val additions = visibility.filterKeys { !existing.containsKey(it) }
                if (additions.isEmpty()) return@let
                additions.forEach { (id, show) -> logger.info { "Loaded model visibility from YAML: $id -> $show" } }
                existing.apply { putAll(additions) }
            }
            configRepository.set(
                key = KEY_MODELS_VISIBILITY,
                value = gson.toJson(finalMap),
                scope = ConfigScope.APP,
                taskId = null,
                description = "Model visibility settings"
            )
            if (overwrite) {
                count++
                logger.info { "Reloaded model visibility from YAML: ${visibility.size} entries" }
            }
        }

        yamlConfig.providers?.let { p ->
            apply(KEY_PROVIDER_OLLAMA_ENDPOINT, p.ollama?.endpoint, "Ollama endpoint")
            apply(KEY_PROVIDER_ANTHROPIC_API_KEY, p.anthropic?.apiKey, "Anthropic API key")
            apply(KEY_PROVIDER_OPENAI_API_KEY, p.openai?.apiKey, "OpenAI API key")
            apply(KEY_PROVIDER_OPENROUTER_API_KEY, p.openrouter?.apiKey, "OpenRouter API key")
            apply(KEY_PROVIDER_GEMINI_API_KEY, p.gemini?.apiKey, "Gemini API key")
            apply(KEY_PROVIDER_LM_STUDIO_API_KEY, p.lmstudio?.apiKey, "LM Studio API key")
            apply(KEY_PROVIDER_LM_STUDIO_BASE_URL, p.lmstudio?.baseUrl, "LM Studio base URL")
            apply(KEY_PROVIDER_CUSTOM_OPENAI_API_KEY, p.genericOpenai?.apiKey, "Custom OpenAI API key")
            apply(KEY_PROVIDER_CUSTOM_OPENAI_BASE_URL, p.genericOpenai?.baseUrl, "Custom OpenAI base URL")
            apply(KEY_PROVIDER_CUSTOM_OPENAI_MODEL, p.genericOpenai?.model, "Custom OpenAI model")
            apply(KEY_PROVIDER_ZAI_API_KEY, p.zai?.apiKey, "Z.AI API key")
            apply(KEY_PROVIDER_ZAI_BASE_URL, p.zai?.baseUrl, "Z.AI base URL")
        }

        yamlConfig.limits?.let { l ->
            apply(KEY_API_CALL_TIMEOUT, l.apiCallTimeout?.toString(), "API call timeout")
            apply(KEY_TOOL_EXECUTION_TIMEOUT, l.toolExecutionTimeout?.toString(), "tool execution timeout")
            apply(KEY_MAX_CONTEXT_SIZE, l.maxContextSize?.toString(), "max context size")
            apply(KEY_MAX_OUTPUT_SIZE, l.maxOutputSize?.toString(), "max output size")
            apply(KEY_MAX_FILE_SIZE, l.maxFileSize?.toString(), "max file size")
        }

        return count
    }

    private fun resolveProjectId(projectId: String?): String? = projectId ?: defaultProjectId

    private fun taskScope(taskId: String?): ConfigScope =
        if (taskId != null) ConfigScope.TASK else ConfigScope.APP

    private fun invalidateConfigCache(key: String) {
        configCache.invalidateByPrefix("typed:$key:")
        configCache.invalidateByPrefix("raw:$key:")
        configCache.invalidate("yaml:$key")
    }

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
    /**
     * Check if task verification should run for current turn.
     * ADR 0019 P13: Auto-enable verification for longer turns (>5 iterations) to catch hallucinations.
     *
     * @param taskId Task ID (for config override)
     * @param iterationCount Current iteration count in turn
     * @return true if verification should run
     */
    fun shouldVerifyTask(taskId: String? = null, iterationCount: Int = 0, writeToolsExecutedInTurn: Int = 0): Boolean {
        val explicitSetting = getConfigWithPrecedence(KEY_TASK_VERIFICATION_ENABLED, taskId)
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

    /**
     * Data class for model configuration JSON storage.
     */
    private data class ModelConfigData(
        val modelId: String? = null,
        val provider: String? = null
    )
}
