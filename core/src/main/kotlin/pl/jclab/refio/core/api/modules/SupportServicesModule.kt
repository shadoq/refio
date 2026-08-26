package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConversationSummaryService
import pl.jclab.refio.core.services.PendingUserMessageQueue
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.WorkingMemoryIntegration
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.services.hooks.HookExecutor
import pl.jclab.refio.core.services.hooks.HookService
import pl.jclab.refio.core.services.orchestration.UserInteraction
import java.nio.file.Path

/**
 * Supporting services that sit above [PersistenceModule] but below domain routers —
 * hook runtime, working-memory caches, agent-plan registry, conversation summarizer,
 * user-interaction signaller, and the pending-user-message queue.
 *
 * Kept together so [pl.jclab.refio.core.api.CoreApiRouter] can wire them in a
 * single line instead of eight field declarations.
 */
class SupportServicesModule(
    projectRoot: Path?,
    chatMessageRepository: ChatMessageRepository,
    llmClient: LLMClient,
    promptsService: PromptsService,
    configService: ConfigService,
) {
    private val hookExecutor = HookExecutor()
    val hookService = HookService(
        configProvider = { pl.jclab.refio.core.config.HierarchicalConfigLoader.getInstance(projectRoot).getHooks() },
        hookExecutor = hookExecutor,
    )

    val workingMemoryService = WorkingMemoryService(
        maxEntriesPerTask = configService.getTyped(ConfigKeys.WORKING_MEMORY_MAX_FACTS)
    )
    val workingMemoryIntegration = WorkingMemoryIntegration(workingMemoryService)
    val agentPlanService = AgentPlanService()

    val conversationSummaryService = ConversationSummaryService(
        llmClient = llmClient,
        promptsService = promptsService,
        configService = configService,
        chatMessageRepository = chatMessageRepository,
    )

    val userInteraction = UserInteraction(chatMessageRepository = chatMessageRepository)
    val pendingUserMessageQueue = PendingUserMessageQueue(chatMessageRepository)
}
