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
            configService = configService
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
            chatMessageRepository = chatMessageRepository,
            approvalService = toolApprovalService,
            permissionsService = toolPermissionsService,
            hookService = hookService
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

        val completionGuardians = GuardianRegistry()

        val turnSubagentValidator = TurnSubagentValidator(
            maxSubagentDepth = 3
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
            conversationCompactor = null,
            llmRetryHandler = null,
            workingMemoryIntegration = workingMemoryIntegration,
            agentEventBus = agentEventBus,
            hookService = hookService,
            toolPermissionsService = toolPermissionsService
        )
    }
}
