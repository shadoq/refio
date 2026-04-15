package pl.jclab.refio.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Path

/**
 * Data model for YAML configuration file.
 *
 * Configuration hierarchy (from lowest to highest priority):
 * 1. Built-in defaults (hardcoded in ConfigService)
 * 2. User config: ~/.refio/config.yaml (Linux/macOS) or %USERPROFILE%\.refio\config.yaml (Windows)
 * 3. Project config: <project_root>/.refio/config.yaml (project-specific settings only)
 * 4. Database overrides (settings changed via Settings UI)
 *
 * Project-specific settings (only in project config):
 * - prompts (system prompts, commands, rules)
 * - mcp (MCP server configurations)
 * - models.visibility (which models to show for this project)
 * - rag (project-specific RAG settings)
 *
 * User-level settings (in user config):
 * - providers (API keys, endpoints)
 * - models.default (default model selections)
 * - general (UI preferences)
 * - limits (timeouts, size limits)
 * - advanced (security settings)
 * - tools (tool permissions)
 * - terminal (terminal command whitelist)
 */
@Serializable
data class ConfigYaml(
    /**
     * General UI and behavior settings
     */
    val general: GeneralConfig? = null,

    /**
     * Provider configurations (API keys, endpoints)
     */
    val providers: ProvidersConfig? = null,

    /**
     * Default model selection per mode and visibility settings
     */
    val models: ModelsConfig? = null,

    /**
     * System limits (timeouts, context size, etc.)
     */
    val limits: LimitsConfig? = null,

    /**
     * Advanced settings (security, optimization)
     */
    val advanced: AdvancedConfig? = null,

    /**
     * Tool permissions per mode
     */
    val tools: ToolsConfig? = null,

    /**
     * RAG indexing configuration
     */
    val rag: RagConfig? = null,

    /**
     * UI state settings (persisted between sessions)
     */
    val ui: UiConfig? = null,

    /**
     * Custom prompts configuration (project-specific)
     */
    val prompts: PromptsConfig? = null,

    /**
     * MCP server configurations (project-specific)
     */
    val mcp: McpConfig? = null,

    /**
     * Hooks configuration (user-defined lifecycle actions)
     */
    val hooks: HooksConfig? = null
) {
    companion object {
        /**
         * Get the path to the user's config YAML file in home directory
         */
        fun getUserConfigPath(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, ".refio${File.separator}config.yaml")
        }

        /**
         * Get the path to the project's config YAML file
         */
        fun getProjectConfigPath(projectRoot: Path): File {
            return projectRoot.resolve(".refio").resolve("config.yaml").toFile()
        }

        /**
         * Legacy alias for getUserConfigPath()
         */
        fun getConfigPath(): File = getUserConfigPath()

        /**
         * Load user-level configuration from YAML file.
         * Returns null if file doesn't exist or can't be parsed.
         */
        fun load(): ConfigYaml? = loadFromPath(getUserConfigPath())

        /**
         * Load user-level configuration from YAML file.
         * Returns null if file doesn't exist or can't be parsed.
         */
        fun loadUserConfig(): ConfigYaml? = loadFromPath(getUserConfigPath())

        /**
         * Load project-level configuration from YAML file.
         * Returns null if file doesn't exist or can't be parsed.
         */
        fun loadProjectConfig(projectRoot: Path): ConfigYaml? = loadFromPath(getProjectConfigPath(projectRoot))

        /**
         * Load configuration from a specific path.
         * Returns null if file doesn't exist or can't be parsed.
         */
        private fun loadFromPath(configFile: File): ConfigYaml? {
            if (!configFile.exists()) {
                return null
            }

            return try {
                val yamlContent = configFile.readText()
                decodeYamlContent(yamlContent)
            } catch (e: Exception) {
                // Log error but don't fail - return null to indicate failure
                println("Error loading config YAML from ${configFile.absolutePath}: ${e.message}")
                null
            }
        }

        private fun decodeYamlContent(yamlContent: String): ConfigYaml {
            val yaml = Yaml(
                configuration = YamlConfiguration(
                    strictMode = false  // Allow unknown fields for forward compatibility
                )
            )

            val firstAttempt = runCatching {
                yaml.decodeFromString(serializer(), yamlContent)
            }
            if (firstAttempt.isSuccess) {
                return firstAttempt.getOrThrow()
            }

            val sanitizedEscapes = sanitizeInvalidDoubleQuotedEscapes(yamlContent)
            val sanitizedBrokenLines = sanitizeBrokenStandaloneEmptyQuotedLines(sanitizedEscapes)

            if (sanitizedBrokenLines == yamlContent) {
                throw firstAttempt.exceptionOrNull() ?: IllegalStateException("Unknown YAML parsing error")
            }

            val secondAttempt = runCatching {
                yaml.decodeFromString(serializer(), sanitizedBrokenLines)
            }
            if (secondAttempt.isSuccess) {
                if (sanitizedEscapes != yamlContent) {
                    println("Config YAML parser fallback: sanitized invalid double-quoted escape sequences")
                }
                if (sanitizedBrokenLines != sanitizedEscapes) {
                    println("Config YAML parser fallback: repaired broken standalone empty-quoted lines")
                }
                return secondAttempt.getOrThrow()
            }

            throw secondAttempt.exceptionOrNull()
                ?: firstAttempt.exceptionOrNull()
                ?: IllegalStateException("Unknown YAML parsing error")
        }

        private fun sanitizeInvalidDoubleQuotedEscapes(input: String): String {
            val out = StringBuilder(input.length + 32)
            var inDoubleQuoted = false
            var inSingleQuoted = false
            var inComment = false
            var i = 0

            while (i < input.length) {
                val ch = input[i]

                if (inComment) {
                    out.append(ch)
                    if (ch == '\n') {
                        inComment = false
                    }
                    i++
                    continue
                }

                if (inSingleQuoted) {
                    out.append(ch)
                    if (ch == '\'') {
                        if (i + 1 < input.length && input[i + 1] == '\'') {
                            out.append('\'')
                            i += 2
                            continue
                        }
                        inSingleQuoted = false
                    }
                    i++
                    continue
                }

                if (inDoubleQuoted) {
                    if (ch == '"') {
                        inDoubleQuoted = false
                        out.append(ch)
                        i++
                        continue
                    }

                    if (ch == '\\') {
                        val next = input.getOrNull(i + 1)
                        if (next == null || !isValidYamlEscape(input, i + 1)) {
                            out.append("\\\\")
                            i++
                            continue
                        }
                    }

                    out.append(ch)
                    i++
                    continue
                }

                when (ch) {
                    '#' -> inComment = true
                    '"' -> inDoubleQuoted = true
                    '\'' -> inSingleQuoted = true
                }
                out.append(ch)
                i++
            }

            return out.toString()
        }

        private fun isValidYamlEscape(input: String, escapeCharIndex: Int): Boolean {
            val escapeChar = input.getOrNull(escapeCharIndex) ?: return false
            return when (escapeChar) {
                '0', 'a', 'b', 't', 'n', 'v', 'f', 'r', 'e', ' ', '"', '/', '\\', 'N', '_', 'L', 'P' -> true
                'x' -> hasHexDigits(input, escapeCharIndex + 1, 2)
                'u' -> hasHexDigits(input, escapeCharIndex + 1, 4)
                'U' -> hasHexDigits(input, escapeCharIndex + 1, 8)
                else -> false
            }
        }

        private fun hasHexDigits(input: String, start: Int, length: Int): Boolean {
            if (start + length > input.length) {
                return false
            }
            for (idx in start until start + length) {
                if (!input[idx].isDigit() && input[idx].lowercaseChar() !in 'a'..'f') {
                    return false
                }
            }
            return true
        }

        private fun sanitizeBrokenStandaloneEmptyQuotedLines(input: String): String {
            val lines = input.split('\n')
            val out = ArrayList<String>(lines.size)

            var previousSignificant: String? = null
            var lastListIndent: Int? = null
            var changed = false

            for (line in lines) {
                val trimmed = line.trim()

                if (trimmed == "''") {
                    val replacement = when {
                        lastListIndent != null -> "${" ".repeat(lastListIndent)}- ''"
                        previousSignificant?.trimEnd()?.endsWith(":") == true -> {
                            val baseIndent = previousSignificant.takeWhile { it == ' ' }.length
                            "${" ".repeat(baseIndent + 2)}- ''"
                        }
                        else -> "- ''"
                    }
                    out.add(replacement)
                    previousSignificant = replacement
                    lastListIndent = replacement.takeWhile { it == ' ' }.length
                    changed = true
                    continue
                }

                out.add(line)
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    previousSignificant = line
                    if (trimmed.startsWith("- ")) {
                        lastListIndent = line.takeWhile { it == ' ' }.length
                    } else if (trimmed.endsWith(":")) {
                        lastListIndent = null
                    }
                }
            }

            if (!changed) {
                return input
            }
            return out.joinToString("\n")
        }

        /**
         * Merge two configs - values from 'override' take precedence over 'base'
         */
        fun merge(base: ConfigYaml?, override: ConfigYaml?): ConfigYaml {
            if (base == null) return override ?: ConfigYaml()
            if (override == null) return base

            return ConfigYaml(
                general = mergeGeneral(base.general, override.general),
                providers = mergeProviders(base.providers, override.providers),
                models = mergeModels(base.models, override.models),
                limits = mergeLimits(base.limits, override.limits),
                advanced = mergeAdvanced(base.advanced, override.advanced),
                tools = mergeTools(base.tools, override.tools),
                rag = mergeRag(base.rag, override.rag),
                ui = mergeUi(base.ui, override.ui),
                prompts = mergePrompts(base.prompts, override.prompts),
                mcp = mergeMcp(base.mcp, override.mcp),
                hooks = mergeHooks(base.hooks, override.hooks)
            )
        }

        private fun mergeGeneral(base: GeneralConfig?, override: GeneralConfig?): GeneralConfig? {
            if (base == null) return override
            if (override == null) return base

            return GeneralConfig(
                formatMarkdown = override.formatMarkdown ?: base.formatMarkdown,
                streamingEnabled = override.streamingEnabled ?: base.streamingEnabled,
                advancedView = override.advancedView ?: base.advancedView
            )
        }

        private fun mergeProviders(base: ProvidersConfig?, override: ProvidersConfig?): ProvidersConfig? {
            if (base == null) return override
            if (override == null) return base

            return ProvidersConfig(
                ollama = override.ollama ?: base.ollama,
                anthropic = override.anthropic ?: base.anthropic,
                openai = override.openai ?: base.openai,
                openrouter = override.openrouter ?: base.openrouter,
                gemini = override.gemini ?: base.gemini,
                lmstudio = override.lmstudio ?: base.lmstudio,
                genericOpenai = override.genericOpenai ?: base.genericOpenai,
                zai = override.zai ?: base.zai
            )
        }

        private fun mergeModels(base: ModelsConfig?, override: ModelsConfig?): ModelsConfig? {
            if (base == null) return override
            if (override == null) return base

            return ModelsConfig(
                default = override.default ?: base.default,
                defaults = override.defaults ?: base.defaults,
                visibility = mergeVisibility(base.visibility, override.visibility),
                presets = override.presets ?: base.presets
            )
        }

        private fun mergeVisibility(base: Map<String, Boolean>?, override: Map<String, Boolean>?): Map<String, Boolean>? {
            if (base == null) return override
            if (override == null) return base
            return base + override  // override wins on conflicts
        }

        private fun mergeLimits(base: LimitsConfig?, override: LimitsConfig?): LimitsConfig? {
            if (base == null) return override
            if (override == null) return base

            return LimitsConfig(
                apiCallTimeout = override.apiCallTimeout ?: base.apiCallTimeout,
                toolExecutionTimeout = override.toolExecutionTimeout ?: base.toolExecutionTimeout,
                streamingReadTimeout = override.streamingReadTimeout ?: base.streamingReadTimeout,
                streamingRequestTimeout = override.streamingRequestTimeout ?: base.streamingRequestTimeout,
                maxContextSize = override.maxContextSize ?: base.maxContextSize,
                maxOutputSize = override.maxOutputSize ?: base.maxOutputSize,
                maxFileSize = override.maxFileSize ?: base.maxFileSize
            )
        }

        private fun mergeAdvanced(base: AdvancedConfig?, override: AdvancedConfig?): AdvancedConfig? {
            if (base == null) return override
            if (override == null) return base

            return AdvancedConfig(
                noEgressDefault = override.noEgressDefault ?: base.noEgressDefault,
                readOnlyMode = override.readOnlyMode ?: base.readOnlyMode,
                autoOptimizePercentage = override.autoOptimizePercentage ?: base.autoOptimizePercentage,
                orchestrationEnabled = override.orchestrationEnabled ?: base.orchestrationEnabled
            )
        }

        private fun mergeRag(base: RagConfig?, override: RagConfig?): RagConfig? {
            if (base == null) return override
            if (override == null) return base

            return RagConfig(
                enabled = override.enabled ?: base.enabled,
                indexOnStartup = override.indexOnStartup ?: base.indexOnStartup,
                autoIndexOnContextBuild = override.autoIndexOnContextBuild ?: base.autoIndexOnContextBuild,
                maxFileSizeMB = override.maxFileSizeMB ?: base.maxFileSizeMB,
                maxChunksPerFile = override.maxChunksPerFile ?: base.maxChunksPerFile,
                indexBatchSize = override.indexBatchSize ?: base.indexBatchSize,
                embeddingsBatchSize = override.embeddingsBatchSize ?: base.embeddingsBatchSize,
                cacheTtlMs = override.cacheTtlMs ?: base.cacheTtlMs,
                maxConcurrentJobs = override.maxConcurrentJobs ?: base.maxConcurrentJobs,
                ignoredDirectories = mergeList(base.ignoredDirectories, override.ignoredDirectories),
                searchSimilarityThreshold = override.searchSimilarityThreshold ?: base.searchSimilarityThreshold,
                searchTopK = override.searchTopK ?: base.searchTopK,
                searchHybridEnabled = override.searchHybridEnabled ?: base.searchHybridEnabled,
                searchSemanticWeight = override.searchSemanticWeight ?: base.searchSemanticWeight,
                searchIncludeContextChunks = override.searchIncludeContextChunks ?: base.searchIncludeContextChunks
            )
        }

        private fun mergeUi(base: UiConfig?, override: UiConfig?): UiConfig? {
            if (base == null) return override
            if (override == null) return base

            return UiConfig(
                thinkingEnabled = override.thinkingEnabled ?: base.thinkingEnabled,
                noEgressEnabled = override.noEgressEnabled ?: base.noEgressEnabled,
                executionMode = override.executionMode ?: base.executionMode,
                selectedMode = override.selectedMode ?: base.selectedMode,
                selectedModel = override.selectedModel ?: base.selectedModel
            )
        }

        private fun mergeTools(base: ToolsConfig?, override: ToolsConfig?): ToolsConfig? {
            if (base == null) return override
            if (override == null) return base

            val mergedPermissions = (base.permissions ?: emptyMap()) + (override.permissions ?: emptyMap())
            return ToolsConfig(permissions = mergedPermissions)
        }

        private fun mergePrompts(base: PromptsConfig?, override: PromptsConfig?): PromptsConfig? {
            if (base == null) return override
            if (override == null) return base

            return PromptsConfig(
                systemChat = override.systemChat ?: base.systemChat,
                systemPlan = override.systemPlan ?: base.systemPlan,
                systemAgent = override.systemAgent ?: base.systemAgent,
                commands = mergeList(base.commands, override.commands),
                rules = mergeList(base.rules, override.rules)
            )
        }

        private fun <T> mergeList(base: List<T>?, override: List<T>?): List<T>? {
            if (base == null) return override
            if (override == null) return base
            return base + override
        }

        private fun mergeMcp(base: McpConfig?, override: McpConfig?): McpConfig? {
            if (base == null) return override
            if (override == null) return base

            val mergedServers = (base.servers ?: emptyList()) + (override.servers ?: emptyList())
            return McpConfig(servers = mergedServers.distinctBy { it.id })
        }

        private fun mergeHooks(base: HooksConfig?, override: HooksConfig?): HooksConfig? {
            if (base == null) return override
            if (override == null) return base

            return HooksConfig(
                beforeTurnLoop = override.beforeTurnLoop ?: base.beforeTurnLoop,
                afterTurnLoop = override.afterTurnLoop ?: base.afterTurnLoop,
                beforeTool = override.beforeTool ?: base.beforeTool,
                afterTool = override.afterTool ?: base.afterTool,
                onAgentComplete = override.onAgentComplete ?: base.onAgentComplete,
                onAgentError = override.onAgentError ?: base.onAgentError
            )
        }

        /**
         * Serialize ConfigYaml to YAML string.
         */
        fun toYamlString(config: ConfigYaml): String {
            return Yaml(
                configuration = YamlConfiguration(
                    strictMode = false
                )
            ).encodeToString(serializer(), config)
        }

        /**
         * Save configuration to a file.
         *
         * @param config Configuration to save
         * @param file Target file
         * @param withComments If true, adds helpful comments to the output
         */
        fun saveToFile(config: ConfigYaml, file: File, withComments: Boolean = true) {
            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            val content = if (withComments) {
                createCommentedYaml(config)
            } else {
                toYamlString(config)
            }

            file.writeText(content)
        }

        /**
         * Create YAML string with helpful comments.
         */
        private fun createCommentedYaml(config: ConfigYaml): String {
            val sb = StringBuilder()

            sb.appendLine("# ═══════════════════════════════════════════════════════════════════════════════")
            sb.appendLine("# Refio Configuration File")
            sb.appendLine("# Generated: ${java.time.LocalDateTime.now()}")
            sb.appendLine("# ═══════════════════════════════════════════════════════════════════════════════")
            sb.appendLine()

            // General
            config.general?.let { general ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# General Settings")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("general:")
                general.formatMarkdown?.let { sb.appendLine("  formatMarkdown: $it") }
                general.streamingEnabled?.let { sb.appendLine("  streamingEnabled: $it") }
                general.advancedView?.let { sb.appendLine("  advancedView: $it") }
                sb.appendLine()
            }

            // Providers
            config.providers?.let { providers ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# LLM Provider Configuration")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("providers:")

                providers.ollama?.let { ollama ->
                    sb.appendLine("  ollama:")
                    ollama.endpoint?.let { sb.appendLine("    endpoint: \"$it\"") }
                    ollama.contextSize?.let { sb.appendLine("    contextSize: $it") }
                }

                providers.anthropic?.let { anthropic ->
                    sb.appendLine("  anthropic:")
                    anthropic.apiKey?.let {
                        sb.appendLine("    apiKey: \"$it\"")
                    }
                }

                providers.openai?.let { openai ->
                    sb.appendLine("  openai:")
                    openai.apiKey?.let {
                        sb.appendLine("    apiKey: \"$it\"")
                    }
                }

                providers.openrouter?.let { openrouter ->
                    sb.appendLine("  openrouter:")
                    openrouter.apiKey?.let {
                        sb.appendLine("    apiKey: \"$it\"")
                    }
                }

                providers.gemini?.let { gemini ->
                    sb.appendLine("  gemini:")
                    gemini.apiKey?.let {
                        sb.appendLine("    apiKey: \"$it\"")
                    }
                }

                providers.lmstudio?.let { lmstudio ->
                    sb.appendLine("  lmstudio:")
                    lmstudio.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                    lmstudio.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                    lmstudio.contextSize?.let { sb.appendLine("    contextSize: $it") }
                }

                providers.genericOpenai?.let { genericOpenai ->
                    sb.appendLine("  generic_openai:")
                    genericOpenai.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                    genericOpenai.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                    genericOpenai.model?.let { sb.appendLine("    model: \"$it\"") }
                }

                providers.zai?.let { zai ->
                    sb.appendLine("  zai:")
                    zai.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                    zai.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                }

                sb.appendLine()
            }

            // Models
            config.models?.let { models ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# Model Configuration")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("models:")

                models.defaults?.let { defaults ->
                    sb.appendLine("  defaults:")
                    defaults.chat?.let { sb.appendLine("    chat: \"$it\"") }
                    defaults.plan?.let { sb.appendLine("    plan: \"$it\"") }
                    defaults.coding?.let { sb.appendLine("    coding: \"$it\"") }
                    defaults.weak?.let { sb.appendLine("    weak: \"$it\"") }
                    defaults.embedding?.let { sb.appendLine("    embedding: \"$it\"") }
                }

                models.visibility?.let { visibility ->
                    if (visibility.isNotEmpty()) {
                        sb.appendLine("  visibility:")
                        visibility.forEach { (model, visible) ->
                            sb.appendLine("    \"$model\": $visible")
                        }
                    }
                }

                sb.appendLine()
            }

            // Limits
            config.limits?.let { limits ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# System Limits")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("limits:")
                limits.apiCallTimeout?.let { sb.appendLine("  apiCallTimeout: $it") }
                limits.toolExecutionTimeout?.let { sb.appendLine("  toolExecutionTimeout: $it") }
                limits.streamingReadTimeout?.let { sb.appendLine("  streamingReadTimeout: $it") }
                limits.streamingRequestTimeout?.let { sb.appendLine("  streamingRequestTimeout: $it") }
                limits.maxContextSize?.let { sb.appendLine("  maxContextSize: $it") }
                limits.maxOutputSize?.let { sb.appendLine("  maxOutputSize: $it") }
                limits.maxFileSize?.let { sb.appendLine("  maxFileSize: $it") }
                sb.appendLine()
            }

            // Advanced
            config.advanced?.let { advanced ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# Advanced Settings")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("advanced:")
                advanced.noEgressDefault?.let { sb.appendLine("  noEgressDefault: $it") }
                advanced.readOnlyMode?.let { sb.appendLine("  readOnlyMode: $it") }
                advanced.autoOptimizePercentage?.let { sb.appendLine("  autoOptimizePercentage: $it") }
                sb.appendLine()
            }

            // Tools
            config.tools?.let { tools ->
                tools.permissions?.let { permissions ->
                    if (permissions.isNotEmpty()) {
                        sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                        sb.appendLine("# Tool Permissions")
                        sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                        sb.appendLine("tools:")
                        sb.appendLine("  permissions:")
                        permissions.forEach { (tool, perm) ->
                            sb.appendLine("    $tool:")
                            perm.planMode?.let { sb.appendLine("      planMode: \"$it\"") }
                            perm.agentMode?.let { sb.appendLine("      agentMode: \"$it\"") }
                        }
                        sb.appendLine()
                    }
                }
            }

            // RAG
            config.rag?.let { rag ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# RAG Configuration")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("rag:")
                rag.enabled?.let { sb.appendLine("  enabled: $it") }
                rag.indexOnStartup?.let { sb.appendLine("  indexOnStartup: $it") }
                rag.autoIndexOnContextBuild?.let { sb.appendLine("  autoIndexOnContextBuild: $it") }
                rag.maxFileSizeMB?.let { sb.appendLine("  maxFileSizeMB: $it") }
                rag.maxChunksPerFile?.let { sb.appendLine("  maxChunksPerFile: $it") }
                rag.indexBatchSize?.let { sb.appendLine("  indexBatchSize: $it") }
                rag.embeddingsBatchSize?.let { sb.appendLine("  embeddingsBatchSize: $it") }
                rag.cacheTtlMs?.let { sb.appendLine("  cacheTtlMs: $it") }
                rag.maxConcurrentJobs?.let { sb.appendLine("  maxConcurrentJobs: $it") }
                rag.ignoredDirectories?.let { dirs ->
                    if (dirs.isNotEmpty()) {
                        sb.appendLine("  ignoredDirectories:")
                        dirs.forEach { sb.appendLine("    - \"$it\"") }
                    }
                }
                sb.appendLine()
            }

            // UI
            config.ui?.let { ui ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# UI State")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("ui:")
                ui.thinkingEnabled?.let { sb.appendLine("  thinkingEnabled: $it") }
                ui.noEgressEnabled?.let { sb.appendLine("  noEgressEnabled: $it") }
                ui.executionMode?.let { sb.appendLine("  executionMode: \"$it\"") }
                ui.selectedMode?.let { sb.appendLine("  selectedMode: \"$it\"") }
                ui.selectedModel?.let { sb.appendLine("  selectedModel: \"$it\"") }
                sb.appendLine()
            }

            // Prompts (project-specific)
            config.prompts?.let { prompts ->
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# Custom Prompts (project-specific)")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("prompts:")

                prompts.systemChat?.let {
                    sb.appendLine("  systemChat: |")
                    it.lines().forEach { line -> sb.appendLine("    $line") }
                }

                prompts.systemPlan?.let {
                    sb.appendLine("  systemPlan: |")
                    it.lines().forEach { line -> sb.appendLine("    $line") }
                }

                prompts.systemAgent?.let {
                    sb.appendLine("  systemAgent: |")
                    it.lines().forEach { line -> sb.appendLine("    $line") }
                }

                prompts.commands?.let { commands ->
                    if (commands.isNotEmpty()) {
                        sb.appendLine("  commands:")
                        commands.forEach { cmd ->
                            sb.appendLine("    - name: \"${cmd.name}\"")
                            cmd.description?.let { sb.appendLine("      description: \"$it\"") }
                            sb.appendLine("      content: \"${cmd.content.replace("\"", "\\\"")}\"")
                            sb.appendLine("      enabled: ${cmd.enabled}")
                        }
                    }
                }

                prompts.rules?.let { rules ->
                    if (rules.isNotEmpty()) {
                        sb.appendLine("  rules:")
                        rules.forEach { rule ->
                            sb.appendLine("    - name: \"${rule.name}\"")
                            sb.appendLine("      content: \"${rule.content.replace("\"", "\\\"")}\"")
                            sb.appendLine("      enabled: ${rule.enabled}")
                        }
                    }
                }

                sb.appendLine()
            }

            // MCP (project-specific)
            config.mcp?.let { mcp ->
                mcp.servers?.let { servers ->
                    if (servers.isNotEmpty()) {
                        sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                        sb.appendLine("# MCP Server Configuration (project-specific)")
                        sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                        sb.appendLine("mcp:")
                        sb.appendLine("  servers:")
                        servers.forEach { server ->
                            sb.appendLine("    - id: \"${server.id}\"")
                            server.displayName?.let { sb.appendLine("      displayName: \"$it\"") }
                            sb.appendLine("      type: \"${server.type}\"")
                            server.command?.let { sb.appendLine("      command: \"$it\"") }
                            server.args?.let { args ->
                                if (args.isNotEmpty()) {
                                    sb.appendLine("      args: [${args.joinToString(", ") { "\"$it\"" }}]")
                                }
                            }
                            server.url?.let { sb.appendLine("      url: \"$it\"") }
                            sb.appendLine("      accessMode: \"${server.accessMode}\"")
                            sb.appendLine("      enabled: ${server.enabled}")
                            server.env?.let { envs ->
                                if (envs.isNotEmpty()) {
                                    sb.appendLine("      env:")
                                    envs.forEach { env ->
                                        sb.appendLine("        - name: \"${env.name}\"")
                                        val value = if (env.isSecret) "***" else env.value
                                        sb.appendLine("          value: \"$value\"")
                                        if (env.isSecret) sb.appendLine("          isSecret: true")
                                    }
                                }
                            }
                        }
                        sb.appendLine()
                    }
                }
            }

            return sb.toString()
        }

        private fun yamlDoubleQuoted(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
            return "\"$escaped\""
        }

        /**
         * Create example config file with all available options documented
         */
        fun createExampleConfig(): String {
            return """
# ═══════════════════════════════════════════════════════════════════════════════
# Refio Configuration File
# ═══════════════════════════════════════════════════════════════════════════════
#
# Location:
#   User config:    ~/.refio/config.yaml (Linux/macOS) or %USERPROFILE%\.refio\config.yaml (Windows)
#   Project config: <project_root>/.refio/config.yaml
#
# Configuration Hierarchy (lowest to highest priority):
#   1. Built-in defaults (hardcoded)
#   2. User config file (~/.refio/config.yaml)
#   3. Project config file (<project>/.refio/config.yaml)
#   4. Database overrides (changes made in Settings UI)
#
# All fields are optional. Missing fields use built-in defaults.
# ═══════════════════════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────────────────────
# General Settings
# ─────────────────────────────────────────────────────────────────────────────
general:
  formatMarkdown: true          # Format responses as markdown
  streamingEnabled: true        # Stream LLM responses in real-time
  advancedView: false           # Show advanced UI tabs (Steps, Context, RAG, Debug)

# ─────────────────────────────────────────────────────────────────────────────
# LLM Provider Configuration
# ─────────────────────────────────────────────────────────────────────────────
providers:
  ollama:
    endpoint: "http://localhost:11434"
    contextSize: 32768          # Context window size in tokens

  anthropic:
    apiKey: ""                  # sk-ant-...

  openai:
    apiKey: ""                  # sk-...

  openrouter:
    apiKey: ""                  # sk-or-...

  gemini:
    apiKey: ""                  # AIza...

  lmstudio:
    baseUrl: "http://localhost:1234/v1"
    contextSize: 32768

# ─────────────────────────────────────────────────────────────────────────────
# Model Configuration
# ─────────────────────────────────────────────────────────────────────────────
models:
  # Default models per operation mode (format: "provider/model-id")
  defaults:
    chat: "ollama/qwen2.5:7b"       # Default chat/conversation model
    plan: "ollama/qwen2.5:7b"       # Model for planning operations
    coding: "ollama/qwen2.5-coder:7b"  # Model for coding/agent tasks
    weak: "ollama/qwen2.5:3b"       # Cheap model for auxiliary operations
    embedding: "ollama/nomic-embed-text"  # Model for embeddings (RAG)

  # Model visibility in dropdown (format: "provider/model-id": true/false)
  visibility:
    "ollama/qwen2.5:7b": true
    "ollama/qwen2.5:14b": true
    "ollama/qwen2.5-coder:7b": true
    "openai/gpt-4o-mini": true
    "openai/gpt-4o": false          # Hidden by default (expensive)
    "anthropic/claude-3-5-sonnet-20241022": true
    "anthropic/claude-3-opus-20240229": false  # Hidden by default (expensive)

# ─────────────────────────────────────────────────────────────────────────────
# System Limits
# ─────────────────────────────────────────────────────────────────────────────
limits:
  apiCallTimeout: 240           # API call timeout in seconds
  toolExecutionTimeout: 240     # Tool execution timeout in seconds
  streamingReadTimeout: 240     # Streaming read timeout in seconds
  streamingRequestTimeout: 1800 # Total streaming request timeout in seconds
  maxContextSize: 128000        # Maximum context size in tokens
  maxOutputSize: 16384          # Maximum output size in tokens
  maxFileSize: 10               # Maximum file size in MB

# ─────────────────────────────────────────────────────────────────────────────
# Advanced Settings
# ─────────────────────────────────────────────────────────────────────────────
advanced:
  noEgressDefault: false        # Block external network calls by default
  readOnlyMode: false           # Prevent all file write operations
  autoOptimizePercentage: 85    # Auto-optimize context at this % of limit

# ─────────────────────────────────────────────────────────────────────────────
# Tool Permissions
# ─────────────────────────────────────────────────────────────────────────────
tools:
  # Permission format: { planMode: "ON"|"OFF", agentMode: "ON"|"OFF" }
  permissions:
    read_file:
      planMode: "ON"
      agentMode: "ON"
    read_directory:
      planMode: "ON"
      agentMode: "ON"
    file_search:
      planMode: "ON"
      agentMode: "ON"
    grep_search:
      planMode: "ON"
      agentMode: "ON"
    view_diff:
      planMode: "ON"
      agentMode: "ON"
    create_new_file:
      planMode: "OFF"
      agentMode: "ON"
    code_editing:
      planMode: "OFF"
      agentMode: "ON"
    advance_code_editing:
      planMode: "OFF"
      agentMode: "ON"
    multi_edit:
      planMode: "OFF"
      agentMode: "ON"
    run_terminal_command:
      planMode: "OFF"
      agentMode: "ON"          # Enabled by default in AGENT mode

# ─────────────────────────────────────────────────────────────────────────────
# RAG (Retrieval-Augmented Generation) Configuration
# ─────────────────────────────────────────────────────────────────────────────
rag:
  enabled: true                 # Enable RAG features
  indexOnStartup: true          # Index project at IDE startup
  autoIndexOnContextBuild: true # Auto-index when building context
  maxFileSizeMB: 2              # Max file size for indexing
  maxChunksPerFile: 100         # Max chunks per file
  indexBatchSize: 10            # Files per indexing batch
  embeddingsBatchSize: 50       # Embeddings per batch
  cacheTtlMs: 300000            # RAG cache TTL (5 minutes)
  maxConcurrentJobs: 4          # Max concurrent indexing jobs

  # Directories to ignore during indexing
  ignoredDirectories:
    - ".git"
    - ".idea"
    - ".vscode"
    - "node_modules"
    - "build"
    - "dist"
    - "__pycache__"
    - ".venv"
    - "target"
    - "out"

# ─────────────────────────────────────────────────────────────────────────────
# UI State (persisted between sessions)
# ─────────────────────────────────────────────────────────────────────────────
ui:
  thinkingEnabled: false        # Show LLM thinking process
  noEgressEnabled: false        # Block external network calls
  orchestrationEnabled: true    # Enable orchestration toggle
  intentClassificationEnabled: false # Enable LLM intent classification
  executionMode: "AUTO"         # AUTO or INTERACTIVE
  selectedMode: "CHAT"          # CHAT, PLAN, or AGENT
  selectedModel: ""             # Currently selected model (empty = auto)

# ═══════════════════════════════════════════════════════════════════════════════
# PROJECT-SPECIFIC SETTINGS (only in <project>/.refio/config.yaml)
# ═══════════════════════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────────────────────
# Custom Prompts (project-specific)
# ─────────────────────────────────────────────────────────────────────────────
# prompts:
#   systemChat: |
#     You are a helpful coding assistant for this specific project.
#     Always follow the project's coding conventions.
#
#   systemPlan: |
#     You are a planning assistant. Create detailed plans for tasks.
#
#   systemAgent: |
#     You are an autonomous coding agent.
#
#   commands:
#     - name: "fix"
#       description: "Fix code issues"
#       content: "Analyze and fix any issues in the selected code."
#       enabled: true
#
#     - name: "refactor"
#       description: "Refactor code"
#       content: "Refactor the selected code for better readability."
#       enabled: true
#
#   rules:
#     - name: "coding-style"
#       content: "Always use 4-space indentation."
#       enabled: true

# ─────────────────────────────────────────────────────────────────────────────
# MCP Server Configuration (project-specific)
# ─────────────────────────────────────────────────────────────────────────────
# mcp:
#   servers:
#     - id: "github"
#       displayName: "GitHub"
#       type: "STDIO"
#       command: "npx"
#       args: ["-y", "@modelcontextprotocol/server-github"]
#       accessMode: "READ"
#       enabled: true
#       env:
#         - name: "GITHUB_TOKEN"
#           value: ""
#           isSecret: true
#
#     - id: "filesystem"
#       displayName: "Filesystem"
#       type: "STDIO"
#       command: "npx"
#       args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed"]
#       accessMode: "READ_WRITE"
#       enabled: true
""".trimIndent()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Configuration Data Classes
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * General UI and behavior settings
 */
@Serializable
data class GeneralConfig(
    val formatMarkdown: Boolean? = null,
    val streamingEnabled: Boolean? = null,
    val advancedView: Boolean? = null
)

/**
 * Provider configurations
 */
@Serializable
data class ProvidersConfig(
    val ollama: OllamaConfig? = null,
    val anthropic: AnthropicConfig? = null,
    val openai: OpenAIConfig? = null,
    val openrouter: OpenRouterConfig? = null,
    val gemini: GeminiConfig? = null,
    val lmstudio: LMStudioConfig? = null,
    @SerialName("generic_openai")
    val genericOpenai: GenericOpenAIConfig? = null,
    val zai: ZAIConfig? = null
)

@Serializable
data class OllamaConfig(
    val endpoint: String? = null,
    val contextSize: Int? = null,
    val keepAlive: Int? = null
)

@Serializable
data class AnthropicConfig(
    val apiKey: String? = null
)

@Serializable
data class OpenAIConfig(
    val apiKey: String? = null
)

@Serializable
data class OpenRouterConfig(
    val apiKey: String? = null
)

@Serializable
data class GeminiConfig(
    val apiKey: String? = null
)

@Serializable
data class LMStudioConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val contextSize: Int? = null
)

