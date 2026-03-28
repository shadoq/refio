package pl.jclab.refio.core.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.sql.*
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRepository
import pl.jclab.refio.core.db.repositories.PromptsRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.TokenEstimator
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.models.api.SummarizeResponse
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnPromptBuilder
import pl.jclab.refio.core.services.turn.ToolCallParser
import pl.jclab.refio.core.services.turn.TurnResponseProcessor
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.CppLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.CssLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.HtmlLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.JavaLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.KotlinLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.PythonLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.TypeScriptLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.workflow.IntentRouter
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import pl.jclab.refio.core.workflow.executors.ChatExecutor
import pl.jclab.refio.core.workflow.executors.PlanExecutor
import pl.jclab.refio.core.workflow.executors.StepExecutor
import pl.jclab.refio.core.workflow.executors.SubagentExecutor
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
    val agentEventBus = pl.jclab.refio.core.agents.events.AgentEventBus()

    // Services (internal for access by CoreConnectionManager)
    internal val taskRepository = TaskRepository()
    internal val configService = ConfigService(
        configRepository = configRepository,
        defaultProjectId = routerProjectId
    )
    internal val promptsService = PromptsService(promptsRepository)
    internal val toolPermissionsService = ToolPermissionsService(configRepository)
    internal val llmClient = llmClientOverride ?: LLMClient(configService)
    private val workingMemoryService = WorkingMemoryService()
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
    private val embeddingsMutex = Mutex()
    private val languageAnalyzers = listOf(
        KotlinLanguageAnalyzer(),
        JavaLanguageAnalyzer(),
        PythonLanguageAnalyzer(),
        TypeScriptLanguageAnalyzer(),
        HtmlLanguageAnalyzer(),
        CppLanguageAnalyzer(),
        CssLanguageAnalyzer()
    )

    private val embeddingsService: EmbeddingsService? = if (projectRoot != null) {
        EmbeddingsService(
            configService = configService,
            providerFactory = ::embeddingProviderFor
        )
    } else null

    private val ragChunkingStrategy: ChunkingStrategy = when (ChunkingMode.fromConfig(configService.getTyped(ConfigKeys.RAG_CHUNKING_MODE))) {
        ChunkingMode.LINE_BASED -> DefaultChunkingStrategy()
        ChunkingMode.SEMANTIC -> SemanticChunkingStrategy()
    }

    private val fileAnalyzerService: FileAnalyzerService? = if (projectRoot != null && embeddingsService != null) {
        FileAnalyzerService(
            configService = configService,
            ragRepository = ragRepository,
            chunkingStrategy = ragChunkingStrategy,
            embeddingsService = embeddingsService,
            analyzers = languageAnalyzers,
            scope = routerScope
        )
    } else null

    private val richProjectAnalysisEngine: RichProjectAnalysisEngine? =
        if (projectRoot != null && fileAnalyzerService != null) {
            RichProjectAnalysisEngine(
                fileAnalyzerService = fileAnalyzerService,
                configService = configService,
                repository = projectAnalysisReportRepository,
                languageAnalyzers = languageAnalyzers
            )
        } else null

    // ProjectAnalyzerService and ContextService (optional, requires projectRoot)
    private val projectAnalyzer: ProjectAnalyzerService? = if (projectRoot != null) {
        ProjectAnalyzerService(configService, richProjectAnalysisEngine)
    } else null

    private val contextService: ContextService? = if (projectRoot != null && projectAnalyzer != null) {
        ContextService(
            projectAnalyzer = projectAnalyzer,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            subtaskRepository = subtaskRepository,
            fileAnalyzerService = fileAnalyzerService,
            configService = configService,
            workingMemoryService = workingMemoryService,
            conversationSummaryService = conversationSummaryService
        )
    } else null

    // SnapshotService (optional, requires projectRoot)
    private val snapshotService: SnapshotService? = if (projectRoot != null) {
        SnapshotService(snapshotRepository, projectRoot)
    } else null

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
     * Uses turn/ package components for prompt building, tool parsing, and tool execution.
     */
    private val agentTurnLoop: AgentTurnLoop? = if (toolRegistry != null && toolExecutor != null) {
        val toolResultSummarizer = ToolResultSummarizer(
            llmClient = llmClient,
            configService = configService,
            taskRepository = taskRepository
        )
        val taskVerifier = LlmTaskVerifier(
            llmClient = llmClient,
            configService = configService,
            chatMessageRepository = chatMessageRepository
        )
        val tokenEstimator = PromptTokenEstimator()

        // Create turn/ package components
        val turnPromptBuilder = TurnPromptBuilder(
            promptsService = promptsService,
            chatMessageRepository = chatMessageRepository,
            toolDescriptionBuilder = toolDescriptionBuilder,
            contextService = contextService,
            workingMemoryService = workingMemoryService,
            projectRoot = projectRoot,
            tokenEstimator = tokenEstimator,
            promptCache = null  // Could be added later if needed
        )

        val toolCallParser = ToolCallParser(
            toolRegistry = toolRegistry,
            toolPermissionsService = toolPermissionsService,
            getJsonThinkingXmlTags = { taskId -> configService.getTyped(ConfigKeys.JSON_THINKING_XML_TAGS, taskId) }
        )

        val turnToolExecutor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = subtaskRepository,
            toolResultSummarizer = toolResultSummarizer,
            snapshotService = snapshotService,
            workingMemoryIntegration = null  // Could be added later if needed
        )

        val turnLLMCaller = TurnLLMCaller(
            llmClient = llmClient,
            configService = configService
        )

        val turnResponseProcessor = TurnResponseProcessor(
            subtaskRepository = subtaskRepository,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder
        )

        val turnFinalizer = TurnFinalizer(
            chatMessageRepository = chatMessageRepository
        )

        val turnSubagentValidator = TurnSubagentValidator(
            maxSubagentDepth = 3
        )

        AgentTurnLoop(
            // Core dependencies
            llmClient = llmClient,
            chatMessageRepository = chatMessageRepository,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            configService = configService,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder,
            taskVerifier = taskVerifier,
            // turn/ package components
            turnPromptBuilder = turnPromptBuilder,
            toolCallParser = toolCallParser,
            turnToolExecutor = turnToolExecutor,
            turnLLMCaller = turnLLMCaller,
            turnResponseProcessor = turnResponseProcessor,
            turnFinalizer = turnFinalizer,
            turnSubagentValidator = turnSubagentValidator,
            // ADR-0028: Optional dependencies
            tokenEstimator = tokenEstimator,
            conversationCompactor = null,
            llmRetryHandler = null,
            workingMemoryIntegration = null
        )
    } else null

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
            toolDescriptionBuilder = toolDescriptionBuilder
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
            taskRepository = taskRepository
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
    val subagentRouter by lazy {
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
                    runTurn(
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
     * Workflow orchestrator (unified intent routing + execution adapters).
     */
    val workflowOrchestrator by lazy {
        val intentRouter = IntentRouter(
            subtaskRepository = subtaskRepository,
            subagentRouter = subagentRouter
        )
        val chatExecutor = ChatExecutor(chatService)
        val planExecutor = PlanExecutor(planningService)
        val stepExecutor = StepExecutor(agentRouter)
        val subagentExecutor = subagentRouter?.let { SubagentExecutor(it) }

        WorkflowOrchestrator(
            intentRouter = intentRouter,
            chatExecutor = chatExecutor,
            planExecutor = planExecutor,
            stepExecutor = stepExecutor,
            subagentExecutor = subagentExecutor,
            userInteraction = userInteraction
        )
    }

    /**
     * Multi-agent runner for parallel agent orchestration.
     */
    val multiAgentRunner by lazy {
        pl.jclab.refio.core.agents.MultiAgentRunner(agentEventBus)
    }

    init {
        if (toolRegistry != null && projectRoot != null) {
            try {
                if (!toolRegistry.hasTool("invoke_subagent")) {
                    val invokeSubagentTool = pl.jclab.refio.core.tools.implementations.InvokeSubagentTool(
                        subagentRouterProvider = { subagentRouter },
                        runTurnCallback = { request, turnEventListener, streamCallback ->
                            runTurn(
                                request = request,
                                streamCallback = streamCallback,
                                listener = turnEventListener?.let {
                                    pl.jclab.refio.core.services.AgentTurnLoop.TurnEventListener.fromTurnEventListener(it)
                                }
                            )
                        },
                        configServiceProvider = { configService }
                    )
                    toolRegistry.register(invokeSubagentTool)
                    logger.info { "CoreApiRouter: invoke_subagent tool registered" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "CoreApiRouter: failed to register invoke_subagent tool" }
            }
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

    /**
     * Get ConfigService (for SessionManager to save UI state)
     */
    fun getConfigService(): ConfigService {
        return configService
    }

    /**
     * Get ProjectAnalyzerService (for startup analysis and caching)
     * Returns null if projectRoot was not provided during router creation
     */
    fun getProjectAnalyzerService(): ProjectAnalyzerService? {
        return projectAnalyzer
    }

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
        if (projectRoot != null && contextService != null) {
            val ragComponents = initializeRagSearchService()
            ragComponents?.let { (service, modelId, providerId) ->
                contextService.updateRagSearchConfig(service, modelId, providerId)
            }
        }
    }

    // ========== Task Management ==========

    /**
     * Create a new task
     */
    fun createTask(request: CreateTaskRequest): TaskResponse {
        logger.info { "Creating task: name=${request.name}, mode=${request.mode}" }

        val effectiveProjectId = request.projectId.ifBlank { routerProjectId ?: LEGACY_PROJECT_ID }
        val effectiveProjectPath = request.projectPath.ifBlank { routerProjectPath ?: LEGACY_PROJECT_PATH }
        val readOnly = request.readOnly ?: (request.mode == TaskMode.PLAN || configService.getTyped(ConfigKeys.READ_ONLY_MODE))
        val requiresPlanApproval = request.requiresPlanApproval ?: false

        val task = taskRepository.create(
            name = request.name,
            mode = request.mode,
            projectId = effectiveProjectId,
            projectPath = effectiveProjectPath,
            readOnly = readOnly,
            requiresPlanApproval = requiresPlanApproval,
            planApproved = false
        )

        return task.toResponse()
    }

    /**
     * List all tasks with aggregated stats
     * US-204: Returns tasks with token/cost data for history panel
     */
    fun listTasks(): ListTasksResponse {
        logger.info { "Listing all tasks with stats" }

        val tasksWithStats = taskRepository.listTasksWithStats(limit = 100)

        val tasks = tasksWithStats.map { tws ->
            tws.task.toResponse(
                tokensInOverride = tws.tokensIn,
                tokensOutOverride = tws.tokensOut,
                costUsdOverride = tws.costUsd
            )
        }

        return ListTasksResponse(
            tasks = tasks,
            count = tasks.size
        )
    }

    fun getTasksForProject(projectId: String): List<TaskResponse> {
        return taskRouter.getTasksForProject(projectId)
    }

    fun getLastSessionForProject(projectId: String): TaskResponse? {
        return taskRouter.getLastSessionForProject(projectId)
    }

    /**
     * Get task by ID
     */
    fun getTask(taskId: String): TaskResponse? {
        return taskRouter.getTask(taskId)
    }

    /**
     * Update task (mode, name, status, etc.)
     */
    fun updateTask(taskId: String, request: UpdateTaskRequest): TaskResponse {
        logger.info { "Updating task: id=$taskId, mode=${request.mode}, name=${request.name}, executionMode=${request.executionMode}" }

        val updatedTask = taskRepository.update(
            id = taskId,
            name = request.name,
            mode = request.mode,
            status = request.status,
            readOnly = request.readOnly,
            pinned = request.pinned,
            executionMode = request.executionMode,
            requiresPlanApproval = request.requiresPlanApproval,
            planApproved = request.planApproved,
            uiState = request.uiState,
            rate = request.rate
        ) ?: throw IllegalArgumentException("Task not found: $taskId")

        return updatedTask.toResponse()
    }

    /**
     * Delete task by ID
     */
    fun deleteTask(taskId: String): Boolean {
        return taskRouter.deleteTask(taskId)
    }

    /**
     * Health check
     */
    fun health(): HealthResponse {
        return HealthResponse(
            status = "ok",
            version = "1.0-SNAPSHOT",
            timestamp = System.currentTimeMillis(),
            message = "Core is healthy"
        )
    }

    // ========== Models ==========

    /**
     * Get list of available models.
     *
     * @param provider Optional provider filter (ollama, openai, anthropic)
     * @return List of available models with full configuration
     */
    suspend fun getModels(provider: String? = null): GetModelsResponse {
        return configRouter.getModels(provider)
    }

    /**
     * Get list of available models with visibility settings applied.
     *
     * @param provider Optional provider filter (ollama, openai, anthropic)
     * @return List of ModelInfo with visibility settings
     */
    suspend fun getModelsWithVisibility(provider: String? = null): List<ModelInfo> {
        return configRouter.getModelsWithVisibility(provider)
    }

    // ========== Configuration ==========

    /**
     * Get default model for given mode.
     *
     * @param mode Task mode (CHAT, PLAN, AGENT)
     * @param taskId Optional task ID for task-level override
     * @return Default model configuration
     */
    /**
     * Get the logical model to use for a request.
     * Centralizes model selection logic - if explicit model is provided, use it; otherwise use default.
     *
     * @param mode Task mode
     * @param taskId Optional task ID for task-level config
     * @param model Optional explicit model (null or "auto" means use default)
     * @param provider Optional explicit provider
     * @return Model configuration to use
     */
    fun getModel(
        operation: ModelOperation,
        taskId: String? = null
    ): GetDefaultModelResponse {
        logger.info { "Getting model: operation=$operation, taskId=${taskId ?: "none"}" }

        val (modelId, resolvedProvider) = configService.getModel(
            operation = operation,
            taskId = taskId
        )

        return GetDefaultModelResponse(
            operation = operation.name,
            modelId = modelId,
            provider = resolvedProvider
        )
    }

    fun getDefaultModel(operation: ModelOperation, taskId: String? = null): GetDefaultModelResponse {
        return configRouter.getDefaultModel(operation, taskId)
    }

    /**
     * Set default model for given mode.
     *
     * @param request Request with mode, model ID, and provider
     * @param taskId Optional task ID for task-level config
     * @return Confirmation response
     */
    fun setDefaultModel(request: SetDefaultModelRequest, taskId: String? = null): SetDefaultModelResponse {
        return configRouter.setDefaultModel(request, taskId)
    }

    /**
     * Set default model for ALL modes (chat, plan, agent) in one request.
     *
     * @param request Request with model ID and provider
     * @param taskId Optional task ID for task-level config
     * @return Confirmation response
     */
    fun setDefaultModelAllModes(
        request: SetDefaultModelAllModesRequest,
        taskId: String? = null
    ): SetDefaultModelAllModesResponse {
        logger.info {
            "Setting default model for ALL modes: modelId=${request.modelId}, " +
                    "provider=${request.provider}, taskId=${taskId ?: "none"}"
        }

        configService.setDefaultModelAllModes(
            modelId = request.modelId,
            provider = request.provider,
            taskId = taskId
        )

        return SetDefaultModelAllModesResponse(
            modelId = request.modelId,
            provider = request.provider,
            scope = if (taskId != null) "task" else "app",
            modes = listOf(
                ModelOperation.DEFAULT.name,
                ModelOperation.PLAN.name,
                ModelOperation.CODING.name
            )
        )
    }

    // ========== Chat ==========

    /**
     * Send chat message and get LLM response (RFC 0032: unified streaming/non-streaming).
     *
     * This endpoint handles conversational interactions with LLM providers.
     * Must be called with an existing task in CHAT mode.
     *
     * @param request Chat request with task ID, input message, and parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Chat response with assistant message and metadata
     * @throws IllegalArgumentException If task not found or mode is not CHAT
     * @throws Exception On LLM API errors
     */
    suspend fun chat(
        request: ChatRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ChatResponse {
        return chatRouter.chat(request, stream, onChunk)
    }

    /**
     * Generate a conversation summary (new system message) for the given task.
     */
    suspend fun summarizeConversation(
        taskId: String,
        streamCallback: StreamCallback? = null
    ): SummarizeResponse {
        return chatRouter.summarizeConversation(taskId, streamCallback)
    }

    suspend fun generateSessionTitle(taskId: String, userMessage: String): String {
        return chatRouter.generateSessionTitle(taskId, userMessage)
    }

    /**
     * Run a single turn with turn-loop pattern (Codex CLI-style).
     *
     * This method implements the turn-loop pattern where:
     * - One turn = one request from user
     * - Model may emit tool calls during the turn
     * - Tool results are automatically fed back to the model
     * - Turn completes when model responds with text (no more tool calls)
     *
     * Key differences from old plan-based approach:
     * - No separate "plan" entity - plan is just text response
     * - Model self-directs tool usage in a loop
     * - Context grows within turn (tool calls and results)
     *
     * Modes:
     * - CHAT: No tools available (conversation only)
     * - PLAN: Read-only tools for analysis and planning
     * - AGENT: All tools for autonomous execution
     *
     * @param request TurnRequest with taskId, userInput, mode, executionMode, model, provider
     * @param streamCallback Optional callback for streaming updates to UI
     * @param listener Optional listener for turn events (tool execution, etc.)
     * @return TurnResult with final response and metadata
     * @throws IllegalArgumentException If task not found or agentTurnLoop not available
     * @throws IllegalStateException If toolRegistry is not configured
     */
    suspend fun runTurn(
        request: TurnRequest,
        streamCallback: StreamCallback? = null,
        listener: pl.jclab.refio.core.services.AgentTurnLoop.TurnEventListener? = null
    ): TurnResult {
        val turnLoop = agentTurnLoop
            ?: throw IllegalStateException("AgentTurnLoop not available - toolRegistry is not configured")

        return turnLoop.runTurn(
            taskId = request.taskId,
            userInput = request.userInput,
            mode = request.mode,
            executionMode = request.executionMode,
            listener = listener,
            streamCallback = streamCallback,
            model = request.model,
            provider = request.provider,
            userContextRefs = request.userContextRefs,
            runProfile = request.runProfile,
            profileOverrides = request.profileOverrides
        )
    }

    /**
     * Continue a turn after user provides additional input (for INTERACTIVE mode).
     *
     * This is called when the turn was paused for user confirmation of a tool call.
     *
     * @param taskId Task ID
     * @param mode Task mode
     * @param executionMode Execution mode (AUTO or INTERACTIVE)
     * @param stream If true, stream response chunks via onChunk callback
     * @param onChunk Optional callback for streaming updates to UI
     * @return TurnResult with final response and metadata
     */
    suspend fun continueTurn(
        taskId: String,
        mode: pl.jclab.refio.core.db.TaskMode,
        executionMode: pl.jclab.refio.core.db.ExecutionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): TurnResult {
        val turnLoop = agentTurnLoop
            ?: throw IllegalStateException("AgentTurnLoop not available - toolRegistry is not configured")

        return turnLoop.continueTurn(
            taskId = taskId,
            mode = mode,
            executionMode = executionMode,
            listener = null,
            streamCallback = if (stream && onChunk != null) {
                { chunk -> onChunk(chunk) }
            } else null
        )
    }

    // ========== Multi-Agent API ==========

    /**
     * Launch a multi-agent session from YAML definition.
     *
     * Parses the YAML, creates a DB session, registers agent instances,
     * and runs all agents in parallel respecting DAG dependencies.
     * Each agent executes as an independent turn-loop with its own task.
     *
     * @param request Multi-agent session request with YAML definition
     * @param streamCallback Optional callback for streaming updates
     * @return Session response with agent results
     */
    suspend fun launchMultiAgentSession(
        request: MultiAgentSessionRequest,
        streamCallback: StreamCallback? = null
    ): MultiAgentSessionResponse {
        val projectId = routerProjectId ?: LEGACY_PROJECT_ID

        // Parse YAML definition
        val definition = pl.jclab.refio.core.agents.MultiAgentTaskParser.parse(request.yamlDefinition)
        val specs = pl.jclab.refio.core.agents.MultiAgentTaskParser.toAgentSpecs(definition)

        // Validate dependencies before creating DB records
        multiAgentRunner.validateDependencies(specs)

        // Create DB session
        val session = agentSessionRepository.create(
            projectId = projectId,
            name = request.name,
            definitionYaml = request.yamlDefinition
        )
        agentSessionRepository.updateStatus(session.id, "RUNNING")

        // Register agent instances in DB
        val instanceMap = mutableMapOf<String, pl.jclab.refio.core.db.AgentInstance>()
        for (spec in specs) {
            val instance = agentInstanceRepository.create(
                sessionId = session.id,
                name = spec.name,
                taskDescription = spec.task,
                profile = spec.profile,
                model = spec.model ?: request.model,
                dependsOn = if (spec.dependsOn.isNotEmpty()) spec.dependsOn.joinToString(",") else null
            )
            instanceMap[spec.name] = instance
        }

        val startTime = System.currentTimeMillis()

        try {
            // Run agents via MultiAgentRunner with turn-loop executor
            val results = multiAgentRunner.run(session.id, specs) { spec, agentId ->
                val instance = instanceMap[spec.name]!!
                agentInstanceRepository.updateStatus(
                    instance.id, pl.jclab.refio.core.db.AgentInstanceStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )

                // Create a task for this agent
                val agentTask = createTask(CreateTaskRequest(
                    name = "${request.name} — ${spec.name}",
                    mode = spec.mode,
                    projectId = projectId,
                    projectPath = routerProjectPath ?: LEGACY_PROJECT_PATH
                ))

                // Execute via turn-loop
                val turnResult = runTurn(
                    request = TurnRequest(
                        taskId = agentTask.id,
                        userInput = spec.task,
                        mode = spec.mode,
                        executionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
                        model = spec.model ?: request.model,
                        provider = request.provider
                    ),
                    streamCallback = streamCallback
                )

                val completedAt = System.currentTimeMillis()
                agentInstanceRepository.updateStatus(
                    instance.id, pl.jclab.refio.core.db.AgentInstanceStatus.COMPLETED,
                    completedAt = completedAt
                )
                agentInstanceRepository.updateResult(
                    instance.id,
                    result = turnResult.response.take(10000),
                    tokensIn = turnResult.tokensIn,
                    tokensOut = turnResult.tokensOut,
                    costUsd = turnResult.cost
                )

                pl.jclab.refio.core.agents.AgentResult(
                    agentName = spec.name,
                    success = turnResult.success,
                    response = turnResult.response,
                    tokensUsed = (turnResult.tokensIn + turnResult.tokensOut).toLong(),
                    costUsd = turnResult.cost,
                    durationMs = completedAt - (instance.startedAt ?: completedAt)
                )
            }

            val completedAt = System.currentTimeMillis()
            agentSessionRepository.updateStatus(session.id, "COMPLETED", completedAt)

            return MultiAgentSessionResponse(
                sessionId = session.id,
                name = request.name,
                status = "COMPLETED",
                agents = results.map { (name, result) ->
                    MultiAgentInstanceResponse(
                        agentName = name,
                        status = if (result.success) "COMPLETED" else "FAILED",
                        success = result.success,
                        response = result.response.take(2000),
                        tokensUsed = result.tokensUsed,
                        costUsd = result.costUsd,
                        durationMs = result.durationMs,
                        error = result.error
                    )
                },
                totalTokens = results.values.sumOf { it.tokensUsed },
                totalCostUsd = results.values.sumOf { it.costUsd },
                durationMs = completedAt - startTime,
                createdAt = session.createdAt,
                completedAt = completedAt
            )
        } catch (e: Exception) {
            agentSessionRepository.updateStatus(session.id, "FAILED", System.currentTimeMillis())
            throw e
        }
    }

    /**
     * Get status of a multi-agent session.
     */
    fun getMultiAgentSession(sessionId: String): MultiAgentSessionResponse? {
        val session = agentSessionRepository.findById(sessionId) ?: return null
        val instances = agentInstanceRepository.findBySessionId(sessionId)

        return MultiAgentSessionResponse(
            sessionId = session.id,
            name = session.name,
            status = session.status,
            agents = instances.map { inst ->
                MultiAgentInstanceResponse(
                    agentName = inst.name,
                    status = inst.status,
                    success = inst.status == "COMPLETED",
                    response = inst.result?.take(2000),
                    tokensUsed = (inst.tokensIn + inst.tokensOut).toLong(),
                    costUsd = inst.costUsd,
                    durationMs = if (inst.startedAt != null && inst.completedAt != null)
                        inst.completedAt - inst.startedAt else 0,
                    error = if (inst.status == "FAILED") inst.result else null
                )
            },
            totalTokens = instances.sumOf { (it.tokensIn + it.tokensOut).toLong() },
            totalCostUsd = instances.sumOf { it.costUsd },
            durationMs = if (session.completedAt != null) session.completedAt - session.createdAt else 0,
            createdAt = session.createdAt,
            completedAt = session.completedAt
        )
    }

    /**
     * List all multi-agent sessions for the current project.
     */
    fun listMultiAgentSessions(): List<MultiAgentSessionResponse> {
        val projectId = routerProjectId ?: return emptyList()
        return agentSessionRepository.findByProjectId(projectId).map { session ->
            getMultiAgentSession(session.id)!!
        }
    }

    /**
     * Get all messages for a task.
     *
     * @param taskId Task ID to get messages for
     * @return List of messages ordered by creation time
     */
    fun getMessages(taskId: String): GetMessagesResponse {
        return chatRouter.getMessages(taskId)
    }

    /**
     * Get all subtasks for a task.
     *
     * @param taskId Task ID to get subtasks for
     * @return List of subtasks ordered by orderIndex
     */
    fun getSubtasks(taskId: String): GetSubtasksResponse {
        return subtaskRouter.getSubtasks(taskId)
    }

    /**
     * Update subtask status or approval status.
     *
     * @param taskId Task ID (for validation)
     * @param subtaskId Subtask ID to update
     * @param request Update request with optional fields
     * @return Updated subtask response
     */
    fun updateSubtask(taskId: String, subtaskId: String, request: UpdateSubtaskRequest): SubtaskResponse {
        return subtaskRouter.updateSubtask(taskId, subtaskId, request)
    }

    /**
     * Plan subtask execution.
     *
     * Generates execution plan for a subtask using StepPlanner.
     * Updates subtask status from PENDING → PLANNED.
     * Saves approval message to chat_messages for UI display.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to plan
     * @return Plan with tools and estimated duration
     */
    fun planSubtaskStep(taskId: String, subtaskId: String): PlanStepResponse {
        return agentRouter.planSubtaskStep(taskId, subtaskId)
    }

    /**
     * Execute subtask step.
     *
     * Executes tools from subtask plan using AgentExecutor.
     * Updates subtask status: PLANNED/PENDING → RUNNING → SUCCESS/FAILED.
     * Saves execution summary to chat_messages for UI display.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to execute
     * @return Execution result with status and summary
     */
    fun executeSubtaskStep(taskId: String, subtaskId: String): ExecuteStepResponse {
        return agentRouter.executeSubtaskStep(taskId, subtaskId)
    }

    fun executeSubtaskStepWithListener(
        taskId: String,
        subtaskId: String,
        externalListener: ExecutionEventListener? = null
    ): ExecuteStepResponse {
        return agentRouter.executeSubtaskStepWithListener(taskId, subtaskId, externalListener)
    }

    /**
     * Execute a single subtask step with orchestration (INTERACTIVE mode with orchestration).
     *
     * Executes ONE step, then performs reflection analysis to decide:
     * - CONTINUE: step successful, continue to next
     * - MODIFY_PLAN: modify remaining subtasks based on reflection
     * - ASK_USER: pause for user input
     * - ABORT: stop execution
     *
     * Saves execution summary and reflection analysis to chat_messages.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to execute
     * @return Execution result with status, summary, and reflection decision
     */
    /**
     * Approve task plan for auto execution.
     */
    fun approvePlan(taskId: String) {
        agentRouter.approvePlan(taskId)
    }

    /**
     * Reject task plan for auto execution.
     */
    fun rejectPlan(taskId: String, reason: String? = null) {
        agentRouter.rejectPlan(taskId, reason)
    }

    /**
     * Get task plan summary for approval UI.
     */
    fun getPlanSummary(taskId: String): PlanSummaryResponse {
        return agentRouter.getPlanSummary(taskId)
    }
    /**
     * Generate execution summary via LLM after PLAN/AGENT completion.
     * Creates a detailed, natural language summary of what was accomplished.
     *
     * @param taskId Task ID
     * @return Summary text
     */
    suspend fun generateExecutionSummary(taskId: String): String {
        return agentRouter.generateExecutionSummary(taskId)
    }

    /**
     * Generate a lightweight project analysis summary for intent classification.
     * Does NOT perform full AST analysis - creates a simple summary from cached data.
     *
     * @return Brief project summary string (typically 500-1000 chars)
     */
    suspend fun getProjectAnalysisSummary(): String {
        if (projectRoot == null || richProjectAnalysisEngine == null) {
            return "Unknown project type"
        }

        return try {
            // Try to get cached analysis (fast path)
            val report = richProjectAnalysisEngine.analyzeProject(projectRoot)

            // Build concise summary
            buildString {
                append("Project: ${report.architecture.style ?: "Unknown architecture"}\n")

                // Languages (top 3 by line count)
                val topLanguages = report.statistics.linesByLanguage
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(", ") { "${it.key} (${it.value} lines)" }
                append("Languages: $topLanguages\n")

                // Technologies and frameworks
                if (report.technologies.frameworks.isNotEmpty()) {
                    val frameworks = report.technologies.frameworks
                        .take(3)
                        .joinToString(", ") { it.name }
                    append("Frameworks: $frameworks\n")
                }

                // Key statistics
                append("Files: ${report.statistics.totalFiles}, ")
                append("Classes: ${report.codeStructure.classes.size}, ")
                append("Packages: ${report.codeStructure.packages.size}\n")

                // Architecture layers
                if (report.architecture.layers.isNotEmpty()) {
                    val layers = report.architecture.layers
                        .take(3)
                        .joinToString(", ") { it.name }
                    append("Layers: $layers")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate project analysis summary, using fallback" }
            // Fallback: simple summary based on project root
            "Project at $projectRoot"
        }
    }

    // ========== Subtask Management ==========

    /**
     * List all subtasks for a task.
     *
     * @param taskId Task ID
     * @return List of subtasks with metadata
     */
    fun listSubtasks(taskId: String): GetSubtasksResponse {
        return subtaskRouter.getSubtasks(taskId)
    }

    /**
     * Get single subtask by ID.
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Subtask details
     * @throws IllegalArgumentException If subtask not found
     */
    fun getSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        return subtaskRouter.getSubtask(taskId, subtaskId)
    }

    /**
     * Approve subtask for execution.
     *
     * Updates approval status to APPROVED, allowing execution in interactive mode.
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Updated subtask
     * @throws IllegalArgumentException If subtask not found
     */
    fun approveSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        return subtaskRouter.approveSubtask(taskId, subtaskId)
    }

    /**
     * Reject subtask execution.
     *
     * Updates approval status to SKIPPED and status to CANCELED.
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Updated subtask
     * @throws IllegalArgumentException If subtask not found
     */
    fun rejectSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        return subtaskRouter.rejectSubtask(taskId, subtaskId)
    }

    /**
     * Delete all PENDING and PLANNED subtasks for a task.
     *
     * This is used for re-planning and cancellation workflows.
     * Only deletes subtasks that haven't started executing yet.
     *
     * @param taskId Task ID
     * @return Response with count of deleted subtasks
     */
    fun deletePendingSubtasks(taskId: String): DeleteSubtasksResponse {
        val result = subtaskRouter.deletePendingSubtasks(taskId)
        return DeleteSubtasksResponse(
            deletedCount = result.deletedCount,
            message = "Successfully deleted ${result.deletedCount} pending/planned subtasks"
        )
    }

    /**
     * Delete a single subtask by ID.
     *
     * @param taskId Task ID (for validation)
     * @param subtaskId Subtask ID to delete
     * @return true if deleted, false if not found
     */
    fun deleteSubtask(taskId: String, subtaskId: String): Boolean {
        val result = subtaskRouter.deleteSubtask(taskId, subtaskId)
        return result.deleted
    }

    /**
     * Swap order of two subtasks.
     * Used for reordering steps in the queue.
     *
     * @param taskId Task ID (for validation)
     * @param subtaskId1 First subtask ID
     * @param subtaskId2 Second subtask ID
     */
    fun swapSubtaskOrder(taskId: String, subtaskId1: String, subtaskId2: String) {
        subtaskRouter.swapSubtaskOrder(taskId, subtaskId1, subtaskId2)
    }

    // ========== Prompts Management ==========

    /**
     * Get system prompt for given type with variable substitution
     */
    fun getSystemPrompt(request: GetSystemPromptRequest): SystemPromptResponse {
        return promptsRouter.getSystemPrompt(request)
    }

    /**
     * Get all prompts of given type
     */
    fun getPromptsByType(type: PromptType): PromptsListResponse {
        return promptsRouter.getPromptsByType(type)
    }

    /**
     * Get all system prompts (all system PromptType values)
     */
    fun getSystemPrompts(): PromptsListResponse {
        return promptsRouter.getSystemPrompts()
    }

    /**
     * Get all enabled rules
     */
    fun getEnabledRules(): PromptsListResponse {
        return promptsRouter.getEnabledRules()
    }

    /**
     * Get all enabled slash commands
     */
    fun getEnabledCommands(): PromptsListResponse {
        return promptsRouter.getEnabledCommands()
    }

    /**
     * Find slash command by name
     */
    fun findCommand(commandName: String): PromptResponse? {
        return promptsRouter.findCommand(commandName)
    }

    /**
     * Save (create or update) a rule
     */
    fun saveRule(request: SaveRuleRequest): PromptResponse {
        return promptsRouter.saveRule(request)
    }

    /**
     * Save (create or update) a slash command
     */
    fun saveCommand(request: SaveCommandRequest): PromptResponse {
        return promptsRouter.saveCommand(request)
    }

    /**
     * Update system prompt content
     */
    fun updateSystemPrompt(request: UpdateSystemPromptRequest): PromptResponse? {
        return promptsRouter.updateSystemPrompt(request)
    }

    /**
     * Reset system prompt to default
     */
    fun resetSystemPromptToDefault(type: PromptType): PromptResponse? {
        return promptsRouter.resetSystemPromptToDefault(type)
    }

    /**
     * Delete rule or command by ID
     */
    fun deletePrompt(id: String): DeletePromptResponse {
        return promptsRouter.deletePrompt(id)
    }

    /**
     * Get prompt by ID
     */
    fun getPromptById(id: String): PromptResponse? {
        return promptsRouter.getPromptById(id)
    }

    /**
     * Get default (hardcoded) content for system prompt type
     */
    fun getDefaultSystemPromptContent(type: PromptType): String {
        return promptsRouter.getDefaultSystemPromptContent(type)
    }

    // ========== Configuration Management ==========

    /**
     * Public method to initialize provider API keys on application startup.
     * This loads all provider configurations from the database and sets them
     * as System properties so LLM adapters can access them.
     *
     * Called by CoreConnectionManager during initialization.
     */
    fun initializeProviderKeys() {
        configRouter.initializeProviderKeys()
    }

    /**
     * Syncs provider API keys from database to System properties.
     * This ensures LLM adapters can find API keys via System.getProperty().
     *
     * Called:
     * 1. On application startup (via initializeProviderKeys)
     * 2. When provider settings are updated (via updateConfig)
     */
    /**
     * Update configuration setting.
     */
    fun updateConfig(
        section: String,
        scope: String,
        taskId: String?,
        settings: Map<String, Any>
    ): UpdateConfigResponse {
        return configRouter.updateConfig(section, scope, taskId, settings)
    }

    /**
     * Get configuration settings for a section.
     */
    fun getConfig(
        section: String,
        scope: String,
        taskId: String? = null
    ): GetConfigResponse {
        return configRouter.getConfig(section, scope, taskId)
    }

    /**
     * Get configuration for a section (delegates to version with optional taskId).
     */
    fun getConfig(section: String, scope: String): GetConfigResponse {
        return configRouter.getConfig(section, scope)
    }

    /**
     * Reset all settings to defaults.
     */
    fun resetAllSettingsToDefaults(): ResetConfigResponse {
        return configRouter.resetAllSettingsToDefaults()
    }

    // ========== Provider Management ==========

    /**
     * Test connection to LLM provider
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @param config Provider configuration (api_key, base_url, etc.)
     * @return Test result with success status and details
     */
    suspend fun testProviderConnection(provider: String, config: Map<String, String>): TestConnectionResult {
        return configRouter.testProviderConnection(provider, config)
    }

    /**
     * Refresh list of available models for a provider
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @return List of available models with details
     */
    suspend fun refreshProviderModels(provider: String): List<ModelInfo> {
        return configRouter.refreshProviderModels(provider)
    }

    /**
     * Refresh list of available models for all providers
     *
     * @return List of available models with details from all providers
     */
    suspend fun refreshAllModels(): List<ModelInfo> {
        return configRouter.refreshAllModels()
    }

    /**
     * Update model visibility (show in dropdown)
     *
     * @param modelId Model ID to update
     * @param showInDropdown Whether to show model in dropdown
     */
    suspend fun updateModelVisibility(modelId: String, showInDropdown: Boolean) {
        return configRouter.updateModelVisibility(modelId, showInDropdown)
    }

    /**
     * Update visibility for all models in one operation.
     *
     * @param visibilityMap Map of modelId to showInDropdown setting
     */
    suspend fun updateModelsVisibility(visibilityMap: Map<String, Boolean>) {
        return configRouter.updateModelsVisibility(visibilityMap)
    }

    // ========== Project Context API ==========

    /**
     * Get project context (for UI visualization)
     *
     * @param taskId Task ID for context
     * @return Project context response
     */
    suspend fun getProjectContext(
        taskId: String,
        userInput: String? = null,
        contextRefs: List<ContextReference> = emptyList()
    ): ProjectContextResponse {
        logger.debug { "Getting project context for task=$taskId" }

        if (contextService == null || projectRoot == null) {
            throw IllegalStateException("Context service not available - projectRoot required")
        }

        try {
            val task = taskRepository.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")

            val chatHistory = chatMessageRepository.findByTaskId(taskId)
            val pendingUserInput = userInput?.takeIf { it.isNotBlank() }
            val effectiveQuery = pendingUserInput
                ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content

            // 1. Build context (all logic inside ContextService)
            val userContextRefs = contextService.collectAllUserContextRefs(taskId) + contextRefs
            val context = contextService.buildProjectContext(
                projectRoot = projectRoot,
                taskId = taskId,
                project = resolvedIdeProject,
                query = effectiveQuery,
                userContextRefs = userContextRefs
            )

            // 2. Generate context payload using runtime shape per mode.
            // CHAT and AGENT provide conversation as messages (not inside context payload).
            // PLAN keeps conversation inside context payload (matches PlanningService).
            val promptContext = if (task.mode == TaskMode.AGENT || task.mode == TaskMode.CHAT) {
                context.copy(conversationHistory = emptyList())
            } else {
                context
            }
            val llmPrompt = contextService.buildLLMContextPrompt(context = promptContext)

            val contextSectionTokens = contextService.calculateContextSectionTokens(promptContext, llmPrompt)
            val runtimePreview = buildRuntimePromptPreview(
                task = task,
                chatHistory = chatHistory,
                pendingUserInput = pendingUserInput,
                contextPrompt = llmPrompt,
                userContextRefs = userContextRefs,
                contextSectionTokens = contextSectionTokens
            )
            val auxiliaryPreview = buildAuxiliaryPromptPreview(task.mode)
            val combinedPreview = buildString {
                append(runtimePreview.previewText)
                if (auxiliaryPreview.previewText.isNotBlank()) {
                    append("\n\n")
                    append(auxiliaryPreview.previewText)
                }
            }

            val updatedContext = context.copy(sectionTokens = runtimePreview.sectionTokens)

            // 3. Map to response DTO
            return mapToProjectContextResponse(
                context = updatedContext,
                llmPrompt = llmPrompt,
                llmPreviewPrompt = combinedPreview,
                activeLlmPreviewPrompt = runtimePreview.previewText,
                auxiliaryPreviewPrompt = auxiliaryPreview.previewText,
                activeEstimatedTokens = runtimePreview.activeEstimatedTokens,
                auxiliaryEstimatedTokens = auxiliaryPreview.estimatedTokens,
                combinedEstimatedTokens = runtimePreview.activeEstimatedTokens + auxiliaryPreview.estimatedTokens
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Job was cancelled - this is normal, re-throw without logging as error
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project context" }
            throw e
        }
    }

    private data class PromptPreviewEntry(
        val source: String,
        val role: String,
        val content: String,
        val estimatedTokens: Int
    )

    private data class RuntimePromptPreview(
        val previewText: String,
        val activeEstimatedTokens: Int,
        val sectionTokens: Map<String, ContextSectionTokenInfo>
    )

    private data class AuxiliaryPromptPreview(
        val previewText: String,
        val estimatedTokens: Int
    )

    private suspend fun buildRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        userContextRefs: List<ContextReference>,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        return when (task.mode) {
            TaskMode.CHAT -> buildChatRuntimePromptPreview(
                chatHistory = chatHistory,
                pendingUserInput = pendingUserInput,
                contextPrompt = contextPrompt,
                contextSectionTokens = contextSectionTokens
            )

            TaskMode.PLAN -> buildPlanRuntimePromptPreview(
                task = task,
                chatHistory = chatHistory,
                pendingUserInput = pendingUserInput,
                contextPrompt = contextPrompt,
                contextSectionTokens = contextSectionTokens
            )

            TaskMode.AGENT -> buildAgentRuntimePromptPreview(
                task = task,
                chatHistory = chatHistory,
                pendingUserInput = pendingUserInput,
                contextPrompt = contextPrompt,
                userContextRefs = userContextRefs,
                contextSectionTokens = contextSectionTokens
            )
        }
    }

    private fun buildChatRuntimePromptPreview(
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val systemPrompt = promptsService.getSystemPrompt(PromptType.SYSTEM_CHAT)
        val messages = chatHistory
            .map { msg -> LLMMessage(role = msg.role.name.lowercase(), content = msg.content) }
            .toMutableList()
        appendPendingUserMessage(messages, pendingUserInput)

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = systemPrompt,
            contextContent = contextPrompt.takeIf { it.isNotBlank() },
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = systemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = contextPrompt.isNotBlank()
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.CHAT,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(
            previewText = preview,
            activeEstimatedTokens = activeTokens,
            sectionTokens = sectionTokens
        )
    }

    private fun buildPlanRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(TaskMode.PLAN, task.id)
        val validToolNames = toolDescriptionBuilder.getValidToolNames(TaskMode.PLAN, task.id)
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_PLAN,
            variables = mapOf(
                "tool_descriptions" to toolDescriptions,
                "valid_tool_names" to validToolNames
            )
        )

        val requestText = pendingUserInput
            ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content
            ?: ""
        val userPrompt = buildPlanningUserPrompt(requestText)
        val messages = listOf(LLMMessage(role = "user", content = userPrompt))

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = systemPrompt,
            contextContent = contextPrompt.takeIf { it.isNotBlank() },
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = systemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = contextPrompt.isNotBlank()
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.PLAN,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(
            previewText = preview,
            activeEstimatedTokens = activeTokens,
            sectionTokens = sectionTokens
        )
    }

    private suspend fun buildAgentRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        userContextRefs: List<ContextReference>,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(TaskMode.AGENT, task.id)
        val baseSystemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_AGENT,
            variables = mapOf("tool_descriptions" to toolDescriptions)
        )

        val runtimeSystemPrompt = if (contextPrompt.isNotBlank()) {
            """
$baseSystemPrompt

<context>
$contextPrompt
</context>
            """.trimIndent()
        } else {
            baseSystemPrompt
        }

        val query = pendingUserInput ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content
        val messages = buildAgentMessagesForPreview(
            taskId = task.id,
            chatHistory = chatHistory,
            pendingUserInput = pendingUserInput,
            userContextRefs = userContextRefs,
            query = query
        )

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = runtimeSystemPrompt,
            contextContent = null,
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = baseSystemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = false
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.AGENT,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(
            previewText = preview,
            activeEstimatedTokens = activeTokens,
            sectionTokens = sectionTokens
        )
    }

    private suspend fun buildAgentMessagesForPreview(
        taskId: String,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        userContextRefs: List<ContextReference>,
        query: String?
    ): List<LLMMessage> {
        if (contextService != null && projectRoot != null) {
            return try {
                val turnMessages = contextService.buildAgentTurnMessages(
                    taskId = taskId,
                    projectRoot = projectRoot,
                    project = null,
                    userContextRefs = userContextRefs,
                    query = query
                ).messages.toMutableList()
                appendPendingUserMessage(turnMessages, pendingUserInput)
                turnMessages
            } catch (e: Exception) {
                logger.warn(e) { "[CONTEXT_PREVIEW] Failed to build agent messages via ContextService, using fallback" }
                buildAgentMessagesFallback(chatHistory, pendingUserInput)
            }
        }

        return buildAgentMessagesFallback(chatHistory, pendingUserInput)
    }

    private fun buildAgentMessagesFallback(
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?
    ): List<LLMMessage> {
        val lastToolIndex = chatHistory.indexOfLast { it.role == MessageRole.TOOL }
        val messages = chatHistory.mapIndexedNotNull { index, msg ->
            when (msg.role) {
                MessageRole.USER -> LLMMessage(role = "user", content = msg.content)
                MessageRole.ASSISTANT -> {
                    val toolCallsText = if (!msg.toolCalls.isNullOrEmpty()) {
                        msg.toolCalls.joinToString("\n") { tc ->
                            "TOOL_CALL: ${tc.name}\nARGUMENTS: ${tc.arguments}"
                        }
                    } else {
                        null
                    }

                    val content = buildString {
                        if (msg.content.isNotBlank()) append(msg.content)
                        if (!toolCallsText.isNullOrBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("Tool calls:\n")
                            append(toolCallsText)
                        }
                    }

                    if (content.isNotBlank()) LLMMessage(role = "assistant", content = content) else null
                }

                MessageRole.TOOL -> {
                    val content = if (index == lastToolIndex && msg.isSummarized) {
                        val raw = msg.rawOutput ?: msg.content
                        "[Tool Result for ${msg.toolCallId}]\n$raw"
                    } else {
                        "[Tool Result for ${msg.toolCallId}]\n${msg.content}"
                    }
                    LLMMessage(role = "user", content = content)
                }

                MessageRole.SYSTEM -> LLMMessage(role = "system", content = msg.content)
            }
        }.toMutableList()

        appendPendingUserMessage(messages, pendingUserInput)
        return messages
    }

    private fun appendPendingUserMessage(messages: MutableList<LLMMessage>, pendingUserInput: String?) {
        if (pendingUserInput.isNullOrBlank()) return
        val lastUser = messages.lastOrNull { it.role == "user" }?.content
        if (lastUser == pendingUserInput) return
        messages.add(LLMMessage(role = "user", content = pendingUserInput))
    }

    private fun buildPlanningUserPrompt(request: String): String {
        return buildString {
            appendLine("User request:")
            appendLine(request)
            appendLine()
            appendLine("Create a detailed execution plan as JSON.")
        }.trim()
    }

    private fun estimateMessageWithOverheadTokens(content: String): Int {
        return ((content.length + 10) / 4).coerceAtLeast(1)
    }

    private fun buildActiveSectionTokens(
        baseContextSections: Map<String, ContextSectionTokenInfo>,
        activeTokens: Int,
        systemPromptForBreakdown: String?,
        systemMessages: List<String>,
        messages: List<LLMMessage>,
        hasInjectedContext: Boolean
    ): Map<String, ContextSectionTokenInfo> {
        val sections = linkedMapOf<String, Pair<String, Int>>()
        baseContextSections.forEach { (key, info) ->
            sections[key] = info.name to info.tokens
        }

        fun addTokens(key: String, name: String, tokens: Int) {
            if (tokens <= 0) return
            val existing = sections[key]
            if (existing == null) {
                sections[key] = name to tokens
            } else {
                sections[key] = existing.first to (existing.second + tokens)
            }
        }

        val systemPromptTokens = systemPromptForBreakdown?.let { TokenEstimator.estimateTokens(it) } ?: 0
        addTokens("system_prompt", "System Prompt", systemPromptTokens)

        val systemMessagesTokens = systemMessages.sumOf { estimateMessageWithOverheadTokens(it) }
        addTokens("system_messages", "System Messages", systemMessagesTokens)

        val userTokens = messages.filter { it.role == "user" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val assistantTokens = messages.filter { it.role == "assistant" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val systemRoleTokens = messages.filter { it.role == "system" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val otherTokens = messages.filter { it.role !in setOf("user", "assistant", "system") }
            .sumOf { estimateMessageWithOverheadTokens(it.content) }

        addTokens("messages_user", "User Messages", userTokens)
        addTokens("messages_assistant", "Assistant Messages", assistantTokens)
        addTokens("messages_system", "System Role Messages", systemRoleTokens)
        addTokens("messages_other", "Other Role Messages", otherTokens)

        if (hasInjectedContext) {
            addTokens("context_injection_overhead", "Context Injection Overhead", 10)
        }

        val normalizedSections = if (activeTokens > 0) {
            val subtotal = sections.values.sumOf { it.second }
            if (subtotal > activeTokens && subtotal > 0) {
                val scale = activeTokens.toDouble() / subtotal.toDouble()
                sections.mapValues { (_, value) ->
                    val scaledTokens = (value.second * scale).toInt().coerceAtLeast(1)
                    value.first to scaledTokens
                }.toMutableMap()
            } else {
                sections.toMutableMap()
            }
        } else {
            sections.toMutableMap()
        }

        val subtotal = normalizedSections.values.sumOf { it.second }
        val residual = (activeTokens - subtotal).coerceAtLeast(0)
        if (residual > 0) {
            val existing = normalizedSections["request_overhead"]
            normalizedSections["request_overhead"] = if (existing == null) {
                "Request Overhead" to residual
            } else {
                existing.first to (existing.second + residual)
            }
        }

        val denominator = activeTokens.coerceAtLeast(1).toDouble()
        return normalizedSections.mapValues { (_, value) ->
            val (name, tokens) = value
            ContextSectionTokenInfo(
                name = name,
                tokens = tokens,
                chars = tokens * 4,
                percentage = (tokens / denominator) * 100.0
            )
        }
    }

    private fun renderActiveRequestPreview(
        mode: TaskMode,
        systemMessages: List<String>,
        messages: List<LLMMessage>
    ): String {
        return buildString {
            appendLine("Mode: ${mode.name}")
            appendLine()
            appendLine("SYSTEM MESSAGES (${systemMessages.size}):")
            if (systemMessages.isEmpty()) {
                appendLine("(none)")
            } else {
                systemMessages.forEachIndexed { index, message ->
                    appendLine("[SYSTEM ${index + 1}]")
                    appendLine(message)
                    appendLine()
                }
            }
            appendLine()
            appendLine("MESSAGES (${messages.size}):")
            if (messages.isEmpty()) {
                appendLine("(none)")
            } else {
                messages.forEachIndexed { index, msg ->
                    appendLine("[MESSAGE ${index + 1}] role=${msg.role}")
                    appendLine(msg.content)
                    appendLine()
                }
            }
            if (isNotEmpty() && last() == '\n') {
                setLength(length - 1)
            }
        }
    }

    private fun buildAuxiliaryPromptPreview(mode: TaskMode): AuxiliaryPromptPreview {
        val activeType = when (mode) {
            TaskMode.CHAT -> PromptType.SYSTEM_CHAT
            TaskMode.PLAN -> PromptType.SYSTEM_PLAN
            TaskMode.AGENT -> PromptType.SYSTEM_AGENT
        }

        val auxiliaryEntries = PromptType.SYSTEM_PROMPT_TYPES
            .filter { it != activeType }
            .sortedBy { it.name }
            .mapNotNull { type ->
                val content = runCatching { promptsService.getSystemPrompt(type) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val role = if (type.name.endsWith("_USER")) "user_template" else "system"
                PromptPreviewEntry(
                    source = type.name,
                    role = role,
                    content = content,
                    estimatedTokens = TokenEstimator.estimateTokens(content)
                )
            }

        if (auxiliaryEntries.isEmpty()) {
            return AuxiliaryPromptPreview(previewText = "", estimatedTokens = 0)
        }

        val tokens = auxiliaryEntries.sumOf { it.estimatedTokens }
        val preview = buildString {
            appendLine("<AUXILIARY_LLM_PROMPTS>")
            appendLine("<NOTE>")
            appendLine("These prompts are used by tools/summarizers/workflow helpers and are not sent in a single active request.")
            appendLine("</NOTE>")
            appendLine()
            auxiliaryEntries.forEachIndexed { index, prompt ->
                appendLine("""<prompt index="${index + 1}" source="${prompt.source}" role="${prompt.role}" tokens="${prompt.estimatedTokens}">""")
                appendLine(prompt.content)
                appendLine("</prompt>")
                appendLine()
            }
            appendLine("</AUXILIARY_LLM_PROMPTS>")
        }.trim()

        return AuxiliaryPromptPreview(previewText = preview, estimatedTokens = tokens)
    }

    /**
     * Extract a specific section from LLM prompt (e.g., RECENT_WORK, RAG_FRAGMENTS)
     * Used to show the actual content that will be sent to LLM in ContextPanel.
     */
    private fun extractSectionFromPrompt(prompt: String, sectionTag: String): String? {
        val openTag = "<$sectionTag>"
        val closeTag = "</$sectionTag>"

        val openIndex = findTagAtLineStart(prompt, openTag, 0)
        if (openIndex == -1) return null

        val contentStart = openIndex + openTag.length
        val closeIndex = findTagAtLineStart(prompt, closeTag, contentStart)
        val nextSectionIndex = findNextKnownSectionStart(prompt, contentStart)

        val hasClosingTag = closeIndex != -1 && (nextSectionIndex == null || closeIndex <= nextSectionIndex)
        val contentEnd = when {
            hasClosingTag -> closeIndex
            nextSectionIndex != null -> nextSectionIndex
            else -> prompt.length
        }

        if (contentEnd < contentStart) return null
        return prompt.substring(contentStart, contentEnd).trim()
    }

    private fun findNextKnownSectionStart(prompt: String, fromIndex: Int): Int? {
        val knownSectionTags = listOf(
            "PROJECT_CONTEXT",
            "CURRENT_TASK",
            "USER_REQUIREMENTS",
            "USER_PROVIDED_CONTEXT",
            "WORKING_MEMORY",
            "MCP_RESOURCES",
            "RAG_FRAGMENTS",
            "CONVERSATION_HISTORY",
            "RECENT_WORK",
            "SUBTASKS_STATUS",
            "KEY_COMPONENTS",
            "PROJECT_DEPENDENCIES",
            "CODE_ANALYSIS"
        )

        var nextIndex: Int? = null
        for (tag in knownSectionTags) {
            val candidate = findTagAtLineStart(prompt, "<$tag>", fromIndex)
            if (candidate != -1 && (nextIndex == null || candidate < nextIndex)) {
                nextIndex = candidate
            }
        }
        return nextIndex
    }

    private fun findTagAtLineStart(prompt: String, tag: String, fromIndex: Int): Int {
        var index = prompt.indexOf(tag, fromIndex.coerceAtLeast(0))
        while (index != -1) {
            val lineStart = prompt.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            if (prompt.substring(lineStart, index).isBlank()) {
                return index
            }
            index = prompt.indexOf(tag, index + 1)
        }
        return -1
    }

    /**
     * Map ProjectContextDTO to ProjectContextResponse (API response format).
     * Simple mapping helper to convert internal DTO to API response.
     */
    private suspend fun mapToProjectContextResponse(
        context: ProjectContextDTO,
        llmPrompt: String,
        llmPreviewPrompt: String,
        activeLlmPreviewPrompt: String,
        auxiliaryPreviewPrompt: String,
        activeEstimatedTokens: Int,
        auxiliaryEstimatedTokens: Int,
        combinedEstimatedTokens: Int
    ): ProjectContextResponse {
        // Get project analysis for infrastructure and primaryLanguage (ADR 0040)
        val projectAnalysis = projectAnalyzer?.analyzeProject(projectRoot!!, includeContent = false)

        // Convert conversation history to response DTO
        val conversationDTOs = context.conversationHistory.map { msg ->
            ConversationMessageDTO(
                id = msg.id,
                role = msg.role,
                content = msg.content,
                createdAt = msg.createdAt,
                processingTime = msg.processingTime,
                inputTokens = msg.inputTokens,
                outputTokens = msg.outputTokens,
                cost = msg.cost,
                modelId = msg.modelId,
                metadata = msg.metadata
            )
        }

        // Convert user context to UserContextRefDTO format (for backward compatibility with UI)
        val userContextRefDTOs = context.userContextRefs.map { ref ->
            UserContextRefDTO(
                type = ref.type,
                providerId = ref.providerId,
                path = ref.path,
                displayName = ref.displayName,
                content = ref.content,
                sizeBytes = ref.sizeBytes,
                estimatedTokens = ref.estimatedTokens
            )
        }

        // Extract sticky prompt sections for display in ContextPanel/TUI
        val taskRequirementsPrompt = extractSectionFromPrompt(llmPrompt, "TASK_REQUIREMENTS")
        val recentWorkPrompt = extractSectionFromPrompt(llmPrompt, "RECENT_WORK")

        return ProjectContextResponse(
            projectPath = context.workspace.path,
            projectType = context.projectType,
            technologies = context.technologies,
            technologyVersions = context.technologyVersions,
            infrastructure = projectAnalysis?.infrastructure ?: emptyList(),
            primaryLanguage = projectAnalysis?.primaryLanguage ?: "Unknown",
            mainLanguage = context.summary.mainLanguage,
            complexity = context.summary.complexity,
            totalFiles = context.structure.totalFiles,
            fileTypes = context.structure.fileTypes,
            keyComponents = context.keyComponents,
            dependencies = mapOf(
                "python" to context.dependencies.python,
                "javascript" to context.dependencies.javascript
            ),
            codeAnalysis = mapOf(
                "kotlin" to context.codeAnalysis.kotlin,
                "java" to context.codeAnalysis.java,
                "python" to context.codeAnalysis.python,
                "javascript" to context.codeAnalysis.javascript,
                "typescript" to context.codeAnalysis.typescript,
                "html" to context.codeAnalysis.html,
                "css" to context.codeAnalysis.css
            ),
            currentTask = context.currentTask,
            subtasks = context.subtasks,
            executedSteps = context.executedSteps,
            completedFiles = context.completedFiles,
            llmContextPrompt = llmPreviewPrompt,
            analyzedAt = context.contextGeneratedAt.toEpochMilli(),
            contextBuiltAt = context.contextGeneratedAt.toEpochMilli(),
            userRequirements = context.userRequirements,
            ragFragments = context.ragFragments,
            mcpResources = context.mcpResources.map {
                MCPResourceResponse(
                    serverId = it.serverId,
                    uri = it.uri,
                    name = it.name,
                    description = it.description,
                    mimeType = it.mimeType
                )
            },
            userContextRefs = userContextRefDTOs,
            conversationHistory = conversationDTOs,
            previousSubtasks = context.executedSteps.map { it.displayContent },
            domainAnalysis = context.domainAnalysis,
            directoryCount = context.structure.directoryCount,
            maxDepth = context.structure.maxDepth,
            contextSectionTokens = context.sectionTokens ?: emptyMap(),
            totalEstimatedTokens = activeEstimatedTokens,
            activeEstimatedTokens = activeEstimatedTokens,
            auxiliaryEstimatedTokens = auxiliaryEstimatedTokens,
            combinedEstimatedTokens = combinedEstimatedTokens,
            semanticSummary = context.semanticSummary,
            projectInstructions = context.projectInstructions,
            taskRequirementsPrompt = taskRequirementsPrompt,
            recentWorkPrompt = recentWorkPrompt,
            activeLlmRequestPrompt = activeLlmPreviewPrompt,
            auxiliaryPromptsPreview = auxiliaryPreviewPrompt
        )
    }


    /**
     * Invalidate project analysis cache
     *
     * @return Success response
     */
    fun invalidateProjectCache(): Map<String, Any> {
        logger.info { "Invalidating project analysis cache" }

        if (projectAnalyzer == null || projectRoot == null) {
            throw IllegalStateException("Project analyzer not available - projectRoot required")
        }

        try {
            projectAnalyzer.invalidateCache(projectRoot)
            return mapOf(
                "success" to true,
                "message" to "Project analysis cache invalidated"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to invalidate project cache" }
            throw e
        }
    }

    // ========== Tool Permissions API ==========

    /**
     * Get permissions for all tools
     *
     * @param taskId Optional task ID for task-level permissions
     * @return Tool permissions response
     */
    fun getToolPermissions(taskId: String? = null): pl.jclab.refio.core.models.api.ToolPermissionsResponse {
        return toolRouter.getToolPermissions(taskId)
    }

    /**
     * Get all currently registered tool definitions.
     */
    fun getAvailableToolDefinitions(): List<ToolDefinitionInfo> {
        return toolRegistry
            ?.getAllTools()
            .orEmpty()
            .sortedBy { it.name }
            .map { tool ->
                ToolDefinitionInfo(
                    name = tool.name,
                    description = tool.description,
                    mode = tool.mode.name,
                    category = tool.category.name,
                    defaultPlanMode = if (tool.mode == pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY) "ON" else "OFF",
                    defaultAgentMode = "ON"
                )
            }
    }

    /**
     * Set permission for a specific tool
     *
     * @param toolName Name of the tool
     * @param request Permission levels for plan and agent modes
     * @param taskId Optional task ID for task-level permissions
     */
    fun setToolPermission(
        toolName: String,
        request: pl.jclab.refio.core.models.api.SetToolPermissionRequest,
        taskId: String? = null
    ) {
        toolRouter.setToolPermission(toolName, request, taskId)
    }

    /**
     * Reset tool permissions to smart defaults
     *
     * @param taskId Optional task ID for task-level permissions
     */
    fun resetToolPermissions(taskId: String? = null) {
        try {
            toolPermissionsService.resetToDefaults(taskId)
            logger.info { "Reset tool permissions to defaults" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to reset tool permissions" }
            throw e
        }
    }

    // ========== Snapshot API ==========

    /**
     * Get snapshot content for given snapshot ID.
     *
     * @param snapshotId Snapshot ID (subtask_id)
     * @return Map of file path to content
     */
    suspend fun getSnapshot(snapshotId: String): SnapshotResponse {
        if (snapshotService == null) {
            throw IllegalStateException("Snapshot operations require project context")
        }

        try {
            val files = snapshotService.getSnapshot(snapshotId)
            return SnapshotResponse(
                snapshotId = snapshotId,
                files = files
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get snapshot: $snapshotId" }
            throw e
        }
    }

    /**
     * Get content of a specific file from snapshot.
     *
     * @param snapshotId Snapshot ID
     * @param filePath Relative file path
     * @return File content or null if not found
     */
    suspend fun getSnapshotFileContent(snapshotId: String, filePath: String): String? {
        if (snapshotService == null) {
            throw IllegalStateException("Snapshot operations require project context")
        }

        try {
            val snapshot = snapshotService.getSnapshot(snapshotId)
            return snapshot[filePath]
        } catch (e: Exception) {
            logger.error(e) { "Failed to get snapshot file content: $snapshotId/$filePath" }
            return null
        }
    }

    /**
     * Delete all snapshots for a task.
     * Useful for "rewind conversation" to avoid leaving orphaned snapshots after deleting subtasks.
     */
    fun deleteSnapshotsByTaskId(taskId: String): Int {
        return snapshotRepository.deleteByTaskId(taskId)
    }

    // ========== RAG API ==========

    /**
     * Get RAG indexed files for current project
     * Includes both project files AND documentation files associated with this project
     *
     * @return List of indexed files with chunks/embeddings count
     */
    suspend fun getRagIndexedFiles(): List<pl.jclab.refio.core.api.RagIndexedFileDto> {
        return ragRouter.getRagIndexedFiles()
    }

    /**
     * Get RAG statistics for current project
     * Includes both project files AND documentation files associated with this project
     *
     * @return Statistics (files, chunks, embeddings count)
     */
    suspend fun getRagStatistics(): pl.jclab.refio.core.api.RagStatisticsDto {
        return ragRouter.getRagStatistics()
    }

    /**
     * Get RAG chunks for file
     *
     * @param filePath File path (relative)
     * @return List of chunks for file
     */
    suspend fun getRagChunksForFile(filePath: String): List<pl.jclab.refio.core.api.RagChunkDto> {
        return ragRouter.getRagChunksForFile(filePath)
    }

    /**
     * Clear RAG index for current project (delete all indexed files, chunks, embeddings)
     */
    suspend fun clearRagIndex() {
        return ragRouter.clearRagIndex()
    }

    /**
     * Search RAG for relevant chunks
     *
     * @param query Search query
     * @param model Embedding model (must match indexed embeddings)
     * @param topK Number of results to return
     * @param contentType Filter by content type (optional)
     * @return List of search results
     */
    suspend fun searchRag(
        query: String,
        model: String = "ollama/nomic-embed-text",
        topK: Int = 5,
        contentType: RagContentType? = null
    ): List<RagSearchResultDto> {
        return ragRouter.searchRag(query, model, topK, contentType)
    }

    /**
     * Index project files for RAG
     *
     * @param ignorePatterns Additional ignore patterns (optional)
     * @param onProgress Progress callback
     */
    suspend fun indexProjectForRag(
        ignorePatterns: Set<String> = emptySet(),
        onProgress: ((IndexingProgress) -> Unit)? = null
    ) {
        return ragRouter.indexProjectForRag(ignorePatterns, onProgress)
    }

    /**
     * Generate embeddings for indexed chunks in current project
     *
     * @param model Embedding model to use
     * @param onProgress Progress callback
     */
    suspend fun generateEmbeddings(
        model: String = "ollama/nomic-embed-text",
        failFastOnUnavailable: Boolean = false,
        onProgress: ((EmbeddingProgress) -> Unit)? = null
    ) {
        return ragRouter.generateEmbeddings(model, failFastOnUnavailable, onProgress)
    }

    private fun initializeRagSearchService(): Triple<RagSearchService, String, String>? {
        return try {
            val embeddingModelSetting = configService.getEmbeddingModel()
            val (providerId, modelId) = resolveEmbeddingProvider(embeddingModelSetting)
            val embeddingProvider = embeddingProviderFor(providerId)
            Triple(RagSearchService(ragRepository, embeddingProvider), modelId, providerId)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to initialize RagSearchService: ${e.message}" }
            null
        }
    }

    /**
     * Create embedding provider based on model name
     * Supports formats: "provider/modelId" (e.g., "ollama/nomic-embed-text") or just "modelId"
     */
    private fun createEmbeddingProvider(model: String): EmbeddingProvider {
        val (provider, modelId) = if (model.contains("/")) {
            val parts = model.split("/", limit = 2)
            parts[0].lowercase() to parts[1]
        } else {
            null to model
        }

        return provider?.let { embeddingProviderFor(it) } ?: embeddingProviderFromModel(modelId)
    }

    private fun embeddingProviderFromModel(modelId: String?): EmbeddingProvider {
        if (modelId == null) {
            logger.warn { "Embedding model not specified, defaulting to OpenAI provider" }
            return embeddingProviderFor("openai")
        }

        return when {
            modelId.startsWith("text-embedding") -> embeddingProviderFor("openai")
            modelId in setOf(
                "nomic-embed-text",
                "mxbai-embed-large",
                "all-minilm",
                "all-MiniLM-L6-v2"
            ) -> embeddingProviderFor("ollama")

            else -> {
                logger.warn { "Unknown embedding model: $modelId, defaulting to OpenAI provider" }
                embeddingProviderFor("openai")
            }
        }
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

    private fun extractModelId(model: String): String {
        return if (model.contains("/")) {
            model.split("/", limit = 2)[1]
        } else {
            model
        }
    }

    // ========== Documentation API ==========

    /**
     * Get all documentation sources for current project
     *
     * @return List of documentation sources with indexing status
     */
    fun getDocumentationSources(): List<DocumentationSource> {
        return ragRouter.getDocumentationSources()
    }

    /**
     * Add documentation source for current project (create but don't index yet)
     *
     * @param url Documentation base URL
     * @param crawlDepth Maximum crawl depth (default: 2)
     * @return Created documentation source
     */
    fun addDocumentationSource(
        url: String,
        crawlDepth: Int = 2
    ): DocumentationSource {
        return ragRouter.addDocumentationSource(url, crawlDepth)
    }

    fun addDocumentationFile(
        filePath: String
    ): DocumentationSource {
        return ragRouter.addDocumentationFile(filePath)
    }

    /**
     * Index documentation from source
     *
     * @param docId Documentation source ID
     * @return Flow of indexing progress
     */
    fun indexDocumentation(docId: Int): Flow<DocIndexingProgress> {
        return ragRouter.indexDocumentation(docId)
    }

    /**
     * Delete documentation source and all indexed pages
     *
     * @param docId Documentation source ID
     */
    fun deleteDocumentationSource(docId: Int) {
        ragRouter.deleteDocumentationSource(docId)
    }

    /**
     * Delete documentation index (indexed pages) but keep the source
     *
     * @param docId Documentation source ID
     */
    fun deleteDocumentationIndex(docId: Int) {
        ragRouter.deleteDocumentationIndex(docId)
    }

    /**
     * Get documentation statistics for task
     *
     * @param taskId Task ID
     * @return Documentation statistics
     */
    fun getDocumentationStatistics(taskId: String): DocStatistics {
        return ragRouter.getDocumentationStatistics(taskId)
    }

    // ========== API Logs Management ==========

    /**
     * Get recent API logs.
     */
    fun getRecentApiLogs(limit: Int = 50): List<ApiLog> {
        return apiLogsRouter.getRecentApiLogs(limit)
    }

    /**
     * Get filtered API logs.
     */
    fun getFilteredApiLogs(
        provider: String? = null,
        model: String? = null,
        source: String? = null,
        limit: Int = 50
    ): List<ApiLog> {
        return apiLogsRouter.getFilteredApiLogs(provider, model, source, limit)
    }

    /**
     * Get global API log statistics.
     */
    fun getApiLogStatistics(): ApiLogStatistics {
        return apiLogsRouter.getApiLogStatistics()
    }

    /**
     * Get distinct providers from API logs.
     */
    fun getDistinctProviders(): List<String> {
        return apiLogsRouter.getDistinctProviders()
    }

    /**
     * Get distinct models from API logs.
     */
    fun getDistinctModels(): List<String> {
        return apiLogsRouter.getDistinctModels()
    }

    /**
     * Get distinct sources from API logs.
     */
    fun getDistinctSources(): List<String> {
        return apiLogsRouter.getDistinctSources()
    }

    /**
     * Delete all API logs.
     */
    fun deleteAllApiLogs(): Int {
        return apiLogsRouter.deleteAllApiLogs()
    }

    /**
     * Export all API logs to JSON.
     */
    fun exportAllApiLogsToJson(): String {
        return apiLogsRouter.exportAllApiLogsToJson()
    }

    /**
     * Export all API logs to CSV.
     */
    fun exportAllApiLogsToCsv(): String {
        return apiLogsRouter.exportAllApiLogsToCsv()
    }
}

// ========== Extension Functions ==========

/**
 * Convert Task entity to response DTO.
 */
private fun Task.toResponse(
    tokensInOverride: Int? = null,
    tokensOutOverride: Int? = null,
    costUsdOverride: Double? = null
): TaskResponse {
    return TaskResponse(
        id = id,
        name = name,
        mode = mode.name,
        status = status.name,
        readOnly = readOnly,
        pinned = pinned,
        executionMode = executionMode.name,
        requiresPlanApproval = requiresPlanApproval,
        planApproved = planApproved,
        uiState = uiState,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tokensIn = tokensInOverride ?: tokensIn,
        tokensOut = tokensOutOverride ?: tokensOut,
        costUsd = costUsdOverride ?: costUsd,
        rate = rate,
        projectId = projectId,
        projectPath = projectPath
    )
}


