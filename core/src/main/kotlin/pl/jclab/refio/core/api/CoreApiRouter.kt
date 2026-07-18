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
    private val codebaseCacheInvalidator: (projectRoot: String) -> Unit = {},
    /**
     * Run-scope config overrides threaded into this router's [configService] and any
     * project router it spawns via [createProjectRouter]. Highest priority, read-only, never
     * persisted. Empty by default — plugin and normal callers are unaffected.
     */
    private val runConfigOverrides: Map<String, String> = emptyMap()
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

    // Per-session, per-agent inbox lookup for A2A peer messaging.
    val agentInboxRegistry = pl.jclab.refio.core.agents.events.AgentInboxRegistry()

    // Core services (public for cross-module access by plugin services)
    val taskRepository get() = persistence.taskRepository
    val configService = ConfigService(
        configRepository = persistence.configRepository,
        defaultProjectId = routerProjectId,
        runConfigOverrides = runConfigOverrides
    )

    /** Builds the structured `run.json` session snapshot for the CLI `--output json`. */
    val sessionDebugExporter = pl.jclab.refio.core.debug.SessionDebugExporter(
        taskRepository = persistence.taskRepository,
        subtaskRepository = persistence.subtaskRepository,
        apiLogRepository = persistence.apiLogRepository,
        chatMessageRepository = persistence.chatMessageRepository,
    )
    private val promptRegistry = pl.jclab.refio.core.prompts.PromptRegistry(projectRoot)
    val promptsService = PromptsService(persistence.promptsRepository, promptRegistry)
    val toolPermissionsService = ToolPermissionsService(
        configRepository = persistence.configRepository,
        toolRegistry = toolRegistry
    )
    val toolApprovalService = ToolApprovalService()
    val llmClient = llmClientOverride ?: LLMClient(
        configService = configService,
        taskRepository = persistence.taskRepository,
        subtaskRepository = persistence.subtaskRepository
    )

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
        snapshotGroupRepository = persistence.snapshotGroupRepository,
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
        contextService = contextService,
        projectRoot = projectRoot,
    )

    // ToolExecutor used by AgentTurnLoop — null when toolRegistry is absent
    // (app-level router without a project selected).
    private val agentExecutionModule = pl.jclab.refio.core.api.modules.AgentExecutionModule(
        persistence = persistence,
        toolPermissionsService = toolPermissionsService,
        snapshotService = snapshotService,
        toolRegistry = toolRegistry
    )
    private val toolExecutor get() = agentExecutionModule.toolExecutor

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
        agentInboxRegistry = agentInboxRegistry,
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

    // ========== Domain Routers - Public API ==========
    // All 12 router lazy vals + workflow plumbing live in [DomainRouters] so
    // composition-root concerns stay separated from public API wiring.

    val multiAgentRunner by lazy {
        pl.jclab.refio.core.agents.MultiAgentRunner(agentEventBus, agentInboxRegistry)
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
    val multiAgentRouter get() = domainRouters.multiAgentRouter

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
        pl.jclab.refio.core.llm.NativeToolsFallbackTracker.bind(configService)
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
        runConfigOverrides = runConfigOverrides,
    )

    /** Initialize core components (database, prompt defaults, RAG tool). */
    fun initialize(dbPath: String = "database.sqlite") {
        pl.jclab.refio.core.api.modules.CoreApiRouterBootstrap.initializeCore(this, dbPath)
    }

    fun close() {
        subagentRouter?.clearTemporary()
        agentPlanService.clear()
        agentEventBus.close()
        routerScope.cancel("CoreApiRouter closing")
    }
}


