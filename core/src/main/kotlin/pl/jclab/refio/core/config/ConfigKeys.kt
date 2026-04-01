package pl.jclab.refio.core.config

import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * Typed configuration key descriptor.
 *
 * Encapsulates the key string, default value, parsing/serialization logic,
 * and optional YAML accessor for hierarchical config lookup.
 *
 * @param T The value type for this configuration key
 * @param key The dot-notation configuration key string
 * @param parser Converts a stored String value to T, returning null on parse failure
 * @param default The fallback value when no configuration is found
 * @param serializer Converts T to a String for storage (defaults to toString())
 * @param yamlAccessor Optional function to read this key from YAML config hierarchy
 */
data class ConfigKey<T>(
    val key: String,
    val parser: (String) -> T?,
    val default: T,
    val serializer: (T) -> String = { it.toString() },
    val yamlAccessor: ((HierarchicalConfigLoader) -> Any?)? = null
)

/**
 * Central registry of all typed configuration keys.
 *
 * Each key definition consolidates:
 * - The string key constant
 * - The default value
 * - Type-safe parsing from String
 * - YAML accessor for hierarchical lookup
 *
 * Usage:
 * ```
 * val timeout = configService.getTyped(ConfigKeys.API_CALL_TIMEOUT)
 * configService.setTyped(ConfigKeys.API_CALL_TIMEOUT, 120)
 * ```
 */
object ConfigKeys {

    // ==================== GENERAL ====================

