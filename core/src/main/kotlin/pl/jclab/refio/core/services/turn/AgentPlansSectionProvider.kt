package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.AgentPlanService

/**
 * Injects agent execution plans into the LLM prompt context.
 *
 * Shows all plans for the current task, including subagent plans.
 * This allows the orchestrator to see what each agent planned and how far they got.
 */
class AgentPlansSectionProvider(
    private val agentPlanService: AgentPlanService
) : PromptSectionProvider {

    override suspend fun build(context: PromptBuildContext): String? {
        val section = agentPlanService.buildPlanSection(context.taskId)
        return section.ifBlank { null }
    }
}
