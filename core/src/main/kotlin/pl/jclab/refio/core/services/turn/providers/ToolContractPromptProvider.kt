package pl.jclab.refio.core.services.turn.providers

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.turn.PromptBuildContext
import pl.jclab.refio.core.services.turn.PromptSectionProvider
import pl.jclab.refio.core.tools.base.ToolRegistry

/**
 * Provides tool contract section for the system prompt.
 * Extracted from TurnPromptBuilder's tool description handling.
 *
 * In CHAT mode, no tools are exposed. In PLAN/AGENT modes,
 * tool descriptions are resolved and optionally filtered by profile overrides.
 */
class ToolContractPromptProvider(
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val toolRegistry: ToolRegistry
) : PromptSectionProvider {

    override suspend fun build(context: PromptBuildContext): String? {
        if (context.mode == TaskMode.CHAT) return null

        val profileOverrides = context.profileOverrides

        val toolDescriptions = if (profileOverrides?.allowedTools != null || profileOverrides?.disallowedTools != null) {
            val baseTools = toolDescriptionBuilder.getToolsForMode(context.mode, context.taskId)
            val allowed = profileOverrides.allowedTools?.map { it.lowercase() }?.toSet()
            val disallowed = profileOverrides.disallowedTools?.map { it.lowercase() }?.toSet()
            val filteredTools = baseTools.filter { tool ->
                val name = tool.name.lowercase()
                when {
                    allowed != null -> name in allowed
                    disallowed != null -> name !in disallowed
                    else -> true
                }
            }
            toolDescriptionBuilder.getToolDescriptionsForTools(context.mode, filteredTools)
        } else {
            toolDescriptionBuilder.getToolDescriptions(context.mode, context.taskId)
        }

        if (toolDescriptions.isBlank()) return null
        return toolDescriptions
    }
}
