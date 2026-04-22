package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.agents.MultiAgentRunner
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.routers.AgentRouter
import pl.jclab.refio.core.api.routers.ApiLogsRouter
import pl.jclab.refio.core.api.routers.ChatRouter
import pl.jclab.refio.core.api.routers.ConfigRouter
import pl.jclab.refio.core.api.routers.MultiAgentRouter
import pl.jclab.refio.core.api.routers.ProjectContextRouter
import pl.jclab.refio.core.api.routers.PromptsRouter
import pl.jclab.refio.core.api.routers.RagRouter
import pl.jclab.refio.core.api.routers.SnapshotRouter
import pl.jclab.refio.core.api.routers.SubtaskRouter
import pl.jclab.refio.core.api.routers.TaskRouter
import pl.jclab.refio.core.api.routers.ToolRouter
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.services.turn.PromptSectionProvider
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.workflow.IntentRouter
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import java.nio.file.Path

/**
 * Lazy holder for the 12 domain routers + workflow plumbing exposed by [CoreApiRouter].
 *
 * Pulling the router wiring out of [CoreApiRouter] keeps the composition root focused
 * on *creating* dependencies; this module *assembles* them into the public surface.
 * Each router is lazy so callers that never touch (e.g.) the RAG surface don't pay
 * its construction cost.
 */
internal class DomainRouters(
    private val persistence: PersistenceModule,
    private val analysisStack: AnalysisStack,
    private val chatPlanning: ChatPlanningModule,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry?,
    private val toolPermissionsService: ToolPermissionsService,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val agentExecutor: AgentExecutor?,
    private val agentTurnLoop: AgentTurnLoop?,
    private val userInteraction: UserInteraction,
    private val multiAgentRunner: MultiAgentRunner,
    private val projectRoot: Path?,
    private val promptSectionProviders: List<PromptSectionProvider>,
    private val routerProjectId: String?,
    private val routerProjectPath: String?,
    private val embeddingProviderFactory: (String) -> EmbeddingProvider,
    private val codebaseCacheInvalidator: (projectRoot: String) -> Unit,
) {
    private val chatService get() = chatPlanning.chatService
    private val planningService get() = chatPlanning.planningService
    private val ragSearchService get() = analysisStack.ragSearchService
    private val embeddingsService get() = analysisStack.embeddingsService
    private val fileAnalyzerService get() = analysisStack.fileAnalyzerService
    private val snapshotService get() = analysisStack.snapshotService
    private val contextService get() = analysisStack.contextService
    private val projectAnalyzer get() = analysisStack.projectAnalyzer
    private val richProjectAnalysisEngine get() = analysisStack.richProjectAnalysisEngine
    val chatRouter: ChatRouter by lazy {
        ChatRouter(
            chatService = chatService,
            chatMessageRepository = persistence.chatMessageRepository,
            taskRepository = persistence.taskRepository,
        )
    }

    val configRouter: ConfigRouter by lazy {
        ConfigRouter(
            configService = configService,
            llmClient = llmClient,
            configRepository = persistence.configRepository,
        )
    }

    val toolRouter: ToolRouter by lazy {
        ToolRouter(
            toolRegistry = toolRegistry,
            toolPermissionsService = toolPermissionsService,
        )
    }

    val agentRouter: AgentRouter by lazy {
        AgentRouter(
            agentExecutor = agentExecutor,
            taskRepository = persistence.taskRepository,
            subtaskRepository = persistence.subtaskRepository,
            chatMessageRepository = persistence.chatMessageRepository,
            configService = configService,
            llmClient = llmClient,
            promptsService = promptsService,
            contextService = contextService,
            projectRoot = projectRoot,
            toolDescriptionBuilder = toolDescriptionBuilder,
            agentTurnLoop = agentTurnLoop,
        )
    }

    val ragRouter: RagRouter by lazy {
        RagRouter(
            ragRepository = persistence.ragRepository,
            documentationRepository = persistence.documentationRepository,
            ragSearchService = ragSearchService,
            embeddingsService = embeddingsService,
            fileAnalyzerService = fileAnalyzerService,
            projectRoot = projectRoot,
            configService = configService,
            embeddingProviderFactory = embeddingProviderFactory,
            codebaseCacheInvalidator = codebaseCacheInvalidator,
        )
    }

    val taskRouter: TaskRouter by lazy {
        TaskRouter(
            taskRepository = persistence.taskRepository,
            configService = configService,
            defaultProjectId = routerProjectId,
            defaultProjectPath = routerProjectPath,
        )
    }

    val subtaskRouter: SubtaskRouter by lazy {
        SubtaskRouter(
            subtaskRepository = persistence.subtaskRepository,
        )
    }

    val promptsRouter: PromptsRouter by lazy {
        PromptsRouter(promptsService = promptsService)
    }

    val apiLogsRouter: ApiLogsRouter by lazy {
        ApiLogsRouter(apiLogRepository = persistence.apiLogRepository)
    }

    val subagentRouter: SubagentRouter? by lazy {
        if (projectRoot != null && toolRegistry != null) {
            SubagentRouter(
                projectRoot = projectRoot,
                toolRegistry = toolRegistry,
                configService = configService,
                llmClient = llmClient,
                toolPermissionsService = toolPermissionsService,
                chatMessageRepository = persistence.chatMessageRepository,
                contextService = contextService,
                runTurnCallback = { request, callback ->
                    agentRouter.runTurn(
                        request = request,
                        streamCallback = callback,
                        listener = null,
                    )
                },
            )
        } else null
    }

    val snapshotRouter: SnapshotRouter by lazy {
        SnapshotRouter(
            snapshotService = snapshotService,
            snapshotRepository = persistence.snapshotRepository,
        )
    }

    val projectContextRouter: ProjectContextRouter by lazy {
        ProjectContextRouter(
            contextService = contextService,
            projectRoot = projectRoot,
            taskRepository = persistence.taskRepository,
            chatMessageRepository = persistence.chatMessageRepository,
            promptsService = promptsService,
            toolDescriptionBuilder = toolDescriptionBuilder,
            projectAnalyzer = projectAnalyzer,
            richProjectAnalysisEngine = richProjectAnalysisEngine,
            promptSectionProviders = promptSectionProviders,
            configService = configService,
        )
    }

    val workflowOrchestrator: WorkflowOrchestrator by lazy {
        val intentRouter = IntentRouter(
            subtaskRepository = persistence.subtaskRepository,
            subagentRouter = subagentRouter,
        )
        WorkflowOrchestrator(
            intentRouter = intentRouter,
            chatService = chatService,
            planningService = planningService,
            agentRouter = agentRouter,
            subagentRouter = subagentRouter,
            userInteraction = userInteraction,
        )
    }

    val multiAgentRouter: MultiAgentRouter by lazy {
        MultiAgentRouter(
            defaultProjectId = routerProjectId,
            defaultProjectPath = routerProjectPath,
            agentSessionRepository = persistence.agentSessionRepository,
            agentInstanceRepository = persistence.agentInstanceRepository,
            multiAgentRunner = multiAgentRunner,
            createTaskFn = { request -> taskRouter.createTask(request) },
            runTurnFn = { request: TurnRequest, callback: StreamCallback? -> agentRouter.runTurn(request, callback) },
        )
    }

}
