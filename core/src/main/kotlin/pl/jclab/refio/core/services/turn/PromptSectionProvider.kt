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

/**
 * Context available to prompt section providers during build.
 */
data class PromptBuildContext(
    val taskId: String,
    val mode: TaskMode,
    val iteration: Int,
    val maxIterations: Int,
    val writeToolsExecutedInTurn: Int,
    val profileOverrides: TurnProfileOverrides?
)
