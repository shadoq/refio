package pl.jclab.refio.core.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.*
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.turn.*
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.CppLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.CssLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.GoLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.HtmlLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.JavaLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.KotlinLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.PythonLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.RustLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.TypeScriptLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.workflow.IntentRouter
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("CoreApiRouter")

/**
 * Core API Router - Internal API layer (no HTTP transport)
 *
 * This is the API boundary between plugin UI and core logic.
 * Plugin code calls methods on this router, NOT services directly.
 *
 * This design allows adding HTTP transport in the future without
 * changing plugin code.
 */
class CoreApiRouter(
    private val toolRegistry: ToolRegistry? = null,
    private val projectRoot: java.nio.file.Path? = null,
    private val ideProject: Any? = null,
    private val llmClientOverride: LLMClient? = null,
    /** Platform-agnostic project handle. When provided, ideProject is derived from platformProject. */
    val projectHandle: pl.jclab.refio.core.project.ProjectHandle? = null,
    /** Callback to invalidate codebase context cache after RAG operations. Set by plugin layer. */
    private val codebaseCacheInvalidator: (projectRoot: String) -> Unit = {}
) {
    private val routerProjectId: String? = projectHandle?.id ?: projectRoot?.let { ProjectIdGenerator.generate(it) }
    private val routerProjectPath: String? = projectHandle?.rootPath?.toAbsolutePath()?.normalize()?.toString()
        ?: projectRoot?.toAbsolutePath()?.normalize()?.toString()

    /** Resolved IDE project — from projectHandle.platformProject or direct ideProject param */
    private val resolvedIdeProject: Any?
        get() = ideProject ?: projectHandle?.platformProject

    // Repositories
    private val chatMessageRepository = ChatMessageRepository()
    private val subtaskRepository = SubtaskRepository()
    private val configRepository = ConfigRepository()
    private val apiLogRepository = ApiLogRepository()
    private val promptsRepository = PromptsRepository()
    private val ragRepository = RagRepository()
    private val documentationRepository = DocumentationRepository()
    private val snapshotRepository = SnapshotRepository()
    private val projectAnalysisReportRepository = ProjectAnalysisReportRepository()
    private val agentSessionRepository = pl.jclab.refio.core.db.repositories.AgentSessionRepository()
    private val agentInstanceRepository = pl.jclab.refio.core.db.repositories.AgentInstanceRepository()

    // Multi-agent infrastructure
    val agentEventBus = pl.jclab.refio.core.agents.events.AgentEventBus().apply {
        // Persist all events so Session Trace / Timeline / Graph can be replayed
        // when the user reloads a session from history.
        setRepository(pl.jclab.refio.core.db.repositories.AgentEventSqlRepository())
    }

    // Hook system
    private val hookExecutor = pl.jclab.refio.core.services.hooks.HookExecutor()
    private val hookService = pl.jclab.refio.core.services.hooks.HookService(
        configProvider = { pl.jclab.refio.core.config.HierarchicalConfigLoader.getInstance(projectRoot).getHooks() },
        hookExecutor = hookExecutor
    )

    // Single source of truth for prompt section providers.
    // IMPORTANT: this same list must be used by both TurnPromptBuilder (runtime)
    // and ProjectContextRouter (preview) so the Context panel shows the actual
    // prompt the model receives. Previously preview used a stripped-down path
    // and e.g. <system_environment> was invisible in the Context panel even
    // though the real agent call included it.
    val promptSectionProviders: List<pl.jclab.refio.core.services.turn.PromptSectionProvider> by lazy {
        listOf(
            AgentPlansSectionProvider(agentPlanService),
            pl.jclab.refio.core.services.turn.providers.SystemEnvironmentPromptProvider(projectRoot)
        )
    }

    // Services (public for cross-module access by plugin services)
    val taskRepository = TaskRepository()
    val configService = ConfigService(
        configRepository = configRepository,
        defaultProjectId = routerProjectId
    )
    private val promptRegistry = pl.jclab.refio.core.prompts.PromptRegistry(projectRoot)
    val promptsService = PromptsService(promptsRepository, promptRegistry)
    val toolPermissionsService = ToolPermissionsService(
        configRepository = configRepository,
        toolRegistry = toolRegistry
    )
    val toolApprovalService = ToolApprovalService()
    val pendingUserMessageQueue = PendingUserMessageQueue(chatMessageRepository)
    val llmClient = llmClientOverride ?: LLMClient(configService)
    private val workingMemoryService = WorkingMemoryService()
    private val workingMemoryIntegration = WorkingMemoryIntegration(workingMemoryService)
    val agentPlanService = AgentPlanService()
    private val conversationSummaryService = ConversationSummaryService(
        llmClient = llmClient,
        promptsService = promptsService,
        configService = configService,
        chatMessageRepository = chatMessageRepository
    )

    // User interaction service (public for UI to detect waiting state)
    val userInteraction = pl.jclab.refio.core.services.orchestration.UserInteraction(
        chatMessageRepository = chatMessageRepository
    )

    /**
     * Get the ToolRegistry for this router.
     * Used by MCPManager to register MCP tools.
     */
    fun getToolRegistry(): ToolRegistry {
        return toolRegistry ?: throw IllegalStateException("ToolRegistry not available for this router")
    }

    fun hasIdeProject(): Boolean {
        return resolvedIdeProject != null
    }

    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Analysis stack — embeddings / RAG / analyzer / context / snapshot.
    // Null-valued fields when projectRoot is absent (app-level router).
    private val analysisStack = pl.jclab.refio.core.api.modules.AnalysisStack(
        projectRoot = projectRoot,
        configService = configService,
        ragRepository = ragRepository,
        snapshotRepository = snapshotRepository,
        analysisReportRepository = projectAnalysisReportRepository,
        taskRepository = taskRepository,
        chatMessageRepository = chatMessageRepository,
        subtaskRepository = subtaskRepository,
        workingMemoryService = workingMemoryService,
        conversationSummaryService = conversationSummaryService,
        scope = routerScope,
        embeddingProviderFactory = ::embeddingProviderFor
    )
    private val embeddingsService get() = analysisStack.embeddingsService
    private val fileAnalyzerService get() = analysisStack.fileAnalyzerService
    private val richProjectAnalysisEngine get() = analysisStack.richProjectAnalysisEngine
    val projectAnalyzer get() = analysisStack.projectAnalyzer
    private val contextService get() = analysisStack.contextService
    private val snapshotService get() = analysisStack.snapshotService

    // Tool description builder (needs ToolRegistry and ToolPermissionsService)
    private val toolDescriptionBuilder = pl.jclab.refio.core.prompts.ToolDescriptionBuilder(
        toolRegistry = toolRegistry ?: ToolRegistry(), // Fallback to empty registry if not provided
        toolPermissionsService = toolPermissionsService
    )

    private val chatService = ChatService(
        taskRepository = taskRepository,
        chatMessageRepository = chatMessageRepository,
        configService = configService,
        llmClient = llmClient,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        contextService = contextService,
        projectRoot = projectRoot,
        ideProject = resolvedIdeProject
    )
    private val planningService = PlanningService(
        taskRepository = taskRepository,
        chatMessageRepository = chatMessageRepository,
        subtaskRepository = subtaskRepository,
        configService = configService,
        llmClient = llmClient,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        toolRegistry = toolRegistry,
        toolPermissionsService = toolPermissionsService,
        contextService = contextService,
        projectRoot = projectRoot,
        ideProject = resolvedIdeProject
    )

    // Agent execution services (optional if toolRegistry not provided)
    private val stepPlanner: StepPlanner? = if (toolRegistry != null) {
        StepPlanner(
            taskRepository,
            subtaskRepository,
            toolRegistry,
            llmClient,
            promptsService,
            toolDescriptionBuilder,
            configService,
            toolPermissionsService,
            contextService,
            projectRoot
        )
    } else null

    private val stepSummarizer = StepSummarizer(
        llmClient = llmClient,
        promptsService = promptsService,
        configService = configService,
        taskRepository = taskRepository
    )

    private val toolExecutor: ToolExecutor? = if (toolRegistry != null) {
        ToolExecutor(
            toolRegistry = toolRegistry,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            snapshotService = snapshotService,
            toolPermissionsService = toolPermissionsService,
            mode = TaskMode.AGENT,
            executionMode = pl.jclab.refio.api.models.ExecutionMode.AUTO
        )
    } else null

    private val agentExecutor: AgentExecutor? = if (toolExecutor != null && stepPlanner != null) {
        AgentExecutor(
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            toolExecutor = toolExecutor,
            llmClient = llmClient,
            promptsService = promptsService,
            configService = configService,
            stepPlanner = stepPlanner
        )
    } else null

    /**
     * AgentTurnLoop - Turn-based execution loop implementing Codex CLI-style pattern.
     * Optional service (requires toolRegistry).
     * Wiring lives in [pl.jclab.refio.core.api.modules.AgentTurnLoopFactory].
     */
    private val agentTurnLoop: AgentTurnLoop? = pl.jclab.refio.core.api.modules.AgentTurnLoopFactory(
        llmClient = llmClient,
        chatMessageRepository = chatMessageRepository,
        taskRepository = taskRepository,
        subtaskRepository = subtaskRepository,
        configService = configService,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        contextService = contextService,
        workingMemoryService = workingMemoryService,
        workingMemoryIntegration = workingMemoryIntegration,
        snapshotService = snapshotService,
        toolApprovalService = toolApprovalService,
        toolPermissionsService = toolPermissionsService,
        hookService = hookService,
        agentEventBus = agentEventBus,
        promptSectionProviders = promptSectionProviders,
        projectRoot = projectRoot
    ).build(toolRegistry, toolExecutor)

    /**
     * Observable state of the current turn execution (phase, iteration, tokens, active tool).
     * Null if AgentTurnLoop is not available (no toolRegistry).
     */
    val turnState: kotlinx.coroutines.flow.StateFlow<pl.jclab.refio.core.services.turn.TurnStateSnapshot>?
        get() = agentTurnLoop?.turnState

    /**
     * Last prompt snapshot captured during turn execution.
     * Contains context decision trace and token usage.
     * Null if AgentTurnLoop is not available or no prompt has been built yet.
     */
    val lastPromptSnapshot: kotlinx.coroutines.flow.StateFlow<pl.jclab.refio.core.services.turn.PromptSnapshot?>?
        get() = agentTurnLoop?.lastPromptSnapshot

    // ========== RAG Services ==========

    private val ragSearchService: RagSearchService? by lazy {
        try {
            val embeddingModelSetting = configService.getEmbeddingModel()
            val (providerId, _) = resolveEmbeddingProvider(embeddingModelSetting)
            val embeddingProvider = embeddingProviderFor(providerId)
            RagSearchService(ragRepository, embeddingProvider)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to initialize RagSearchService: ${e.message}" }
            null
        }
    }

    // ========== Domain Routers (RFC 0005) - Public API ==========

    /**
     * Chat operations router (messages, summarization).
     * Direct access for clients that want to bypass facade methods.
     */
    val chatRouter by lazy {
        pl.jclab.refio.core.api.routers.ChatRouter(
            chatService = chatService,
            chatMessageRepository = chatMessageRepository,
            taskRepository = taskRepository
        )
    }

    /**
     * Configuration router (models, settings).
     * Direct access for clients that want to bypass facade methods.
     */
    val configRouter by lazy {
        pl.jclab.refio.core.api.routers.ConfigRouter(
            configService = configService,
            llmClient = llmClient,
            configRepository = configRepository
        )
    }

    /**
     * Tool management router (permissions, registry).
     * Direct access for clients that want to bypass facade methods.
     */
    val toolRouter by lazy {
        pl.jclab.refio.core.api.routers.ToolRouter(
            toolRegistry = toolRegistry,
            toolPermissionsService = toolPermissionsService
        )
    }

    /**
     * Agent execution router (step planning, execution).
     * Direct access for clients that want to bypass facade methods.
     */
    val agentRouter by lazy {
        pl.jclab.refio.core.api.routers.AgentRouter(
            agentExecutor = agentExecutor,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            chatMessageRepository = chatMessageRepository,
            configService = configService,
            llmClient = llmClient,
            promptsService = promptsService,
            contextService = contextService,
            projectRoot = projectRoot,
            ideProject = resolvedIdeProject,
            toolDescriptionBuilder = toolDescriptionBuilder,
            agentTurnLoop = agentTurnLoop
        )
    }

    /**
     * RAG operations router (indexing, search).
     * Direct access for clients that want to bypass facade methods.
     */
    val ragRouter by lazy {
        pl.jclab.refio.core.api.routers.RagRouter(
            ragRepository = ragRepository,
            documentationRepository = documentationRepository,
            ragSearchService = ragSearchService,
            embeddingsService = embeddingsService,
            fileAnalyzerService = fileAnalyzerService,
            projectRoot = projectRoot,
            configService = configService,
            embeddingProviderFactory = { model -> createEmbeddingProvider(model) },
            codebaseCacheInvalidator = codebaseCacheInvalidator
        )
    }

    /**
     * Task management router (CRUD, queries).
     * Direct access for clients that want to bypass facade methods.
     */
    val taskRouter by lazy {
        pl.jclab.refio.core.api.routers.TaskRouter(
            taskRepository = taskRepository,
            configService = configService,
            defaultProjectId = routerProjectId,
            defaultProjectPath = routerProjectPath
        )
    }

    /**
     * Subtask management router (CRUD, approval, ordering).
     * Direct access for clients that want to bypass facade methods.
     */
    val subtaskRouter by lazy {
        pl.jclab.refio.core.api.routers.SubtaskRouter(
            subtaskRepository = subtaskRepository
        )
    }

    /**
     * Prompts management router (system prompts, rules, slash commands).
     * Direct access for clients that want to bypass facade methods.
     */
    val promptsRouter by lazy {
        pl.jclab.refio.core.api.routers.PromptsRouter(
            promptsService = promptsService
        )
    }

    /**
     * API logs management router (logging, statistics, export).
     * Direct access for clients that want to bypass facade methods.
     */
    val apiLogsRouter by lazy {
        pl.jclab.refio.core.api.routers.ApiLogsRouter(
            apiLogRepository = apiLogRepository
        )
    }

    /**
     * Subagent management router (subagent definitions, execution).
     * Direct access for clients that want to bypass facade methods.
     */
    val subagentRouter: pl.jclab.refio.core.subagents.SubagentRouter? by lazy {
        if (projectRoot != null && toolRegistry != null) {
            pl.jclab.refio.core.subagents.SubagentRouter(
                projectRoot = projectRoot,
                toolRegistry = toolRegistry,
                configService = configService,
                llmClient = llmClient,
                toolPermissionsService = toolPermissionsService,
                chatMessageRepository = chatMessageRepository,
                contextService = contextService,
                ideProject = resolvedIdeProject,
                runTurnCallback = { request, callback ->
                    agentRouter.runTurn(
                        request = request,
                        streamCallback = callback,
                        listener = null
                    )
                }
            )
        } else {
            null
        }
    }

    /**
     * File snapshot router (pre-edit backups, rollback).
     */
    val snapshotRouter by lazy {
        pl.jclab.refio.core.api.routers.SnapshotRouter(
            snapshotService = snapshotService,
            snapshotRepository = snapshotRepository
        )
    }

    /**
     * Project context and analysis router (context panel, prompt preview).
     */
    val projectContextRouter by lazy {
        pl.jclab.refio.core.api.routers.ProjectContextRouter(
            contextService = contextService,
            projectRoot = projectRoot,
            ideProject = resolvedIdeProject,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            promptsService = promptsService,
            toolDescriptionBuilder = toolDescriptionBuilder,
            projectAnalyzer = projectAnalyzer,
            richProjectAnalysisEngine = richProjectAnalysisEngine,
            promptSectionProviders = promptSectionProviders
        )
    }

    /**
     * Workflow orchestrator — dispatches intents directly to domain services.
     */
    val workflowOrchestrator by lazy {
        val intentRouter = IntentRouter(
            subtaskRepository = subtaskRepository,
            subagentRouter = subagentRouter
        )
        WorkflowOrchestrator(
            intentRouter = intentRouter,
            chatService = chatService,
            planningService = planningService,
            agentRouter = agentRouter,
            subagentRouter = subagentRouter,
            userInteraction = userInteraction
        )
    }

    /**
     * Multi-agent runner for parallel agent orchestration.
     */
    val multiAgentRunner by lazy {
        pl.jclab.refio.core.agents.MultiAgentRunner(agentEventBus)
    }

    /**
     * Multi-agent session management router.
     */
    val multiAgentRouter by lazy {
        pl.jclab.refio.core.api.routers.MultiAgentRouter(
            defaultProjectId = routerProjectId,
            defaultProjectPath = routerProjectPath,
            agentSessionRepository = agentSessionRepository,
            agentInstanceRepository = agentInstanceRepository,
            multiAgentRunner = multiAgentRunner,
            createTaskFn = { request -> taskRouter.createTask(request) },
            runTurnFn = { request, callback -> agentRouter.runTurn(request, callback) }
        )
    }

    init {
        if (toolRegistry != null && projectRoot != null) {
            val runTurnCallback: suspend (pl.jclab.refio.core.api.TurnRequest, pl.jclab.refio.core.services.turn.TurnEventListener?, pl.jclab.refio.core.api.StreamCallback?) -> pl.jclab.refio.core.services.TurnResult =
                { request, turnEventListener, streamCallback ->
                    agentRouter.runTurn(
                        request = request,
                        streamCallback = streamCallback,
                        listener = turnEventListener?.let {
                            pl.jclab.refio.core.services.AgentTurnLoop.TurnEventListener.fromTurnEventListener(it)
                        }
                    )
                }

            pl.jclab.refio.core.api.modules.SystemToolsRegistrar(
                configService = configService,
                llmClient = llmClient,
                agentPlanService = agentPlanService,
                workingMemoryService = workingMemoryService,
                subtaskRepository = subtaskRepository,
                agentEventBus = agentEventBus,
                subagentRouterProvider = { subagentRouter },
                runTurnCallback = runTurnCallback
            ).register(toolRegistry)
        }

        // Apply Ollama concurrency from config
        val ollamaMaxConcurrent = configService.get(ConfigService.KEY_OLLAMA_MAX_CONCURRENT)?.toIntOrNull()
        if (ollamaMaxConcurrent != null && ollamaMaxConcurrent > 0) {
            OllamaRequestGate.maxConcurrentPerEndpoint = ollamaMaxConcurrent
            logger.info { "CoreApiRouter: Ollama maxConcurrent set to $ollamaMaxConcurrent" }
        }

        logger.info { "CoreApiRouter initialized with services" }
        if (contextService != null) {
            logger.info { "CoreApiRouter: ContextService initialized with projectRoot=$projectRoot" }
        } else {
            logger.warn { "CoreApiRouter: ContextService NOT available (projectRoot not provided)" }
        }
        logger.info { "CoreApiRouter: ideProject available=${resolvedIdeProject != null}, projectHandle=${projectHandle != null}" }
        if (toolRegistry != null) {
            logger.info { "CoreApiRouter: Agent execution services initialized" }
        } else {
            logger.warn { "CoreApiRouter: Agent execution services NOT available (toolRegistry not provided)" }
        }
    }

    // configService is accessible directly as a public property

    /**
     * Create a project-level router from this app-level router.
     *
     * Shares the same database but creates project-specific tools and services.
     * Used by StandaloneCoreBootstrap and CoreConnectionManager.
     *
     * @param projectRoot Project root directory
     * @param projectHandle Platform-agnostic project handle (optional)
     * @param ideProject Platform-specific project instance (optional)
     * @return Configured project-level CoreApiRouter
     */
    fun createProjectRouter(
        projectRoot: java.nio.file.Path,
        projectHandle: pl.jclab.refio.core.project.ProjectHandle? = null,
        ideProject: Any? = null
    ): CoreApiRouter {
        val toolRegistry = ToolRegistry()

        val maxFileSizeBytes = configService.getTyped(ConfigKeys.MAX_FILE_SIZE).toLong() * 1024 * 1024
        val fileLimits = pl.jclab.refio.core.tools.security.FileLimits(maxFileSize = maxFileSizeBytes)

        val toolFactory = pl.jclab.refio.core.tools.base.ToolFactory(
            projectRoot = projectRoot,
            toolRegistry = toolRegistry,
            llmClient = llmClient,
            configService = configService,
            promptsService = promptsService,
            taskRepository = taskRepository,
            fileLimits = fileLimits
        )
        val tools = toolFactory.createAllTools()
        tools.forEach { tool -> toolRegistry.register(tool) }

        return CoreApiRouter(
            toolRegistry = toolRegistry,
            projectRoot = projectRoot,
            ideProject = ideProject,
            projectHandle = projectHandle
        )
    }

    /**
     * Initialize core components (database, etc.)
     */
    fun initialize(dbPath: String = "database.sqlite") {
        logger.info { "Initializing core with dbPath=$dbPath" }
        DatabaseFactory.init(dbPath)
        promptsService.initializeDefaults()
        if (projectRoot != null && toolRegistry != null) {
            val service = ragSearchService
            if (service != null) {
                try {
                    val embeddingModelSetting = configService.getEmbeddingModel()
                    val (providerId, modelId) = resolveEmbeddingProvider(embeddingModelSetting)
                    val ragTool = pl.jclab.refio.core.tools.implementations.RagSearchTool(
                        ragSearchService = service,
                        embeddingModel = modelId,
                        projectRoot = projectRoot
                    )
                    toolRegistry.register(ragTool)
                    logger.info { "Registered rag_search tool (model=$modelId, provider=$providerId)" }
                } catch (e: IllegalArgumentException) {
                    logger.debug { "rag_search tool already registered" }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to register rag_search tool: ${e.message}" }
                }
            }
        }
    }

    /**
     * Create embedding provider based on model name.
     * Supports formats: "provider/modelId" (e.g., "ollama/nomic-embed-text") or just "modelId".
     */
    private fun createEmbeddingProvider(model: String): EmbeddingProvider {
        val (providerId, _) = resolveEmbeddingProvider(model)
        return embeddingProviderFor(providerId)
    }

    private fun resolveEmbeddingProvider(model: String): Pair<String, String> {
        return if (model.contains("/")) {
            val parts = model.split("/", limit = 2)
            parts[0].lowercase() to parts[1]
        } else {
            val provider = when {
                model.startsWith("text-embedding") -> "openai"
                model in setOf("nomic-embed-text", "mxbai-embed-large", "all-minilm", "all-MiniLM-L6-v2") -> "ollama"
                else -> "openai"
            }
            provider to model
        }
    }

    private fun embeddingProviderFor(providerId: String): EmbeddingProvider {
        return when (providerId.lowercase()) {
            "ollama" -> {
                val ollamaEndpoint = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
                OllamaEmbeddingProvider(ollamaEndpoint)
            }

            "openai" -> OpenAIEmbeddingProvider()

            else -> {
                logger.warn { "Unknown embedding provider: $providerId, defaulting to OpenAI" }
                OpenAIEmbeddingProvider()
            }
        }
    }

    fun close() {
        subagentRouter?.clearTemporary()
        agentPlanService.clear()
        routerScope.cancel("CoreApiRouter closing")
    }
}


