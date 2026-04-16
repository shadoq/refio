package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry

/**
 * Bundles the two "conversational-only" services: [ChatService] (CHAT mode) and
 * [PlanningService] (PLAN mode preview + step generation).
 *
 * Extracted from [pl.jclab.refio.core.api.CoreApiRouter] so the composition root
 * doesn't have to hold every service field. Both services share the same
 * repositories, config, and tool metadata, so bundling them avoids repeating the
 * constructor arguments in the composition root.
 */
internal class ChatPlanningModule(
    persistence: PersistenceModule,
    configService: ConfigService,
    llmClient: LLMClient,
    promptsService: PromptsService,
    toolDescriptionBuilder: ToolDescriptionBuilder,
    toolRegistry: ToolRegistry?,
    toolPermissionsService: ToolPermissionsService,
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

    val planningService = PlanningService(
        taskRepository = persistence.taskRepository,
        chatMessageRepository = persistence.chatMessageRepository,
        subtaskRepository = persistence.subtaskRepository,
        configService = configService,
        llmClient = llmClient,
        promptsService = promptsService,
        toolDescriptionBuilder = toolDescriptionBuilder,
        toolRegistry = toolRegistry,
        toolPermissionsService = toolPermissionsService,
        contextService = contextService,
        projectRoot = projectRoot,
    )
}
