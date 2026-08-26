package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.adapters.ZAIUrls
import pl.jclab.refio.core.logging.dualLogger
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
/**
 * Owns the one-shot startup contract:
 *  1. [initializeDefaults] — seed DB with built-in defaults (only keys not already set).
 *  2. [loadFromYamlIfMissing] - merge user YAML into DB for missing keys only.
 *  3. [reloadFromYaml] — force reload from YAML, overwriting DB (invoked via Settings UI button).
 *  4. [materializeProjectConfig] - turn the project config file into PROJECT-scoped rows.
 *
 * Extracted from [ConfigService] so the facade isn't 1000+ LOC.
 * Writes go through [configService.set] so cache invalidation stays consistent;
 * YAML merges are delegated to the existing [ConfigYamlApplier] wired inside [ConfigService].
 */
internal class ConfigDefaultsInitializer(
    private val configRepository: ConfigRepository,
    private val applyYaml: (ConfigYaml, Boolean) -> Int,
    private val invalidateAllCaches: () -> Unit,
    private val projectRoot: Path? = null,
    private val projectId: String? = null,
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
        // The user file was just written over the whole APP scope, which clears the project rows
        // for every key it touched; re-apply the project file so both files stay in effect.
        val projectCount = materializeProjectConfig(force = true)
        logger.info { "Finished reloading configuration from YAML: $updatedCount keys updated, $projectCount project keys" }
        invalidateAllCaches()
        return updatedCount
    }

    /**
     * Turn `<project>/.refio/config.yaml` into PROJECT-scoped rows so it wins over the built-in
     * defaults seeded into APP scope, and loses to a per-task override - the documented
     * TASK > PROJECT > APP order.
     *
     * The rows are rewritten only when the file's content changed since the last run (its digest
     * is stored alongside them). That keeps the rule predictable in both directions: editing the
     * file applies it on the next start, and changing the same setting afterwards in the UI keeps
     * working, because that write drops the project row for that one key and no later start
     * resurrects it until the file itself changes.
     *
     * @param force rewrite even when the file is unchanged (used by the explicit YAML reload).
     * @return number of keys written.
     */
    fun materializeProjectConfig(force: Boolean = false): Int {
        val root = projectRoot ?: return 0
        val project = projectId ?: return 0
        val file = ConfigYaml.getProjectConfigPath(root)

        if (!file.exists()) {
            return clearMaterializedRows(project)
        }

        val fingerprint = fingerprintOf(file)
        val storedFingerprint = configRepository
            .get(PROJECT_CONFIG_FINGERPRINT_KEY, ConfigScope.PROJECT, projectId = project)
            ?.value
        if (!force && storedFingerprint == fingerprint) {
            logger.debug { "Project config unchanged since last run, keeping current values" }
            return 0
        }

        val projectYaml = ConfigYaml.loadProjectConfig(root)
        if (projectYaml == null) {
            logger.warn { "Project config at ${file.absolutePath} could not be parsed, keeping previous values" }
            return 0
        }

        // Read the project file alone (no user file merged underneath) so only what this project
        // actually declares becomes a project-scoped value.
        val projectOnly = HierarchicalConfigLoader.forSnapshot(projectYaml)
        configRepository.deleteByScope(ConfigScope.PROJECT, projectId = project)

        var count = 0
        for (configKey in ConfigKeys.allKeys()) {
            val value = configKey.yamlAccessor?.invoke(projectOnly)?.toString() ?: continue
            configRepository.set(
                key = configKey.key,
                value = value,
                scope = ConfigScope.PROJECT,
                projectId = project,
                description = PROJECT_ROW_DESCRIPTION,
            )
            logger.info { "Applied project config: ${configKey.key} = $value" }
            count++
        }
        configRepository.set(
            key = PROJECT_CONFIG_FINGERPRINT_KEY,
            value = fingerprint,
            scope = ConfigScope.PROJECT,
            projectId = project,
            description = PROJECT_ROW_DESCRIPTION,
        )

        logger.info { "Materialized project config from ${file.absolutePath}: $count keys" }
        invalidateAllCaches()
        return count
    }

    /** The project file is gone, so its values must stop applying. */
    private fun clearMaterializedRows(project: String): Int {
        val hadRows = configRepository
            .get(PROJECT_CONFIG_FINGERPRINT_KEY, ConfigScope.PROJECT, projectId = project) != null
        if (!hadRows) return 0

        val deleted = configRepository.deleteByScope(ConfigScope.PROJECT, projectId = project)
        logger.info { "Project config file removed, dropped $deleted project-scoped values" }
        invalidateAllCaches()
        return 0
    }

    private fun fingerprintOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Digest of the project config file the current PROJECT-scoped rows were built from.
         * Stored in the same scope so it is dropped together with them.
         */
        private const val PROJECT_CONFIG_FINGERPRINT_KEY = "project_config.fingerprint"

        private const val PROJECT_ROW_DESCRIPTION = "From project config file"

        /**
         * (key, value, description) triples seeded on first run. Order is intentional —
         * UI toggles first, then models, then feature flags, then RAG/context/agent knobs.
         */
        private val BUILTIN_DEFAULTS: List<Triple<String, String, String>> = listOf(
            Triple(ConfigKeys.GENERAL_REASONING_EFFORT.key, "OFF", "Reasoning strength (OFF/LOW/MEDIUM/HIGH) across providers (Ollama think, Gemini budget, Anthropic extended thinking, OpenRouter/OpenAI reasoning effort); OFF suppresses it where the provider allows"),
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
