package pl.jclab.refio.core.config

import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path

/**
 * Hierarchical configuration loader.
 *
 * Loads and merges configuration from multiple sources in priority order:
 * 1. Built-in defaults (hardcoded)
 * 2. User config file (~/.refio/config.yaml)
 * 3. Project config file (<project>/.refio/config.yaml)
 * 4. Database overrides (handled separately by ConfigService)
 *
 * This class handles only file-based configuration loading.
 * Database overrides are applied by ConfigService at runtime.
 */
class HierarchicalConfigLoader private constructor(
    private val projectRoot: Path?
) {
    private val logger = dualLogger("HierarchicalConfigLoader")

    /**
     * Cached merged configuration (user + project files).
     * Lazily loaded on first access.
     */
    private var cachedConfig: ConfigYaml? = null
    private var cacheTimestamp: Long = 0
    private val cacheValidityMs = 30_000L  // 30 seconds cache

    /**
     * Get the merged configuration from all YAML sources.
     * Results are cached for 30 seconds.
     */
    fun getConfig(): ConfigYaml {
        val now = System.currentTimeMillis()
        if (cachedConfig != null && (now - cacheTimestamp) < cacheValidityMs) {
            return cachedConfig!!
        }

        cachedConfig = loadMergedConfig()
        cacheTimestamp = now
        return cachedConfig!!
    }

    /**
     * Force reload of configuration from files.
     */
    fun reloadConfig(): ConfigYaml {
        cachedConfig = null
        cacheTimestamp = 0
        return getConfig()
    }

    /**
     * Load and merge configuration from user and project YAML files.
     */
    private fun loadMergedConfig(): ConfigYaml {
        val userConfig = try {
            ConfigYaml.loadUserConfig().also {
                if (it != null) {
                    logger.info { "Loaded user config from ${ConfigYaml.getUserConfigPath()}" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load user config: ${e.message}" }
            null
        }

        val projectConfig = if (projectRoot != null) {
            try {
                ConfigYaml.loadProjectConfig(projectRoot).also {
                    if (it != null) {
                        logger.info { "Loaded project config from ${ConfigYaml.getProjectConfigPath(projectRoot)}" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to load project config: ${e.message}" }
                null
            }
        } else {
            null
        }

        // Merge: project config overrides user config
        return ConfigYaml.merge(userConfig, projectConfig)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // General Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getFormatMarkdown(): Boolean? = getConfig().general?.formatMarkdown
    fun getStreamingEnabled(): Boolean? = getConfig().general?.streamingEnabled
    fun getAdvancedView(): Boolean? = getConfig().general?.advancedView

    // ═══════════════════════════════════════════════════════════════════════════════
    // Provider Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getOllamaEndpoint(): String? = getConfig().providers?.ollama?.endpoint
    fun getOllamaContextSize(): Int? = getConfig().providers?.ollama?.contextSize
    fun getOllamaKeepAlive(): Int? = getConfig().providers?.ollama?.keepAlive

    fun getAnthropicApiKey(): String? = getConfig().providers?.anthropic?.apiKey
    fun getOpenAIApiKey(): String? = getConfig().providers?.openai?.apiKey
    fun getOpenRouterApiKey(): String? = getConfig().providers?.openrouter?.apiKey
    fun getGeminiApiKey(): String? = getConfig().providers?.gemini?.apiKey

    fun getLMStudioApiKey(): String? = getConfig().providers?.lmstudio?.apiKey
    fun getLMStudioBaseUrl(): String? = getConfig().providers?.lmstudio?.baseUrl
    fun getLMStudioContextSize(): Int? = getConfig().providers?.lmstudio?.contextSize
    fun getCustomOpenAIApiKey(): String? = getConfig().providers?.customOpenai?.apiKey
    fun getCustomOpenAIBaseUrl(): String? = getConfig().providers?.customOpenai?.baseUrl
    fun getCustomOpenAIModel(): String? = getConfig().providers?.customOpenai?.model
    fun getZAIApiKey(): String? = getConfig().providers?.zai?.apiKey
    fun getZAIBaseUrl(): String? = getConfig().providers?.zai?.baseUrl

    // ═══════════════════════════════════════════════════════════════════════════════
    // Model Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getDefaultChatModel(): String? = getConfig().models?.defaults?.chat

    fun getDefaultPlanModel(): String? = getConfig().models?.defaults?.plan

    fun getDefaultCodingModel(): String? = getConfig().models?.defaults?.coding

    fun getDefaultWeakModel(): String? = getConfig().models?.defaults?.weak

    fun getDefaultEmbeddingModel(): String? = getConfig().models?.defaults?.embedding

    fun getModelsVisibility(): Map<String, Boolean>? = getConfig().models?.visibility

    // ═══════════════════════════════════════════════════════════════════════════════
    // Limits Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getApiCallTimeout(): Int? = getConfig().limits?.apiCallTimeout
    fun getToolExecutionTimeout(): Int? = getConfig().limits?.toolExecutionTimeout
    fun getStreamingReadTimeout(): Int? = getConfig().limits?.streamingReadTimeout
    fun getStreamingRequestTimeout(): Int? = getConfig().limits?.streamingRequestTimeout
    fun getMaxContextSize(): Int? = getConfig().limits?.maxContextSize
    fun getMaxOutputSize(): Int? = getConfig().limits?.maxOutputSize
    fun getMaxFileSize(): Int? = getConfig().limits?.maxFileSize

    // ═══════════════════════════════════════════════════════════════════════════════
    // Advanced Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getNoEgressDefault(): Boolean? = getConfig().advanced?.noEgressDefault
    fun getReadOnlyMode(): Boolean? = getConfig().advanced?.readOnlyMode
    fun getAutoOptimizePercentage(): Int? = getConfig().advanced?.autoOptimizePercentage
    fun getOrchestrationEnabled(): Boolean? = getConfig().advanced?.orchestrationEnabled

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tools Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getToolPermission(toolName: String): ToolPermissionConfig? {
        return getConfig().tools?.permissions?.get(toolName)
    }

    fun getAllToolPermissions(): Map<String, ToolPermissionConfig>? {
        return getConfig().tools?.permissions
    }

    fun getTerminalWhitelist(): TerminalWhitelistConfig? {
        return getConfig().terminal?.whitelist
    }

    fun getTerminalWhitelistEnabled(): Boolean? = getConfig().terminal?.whitelist?.enabled

    fun getTerminalWhitelistMode(): String? = getConfig().terminal?.whitelist?.mode

    // ═══════════════════════════════════════════════════════════════════════════════
    // RAG Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getRagEnabled(): Boolean? = getConfig().rag?.enabled
    fun getRagIndexOnStartup(): Boolean? = getConfig().rag?.indexOnStartup
    fun getRagAutoIndexOnContextBuild(): Boolean? = getConfig().rag?.autoIndexOnContextBuild
    fun getRagMaxFileSizeMB(): Long? = getConfig().rag?.maxFileSizeMB
    fun getRagMaxChunksPerFile(): Int? = getConfig().rag?.maxChunksPerFile
    fun getRagIndexBatchSize(): Int? = getConfig().rag?.indexBatchSize
    fun getRagEmbeddingsBatchSize(): Int? = getConfig().rag?.embeddingsBatchSize
    fun getRagCacheTtlMs(): Long? = getConfig().rag?.cacheTtlMs
    fun getRagMaxConcurrentJobs(): Int? = getConfig().rag?.maxConcurrentJobs
    fun getRagIgnoredDirectories(): List<String>? = getConfig().rag?.ignoredDirectories
    fun getRagSearchSimilarityThreshold(): Float? = getConfig().rag?.searchSimilarityThreshold
    fun getRagSearchTopK(): Int? = getConfig().rag?.searchTopK
    fun getRagSearchHybridEnabled(): Boolean? = getConfig().rag?.searchHybridEnabled
    fun getRagSearchSemanticWeight(): Float? = getConfig().rag?.searchSemanticWeight
    fun getRagSearchIncludeContextChunks(): Boolean? = getConfig().rag?.searchIncludeContextChunks

    // ═══════════════════════════════════════════════════════════════════════════════
    // UI Settings
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getUiThinkingEnabled(): Boolean? = getConfig().ui?.thinkingEnabled
    fun getUiNoEgressEnabled(): Boolean? = getConfig().ui?.noEgressEnabled
    fun getUiExecutionMode(): String? = getConfig().ui?.executionMode
    fun getUiSelectedMode(): String? = getConfig().ui?.selectedMode
    fun getUiSelectedModel(): String? = getConfig().ui?.selectedModel

    // ═══════════════════════════════════════════════════════════════════════════════
    // Prompts Settings (project-specific)
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getSystemChatPrompt(): String? = getConfig().prompts?.systemChat
    fun getSystemPlanPrompt(): String? = getConfig().prompts?.systemPlan
    fun getSystemAgentPrompt(): String? = getConfig().prompts?.systemAgent
    fun getCommands(): List<CommandConfig>? = getConfig().prompts?.commands
    fun getRules(): List<RuleConfig>? = getConfig().prompts?.rules

    // ═══════════════════════════════════════════════════════════════════════════════
    // MCP Settings (project-specific)
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getMcpServers(): List<McpServerConfig>? = getConfig().mcp?.servers

    companion object {
        private val instances = mutableMapOf<Path?, HierarchicalConfigLoader>()

        /**
         * Get or create a loader instance for the given project root.
         * Instances are cached per project root.
         */
        @Synchronized
        fun getInstance(projectRoot: Path? = null): HierarchicalConfigLoader {
            return instances.getOrPut(projectRoot) {
                HierarchicalConfigLoader(projectRoot)
            }
        }

        /**
         * Clear all cached instances (useful for testing).
         */
        @Synchronized
        fun clearInstances() {
            instances.clear()
        }
    }
}
