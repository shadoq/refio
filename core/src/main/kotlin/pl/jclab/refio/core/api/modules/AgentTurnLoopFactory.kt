package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.turn.*
import pl.jclab.refio.core.tools.base.ToolRegistry

/**
 * Wires AgentTurnLoop with all its turn/ package sub-components.
 *
 * Extracted from CoreApiRouter to keep composition root readable.
 * Returns null when pre-requisites (toolRegistry, toolExecutor) are not available.
 */
internal class AgentTurnLoopFactory(
    private val persistence: PersistenceModule,
    private val support: SupportServicesModule,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: pl.jclab.refio.core.prompts.ToolDescriptionBuilder,
    private val contextService: ContextService?,
    private val snapshotService: SnapshotService?,
    private val toolApprovalService: ToolApprovalService,
    private val toolPermissionsService: ToolPermissionsService,
    private val agentEventBus: pl.jclab.refio.core.agents.events.AgentEventBus,
    private val agentInboxRegistry: pl.jclab.refio.core.agents.events.AgentInboxRegistry,
    private val promptSectionProviders: List<PromptSectionProvider>,
    private val projectRoot: java.nio.file.Path?,
) {
    private val chatMessageRepository get() = persistence.chatMessageRepository
    private val taskRepository get() = persistence.taskRepository
    private val subtaskRepository get() = persistence.subtaskRepository
    private val workingMemoryService get() = support.workingMemoryService
    private val workingMemoryIntegration get() = support.workingMemoryIntegration
    private val hookService get() = support.hookService

    fun build(toolRegistry: ToolRegistry?, toolExecutor: ToolExecutor?): AgentTurnLoop? {
        if (toolRegistry == null || toolExecutor == null) return null

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

        val turnPromptBuilder = TurnPromptBuilder(
            promptsService = promptsService,
            chatMessageRepository = chatMessageRepository,
            toolDescriptionBuilder = toolDescriptionBuilder,
            contextService = contextService,
            workingMemoryService = workingMemoryService,
            projectRoot = projectRoot,
            tokenEstimator = tokenEstimator,
            promptCache = null,
            sectionProviders = promptSectionProviders,
            configService = configService,
            agentInboxRegistry = agentInboxRegistry
        )

        val toolCallParser = ToolCallParser(
            toolRegistry = toolRegistry,
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
            chatMessageRepository = chatMessageRepository,
            approvalService = toolApprovalService,
            permissionsService = toolPermissionsService,
            hookService = hookService,
            proposedChangeBuilder = projectRoot?.let { ProposedChangeBuilder(it) }
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

        // Next-speaker judge runs at the terminal point of every PLAN/AGENT turn to confirm
        // the agent actually finished (vs. paused mid-task after announcing intent without
        // calling a tool). See [NextSpeakerJudgeGuardian]. CHAT mode self-skips inside the
        // guardian (no tool loop to re-enter).
        // maxReentries matches NextSpeakerJudgeGuardian.MAX_JUDGE_REENTRIES so the
        // registry's hard cap and the guardian's self-cap stay in sync.
        val nextSpeakerJudge = NextSpeakerJudgeGuardian(
            llmClient = llmClient,
            configService = configService
        )
        val completionGuardians = GuardianRegistry(
            guardians = listOf(nextSpeakerJudge),
            maxReentries = NextSpeakerJudgeGuardian.MAX_JUDGE_REENTRIES
        )

        val turnSubagentValidator = TurnSubagentValidator(
            maxSubagentDepth = 3
        )

        // Deterministic post-turn verification: after a file-writing AGENT turn completes, run
        // the project's build/test command (verify.command or autodetected) and feed failures
        // back to the model as a bounded repair loop.
        val turnVerifier = TurnVerifier(
            configService = configService,
            projectRoot = projectRoot
        )

        return AgentTurnLoop(
            llmClient = llmClient,
            chatMessageRepository = chatMessageRepository,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            configService = configService,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder,
            taskVerifier = taskVerifier,
            turnPromptBuilder = turnPromptBuilder,
            toolCallParser = toolCallParser,
            turnToolExecutor = turnToolExecutor,
            turnLLMCaller = turnLLMCaller,
            turnResponseProcessor = turnResponseProcessor,
            turnFinalizer = turnFinalizer,
            turnSubagentValidator = turnSubagentValidator,
            completionGuardians = completionGuardians,
            tokenEstimator = tokenEstimator,
            llmRetryHandler = null,
            workingMemoryIntegration = workingMemoryIntegration,
            agentEventBus = agentEventBus,
            hookService = hookService,
            toolPermissionsService = toolPermissionsService,
            turnVerifier = turnVerifier
        )
    }
}
