package pl.jclab.refio.core.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
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

    // Services (public for cross-module access by plugin services)
    val taskRepository = TaskRepository()
    val configService = ConfigService(
        configRepository = configRepository,
        defaultProjectId = routerProjectId
    )
    private val promptRegistry = pl.jclab.refio.core.prompts.PromptRegistry(projectRoot)
    val promptsService = PromptsService(promptsRepository, promptRegistry)
    val toolPermissionsService = ToolPermissionsService(configRepository)
    val llmClient = llmClientOverride ?: LLMClient(configService)
    private val workingMemoryService = WorkingMemoryService()
    private val workingMemoryIntegration = WorkingMemoryIntegration(workingMemoryService)
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
        GoLanguageAnalyzer(),
        RustLanguageAnalyzer(),
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
            workingMemoryIntegration = workingMemoryIntegration,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository
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
            workingMemoryIntegration = workingMemoryIntegration
        )
    } else null

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
            richProjectAnalysisEngine = richProjectAnalysisEngine
        )
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
            try {
                if (!toolRegistry.hasTool("invoke_subagent")) {
                    val invokeSubagentTool = pl.jclab.refio.core.tools.implementations.InvokeSubagentTool(
                        subagentRouterProvider = { subagentRouter },
                        runTurnCallback = { request, turnEventListener, streamCallback ->
                            agentRouter.runTurn(
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

    // configService is accessible directly as a public property

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

    // ========== Snapshot API (kept — no domain router) ==========

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


