package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.AgentPlanService

/**
 * Injects agent execution plans into the LLM prompt context.
 *
 * The top-level orchestrator (context.agentId == null) sees every agent's plan for the task, so it
 * can track how far each subagent got. A subagent (context.agentId set) sees only its own plan -
 * its parent's and siblings' plans are noise and can mislead a weak model into updating a step it
 * never created.
 */
class AgentPlansSectionProvider(
    private val agentPlanService: AgentPlanService
) : PromptSectionProvider {

    override suspend fun build(context: PromptBuildContext): String? {
        val section = agentPlanService.buildPlanSection(context.taskId, context.agentId)
        return section.ifBlank { null }
    }
}
