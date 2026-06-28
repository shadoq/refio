package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.adapters.ZAIUrls
import pl.jclab.refio.core.logging.dualLogger
/**
 * Owns the one-shot startup contract:
 *  1. [initializeDefaults] — seed DB with built-in defaults (only keys not already set).
 *  2. [loadFromYamlIfMissing] — merge user/project YAML into DB for missing keys only.
 *  3. [reloadFromYaml] — force reload from YAML, overwriting DB (invoked via Settings UI button).
 *
 * Extracted from [ConfigService] so the facade isn't 1000+ LOC.
 * Writes go through [configService.set] so cache invalidation stays consistent;
 * YAML merges are delegated to the existing [ConfigYamlApplier] wired inside [ConfigService].
 */
internal class ConfigDefaultsInitializer(
    private val configRepository: ConfigRepository,
    private val applyYaml: (ConfigYaml, Boolean) -> Int,
    private val invalidateAllCaches: () -> Unit,
) {
    private val logger = dualLogger("ConfigDefaultsInitializer")

    fun initializeDefaults() {
        logger.info { "Initializing default configuration values (only missing keys)" }

        var initializedCount = 0
        for ((key, value, description) in BUILTIN_DEFAULTS) {
            if (configRepository.get(key, ConfigScope.APP) == null) {
                configRepository.set(
                    key = key,
                    value = value,
                    scope = ConfigScope.APP,
                    taskId = null,
                    description = description,
                )
                logger.info { "Initialized default: $key = $value" }
                initializedCount++
            }
        }

        logger.info { "Finished initializing defaults: $initializedCount keys set" }
        invalidateAllCaches()
    }

    fun loadFromYamlIfMissing() {
        val yamlConfig = ConfigYaml.load()
        if (yamlConfig == null) {
            logger.info { "No YAML config file found or failed to parse, skipping" }
            return
        }
        logger.info { "Loading configuration from YAML file (only missing keys)" }
        applyYaml(yamlConfig, false)
        logger.info { "Finished loading configuration from YAML" }
    }

    fun reloadFromYaml(): Int {
        val yamlConfig = ConfigYaml.load()
            ?: throw IllegalStateException("No YAML config file found at ${ConfigYaml.getConfigPath().absolutePath}")

        logger.info { "Reloading all configuration from YAML file (overwriting DB)" }
        val updatedCount = applyYaml(yamlConfig, true)
        logger.info { "Finished reloading configuration from YAML: $updatedCount keys updated" }
        invalidateAllCaches()
        return updatedCount
    }

    companion object {
        /**
         * (key, value, description) triples seeded on first run. Order is intentional —
         * UI toggles first, then models, then feature flags, then RAG/context/agent knobs.
         */
        private val BUILTIN_DEFAULTS: List<Triple<String, String, String>> = listOf(
            Triple(ConfigKeys.GENERAL_THINKING_ENABLED.key, "false", "Enable model thinking/reasoning across providers (Ollama think, Gemini budget, Anthropic extended thinking, OpenRouter reasoning); OFF suppresses it where the provider allows"),
            Triple(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, "false", "Block external network calls"),
            Triple(ConfigKeys.UI_INTENT_CLASSIFICATION_ENABLED.key, "false", "Enable LLM intent classification"),
            Triple(ConfigKeys.GENERAL_EXECUTION_MODE.key, "AUTO", "Execution mode (AUTO/INTERACTIVE)"),
            Triple(ConfigKeys.UI_SELECTED_MODE.key, "CHAT", "Selected task mode (CHAT/PLAN/AGENT)"),
            Triple(ConfigKeys.EMBEDDING_MODEL.key, "ollama/nomic-embed-text", "Model for embeddings"),
            Triple(ConfigKeys.FORMAT_MARKDOWN.key, "true", "Format responses as markdown"),
            Triple(ConfigKeys.STREAMING_ENABLED.key, "true", "Enable streaming responses"),
            Triple(ConfigKeys.ADVANCED_VIEW.key, "false", "Show advanced UI options"),
            Triple(ConfigKeys.TOOL_SUMMARY_ENABLED.key, "true", "Enable tool result summarization"),
            Triple(ConfigKeys.TOOL_SUMMARY_MIN_LENGTH.key, "500", "Minimum tool output length for summarization"),
            Triple(ConfigKeys.SECURITY_ALLOW_SYMLINKS.key, "false", "Allow symbolic links in PathSandbox (unsafe, opt-in)"),
            Triple(ConfigKeys.PROVIDER_ZAI_BASE_URL.key, ZAIUrls.DEFAULT, "Base URL for Z.AI provider"),
            Triple(ConfigKeys.RAG_EMBEDDING_CACHE_SIZE.key, ConfigKeys.RAG_EMBEDDING_CACHE_SIZE.default.toString(), "Maximum embedding cache entries"),
            Triple(ConfigKeys.RAG_CHUNKING_MODE.key, ConfigKeys.RAG_CHUNKING_MODE.default, "RAG chunking mode (semantic or line_based)"),
            Triple(ConfigKeys.RAG_SEARCH_CACHE_TTL_SECONDS.key, ConfigKeys.RAG_SEARCH_CACHE_TTL_SECONDS.default.toString(), "TTL for cached @codebase search results in seconds"),
            Triple(ConfigKeys.WORKING_MEMORY_MAX_FACTS.key, ConfigKeys.WORKING_MEMORY_MAX_FACTS.default.toString(), "Maximum working memory facts stored per task"),
            Triple(ConfigKeys.TASK_VERIFICATION_ENABLED.key, "false", "Enable task completion verification for AGENT mode"),
            Triple(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.default.toString(), "Max consecutive failures of the same tool+args before aborting (definitive loop). Varied args reset the counter."),
            Triple(ConfigKeys.JSON_THINKING_XML_TAGS.key, ConfigKeys.JSON_THINKING_XML_TAGS.default.joinToString(","), "Comma-separated XML tags stripped before JSON extraction (e.g., thinking,think)"),
        )
    }
}