@Serializable
data class GenericOpenAIConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null
)

@Serializable
data class ZAIConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null
)

/**
 * Model configuration
 */
@Serializable
data class ModelsConfig(
    val default: String? = null,  // Legacy single default model
    val defaults: ModelDefaultsConfig? = null,
    val visibility: Map<String, Boolean>? = null,
    val presets: List<ModelPresetConfig>? = null
)

@Serializable
data class ModelDefaultsConfig(
    val chat: String? = null,
    val plan: String? = null,
    val coding: String? = null,
    val weak: String? = null,
    val embedding: String? = null,
    val strong: String? = null
)

@Serializable
data class ModelPresetConfig(
    val name: String,
    val description: String? = null,
    val defaultModel: String,
    val planModel: String? = null,
    val codingModel: String? = null,
    val weakModel: String? = null,
    val strongModel: String? = null,
    val visibleModels: List<String>? = null
)

/**
 * System limits configuration
 */
@Serializable
data class LimitsConfig(
    val apiCallTimeout: Int? = null,
    val toolExecutionTimeout: Int? = null,
    val streamingReadTimeout: Int? = null,
    val streamingRequestTimeout: Int? = null,
    val maxContextSize: Int? = null,
    val maxOutputSize: Int? = null,
    val maxFileSize: Int? = null
)

