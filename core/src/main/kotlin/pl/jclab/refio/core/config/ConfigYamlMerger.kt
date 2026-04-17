package pl.jclab.refio.core.config

/**
 * Merges two [ConfigYaml] snapshots (user + project) using section-aware rules:
 * scalars use override-wins; visibility maps and hook command lists concatenate;
 * MCP servers dedupe by `id`.
 *
 * Lives outside [ConfigYaml] so the data class isn't burdened by 150 LOC of
 * per-section null-coalescing code. Pure function; no hidden state.
 */
internal object ConfigYamlMerger {

    /** Merge two configs — values from [override] take precedence over [base]. */
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
            hooks = mergeHooks(base.hooks, override.hooks),
        )
    }

    private fun mergeGeneral(base: GeneralConfig?, override: GeneralConfig?): GeneralConfig? {
        if (base == null) return override
        if (override == null) return base
        return GeneralConfig(
            formatMarkdown = override.formatMarkdown ?: base.formatMarkdown,
            streamingEnabled = override.streamingEnabled ?: base.streamingEnabled,
            advancedView = override.advancedView ?: base.advancedView,
            thinkingEnabled = override.thinkingEnabled ?: base.thinkingEnabled,
            noEgressEnabled = override.noEgressEnabled ?: base.noEgressEnabled,
            executionMode = override.executionMode ?: base.executionMode,
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
            zai = override.zai ?: base.zai,
        )
    }

    private fun mergeModels(base: ModelsConfig?, override: ModelsConfig?): ModelsConfig? {
        if (base == null) return override
        if (override == null) return base
        return ModelsConfig(
            default = override.default ?: base.default,
            defaults = override.defaults ?: base.defaults,
            visibility = mergeVisibility(base.visibility, override.visibility),
            presets = override.presets ?: base.presets,
        )
    }

    private fun mergeVisibility(base: Map<String, Boolean>?, override: Map<String, Boolean>?): Map<String, Boolean>? {
        if (base == null) return override
        if (override == null) return base
        return base + override
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
            maxFileSize = override.maxFileSize ?: base.maxFileSize,
        )
    }

    private fun mergeAdvanced(base: AdvancedConfig?, override: AdvancedConfig?): AdvancedConfig? {
        if (base == null) return override
        if (override == null) return base
        return AdvancedConfig(
            readOnlyMode = override.readOnlyMode ?: base.readOnlyMode,
            autoOptimizePercentage = override.autoOptimizePercentage ?: base.autoOptimizePercentage,
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
            searchIncludeContextChunks = override.searchIncludeContextChunks ?: base.searchIncludeContextChunks,
        )
    }

    private fun mergeUi(base: UiConfig?, override: UiConfig?): UiConfig? {
        if (base == null) return override
        if (override == null) return base
        return UiConfig(
            selectedMode = override.selectedMode ?: base.selectedMode,
            selectedModel = override.selectedModel ?: base.selectedModel,
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
            rules = mergeList(base.rules, override.rules),
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
            onAgentError = override.onAgentError ?: base.onAgentError,
        )
    }
}
