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
import pl.jclab.refio.core.api.routers.LongTaskRouter
import pl.jclab.refio.core.api.routers.MultiAgentRouter
import pl.jclab.refio.core.api.routers.ProjectContextRouter
import pl.jclab.refio.core.api.routers.PromptsRouter
import pl.jclab.refio.core.api.routers.RagRouter
import pl.jclab.refio.core.api.routers.SnapshotRouter
import pl.jclab.refio.core.api.routers.SubtaskRouter
import pl.jclab.refio.core.api.routers.TaskRouter
import pl.jclab.refio.core.api.routers.ToolRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.services.longtask.DefaultRoundFacts
import pl.jclab.refio.core.services.longtask.GoalJudgeCheck
import pl.jclab.refio.core.services.longtask.LongTaskPrompts
import pl.jclab.refio.core.services.longtask.LongTaskSpec
import pl.jclab.refio.core.services.longtask.ProgressFileWriter
import pl.jclab.refio.core.services.longtask.RoundCheck
import pl.jclab.refio.core.services.longtask.UntilCommandCheck
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.services.turn.ProcessVerificationCommandRunner
import pl.jclab.refio.core.services.turn.PromptSectionProvider
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.implementations.RunTerminalCommandTool
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
    private val agentTurnLoop: AgentTurnLoop?,
    private val userInteraction: UserInteraction,
    private val multiAgentRunner: MultiAgentRunner,
    private val projectRoot: Path?,
    private val promptSectionProviders: List<PromptSectionProvider>,
    private val routerProjectId: String?,
    private val routerProjectPath: String?,
    private val embeddingProviderFactory: (String) -> EmbeddingProvider,
    private val codebaseCacheInvalidator: (projectRoot: String) -> Unit,
    private val agentPlanService: AgentPlanService,
) {
    private val chatService get() = chatPlanning.chatService
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

    /** Sending a message: a single turn, or rounds until the task's `/until` or `/goal` condition holds. */
    val longTaskRouter: LongTaskRouter by lazy {
        LongTaskRouter(
            store = persistence.taskStateRepository,
            taskRepository = persistence.taskRepository,
            configService = configService,
            runTurn = { request, streamCallback, listener -> agentRouter.runTurn(request, streamCallback, listener) },
            facts = DefaultRoundFacts(persistence.subtaskRepository, agentPlanService, snapshotService),
            checkFactory = ::roundCheck,
            progressFileFor = { taskId ->
                projectDir(taskId)?.takeIf { configService.getTyped(ConfigKeys.LONG_TASK_PROGRESS_FILE, taskId) }?.let { ProgressFileWriter(it) }
            },
            originalRequest = { taskId ->
                LongTaskPrompts.originalRequest(
                    persistence.chatMessageRepository.findHistoryForInvocation(taskId, null)
                        .filter { it.role == MessageRole.USER }
                        .map { it.content }
                )
            },
            taskLease = persistence.taskLease,
        )
    }

    private fun roundCheck(taskId: String, spec: LongTaskSpec): RoundCheck {
        val command = spec.untilCommand ?: return GoalJudgeCheck(taskId, spec.goal.orEmpty())
        val workDir = (projectDir(taskId) ?: Path.of(".")).toFile()
        // The way the agent's own terminal runs it: on Windows that is PowerShell, where cmd.exe
        // would fail every round on a command the agent runs without trouble.
        val runner = ProcessVerificationCommandRunner { shellCommand -> RunTerminalCommandTool.shellCommand(shellCommand) }
        return UntilCommandCheck(runner, workDir, command, spec.checkTimeoutSeconds)
    }

    private fun projectDir(taskId: String): Path? =
        projectRoot ?: runCatching { persistence.taskRepository.findById(taskId)?.projectPath?.let { Path.of(it) } }.getOrNull()

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

    val multiAgentRouter: MultiAgentRouter by lazy {
        MultiAgentRouter(
            defaultProjectId = routerProjectId,
            defaultProjectPath = routerProjectPath,
            agentSessionRepository = persistence.agentSessionRepository,
            agentInstanceRepository = persistence.agentInstanceRepository,
            multiAgentRunner = multiAgentRunner,
            createTaskFn = { request -> taskRouter.createTask(request) },
            runTurnFn = { request: TurnRequest, callback: StreamCallback? -> agentRouter.runTurn(request, callback) },
            configService = configService,
            subagentDefinitionFn = { name -> subagentRouter?.getSubagent(name) },
            taskCostFn = { taskId -> persistence.taskRepository.findById(taskId)?.costUsd },
        )
    }

}
