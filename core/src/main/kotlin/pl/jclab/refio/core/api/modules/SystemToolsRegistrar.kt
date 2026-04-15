package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.ToolRegistry

private val logger = dualLogger("SystemToolsRegistrar")

/**
 * Registers system-level tools (invoke_subagent, delegate_to_strong_model,
 * tasks, memory, manage_subagent, send_message) into a ToolRegistry.
 *
 * Extracted from CoreApiRouter.init to keep the composition root readable.
 */
internal class SystemToolsRegistrar(
    private val configService: ConfigService,
    private val llmClient: LLMClient,
    private val agentPlanService: AgentPlanService,
    private val workingMemoryService: WorkingMemoryService,
    private val subtaskRepository: SubtaskRepository,
    private val agentEventBus: AgentEventBus,
    private val subagentRouterProvider: () -> SubagentRouter?,
    private val runTurnCallback: suspend (
        pl.jclab.refio.core.api.TurnRequest,
        pl.jclab.refio.core.services.turn.TurnEventListener?,
        pl.jclab.refio.core.api.StreamCallback?
    ) -> pl.jclab.refio.core.services.TurnResult
) {

    fun register(toolRegistry: ToolRegistry) {
        try {
            if (!toolRegistry.hasTool("invoke_subagent")) {
                val invokeSubagentTool = pl.jclab.refio.core.tools.implementations.InvokeSubagentTool(
                    subagentRouterProvider = subagentRouterProvider,
                    runTurnCallback = runTurnCallback,
                    configServiceProvider = { configService }
                )
                toolRegistry.register(invokeSubagentTool)
                logger.info { "invoke_subagent tool registered" }
            }

            val strongModel = configService.getStrongModel()
            if (strongModel != null && !toolRegistry.hasTool("delegate_to_strong_model")) {
                val delegateToStrongModelTool = pl.jclab.refio.core.tools.implementations.DelegateToStrongModelTool(
                    llmClient = llmClient,
                    configServiceProvider = { configService },
                    runTurnCallback = runTurnCallback
                )
                toolRegistry.register(delegateToStrongModelTool)
                logger.info { "delegate_to_strong_model tool registered (strong model: ${strongModel.second}/${strongModel.first})" }
            }

            val tasksTool = pl.jclab.refio.core.tools.implementations.TasksTool(agentPlanService)
            val memoryTool = pl.jclab.refio.core.tools.implementations.MemoryTool(
                workingMemoryService = workingMemoryService,
                subtaskRepository = subtaskRepository
            )
            val manageSubagentTool = pl.jclab.refio.core.tools.implementations.ManageSubagentTool(subagentRouterProvider)
            val sendMessageTool = pl.jclab.refio.core.tools.implementations.SendMessageTool(agentEventBus)

            listOf(tasksTool, memoryTool, manageSubagentTool, sendMessageTool).forEach { tool ->
                if (!toolRegistry.hasTool(tool.name)) {
                    toolRegistry.register(tool)
                }
            }
            logger.info { "SYSTEM tools registered (tasks, memory, manage_subagent, send_message)" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register system tools" }
        }
    }
}
