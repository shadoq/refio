package pl.jclab.refio.core.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.turn.*
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.ProjectIdGenerator
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
    private val platformProjectOverride: Any? = null,
    private val llmClientOverride: LLMClient? = null,
    /** Platform-agnostic project handle. When provided, platformProject is derived from projectHandle.platformProject. */
    val projectHandle: pl.jclab.refio.core.project.ProjectHandle? = null,
    /** Callback to invalidate codebase context cache after RAG operations. Set by plugin layer. */
    private val codebaseCacheInvalidator: (projectRoot: String) -> Unit = {}
) {
    private val routerProjectId: String? = projectHandle?.id ?: projectRoot?.let { ProjectIdGenerator.generate(it) }
    private val routerProjectPath: String? = projectHandle?.rootPath?.toAbsolutePath()?.normalize()?.toString()
        ?: projectRoot?.toAbsolutePath()?.normalize()?.toString()

    /** Resolved platform-specific project — from projectHandle.platformProject or direct override */
    private val resolvedPlatformProject: Any?
        get() = platformProjectOverride ?: projectHandle?.platformProject

    // Persistence layer — all repositories centralized in PersistenceModule.
    private val persistence = pl.jclab.refio.core.api.modules.PersistenceModule()

    // Multi-agent event bus — persists events so Session Trace / Timeline / Graph can replay history.
    val agentEventBus = pl.jclab.refio.core.agents.events.AgentEventBus().apply {
        setRepository(persistence.agentEventSqlRepository)
    }

    // Core services (public for cross-module access by plugin services)
    val taskRepository get() = persistence.taskRepository
    val configService = ConfigService(
        configRepository = persistence.configRepository,
        defaultProjectId = routerProjectId
    )
    private val promptRegistry = pl.jclab.refio.core.prompts.PromptRegistry(projectRoot)
    val promptsService = PromptsService(persistence.promptsRepository, promptRegistry)
    val toolPermissionsService = ToolPermissionsService(
        configRepository = persistence.configRepository,
        toolRegistry = toolRegistry
    )
    val toolApprovalService = ToolApprovalService()
    val llmClient = llmClientOverride ?: LLMClient(configService)

    // Support services (hooks, working memory, agent plans, conversation summary, user interaction, queue).
    private val supportServices = pl.jclab.refio.core.api.modules.SupportServicesModule(
        projectRoot = projectRoot,
        chatMessageRepository = persistence.chatMessageRepository,
        llmClient = llmClient,
        promptsService = promptsService,
        configService = configService,
    )
    private val workingMemoryService get() = supportServices.workingMemoryService
    val agentPlanService get() = supportServices.agentPlanService
    private val conversationSummaryService get() = supportServices.conversationSummaryService
    val userInteraction get() = supportServices.userInteraction
    val pendingUserMessageQueue get() = supportServices.pendingUserMessageQueue

    /**
     * Shared between [pl.jclab.refio.core.services.turn.TurnPromptBuilder] (runtime)
     * and [pl.jclab.refio.core.api.routers.ProjectContextRouter] (preview) so the
     * Context panel mirrors the exact system prompt the model receives.
     */
    val promptSectionProviders: List<pl.jclab.refio.core.services.turn.PromptSectionProvider> by lazy {
        listOf(
            AgentPlansSectionProvider(agentPlanService),
            pl.jclab.refio.core.services.turn.providers.SystemEnvironmentPromptProvider(projectRoot)
        )
    }

    /**
     * Get the ToolRegistry for this router.
     * Used by MCPManager to register MCP tools.
     */
    fun getToolRegistry(): ToolRegistry {
        return toolRegistry ?: throw IllegalStateException("ToolRegistry not available for this router")
    }

    fun hasIdeProject(): Boolean {
        return resolvedPlatformProject != null
    }

    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val embeddingProviderFactory = pl.jclab.refio.core.api.modules.EmbeddingProviderFactory(configService)

    // Analysis stack — embeddings / RAG / analyzer / context / snapshot.
    // Null-valued fields when projectRoot is absent (app-level router).
    private val analysisStack = pl.jclab.refio.core.api.modules.AnalysisStack(
        projectRoot = projectRoot,
        configService = configService,
        ragRepository = persistence.ragRepository,
        snapshotRepository = persistence.snapshotRepository,
        analysisReportRepository = persistence.projectAnalysisReportRepository,
        taskRepository = taskRepository,
        chatMessageRepository = persistence.chatMessageRepository,
        subtaskRepository = persistence.subtaskRepository,
        workingMemoryService = workingMemoryService,
        conversationSummaryService = conversationSummaryService,
        scope = routerScope,
        embeddingProviderFactory = embeddingProviderFactory,
        platformProject = resolvedPlatformProject,
    )
    val projectAnalyzer get() = analysisStack.projectAnalyzer
    private val contextService get() = analysisStack.contextService
    private val snapshotService get() = analysisStack.snapshotService
    private val ragSearchService get() = analysisStack.ragSearchService

    // Tool description builder (needs ToolRegistry and ToolPermissionsService)
    private val toolDescriptionBuilder = pl.jclab.refio.core.prompts.ToolDescriptionBuilder(
        toolRegistry = toolRegistry ?: ToolRegistry(), // Fallback to empty registry if not provided
        toolPermissionsService = toolPermissionsService
    )

    private val chatPlanning = pl.jclab.refio.core.api.modules.ChatPlanningModule(
        persistence = persistence,
        configService = configService,
        llmClient = llmClient,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        toolRegistry = toolRegistry,
        toolPermissionsService = toolPermissionsService,
        contextService = contextService,
        projectRoot = projectRoot,
    )

    // Agent execution stack (StepPlanner, ToolExecutor, AgentExecutor) — null
    // when toolRegistry is absent (app-level router without a project selected).
    private val agentExecutionModule = pl.jclab.refio.core.api.modules.AgentExecutionModule(
        persistence = persistence,
        llmClient = llmClient,
        configService = configService,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        toolPermissionsService = toolPermissionsService,
        toolApprovalService = toolApprovalService,
        contextService = contextService,
        snapshotService = snapshotService,
        projectRoot = projectRoot,
        toolRegistry = toolRegistry
    )
    private val toolExecutor get() = agentExecutionModule.toolExecutor
    private val agentExecutor get() = agentExecutionModule.agentExecutor

    /**
     * AgentTurnLoop — Turn-based execution loop implementing the Codex CLI-style pattern.
     * Null when [toolRegistry] or [toolExecutor] are unavailable (app-level router).
     */
    private val agentTurnLoop: AgentTurnLoop? = pl.jclab.refio.core.api.modules.AgentTurnLoopFactory(
        persistence = persistence,
        support = supportServices,
        llmClient = llmClient,
        configService = configService,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        contextService = contextService,
        snapshotService = snapshotService,
        toolApprovalService = toolApprovalService,
        toolPermissionsService = toolPermissionsService,
        agentEventBus = agentEventBus,
        promptSectionProviders = promptSectionProviders,
        projectRoot = projectRoot,
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

    // ========== Domain Routers (RFC 0005) - Public API ==========
    // All 12 router lazy vals + workflow plumbing live in [DomainRouters] so
    // composition-root concerns stay separated from public API wiring.

    val multiAgentRunner by lazy {
        pl.jclab.refio.core.agents.MultiAgentRunner(agentEventBus)
    }

    private val domainRouters = pl.jclab.refio.core.api.modules.DomainRouters(
        persistence = persistence,
        analysisStack = analysisStack,
        chatPlanning = chatPlanning,
        configService = configService,
        promptsService = promptsService,
        llmClient = llmClient,
        toolRegistry = toolRegistry,
        toolPermissionsService = toolPermissionsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        agentExecutor = agentExecutor,
        agentTurnLoop = agentTurnLoop,
        userInteraction = userInteraction,
        multiAgentRunner = multiAgentRunner,
        projectRoot = projectRoot,
        promptSectionProviders = promptSectionProviders,
        routerProjectId = routerProjectId,
        routerProjectPath = routerProjectPath,
        embeddingProviderFactory = embeddingProviderFactory::create,
        codebaseCacheInvalidator = codebaseCacheInvalidator,
    )

    val chatRouter get() = domainRouters.chatRouter
    val configRouter get() = domainRouters.configRouter
    val toolRouter get() = domainRouters.toolRouter
    val agentRouter get() = domainRouters.agentRouter
    val ragRouter get() = domainRouters.ragRouter
    val taskRouter get() = domainRouters.taskRouter
    val subtaskRouter get() = domainRouters.subtaskRouter
    val promptsRouter get() = domainRouters.promptsRouter
    val apiLogsRouter get() = domainRouters.apiLogsRouter
    val subagentRouter get() = domainRouters.subagentRouter
    val snapshotRouter get() = domainRouters.snapshotRouter
    val projectContextRouter get() = domainRouters.projectContextRouter
    val workflowOrchestrator get() = domainRouters.workflowOrchestrator
    val multiAgentRouter get() = domainRouters.multiAgentRouter
    val orchestrationDispatcher get() = domainRouters.orchestrationDispatcher

    // Internal accessors for modules in `api.modules` package
    internal val toolRegistryOrNull: ToolRegistry? get() = toolRegistry
    internal val projectRootOrNull: java.nio.file.Path? get() = projectRoot
    internal val persistenceInternal get() = persistence
    internal val workingMemoryServiceInternal get() = workingMemoryService
    internal val ragSearchServiceInternal get() = ragSearchService
    internal val embeddingProviderFactoryInternal get() = embeddingProviderFactory

    init {
        pl.jclab.refio.core.api.modules.CoreApiRouterBootstrap.registerSystemTools(this)
        pl.jclab.refio.core.api.modules.CoreApiRouterBootstrap.applyOllamaConcurrency(configService)
        logger.info {
            "CoreApiRouter init: projectRoot=$projectRoot, contextService=${contextService != null}, " +
                "tools=${toolRegistry != null}, platformProject=${resolvedPlatformProject != null}, " +
                "projectHandle=${projectHandle != null}"
        }
    }

    // configService is accessible directly as a public property

    /**
     * Create a project-level router from this app-level router.
     *
     * Shares the same database but creates project-specific tools and services.
     * Used by StandaloneCoreBootstrap and CoreConnectionManager.
     */
    fun createProjectRouter(
        projectRoot: java.nio.file.Path,
        projectHandle: pl.jclab.refio.core.project.ProjectHandle? = null,
        platformProject: Any? = null
    ): CoreApiRouter = pl.jclab.refio.core.api.modules.ProjectRouterFactory.create(
        projectRoot = projectRoot,
        projectHandle = projectHandle,
        platformProject = platformProject,
        llmClient = llmClient,
        configService = configService,
        promptsService = promptsService,
        taskRepository = taskRepository,
    )

    /** Initialize core components (database, prompt defaults, RAG tool). */
    fun initialize(dbPath: String = "database.sqlite") {
        pl.jclab.refio.core.api.modules.CoreApiRouterBootstrap.initializeCore(this, dbPath)
    }

    fun close() {
        subagentRouter?.clearTemporary()
        agentPlanService.clear()
        routerScope.cancel("CoreApiRouter closing")
    }
}


