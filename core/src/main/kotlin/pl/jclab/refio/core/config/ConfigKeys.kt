package pl.jclab.refio.core.config

import pl.jclab.refio.core.llm.ReasoningEffort


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
    val yamlAccessor: ((HierarchicalConfigLoader) -> Any?)? = null,
    /**
     * Optional validator invoked by [pl.jclab.refio.core.services.ConfigValidator] at startup
     * and after YAML reloads. Return `false` to reject a value — the startup then fails loud
     * with the offending key/value, rather than silently using a broken config.
     */
    val validator: (T) -> Boolean = { true }
) {
    /**
     * True when [raw] both parses to a valid value AND passes this key's [validator].
     * Lets run-scope override parsing reject bad `--config` values loudly without
     * exposing the generic type `T` to callers holding a `ConfigKey<*>`.
     */
    fun acceptsRaw(raw: String): Boolean {
        val parsed = parser(raw) ?: return false
        return validator(parsed)
    }
}

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
        yamlAccessor = { it.getApiCallTimeout() },
        validator = { it > 0 }
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
        yamlAccessor = { it.getMaxContextSize() },
        validator = { it in 1024..4_194_304 }
    )

    val MAX_OUTPUT_SIZE = ConfigKey(
        key = "limits.max_output_size",
        parser = String::toIntOrNull,
        default = 16384,
        yamlAccessor = { it.getMaxOutputSize() },
        validator = { it > 0 }
    )

    /**
     * Hard ceiling, in CHARACTERS, on a single streamed response before the size guardrail aborts
     * it. Distinct from [MAX_OUTPUT_SIZE], which is a token budget handed to the provider: this one
     * is the runaway-decoder brake applied to what actually arrives.
     *
     * `0` (the default) means derive it from the model's context window - see
     * [pl.jclab.refio.core.llm.streaming.OutputSizeLimiter.ceilingForContext]. A fixed number cannot
     * fit every model: it has to sit above the largest legitimate single response, and how large
     * that is depends on the window. Set a non-zero value only to pin the ceiling explicitly.
     */
    val MAX_OUTPUT_CHARS = ConfigKey(
        key = "limits.max_output_chars",
        parser = String::toIntOrNull,
        default = 0,
        validator = { it == 0 || it in 4_096..8_388_608 }
    )

    val MAX_FILE_SIZE = ConfigKey(
        key = "limits.max_file_size",
        parser = String::toIntOrNull,
        default = 10,
        yamlAccessor = { it.getMaxFileSize() },
        validator = { it > 0 }
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

    // ==================== UI ====================

    /**
     * Reasoning strength (OFF/LOW/MEDIUM/HIGH). Replaces the legacy boolean
     * `general.thinking_enabled`; OFF is the former "thinking off". Adapters translate the
     * level to their native knob (reasoning effort or token budget). See [ReasoningEffort].
     */
    val GENERAL_REASONING_EFFORT = ConfigKey(
        key = "general.reasoning_effort",
        parser = ReasoningEffort::parse,
        default = ReasoningEffort.OFF,
        serializer = { it.name },
        validator = { it in ReasoningEffort.entries },
        yamlAccessor = { it.getGeneralReasoningEffort() }
    )

    val GENERAL_NO_EGRESS_ENABLED = ConfigKey(
        key = "general.no_egress_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getGeneralNoEgressEnabled() }
    )

    /**
     * How much isolation a spawned process must have: `auto`, `required` or `off`.
     *
     * `auto` (default) runs on the host and says once per session that nothing is being enforced.
     * `required` refuses to run code the model wrote itself when no backend can contain it; the
     * user's own build, tests and configured MCP servers still run, because the point of those is
     * to exercise the real project with the real toolchain. `off` is the old behaviour, silently.
     *
     * Without `required` this setting is a convenience rather than a control: someone who actually
     * depends on it needs a mode that fails closed.
     */
    val GENERAL_EXECUTION_ISOLATION = ConfigKey(
        key = "general.execution_isolation",
        parser = { it.trim().lowercase().takeIf { v -> v in setOf("auto", "required", "off") } },
        default = "auto"
    )

    /**
     * AGENT-only "is the agent done?" judge. When enabled, a cheap LLM call (WEAK model)
     * confirms that a tool-call-free assistant response really means "task complete"
     * before the turn terminates. If the judge says the agent paused mid-task, the loop
     * is re-entered with a brief SYSTEM nudge. See [NextSpeakerJudgeGuardian].
     *
     * Default `true`: the cost is bounded (≤ 1 + maxReentries judge calls per user
     * message, only at the terminal point of a turn) and the benefit on weak / mid-tier
     * models is significant. Disable on cost-sensitive deployments using only top-tier
     * models that rarely stop mid-task.
     */
    val GENERAL_NEXT_SPEAKER_JUDGE_ENABLED = ConfigKey(
        key = "general.next_speaker_judge_enabled",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    /**
     * When the next-speaker judge decides a turn stalled mid-task, allow more than the single
     * bounded re-entry while the turn is still early and NOTHING has been delivered yet. Weak
     * local models (qwen3.5) frequently abandon a task on iteration 1-3 of 20 by announcing
     * intent without a tool call; a single nudge is often not enough, and the second re-entry
     * escalates to a nudge that re-includes the JSON envelope schema. Bounded by
     * [pl.jclab.refio.core.services.turn.NextSpeakerJudgeGuardian.MAX_EARLY_JUDGE_REENTRIES] and
     * the early-iteration window, so a turn that has burned its iteration budget still stops.
     * Default `true`; disable to restore the strict one-shot re-entry.
     */
    val GENERAL_JUDGE_EXTENDED_REENTRY_ENABLED = ConfigKey(
        key = "general.judge_extended_reentry_enabled",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    const val JUDGE_BACKEND_LLM = "llm"
    const val JUDGE_BACKEND_DECISION = "decision"
    const val JUDGE_BACKEND_HYBRID = "hybrid"
    val JUDGE_BACKENDS = listOf(JUDGE_BACKEND_LLM, JUDGE_BACKEND_DECISION, JUDGE_BACKEND_HYBRID)

    /**
     * What answers the "is the agent done?" question of
     * [pl.jclab.refio.core.services.turn.NextSpeakerJudgeGuardian]:
     * - `llm` (default): the WEAK chat model with the full rule prompt.
     * - `decision`: the model in [DECISION_MODEL] answers three yes/no questions (missing
     *   deliverable, intent without action, claim without evidence) in about a second. The LLM judge
     *   takes over when no decision model is configured or the call fails, so the safety net stays.
     * - `hybrid`: the decision model first; the LLM judge is asked only when the decision model's
     *   "continue" probability falls inside [GENERAL_JUDGE_HYBRID_BAND_LOW]..[GENERAL_JUDGE_HYBRID_BAND_HIGH].
     * The goal-aware judge (`/goal`) always uses the LLM: a free-form condition cannot be turned
     * into fixed yes/no questions.
     */
    val GENERAL_JUDGE_BACKEND = ConfigKey(
        key = "general.judge_backend",
        parser = { raw -> raw.trim().lowercase().takeIf { it in JUDGE_BACKENDS } },
        default = JUDGE_BACKEND_LLM
    )

    /**
     * "Continue" probability at or above which the decision-model judge sends the agent back to
     * work. Decision models are calibrated differently, so this is per installation, not a constant:
     * on the same cases `nimble:9b` separated done/not-done at almost any value while `tev1:4b`
     * put finished turns around 0.5.
     */
    val GENERAL_JUDGE_DECISION_THRESHOLD = ConfigKey(
        key = "general.judge_decision_threshold",
        parser = String::toDoubleOrNull,
        default = 0.5,
        validator = { it in 0.0..1.0 }
    )

    /** Lower edge of the hybrid judge's "unsure" band (inclusive), on the "continue" probability. */
    val GENERAL_JUDGE_HYBRID_BAND_LOW = ConfigKey(
        key = "general.judge_hybrid_band_low",
        parser = String::toDoubleOrNull,
        default = 0.15,
        validator = { it in 0.0..1.0 }
    )

    /** Upper edge of the hybrid judge's "unsure" band (inclusive), on the "continue" probability. */
    val GENERAL_JUDGE_HYBRID_BAND_HIGH = ConfigKey(
        key = "general.judge_hybrid_band_high",
        parser = String::toDoubleOrNull,
        default = 0.85,
        validator = { it in 0.0..1.0 }
    )

    val UI_INTENT_CLASSIFICATION_ENABLED = ConfigKey(
        key = "ui.intent_classification_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    // ==================== TOOLS ====================

    /**
     * Native function-calling mode.
     * - "auto"  (default): use native tools iff ModelDefinition.supportsFunctionCalling=true
     * - "always": force native tools even on unlisted models (debug / opt-in)
     * - "never" : force JSON-in-text path (escape hatch)
     */
    val NATIVE_TOOLS_MODE = ConfigKey(
        key = "tools.native_tools",
        parser = { raw -> raw.trim().lowercase().takeIf { it in setOf("auto", "always", "never") } },
        default = "auto",
        validator = { it in setOf("auto", "always", "never") },
        yamlAccessor = { it.getNativeToolsMode()?.trim()?.lowercase() }
    )

    val GENERAL_EXECUTION_MODE = ConfigKey(
        key = "general.execution_mode",
        parser = { it.trim().uppercase().takeIf { s -> s.isNotBlank() } },
        default = "AUTO",
        yamlAccessor = { it.getGeneralExecutionMode()?.trim()?.uppercase() }
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

    /**
     * Persistent list of model IDs that have failed native function-calling.
     * Stored as a comma-separated string (provider-prefixed model id, e.g.
     * `openrouter/amazon/nova-lite-v1,zai/glm-4.7`). Loaded into the
     * [pl.jclab.refio.core.llm.NativeToolsFallbackTracker] on startup so users do
     * not pay the 2-nudge native-tools probe cost on every fresh session.
     *
     * Cleared by switching providers or by user action via UI/CLI.
     */
    val MODELS_NATIVE_TOOLS_FALLBACKS = ConfigKey(
        key = "models.native_tools_fallbacks",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = ""
    )

    // ==================== DEFAULT MODELS ====================

    val DEFAULT_MODEL_CHAT = ConfigKey(
        key = "default_model.chat",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen3.5:9b"
    )

    val DEFAULT_MODEL_PLAN = ConfigKey(
        key = "default_model.plan",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen3.5:9b"
    )

    val DEFAULT_MODEL_AGENT = ConfigKey(
        key = "default_model.agent",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen3.5:9b"
    )

    val WEAK_MODEL = ConfigKey(
        key = "default_model.weak",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = "qwen3.5:9b"
    )

    val STRONG_MODEL = ConfigKey<String?>(
        key = "default_model.strong",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null
    )

    /**
     * Decision model for the completion judge (see [GENERAL_JUDGE_BACKEND]). Optional with no
     * fallback: a chat model cannot answer the decision API. Stored like the other slots, as
     * `{"modelId","provider"}`; YAML `models.defaults.decision` takes `provider/model`.
     */
    val DECISION_MODEL = ConfigKey<String?>(
        key = "default_model.decision",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        yamlAccessor = { it.getDefaultDecisionModel() }
    )

    val OLLAMA_MAX_CONCURRENT = ConfigKey(
        key = "providers.ollama_max_concurrent",
        parser = String::toIntOrNull,
        default = 1
    )

    /**
     * Max simultaneous outbound LLM requests to a single cloud provider (OpenRouter, OpenAI, ...).
     * Bounds the burst when many subagents call the same provider at once so we don't overrun its
     * rate limit. Does not apply to `ollama` (self-throttled per endpoint via [OLLAMA_MAX_CONCURRENT]).
     */
    val PROVIDER_MAX_CONCURRENT = ConfigKey(
        key = "providers.max_concurrent",
        parser = String::toIntOrNull,
        default = 4
    )

    // ==================== RAG ====================

    val RAG_ENABLED = ConfigKey(
        key = "rag.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getRagEnabled() }
    )

    // Agentic grep/file search is the primary navigation path; vector RAG is an
    // opt-in aid (proven 2025–2026: keyword search reaches ~RAG quality on *code* without the
    // indexing tax). Default OFF so a cold start never pays CPU + embedding cost for users who
    // never use rag_search. Enable explicitly to restore the old auto-indexing behaviour.
    val RAG_AUTO_INDEX_ON_CONTEXT = ConfigKey(
        key = "rag.auto_index_on_context_build",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getRagAutoIndexOnContextBuild() }
    )

    val RAG_INDEX_ON_STARTUP = ConfigKey(
        key = "rag.index_on_startup",
        parser = String::toBooleanStrictOrNull,
        default = false,
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
        // 0.5 → 0.65 → 0.75: auto-RAG injection at the start of every turn was still
        // returning marginal matches that bloated the prompt before the agent had any
        // signal about relevance. The on-demand `rag_search` tool is the right place
        // for broad searches (it can lower the threshold per call). For automatic
        // context loading we keep only high-confidence hits.
        default = 0.75f,
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
        yamlAccessor = { it.getRagSearchSemanticWeight() },
        validator = { it in 0f..1f }
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

    /**
     * Opt-in to let outbound tools (http_request, fetch_webpage) reach loopback / private / local
     * addresses. Default false: UrlPolicy blocks them as SSRF protection. Turned on only for trusted
     * local setups - e.g. the e2e harness serving a deterministic fixture from 127.0.0.1. Governs
     * every UrlPolicy-checked tool uniformly so the two web tools can never diverge.
     */
    val SECURITY_ALLOW_LOOPBACK = ConfigKey(
        key = "security.allow_loopback",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    // ==================== CONTEXT ====================

    val RECENT_WORK_FULL_DATA_LIMIT = ConfigKey(
        key = "context.recent_work.full_data_limit",
        parser = String::toIntOrNull,
        default = 5,
        yamlAccessor = { it.getRecentWorkFullDataLimit() }
    )

    val RECENT_WORK_SUMMARY_MAX_LENGTH = ConfigKey(
        key = "context.recent_work.summary_max_length",
        parser = String::toIntOrNull,
        default = 1000,
        yamlAccessor = { it.getRecentWorkSummaryMaxLength() }
    )

    val CONTEXT_BUDGET_TOTAL_TOKENS = ConfigKey(
        key = "context.budget.total_tokens",
        parser = String::toIntOrNull,
        default = 0,
        yamlAccessor = { it.getContextBudgetTotalTokens() }
    )

    val CONTEXT_BUDGET_INPUT_RATIO = ConfigKey(
        key = "context.budget.input_ratio",
        parser = String::toDoubleOrNull,
        default = 0.85,
        yamlAccessor = { it.getContextBudgetInputRatio() },
        validator = { it > 0.0 && it <= 1.0 }
    )

    val WORKING_MEMORY_MAX_FACTS = ConfigKey(
        key = "working_memory.max_facts",
        parser = String::toIntOrNull,
        default = 20,
        yamlAccessor = { it.getWorkingMemoryMaxFacts() },
        // Zero or less would silently discard every fact the agent extracts.
        validator = { it > 0 }
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
        key = "providers.generic_openai.generic_openai_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getGenericOpenAIApiKey() }
    )

    val PROVIDER_CUSTOM_OPENAI_BASE_URL = ConfigKey<String?>(
        key = "providers.generic_openai.generic_openai_base_url",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getGenericOpenAIBaseUrl() }
    )

    val PROVIDER_CUSTOM_OPENAI_MODEL = ConfigKey<String?>(
        key = "providers.generic_openai.generic_openai_model",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getGenericOpenAIModel() }
    )

    /**
     * Context window the user declares for their OpenAI-compatible server.
     *
     * Needed because `/v1/models` on servers like llama.cpp does not report `context_length`,
     * so discovery cannot tell how large the window really is. Same role as the Ollama and
     * LM Studio overrides, and deliberately without a validator so a server running a very
     * large window can be declared as-is.
     */
    val PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE = ConfigKey(
        key = "providers.generic_openai.generic_openai_context_size",
        parser = String::toIntOrNull,
        default = 32768,
        yamlAccessor = { it.getGenericOpenAIContextSize() }
    )

    /**
     * Send the chat request without any sampling parameters of ours, for servers that already
     * pin generation settings server-side.
     *
     * Omits `temperature`, `max_tokens` and the non-standard `request_id`. Deliberately keeps
     * `stream`/`stream_options` (dropping them loses real token accounting) and `tools`
     * (dropping them breaks AGENT mode). Applies to `generic_openai` only, not to providers
     * that reuse the same adapter.
     */
    val PROVIDER_CUSTOM_OPENAI_RAW_REQUEST = ConfigKey(
        key = "providers.generic_openai.generic_openai_raw_request",
        parser = String::toBooleanStrictOrNull,
        default = false,
        yamlAccessor = { it.getGenericOpenAIRawRequest() }
    )

    /**
     * Base URL of an OpenAI-compatible embeddings endpoint, e.g. a second llama.cpp instance
     * serving an embedding model. Selected with `models.embedding_model:
     * openai_compatible/<model>`.
     *
     * Separate from the chat provider's base URL on purpose: embeddings commonly run as their
     * own process on a different port.
     */
    val PROVIDER_EMBEDDINGS_BASE_URL = ConfigKey<String?>(
        key = "providers.embeddings.embeddings_base_url",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getEmbeddingsBaseUrl() }
    )

    val PROVIDER_EMBEDDINGS_API_KEY = ConfigKey<String?>(
        key = "providers.embeddings.embeddings_api_key",
        parser = { it.takeIf { s -> s.isNotBlank() } },
        default = null,
        serializer = { it ?: "" },
        yamlAccessor = { it.getEmbeddingsApiKey() }
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

    // ==================== AGENT FLOW ====================

    val TASK_VERIFICATION_ENABLED = ConfigKey(
        key = "agent.task_verification_enabled",
        parser = String::toBooleanStrictOrNull,
        default = false
    )

    /**
     * Maximum consecutive failures of the SAME tool call (same tool name + identical arguments)
     * before the turn loop aborts with a "definitive loop" error.
     *
     * Only identical-argument retries count. If the agent varies its arguments or switches
     * tools, the counter resets — the agent is still exploring. This replaces the older
     * "any error" counter, which was too aggressive for tasks involving trial-and-error
     * API interactions (e.g. CTF tasks where remote endpoints return 4xx on wrong answers).
     *
     * The sliding-window error-rate guard (70% failure rate) remains as a secondary safety net.
     */
    val MAX_CONSECUTIVE_TOOL_ERRORS = ConfigKey(
        key = "agent.max_consecutive_tool_errors",
        parser = String::toIntOrNull,
        default = 5
    )

    /**
     * Per-session hard cost ceiling in USD. `0.0` (default) disables the guard. When > 0, the turn
     * loop aborts before an iteration once the session's live cost reaches this value.
     * Surfaced via the CLI `--max-cost` flag (sugar over a run-scope override).
     */
    val AGENT_MAX_COST_USD = ConfigKey(
        key = "agent.max_cost_usd",
        parser = String::toDoubleOrNull,
        default = 0.0,
        validator = { it >= 0.0 }
    )

    /**
     * Per-turn wall-clock ceiling in minutes. `0` disables it.
     *
     * Bounds a turn stuck waiting rather than generating (a model that
     * never answers, a tool blocked on IO). Gemini CLI pairs its subagent turn cap with the same
     * kind of time bound for this reason.
     *
     * 45, not 30: a local 35B-class model building a whole application needs more steps than a
     * hosted one and pays ~80s per step, so 30 minutes cut off turns that were making steady
     * progress rather than stuck (e2e pixel-plumber: 23 productive model calls, killed at the
     * ceiling). The point of the bound is to catch a turn that is waiting, not one that is slow.
     */
    val AGENT_MAX_TURN_MINUTES = ConfigKey(
        key = "agent.max_turn_minutes",
        parser = String::toLongOrNull,
        default = 45L,
        validator = { it >= 0L }
    )

    /**
     * When a turn stops on a limit or a guardrail (iterations, time, repeated errors, a loop), ask
     * the model for a short note on what is done and what is left and store it in the task, so the
     * next turn or the next round of a long task starts from it instead of from scratch.
     * Not written after the cost ceiling: the note would be one more paid call over it.
     */
    val AGENT_HANDOFF_NOTE = ConfigKey(
        key = "agent.handoff_note",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    /** How long the handoff note may take before the turn ends without it. */
    val AGENT_HANDOFF_NOTE_TIMEOUT_SECONDS = ConfigKey(
        key = "agent.handoff_note_timeout_seconds",
        parser = String::toIntOrNull,
        default = 120,
        validator = { it > 0 }
    )

    /** Default round limit of a long task (`/until`, `/long`, `--until`); `/long rounds N` overrides it per task. */
    val LONG_TASK_MAX_ROUNDS = ConfigKey(
        key = "long_task.max_rounds",
        parser = String::toIntOrNull,
        default = 12,
        validator = { it in 1..1_000 }
    )

    /**
     * Default limit of working time of a long task, in minutes; `/long hours H` overrides it per task.
     * Only time spent in rounds counts, not time with the application closed. 0 turns the limit off.
     */
    val LONG_TASK_MAX_MINUTES = ConfigKey(
        key = "long_task.max_minutes",
        parser = String::toLongOrNull,
        default = 240L,
        validator = { it >= 0 }
    )

    /** A long task ends after this many rounds in a row with no file written, no plan step done and no fewer errors. */
    val LONG_TASK_MAX_NO_PROGRESS_ROUNDS = ConfigKey(
        key = "long_task.max_no_progress_rounds",
        parser = String::toIntOrNull,
        default = 3,
        validator = { it > 0 }
    )

    /** How long the `/until` command may run after a round before it is killed and the round counts as failed. */
    val LONG_TASK_CHECK_TIMEOUT_SECONDS = ConfigKey(
        key = "long_task.check_timeout_seconds",
        parser = String::toIntOrNull,
        default = 600,
        validator = { it > 0 }
    )

    /** Write `.refio/progress.md` in the project after every round of a long task. */
    val LONG_TASK_PROGRESS_FILE = ConfigKey(
        key = "long_task.progress_file",
        parser = String::toBooleanStrictOrNull,
        default = true
    )

    /**
     * How long, in seconds counted from the first failure (the failed attempts included), the
     * agent's model call is retried after a passing failure (connection refused, 5xx, 429), with pauses of 2, 4, 8, 16 and then 30 seconds.
     * 0 turns it off: the call then retries `limits.max_retries` times from `limits.retry_delay_ms`.
     */
    val LLM_RETRY_MAX_SECONDS = ConfigKey(
        key = "llm.retry_max_seconds",
        parser = String::toLongOrNull,
        default = 300L,
        validator = { it >= 0 }
    )

    /**
     * Days to keep API call logs, agent events and rolled CLI log files. Older ones are deleted at
     * start and then every 12 hours. 0 keeps everything.
     */
    val LOGS_RETENTION_DAYS = ConfigKey(
        key = "logs.retention_days",
        parser = String::toIntOrNull,
        default = 7,
        validator = { it >= 0 }
    )

    /**
     * How many times the loop may ask the model to answer in the required shape before giving up.
     * `0` gives up on the first malformed answer.
     *
     * The budget used to be the constant 2, on the reasoning that a model which cannot comply after
     * two reminders never will. A measured counter-example: a reasoning model that announces its
     * plan in prose before acting burned its two reminders on iterations 2 and 3 and the turn ended
     * at iteration 4 of an available 200. The default stays 2 so nothing changes without a decision;
     * what changed is that the budget doubles while the turn has produced nothing, because giving
     * up on a turn that already delivered a file costs some wasted time, and giving up on one that
     * delivered nothing costs the whole task.
     */
    val AGENT_MAX_FORMAT_NUDGES = ConfigKey(
        key = "agent.max_format_nudges",
        parser = String::toIntOrNull,
        default = 2,
        validator = { it >= 0 }
    )

    /**
     * Sampling temperature for the PLAN/AGENT decision turn — the turn that picks a tool and
     * emits the response-format envelope. Defaults to `0.7` (the LLM-wide default).
     *
     * Lowering it (e.g. `0.5`) sharpens the softmax so small/local models are less likely to
     * deviate from the response contract (dumping a whole file inline, narrating instead of
     * emitting JSON, malformed envelopes). Raising it gives more variety at the cost of more
     * contract drift. The creative editing sub-call (`advance_code_editing`) keeps its own lower
     * temperature and is unaffected by this key.
     */
    val AGENT_DECISION_TEMPERATURE = ConfigKey(
        key = "agent.decision_temperature",
        parser = String::toDoubleOrNull,
        default = 0.7,
        validator = { it in 0.0..2.0 }
    )

    const val PROMPT_LAYOUT_SYSTEM = "system"
    const val PROMPT_LAYOUT_TRAILING = "trailing"

    /**
     * Where the decision prompt of a PLAN/AGENT turn, the main agent's or a subagent's, carries the
     * per-iteration work state.
     * - "system": WORKING_MEMORY, RECENT_WORK and CONVERSATION_HISTORY sit inside the
     *   system prompt's `<context>`, after the task requirements.
     * - "trailing" (default): CONVERSATION_HISTORY is not built (the message list already carries the same
     *   conversation), and WORKING_MEMORY, RECENT_WORK plus a copy of the task requirements go into a
     *   final user message wrapped in `<system-reminder>`, rebuilt every iteration and never stored.
     *
     * In the system layout the work state changes in the middle of the request every iteration, so a
     * server-side prefix cache (Ollama) re-reads everything after it, the whole conversation included.
     * In the trailing layout the system prompt and the earlier messages stay byte-identical between
     * iterations and only the tail is re-read; the state also sits where models attend most, at the end.
     * A subagent's context profile still trims the final message as it trims the system prompt.
     */
    val AGENT_PROMPT_LAYOUT = ConfigKey(
        key = "agent.prompt_layout",
        parser = { raw -> raw.trim().lowercase().takeIf { it in setOf(PROMPT_LAYOUT_SYSTEM, PROMPT_LAYOUT_TRAILING) } },
        default = PROMPT_LAYOUT_TRAILING,
        validator = { it in setOf(PROMPT_LAYOUT_SYSTEM, PROMPT_LAYOUT_TRAILING) }
    )

    // ==================== VERIFY ====================

    /**
     * Master switch for the deterministic post-turn verification step: after an AGENT turn that
     * wrote files completes, the loop code runs the project's build/test command and feeds
     * failures back to the model as a concrete error list.
     */
    val VERIFY_ENABLED = ConfigKey(
        key = "verify.enabled",
        parser = String::toBooleanStrictOrNull,
        default = true,
        yamlAccessor = { it.getVerifyEnabled() }
    )

    /**
     * The verification command (run through the shell in the project root). Empty (default)
     * means autodetect from project marker files: build.gradle* -> "./gradlew build -q",
     * package.json -> "npm test --silent", Cargo.toml -> "cargo build -q"; when no marker is
     * found verification is skipped.
     */
    val VERIFY_COMMAND = ConfigKey(
        key = "verify.command",
        parser = { it },
        default = "",
        yamlAccessor = { it.getVerifyCommand() }
    )

    /**
     * Maximum repair rounds after a failed verification (fail -> error list to the model ->
     * re-verify). After exhaustion the turn ends as VERIFICATION_FAILED instead of faking success.
     */
    val VERIFY_MAX_REPAIR_ROUNDS = ConfigKey(
        key = "verify.max_repair_rounds",
        parser = String::toIntOrNull,
        default = 2,
        yamlAccessor = { it.getVerifyMaxRepairRounds() },
        validator = { it >= 0 }
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
            MAX_OUTPUT_CHARS,
            MAX_FILE_SIZE,
            MAX_RETRIES,
            RATE_LIMIT_RPM,
            RETRY_DELAY_MS,
            // General (thinking/no-egress/execution mode moved from ui section)
            GENERAL_REASONING_EFFORT,
            GENERAL_NO_EGRESS_ENABLED,
            GENERAL_EXECUTION_ISOLATION,
            GENERAL_EXECUTION_MODE,
            GENERAL_JUDGE_BACKEND,
            GENERAL_JUDGE_DECISION_THRESHOLD,
            GENERAL_JUDGE_HYBRID_BAND_LOW,
            GENERAL_JUDGE_HYBRID_BAND_HIGH,
            // UI
            UI_INTENT_CLASSIFICATION_ENABLED,
            UI_SELECTED_MODE,
            UI_SELECTED_MODEL,
            // Models
            EMBEDDING_MODEL,
            MODELS_VISIBILITY,
            DEFAULT_MODEL_CHAT,
            DEFAULT_MODEL_PLAN,
            DEFAULT_MODEL_AGENT,
            WEAK_MODEL,
            STRONG_MODEL,
            DECISION_MODEL,
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
            READ_ONLY_MODE,
            // Tools
            NATIVE_TOOLS_MODE,
            TOOLS_PERMISSIONS,
            TOOL_PERMISSION_RUN_TERMINAL,
            // Tool summary
            TOOL_SUMMARY_ENABLED,
            TOOL_SUMMARY_MIN_LENGTH,
            // Security
            SECURITY_ALLOW_SYMLINKS,
            SECURITY_ALLOW_LOOPBACK,
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
            OLLAMA_MAX_CONCURRENT,
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
            PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE,
            PROVIDER_CUSTOM_OPENAI_RAW_REQUEST,
            PROVIDER_EMBEDDINGS_BASE_URL,
            PROVIDER_EMBEDDINGS_API_KEY,
            PROVIDER_ZAI_API_KEY,
            PROVIDER_ZAI_BASE_URL,
            // Agent flow
            TASK_VERIFICATION_ENABLED,
            MAX_CONSECUTIVE_TOOL_ERRORS,
            AGENT_MAX_COST_USD,
            AGENT_MAX_TURN_MINUTES,
            AGENT_HANDOFF_NOTE,
            AGENT_HANDOFF_NOTE_TIMEOUT_SECONDS,
            LONG_TASK_MAX_ROUNDS,
            LONG_TASK_MAX_MINUTES,
            LONG_TASK_MAX_NO_PROGRESS_ROUNDS,
            LONG_TASK_CHECK_TIMEOUT_SECONDS,
            LONG_TASK_PROGRESS_FILE,
            LLM_RETRY_MAX_SECONDS,
            LOGS_RETENTION_DAYS,
            AGENT_MAX_FORMAT_NUDGES,
            AGENT_DECISION_TEMPERATURE,
            AGENT_PROMPT_LAYOUT,
            JSON_THINKING_XML_TAGS,
            // Verify
            VERIFY_ENABLED,
            VERIFY_COMMAND,
            VERIFY_MAX_REPAIR_ROUNDS
        ).associateBy { it.key }
    }
}