    val FORMAT_MARKDOWN = ConfigKey(
        key = "general.format_markdown",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getFormatMarkdown() }
    )

    val STREAMING_ENABLED = ConfigKey(
        key = "general.streaming_enabled",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getStreamingEnabled() }
    )

    val ADVANCED_VIEW = ConfigKey(
        key = "general.advanced_view",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getAdvancedView() }
    )

    // ==================== LIMITS ====================

    val API_CALL_TIMEOUT = ConfigKey(
        key = "limits.api_call_timeout",
        parser = String::toIntOrNull,
        default = 360,
        yamlAccessor = { it.getApiCallTimeout() }
    )

    val STREAMING_READ_TIMEOUT = ConfigKey(
        key = "limits.streaming_read_timeout_sec",
        parser = String::toIntOrNull,
        default = 360,
        yamlAccessor = { it.getStreamingReadTimeout() }
    )

    val STREAMING_REQUEST_TIMEOUT = ConfigKey(
        key = "limits.streaming_request_timeout_sec",
        parser = String::toIntOrNull,
        default = 1800,
        yamlAccessor = { it.getStreamingRequestTimeout() }
    )

    val TOOL_EXECUTION_TIMEOUT = ConfigKey(
        key = "limits.tool_execution_timeout",
        parser = String::toIntOrNull,
        default = 360,
        yamlAccessor = { it.getToolExecutionTimeout() }
    )

    val MAX_CONTEXT_SIZE = ConfigKey(
        key = "limits.max_context_size",
        parser = String::toIntOrNull,
        default = 128000,
        yamlAccessor = { it.getMaxContextSize() }
    )

    val MAX_OUTPUT_SIZE = ConfigKey(
        key = "limits.max_output_size",
        parser = String::toIntOrNull,
        default = 16384,
        yamlAccessor = { it.getMaxOutputSize() }
    )

    val MAX_FILE_SIZE = ConfigKey(
        key = "limits.max_file_size",
        parser = String::toIntOrNull,
        default = 10,
        yamlAccessor = { it.getMaxFileSize() }
    )

    val MAX_RETRIES = ConfigKey(
        key = "limits.max_retries",
        parser = String::toIntOrNull,
        default = 3
    )

    val RATE_LIMIT_RPM = ConfigKey(
        key = "limits.rate_limit_rpm",
        parser = String::toIntOrNull,
        default = 60
    )

    val RETRY_DELAY_MS = ConfigKey(
        key = "limits.retry_delay_ms",
        parser = String::toLongOrNull,
        default = 1000L
    )

    // ==================== ORCHESTRATION ====================

    val ORCHESTRATION_ENABLED = ConfigKey(
        key = "orchestration.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    // ==================== UI ====================

    val UI_THINKING_ENABLED = ConfigKey(
        key = "ui.thinking_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getUiThinkingEnabled() }
    )

    val UI_NO_EGRESS_ENABLED = ConfigKey(
        key = "ui.no_egress_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getUiNoEgressEnabled() }
    )

    val UI_ORCHESTRATION_ENABLED = ConfigKey(
        key = "ui.orchestration_enabled",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    val UI_INTENT_CLASSIFICATION_ENABLED = ConfigKey(
        key = "ui.intent_classification_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    val UI_EXECUTION_MODE = ConfigKey(
        key = "ui.execution_mode",
        parser = { it.trim().uppercase().takeIf { s -> s.isNotBlank() } },
        default = "AUTO",
        yamlAccessor = { it.getUiExecutionMode()?.trim()?.uppercase() }
    )

    val UI_SELECTED_MODE = ConfigKey(
        key = "ui.selected_mode",
        parser = { it.trim().uppercase().takeIf { s -> s.isNotBlank() } },
        default = "CHAT",
        yamlAccessor = { it.getUiSelectedMode()?.trim()?.uppercase() }
    )

    val UI_SELECTED_MODEL = ConfigKey<String?>(
        key = "ui.selected_model",
        parser = { it.trim().lowercase().takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getUiSelectedModel()?.trim()?.lowercase() }
    )

    // ==================== MODELS ====================

    val EMBEDDING_MODEL = ConfigKey(
        key = "models.embedding_model",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "ollama/nomic-embed-text",
        yamlAccessor = { it.getDefaultEmbeddingModel() }
    )

    val MODELS_VISIBILITY = ConfigKey(
        key = "models.visibility",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = ""
    )

    // ==================== DEFAULT MODELS ====================

    val DEFAULT_MODEL_CHAT = ConfigKey(
        key = "default_model.chat",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen2.5:7b"
    )

    val DEFAULT_MODEL_PLAN = ConfigKey(
        key = "default_model.plan",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen2.5:7b"
    )

    val DEFAULT_MODEL_AGENT = ConfigKey(
        key = "default_model.agent",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen2.5:7b"
    )

    val WEAK_MODEL = ConfigKey(
        key = "default_model.weak",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen2.5:7b"
    )

    // ==================== RAG ====================

    val RAG_ENABLED = ConfigKey(
        key = "rag.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getRagEnabled() }
    )

    val RAG_AUTO_INDEX_ON_CONTEXT = ConfigKey(
        key = "rag.auto_index_on_context_build",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getRagAutoIndexOnContextBuild() }
    )

    val RAG_INDEX_ON_STARTUP = ConfigKey(
        key = "rag.index_on_startup",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getRagIndexOnStartup() }
    )

    val RAG_MAX_FILE_SIZE_MB = ConfigKey(
        key = "rag.max_file_size_mb",
        parser = String::toLongOrNull,
        default = 2L,
        yamlAccessor = { it.getRagMaxFileSizeMB() }
    )

    val RAG_CACHE_TTL_MS = ConfigKey(
        key = "rag.cache_ttl_ms",
        parser = String::toLongOrNull,
        default = 300_000L,
        yamlAccessor = { it.getRagCacheTtlMs() }
    )

    val RAG_MAX_CONCURRENT_JOBS = ConfigKey(
        key = "rag.max_concurrent_jobs",
        parser = String::toIntOrNull,
        default = 4,
        yamlAccessor = { it.getRagMaxConcurrentJobs() }
    )

    val RAG_MAX_CHUNKS_PER_FILE = ConfigKey(
        key = "rag.max_chunks_per_file",
        parser = String::toIntOrNull,
        default = 100,
        yamlAccessor = { it.getRagMaxChunksPerFile() }
    )

    val RAG_INDEX_BATCH_SIZE = ConfigKey(
        key = "rag.index_batch_size",
        parser = String::toIntOrNull,
        default = 10,
        yamlAccessor = { it.getRagIndexBatchSize() }
    )

    val RAG_EMBEDDINGS_BATCH_SIZE = ConfigKey(
        key = "rag.embeddings_batch_size",
        parser = String::toIntOrNull,
        default = 50,
        yamlAccessor = { it.getRagEmbeddingsBatchSize() }
    )

    val RAG_EMBEDDING_CACHE_SIZE = ConfigKey(
        key = "rag.embedding_cache_size",
        parser = String::toIntOrNull,
        default = 2_000
    )

    val RAG_IGNORED_DIRECTORIES = ConfigKey(
        key = "rag.ignored_directories",
        parser = { raw ->
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        },
        default = listOf(
            ".git", ".idea", ".vscode", ".gradle", ".claude", ".continue",
            ".github", ".refio", ".codex", ".junie", ".husky", ".vscode",
            "node_modules", "build", "dist", "out", "target",
            "__pycache__", ".venv", "*.log", "*.tmp",
            "Agents.md", "CLAUDE.md", "GEMINI.md", ".gitignore", ".aiignore"
        ),
        serializer = { it.joinToString(",") },
        yamlAccessor = { it.getRagIgnoredDirectories()?.joinToString(",") }
    )

    val RAG_CHUNKING_MODE = ConfigKey(
        key = "rag.chunking_mode",
        parser = { it.trim().lowercase().takeIf { s -> s.isNotBlank() } },
        default = "semantic"
    )

    val RAG_SEARCH_SIMILARITY_THRESHOLD = ConfigKey(
        key = "rag.search_similarity_threshold",
        parser = String::toFloatOrNull,
        default = 0.5f,
        yamlAccessor = { it.getRagSearchSimilarityThreshold() }
    )

    val RAG_SEARCH_TOP_K = ConfigKey(
        key = "rag.search_top_k",
        parser = String::toIntOrNull,
        default = 5,
        yamlAccessor = { it.getRagSearchTopK() }
    )

    val RAG_SEARCH_CACHE_TTL_SECONDS = ConfigKey(
        key = "rag.search.cache_ttl_seconds",
        parser = String::toLongOrNull,
        default = 60L
    )

    val RAG_SEARCH_HYBRID_ENABLED = ConfigKey(
        key = "rag.search_hybrid_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getRagSearchHybridEnabled() }
    )

    val RAG_SEARCH_SEMANTIC_WEIGHT = ConfigKey(
        key = "rag.search_semantic_weight",
        parser = String::toFloatOrNull,
        default = 0.7f,
        yamlAccessor = { it.getRagSearchSemanticWeight() }
    )

    val RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS = ConfigKey(
        key = "rag.search_include_context_chunks",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getRagSearchIncludeContextChunks() }
    )

    // ==================== PROJECT ANALYSIS ====================

    val PROJECT_ANALYSIS_MAX_FILES = ConfigKey(
        key = "project_analysis.max_files",
        parser = String::toIntOrNull,
        default = 400
    )

    val PROJECT_ANALYSIS_FINGERPRINT_LIMIT = ConfigKey(
        key = "project_analysis.fingerprint_limit",
        parser = String::toIntOrNull,
        default = 2000
    )

    val PROJECT_ANALYSIS_CACHE_TTL_MS = ConfigKey(
        key = "project_analysis.cache_ttl_ms",
        parser = String::toLongOrNull,
        default = 600_000L
    )

    // ==================== ADVANCED ====================

    val AUTO_OPTIMIZE_PERCENTAGE = ConfigKey(
        key = "advanced.auto_optimize_percentage",
        parser = String::toIntOrNull,
        default = 85,
        yamlAccessor = { it.getAutoOptimizePercentage() }
    )

    val NO_EGRESS_DEFAULT = ConfigKey(
        key = "advanced.no_egress_default",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getNoEgressDefault() }
    )

    val READ_ONLY_MODE = ConfigKey(
        key = "advanced.read_only_mode",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getReadOnlyMode() }
    )

    // ==================== TOOLS ====================

    val TOOLS_PERMISSIONS = ConfigKey(
        key = "tools.permissions",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = ""
    )

    val TOOL_PERMISSION_RUN_TERMINAL = ConfigKey(
        key = "tools.permission_run_terminal_command",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    val TERMINAL_WHITELIST = ConfigKey(
        key = "terminal.whitelist",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "",
        yamlAccessor = { loader ->
            loader.getTerminalWhitelist()?.let {
                gson.toJson(it)
            }
        }
    )

    val TERMINAL_WHITELIST_ENABLED = ConfigKey(
        key = "terminal.whitelist.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getTerminalWhitelistEnabled() }
    )

    val TERMINAL_WHITELIST_MODE = ConfigKey(
        key = "terminal.whitelist.mode",
        parser = { it.trim().uppercase().takeIf { s -> s.isNotBlank() } },
        default = "WHITELIST_ONLY",
        yamlAccessor = { it.getTerminalWhitelistMode()?.trim()?.uppercase() }
    )

    // ==================== TOOL SUMMARY ====================

    val TOOL_SUMMARY_ENABLED = ConfigKey(
        key = "tool_summary.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    val TOOL_SUMMARY_MIN_LENGTH = ConfigKey(
        key = "tool_summary.min_length",
        parser = String::toIntOrNull,
        default = 500
    )

    // ==================== SECURITY ====================

    val SECURITY_ALLOW_SYMLINKS = ConfigKey(
        key = "security.allow_symlinks",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    // ==================== CONTEXT (ADR 0017) ====================

    val RECENT_WORK_FULL_DATA_LIMIT = ConfigKey(
        key = "context.recent_work.full_data_limit",
        parser = String::toIntOrNull,
        default = 5
    )

    val RECENT_WORK_SUMMARY_MAX_LENGTH = ConfigKey(
        key = "context.recent_work.summary_max_length",
        parser = String::toIntOrNull,
        default = 1000
    )

    val CONTEXT_BUDGET_TOTAL_TOKENS = ConfigKey(
        key = "context.budget.total_tokens",
        parser = String::toIntOrNull,
        default = 0
    )

    val CONTEXT_BUDGET_INPUT_RATIO = ConfigKey(
        key = "context.budget.input_ratio",
        parser = String::toDoubleOrNull,
        default = 0.85
    )

    val WORKING_MEMORY_MAX_FACTS = ConfigKey(
        key = "working_memory.max_facts",
        parser = String::toIntOrNull,
        default = 20
    )

    // ==================== SUBAGENTS ====================

    val SUBAGENTS_BUILTIN_ENABLED = ConfigKey(
        key = "subagents.builtin_enabled",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = ""
    )

    // ==================== PROVIDER ====================

    val PROVIDER_OLLAMA_ENDPOINT = ConfigKey(
        key = "providers.ollama.ollama_endpoint",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "http://localhost:11434",
        yamlAccessor = { it.getOllamaEndpoint() }
    )

    val PROVIDER_OLLAMA_CONTEXT_SIZE = ConfigKey(
        key = "providers.ollama.ollama_context_size",
        parser = String::toIntOrNull,
        default = 32768,
        yamlAccessor = { it.getOllamaContextSize() }
    )

    val PROVIDER_OLLAMA_KEEP_ALIVE = ConfigKey(
        key = "providers.ollama.ollama_keep_alive",
        parser = String::toIntOrNull,
        default = 1800,
        yamlAccessor = { it.getOllamaKeepAlive() }
    )

    val PROVIDER_ANTHROPIC_API_KEY = ConfigKey<String?>(
        key = "providers.anthropic.anthropic_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getAnthropicApiKey() }
    )

    val PROVIDER_OPENAI_API_KEY = ConfigKey<String?>(
        key = "providers.openai.openai_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getOpenAIApiKey() }
    )

    val PROVIDER_OPENROUTER_API_KEY = ConfigKey<String?>(
        key = "providers.openrouter.openrouter_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getOpenRouterApiKey() }
    )

    val PROVIDER_GEMINI_API_KEY = ConfigKey<String?>(
        key = "providers.gemini.gemini_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getGeminiApiKey() }
    )

    val PROVIDER_LM_STUDIO_API_KEY = ConfigKey<String?>(
        key = "providers.lmstudio.lmstudio_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getLMStudioApiKey() }
    )

    val PROVIDER_LM_STUDIO_BASE_URL = ConfigKey<String?>(
        key = "providers.lmstudio.lmstudio_base_url",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getLMStudioBaseUrl() }
    )

    val PROVIDER_LM_STUDIO_CONTEXT_SIZE = ConfigKey(
        key = "providers.lmstudio.lmstudio_context_size",
        parser = String::toIntOrNull,
        default = 32768,
        yamlAccessor = { it.getLMStudioContextSize() }
    )

    val PROVIDER_CUSTOM_OPENAI_API_KEY = ConfigKey<String?>(
        key = "providers.custom_openai.custom_openai_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getCustomOpenAIApiKey() }
    )

    val PROVIDER_CUSTOM_OPENAI_BASE_URL = ConfigKey<String?>(
        key = "providers.custom_openai.custom_openai_base_url",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getCustomOpenAIBaseUrl() }
    )

    val PROVIDER_CUSTOM_OPENAI_MODEL = ConfigKey<String?>(
        key = "providers.custom_openai.custom_openai_model",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getCustomOpenAIModel() }
    )

    val PROVIDER_ZAI_API_KEY = ConfigKey<String?>(
        key = "providers.zai.zai_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getZAIApiKey() }
    )

    val PROVIDER_ZAI_BASE_URL = ConfigKey<String?>(
        key = "providers.zai.zai_base_url",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getZAIBaseUrl() }
    )

    // ==================== AGENT FLOW (ADR 0019) ====================

    val TASK_VERIFICATION_ENABLED = ConfigKey(
        key = "agent.task_verification_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    val MAX_CONSECUTIVE_TOOL_ERRORS = ConfigKey(
        key = "agent.max_consecutive_tool_errors",
        parser = String::toIntOrNull,
        default = 3
    )

    val MAX_ITERATIONS = ConfigKey(
        key = "agent.max_iterations",
        parser = String::toIntOrNull,
        default = 50
    )

    val JSON_THINKING_XML_TAGS = ConfigKey(
        key = "agent.json_thinking_xml_tags",
        parser = { raw ->
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        },
        default = listOf("thinking", "think"),
        serializer = { it.joinToString(",") }
    )

    /**
     * Lookup a [ConfigKey] by its string key.
     *
     * @param key The dot-notation configuration key (e.g., "limits.api_call_timeout")
     * @return The matching [ConfigKey], or null if no typed key is registered for this string
     */
    fun byKey(key: String): ConfigKey<*>? = keyMap[key]

    /** All registered config keys, for iteration and discovery. */
    fun allKeys(): Collection<ConfigKey<*>> = keyMap.values

    private val keyMap: Map<String, ConfigKey<*>> by lazy {
        listOf(
            // General
            FORMAT_MARKDOWN,
            STREAMING_ENABLED,
            ADVANCED_VIEW,
            // Limits
            API_CALL_TIMEOUT,
            STREAMING_READ_TIMEOUT,
            STREAMING_REQUEST_TIMEOUT,
            TOOL_EXECUTION_TIMEOUT,
            MAX_CONTEXT_SIZE,
            MAX_OUTPUT_SIZE,
            MAX_FILE_SIZE,
            MAX_RETRIES,
            RATE_LIMIT_RPM,
            RETRY_DELAY_MS,
            // Orchestration
            ORCHESTRATION_ENABLED,
            // UI
            UI_THINKING_ENABLED,
            UI_NO_EGRESS_ENABLED,
            UI_ORCHESTRATION_ENABLED,
            UI_INTENT_CLASSIFICATION_ENABLED,
            UI_EXECUTION_MODE,
            UI_SELECTED_MODE,
            UI_SELECTED_MODEL,
            // Models
            EMBEDDING_MODEL,
            MODELS_VISIBILITY,
            DEFAULT_MODEL_CHAT,
            DEFAULT_MODEL_PLAN,
            DEFAULT_MODEL_AGENT,
            WEAK_MODEL,
            // RAG
            RAG_ENABLED,
            RAG_AUTO_INDEX_ON_CONTEXT,
            RAG_INDEX_ON_STARTUP,
            RAG_MAX_FILE_SIZE_MB,
            RAG_CACHE_TTL_MS,
            RAG_MAX_CONCURRENT_JOBS,
            RAG_MAX_CHUNKS_PER_FILE,
            RAG_INDEX_BATCH_SIZE,
            RAG_EMBEDDINGS_BATCH_SIZE,
            RAG_EMBEDDING_CACHE_SIZE,
            RAG_IGNORED_DIRECTORIES,
            RAG_CHUNKING_MODE,
            RAG_SEARCH_SIMILARITY_THRESHOLD,
            RAG_SEARCH_TOP_K,
            RAG_SEARCH_CACHE_TTL_SECONDS,
            RAG_SEARCH_HYBRID_ENABLED,
            RAG_SEARCH_SEMANTIC_WEIGHT,
            RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS,
            // Project analysis
            PROJECT_ANALYSIS_MAX_FILES,
            PROJECT_ANALYSIS_FINGERPRINT_LIMIT,
            PROJECT_ANALYSIS_CACHE_TTL_MS,
            // Advanced
            AUTO_OPTIMIZE_PERCENTAGE,
            NO_EGRESS_DEFAULT,
            READ_ONLY_MODE,
            // Tools
            TOOLS_PERMISSIONS,
            TOOL_PERMISSION_RUN_TERMINAL,
            TERMINAL_WHITELIST,
            TERMINAL_WHITELIST_ENABLED,
            TERMINAL_WHITELIST_MODE,
            // Tool summary
            TOOL_SUMMARY_ENABLED,
            TOOL_SUMMARY_MIN_LENGTH,
            // Security
            SECURITY_ALLOW_SYMLINKS,
            // Context
            RECENT_WORK_FULL_DATA_LIMIT,
            RECENT_WORK_SUMMARY_MAX_LENGTH,
            CONTEXT_BUDGET_TOTAL_TOKENS,
            CONTEXT_BUDGET_INPUT_RATIO,
            WORKING_MEMORY_MAX_FACTS,
            // Subagents
            SUBAGENTS_BUILTIN_ENABLED,
            // Provider
            PROVIDER_OLLAMA_ENDPOINT,
            PROVIDER_OLLAMA_CONTEXT_SIZE,
            PROVIDER_OLLAMA_KEEP_ALIVE,
            PROVIDER_ANTHROPIC_API_KEY,
            PROVIDER_OPENAI_API_KEY,
            PROVIDER_OPENROUTER_API_KEY,
            PROVIDER_GEMINI_API_KEY,
            PROVIDER_LM_STUDIO_API_KEY,
            PROVIDER_LM_STUDIO_BASE_URL,
            PROVIDER_LM_STUDIO_CONTEXT_SIZE,
            PROVIDER_CUSTOM_OPENAI_API_KEY,
            PROVIDER_CUSTOM_OPENAI_BASE_URL,
            PROVIDER_CUSTOM_OPENAI_MODEL,
            PROVIDER_ZAI_API_KEY,
            PROVIDER_ZAI_BASE_URL,
            // Agent flow
            TASK_VERIFICATION_ENABLED,
            MAX_CONSECUTIVE_TOOL_ERRORS,
            MAX_ITERATIONS,
            JSON_THINKING_XML_TAGS
        ).associateBy { it.key }
    }
}
