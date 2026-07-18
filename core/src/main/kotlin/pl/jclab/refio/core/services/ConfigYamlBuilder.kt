package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.*
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.adapters.ZAIUrls
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.config.ConfigKeys

/**
 * Builds a [ConfigYaml] snapshot from the current database values.
 *
 * Extracted from [ConfigService] to keep responsibilities narrow:
 * ConfigService handles reads/writes + caching; ConfigYamlBuilder composes
 * the cross-section YAML representation used by `exportToYaml`.
 */
internal class ConfigYamlBuilder(
    private val configService: ConfigService,
    private val configRepository: ConfigRepository,
    private val toolPermissionsService: ToolPermissionsService? = null,
    private val projectRoot: java.nio.file.Path? = null
) {
    fun build(includeApiKeys: Boolean, projectId: String? = null): ConfigYaml = ConfigYaml(
        general = buildGeneral(),
        providers = buildProviders(includeApiKeys),
        models = buildModels(),
        limits = buildLimits(),
        advanced = buildAdvanced(),
        tools = buildTools(),
        rag = buildRag(),
        ui = buildUi(),
        mcp = buildMcp(projectId, includeApiKeys),
        context = buildContext(),
        docs = buildDocs(),
        hooks = buildHooks()
    )

    private fun buildGeneral() = GeneralConfig(
        formatMarkdown = configService.getTyped(ConfigKeys.FORMAT_MARKDOWN),
        streamingEnabled = configService.getTyped(ConfigKeys.STREAMING_ENABLED),
        advancedView = configService.getTyped(ConfigKeys.ADVANCED_VIEW),
        reasoningEffort = configService.getTyped(ConfigKeys.GENERAL_REASONING_EFFORT).name,
        noEgressEnabled = configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED),
        executionMode = configService.getTyped(ConfigKeys.GENERAL_EXECUTION_MODE)
    )

    private fun buildProviders(includeApiKeys: Boolean): ProvidersConfig {
        val ollamaEndpoint = configService.get(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT.key)
        val lmstudioBaseUrl = configService.get(ConfigKeys.PROVIDER_LM_STUDIO_BASE_URL.key)
        val customOpenAIBaseUrl = configService.get(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL.key)

        return ProvidersConfig(
            ollama = OllamaConfig(
                endpoint = ollamaEndpoint ?: configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT),
                contextSize = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE),
                keepAlive = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_KEEP_ALIVE)
            ),
            anthropic = if (includeApiKeys) AnthropicConfig(apiKey = configService.get(ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key)) else null,
            openai = if (includeApiKeys) OpenAIConfig(apiKey = configService.get(ConfigKeys.PROVIDER_OPENAI_API_KEY.key)) else null,
            openrouter = if (includeApiKeys) OpenRouterConfig(apiKey = configService.get(ConfigKeys.PROVIDER_OPENROUTER_API_KEY.key)) else null,
            gemini = if (includeApiKeys) GeminiConfig(apiKey = configService.get(ConfigKeys.PROVIDER_GEMINI_API_KEY.key)) else null,
            lmstudio = LMStudioConfig(
                apiKey = if (includeApiKeys) configService.get(ConfigKeys.PROVIDER_LM_STUDIO_API_KEY.key) else null,
                baseUrl = lmstudioBaseUrl,
                contextSize = configService.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE)
            ),
            genericOpenai = GenericOpenAIConfig(
                apiKey = if (includeApiKeys) configService.get(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY.key) else null,
                baseUrl = customOpenAIBaseUrl,
                model = configService.get(ConfigKeys.PROVIDER_CUSTOM_OPENAI_MODEL.key)
            ),
            zai = ZAIConfig(
                apiKey = if (includeApiKeys) configService.get(ConfigKeys.PROVIDER_ZAI_API_KEY.key) else null,
                baseUrl = configService.get(ConfigKeys.PROVIDER_ZAI_BASE_URL.key) ?: ZAIUrls.DEFAULT
            )
        )
    }

    private fun buildModels(): ModelsConfig {
        val (chatModel, chatProvider) = configService.getDefaultModel(ModelOperation.DEFAULT)
        val (planModel, planProvider) = configService.getDefaultModel(ModelOperation.PLAN)
        val (codingModel, codingProvider) = configService.getDefaultModel(ModelOperation.CODING)
        val (weakModel, weakProvider) = configService.getDefaultModel(ModelOperation.WEAK)
        val (embeddingModel, embeddingProvider) = configService.getDefaultModel(ModelOperation.EMBEDDING)

        return ModelsConfig(
            defaults = ModelDefaultsConfig(
                chat = "$chatProvider/$chatModel",
                plan = "$planProvider/$planModel",
                coding = "$codingProvider/$codingModel",
                weak = "$weakProvider/$weakModel",
                embedding = "$embeddingProvider/$embeddingModel"
            ),
            visibility = configService.getModelsVisibility()
        )
    }

    private fun buildLimits() = LimitsConfig(
        apiCallTimeout = configService.getTyped(ConfigKeys.API_CALL_TIMEOUT),
        toolExecutionTimeout = configService.getTyped(ConfigKeys.TOOL_EXECUTION_TIMEOUT),
        streamingReadTimeout = configService.getTyped(ConfigKeys.STREAMING_READ_TIMEOUT),
        streamingRequestTimeout = configService.getTyped(ConfigKeys.STREAMING_REQUEST_TIMEOUT),
        maxContextSize = configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE),
        maxOutputSize = configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE),
        maxFileSize = configService.getTyped(ConfigKeys.MAX_FILE_SIZE)
    )

    private fun buildAdvanced() = AdvancedConfig(
        readOnlyMode = configService.getTyped(ConfigKeys.READ_ONLY_MODE),
        autoOptimizePercentage = configService.getTyped(ConfigKeys.AUTO_OPTIMIZE_PERCENTAGE)
    )

    private fun buildTools(): ToolsConfig {
        // Prefer ToolPermissionsService: enumerates all registered tools and merges
        // stored overrides over smart defaults derived from ToolMode.
        val merged: Map<String, pl.jclab.refio.core.services.ToolPermissionConfig> =
            toolPermissionsService?.getPermissions()
                ?: run {
                    val config = configRepository.get(ConfigKeys.TOOLS_PERMISSIONS.key, ConfigScope.APP)
                        ?: return ToolsConfig()
                    runCatching { gson.fromJson(config.value, ToolPermissions::class.java) }
                        .getOrNull()?.tools ?: return ToolsConfig()
                }
        if (merged.isEmpty()) return ToolsConfig()

        val yamlPermissions = merged.toSortedMap().mapValues { (_, cfg) ->
            pl.jclab.refio.core.config.ToolPermissionConfig(
                planMode = cfg.planMode.name,
                agentMode = cfg.agentMode.name
            )
        }
        return ToolsConfig(permissions = yamlPermissions)
    }

    private fun buildMcp(projectId: String?, includeApiKeys: Boolean): McpConfig? {
        val servers = MCPManager.getAllServers(projectId)
        if (servers.isEmpty()) return null

        val yamlServers = servers.map { s ->
            McpServerConfig(
                id = s.id,
                displayName = s.displayName,
                description = s.description,
                type = s.type.name,
                command = s.command,
                args = s.args.takeIf { it.isNotEmpty() },
                // workingDirectory intentionally omitted: it is per-project (UI
                // defaults to project.basePath) and should not leak into user-level
                // config.yaml. MCP stdio processes inherit the JVM's CWD at runtime.
                workingDirectory = null,
                url = s.url,
                accessMode = s.accessMode.name,
                enabled = s.enabled,
                env = s.env.takeIf { it.isNotEmpty() }?.map { e ->
                    McpEnvConfig(
                        name = e.name,
                        value = if (e.isSecret && !includeApiKeys) "***" else e.value,
                        isSecret = e.isSecret
                    )
                },
                httpHeaders = s.httpHeaders.takeIf { it.isNotEmpty() }?.map { h ->
                    McpHeaderConfig(
                        name = h.name,
                        value = if (h.isSecret && !includeApiKeys) "***" else h.value,
                        isSecret = h.isSecret
                    )
                },
                timeout = s.timeout.toInt(),
                retryAttempts = s.retryAttempts,
                auth = s.auth?.let { a ->
                    McpAuthYamlConfig(
                        type = a.type.name,
                        apiKey = if (includeApiKeys) a.apiKey else a.apiKey?.let { "***" }
                    )
                },
                serverInstructions = s.serverInstructions,
                resourcesEnabled = s.resourcesEnabled,
                toolsEnabled = s.toolsEnabled,
                promptsEnabled = s.promptsEnabled,
                toolsExposureMode = s.toolsExposureMode?.name,
                contextToolName = s.contextToolName,
                contextToolQueryParam = s.contextToolQueryParam,
                toolParamMapping = s.toolParamMapping.takeIf { it.isNotEmpty() }
            )
        }
        return McpConfig(servers = yamlServers)
    }

    private fun buildRag() = RagConfig(
        enabled = configService.getTyped(ConfigKeys.RAG_ENABLED),
        indexOnStartup = configService.getTyped(ConfigKeys.RAG_INDEX_ON_STARTUP),
        autoIndexOnContextBuild = configService.getTyped(ConfigKeys.RAG_AUTO_INDEX_ON_CONTEXT),
        maxFileSizeMB = configService.getTyped(ConfigKeys.RAG_MAX_FILE_SIZE_MB),
        maxChunksPerFile = configService.getTyped(ConfigKeys.RAG_MAX_CHUNKS_PER_FILE),
        indexBatchSize = configService.getTyped(ConfigKeys.RAG_INDEX_BATCH_SIZE),
        embeddingsBatchSize = configService.getTyped(ConfigKeys.RAG_EMBEDDINGS_BATCH_SIZE),
        cacheTtlMs = configService.getTyped(ConfigKeys.RAG_CACHE_TTL_MS),
        maxConcurrentJobs = configService.getTyped(ConfigKeys.RAG_MAX_CONCURRENT_JOBS),
        ignoredDirectories = configService.getTyped(ConfigKeys.RAG_IGNORED_DIRECTORIES),
        searchSimilarityThreshold = configService.getTyped(ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD),
        searchTopK = configService.getTyped(ConfigKeys.RAG_SEARCH_TOP_K),
        searchHybridEnabled = configService.getTyped(ConfigKeys.RAG_SEARCH_HYBRID_ENABLED),
        searchSemanticWeight = configService.getTyped(ConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT),
        searchIncludeContextChunks = configService.getTyped(ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)
    )

    private fun buildContext(): ContextConfig? {
        val recentFull = configRepository.get(ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT.key, ConfigScope.APP)?.value?.toIntOrNull()
        val recentSummary = configRepository.get(ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH.key, ConfigScope.APP)?.value?.toIntOrNull()
        val budgetTotal = configRepository.get(ConfigKeys.CONTEXT_BUDGET_TOTAL_TOKENS.key, ConfigScope.APP)?.value?.toIntOrNull()
        val budgetRatio = configRepository.get(ConfigKeys.CONTEXT_BUDGET_INPUT_RATIO.key, ConfigScope.APP)?.value?.toDoubleOrNull()
        val workingMemMax = configRepository.get(ConfigKeys.WORKING_MEMORY_MAX_FACTS.key, ConfigScope.APP)?.value?.toIntOrNull()

        val sectionPrefix = ConfigService.KEY_CONTEXT_BUDGET_SECTION_PREFIX
        val sectionBudgets = configRepository.search("$sectionPrefix%", ConfigScope.APP)
            .mapNotNull { cfg ->
                val name = cfg.key.removePrefix(sectionPrefix).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val v = cfg.value.toIntOrNull() ?: return@mapNotNull null
                name to v
            }
            .toMap()
            .takeIf { it.isNotEmpty() }

        if (recentFull == null && recentSummary == null && budgetTotal == null &&
            budgetRatio == null && workingMemMax == null && sectionBudgets == null
        ) return null

        return ContextConfig(
            recentWorkFullDataLimit = recentFull,
            recentWorkSummaryMaxLength = recentSummary,
            budgetTotalTokens = budgetTotal,
            budgetInputRatio = budgetRatio,
            workingMemoryMaxFacts = workingMemMax,
            budgetSections = sectionBudgets
        )
    }

    private fun buildDocs(): DocsConfig? {
        val root = projectRoot ?: return null
        val repo = pl.jclab.refio.core.db.repositories.DocumentationRepository()
        val sources = runCatching { repo.getDocSources(root.toString()) }.getOrNull().orEmpty()
        if (sources.isEmpty()) return null
        return DocsConfig(
            sources = sources.map { s ->
                DocsSourceConfig(
                    url = s.url,
                    sourceType = s.sourceType.name,
                    filePath = s.filePath,
                    title = s.title,
                    description = s.description,
                    crawlDepth = s.crawlDepth,
                    status = s.status.name,
                    pagesIndexed = s.pagesIndexed,
                    totalPages = s.totalPages,
                    lastIndexed = s.lastIndexed
                )
            }
        )
    }

    private fun buildHooks(): HooksConfig? = configService.yamlLoader.getHooks()

    private fun buildUi() = UiConfig(
        selectedMode = configService.getTyped(ConfigKeys.UI_SELECTED_MODE),
        selectedModel = configService.getTyped(ConfigKeys.UI_SELECTED_MODEL)
    )
}
