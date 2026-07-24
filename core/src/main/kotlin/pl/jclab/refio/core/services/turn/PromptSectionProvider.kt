package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.TaskMode

/**
 * Provides a section of the system prompt.
 * Each provider is responsible for one logical part of the prompt.
 */
fun interface PromptSectionProvider {
    /**
     * Build prompt section content for given context.
     * Return null if this section should not be included.
     */
    suspend fun build(context: PromptBuildContext): String?
}

data class PromptSection(
    val id: String,
    val content: String,
    val stable: Boolean
)

/**
 * Context available to prompt section providers during build.
 */
data class PromptBuildContext(
    val taskId: String,
    val mode: TaskMode,
    val iteration: Int,
    val maxIterations: Int,
    val writeToolsExecutedInTurn: Int,
    val profileOverrides: TurnProfileOverrides?,
    /**
     * Plan-scope id of the agent this prompt is for: the run id for a subagent, null for the
     * top-level orchestrator. Mirrors the `_agent_id` the `tasks` tool keys plans by, so a
     * section provider can show a subagent only its own plan. Null = orchestrator (all plans).
     */
    val agentId: String? = null
)
