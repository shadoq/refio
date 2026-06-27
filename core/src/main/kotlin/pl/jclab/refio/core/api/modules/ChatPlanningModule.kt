package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService

/**
 * Holds [ChatService] (CHAT mode). PLAN/AGENT execution runs through AgentTurnLoop;
 * the legacy PlanningService (plan/step preview) was removed.
 *
 * Extracted from [pl.jclab.refio.core.api.CoreApiRouter] so the composition root
 * doesn't have to hold every service field.
 */
internal class ChatPlanningModule(
    persistence: PersistenceModule,
    configService: ConfigService,
    llmClient: LLMClient,
    promptsService: PromptsService,
    toolDescriptionBuilder: ToolDescriptionBuilder,
    contextService: ContextService?,
    projectRoot: java.nio.file.Path?,
) {
    val chatService = ChatService(
        taskRepository = persistence.taskRepository,
        chatMessageRepository = persistence.chatMessageRepository,
        configService = configService,
        llmClient = llmClient,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        contextService = contextService,
        projectRoot = projectRoot,
    )
}