/**
 * Advanced settings
 */
@Serializable
data class AdvancedConfig(
    val noEgressDefault: Boolean? = null,
    val readOnlyMode: Boolean? = null,
    val autoOptimizePercentage: Int? = null,
    val orchestrationEnabled: Boolean? = null
)

/**
 * Tool permissions configuration
 */
@Serializable
data class ToolsConfig(
    val permissions: Map<String, ToolPermissionConfig>? = null
)

@Serializable
data class ToolPermissionConfig(
    val planMode: String? = null,  // "ON" or "OFF"
    val agentMode: String? = null  // "ON" or "OFF"
)

/**
 * RAG configuration
 */
@Serializable
data class RagConfig(
    val enabled: Boolean? = null,
    val indexOnStartup: Boolean? = null,
    val autoIndexOnContextBuild: Boolean? = null,
    val maxFileSizeMB: Long? = null,
    val maxChunksPerFile: Int? = null,
    val indexBatchSize: Int? = null,
    val embeddingsBatchSize: Int? = null,
    val cacheTtlMs: Long? = null,
    val maxConcurrentJobs: Int? = null,
    val ignoredDirectories: List<String>? = null,
    val searchSimilarityThreshold: Float? = null,
    val searchTopK: Int? = null,
    val searchHybridEnabled: Boolean? = null,
    val searchSemanticWeight: Float? = null,
    val searchIncludeContextChunks: Boolean? = null
)

/**
 * UI state configuration
 */
@Serializable
data class UiConfig(
    val thinkingEnabled: Boolean? = null,
    val noEgressEnabled: Boolean? = null,
    val executionMode: String? = null,
    val selectedMode: String? = null,
    val selectedModel: String? = null
)

/**
 * Prompts configuration (project-specific)
 */
@Serializable
data class PromptsConfig(
    val systemChat: String? = null,
    val systemPlan: String? = null,
    val systemAgent: String? = null,
    val commands: List<CommandConfig>? = null,
    val rules: List<RuleConfig>? = null
)

@Serializable
data class CommandConfig(
    val name: String,
    val description: String? = null,
    val content: String,
    val enabled: Boolean = true
)

@Serializable
data class RuleConfig(
    val name: String,
    val content: String,
    val enabled: Boolean = true
)

/**
 * MCP server configuration (project-specific)
 */
@Serializable
data class McpConfig(
    val servers: List<McpServerConfig>? = null
)

@Serializable
data class McpServerConfig(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val type: String = "STDIO",  // STDIO or HTTP
    val command: String? = null,
    val args: List<String>? = null,
    val workingDirectory: String? = null,
    val url: String? = null,
    val accessMode: String = "READ",  // READ or READ_WRITE
    val enabled: Boolean = true,
    val env: List<McpEnvConfig>? = null,
    val httpHeaders: List<McpHeaderConfig>? = null,
    val timeout: Int? = null,
    val retryAttempts: Int? = null
)

@Serializable
data class McpEnvConfig(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

@Serializable
data class McpHeaderConfig(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

/**
 * Hooks configuration — user-defined actions triggered on agent lifecycle events.
 * Configured in .refio/config.yaml under the `hooks` key.
 */
@Serializable
data class HooksConfig(
    @SerialName("before_turn_loop")
    val beforeTurnLoop: List<HookDefinition>? = null,
    @SerialName("after_turn_loop")
    val afterTurnLoop: List<HookDefinition>? = null,
    @SerialName("before_tool")
    val beforeTool: List<HookDefinition>? = null,
    @SerialName("after_tool")
    val afterTool: List<HookDefinition>? = null,
    @SerialName("on_agent_complete")
    val onAgentComplete: List<HookDefinition>? = null,
    @SerialName("on_agent_error")
    val onAgentError: List<HookDefinition>? = null
)

@Serializable
data class HookDefinition(
    val action: String,
    val command: String? = null,
    val message: String? = null,
    val match: String? = null,
    val modes: List<String>? = null,
    val timeout: Long? = null
)
