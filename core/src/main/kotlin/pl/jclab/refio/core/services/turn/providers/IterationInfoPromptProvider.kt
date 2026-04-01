package pl.jclab.refio.core.services.turn.providers

import pl.jclab.refio.core.services.turn.PromptBuildContext
import pl.jclab.refio.core.services.turn.PromptSectionProvider

/**
 * Provides iteration status and warnings about remaining loop budget.
 * Extracted from TurnPromptBuilder.buildIterationInfo().
 */
class IterationInfoPromptProvider : PromptSectionProvider {

    override suspend fun build(context: PromptBuildContext): String? {
        if (context.iteration <= 0) return null
        return buildIterationInfo(context.iteration, context.maxIterations)
    }

    private fun buildIterationInfo(current: Int, max: Int): String {
        val remaining = max - current

        val warning = when {
            remaining <= 3 -> "\u26a0\ufe0f CRITICAL: Only $remaining iterations left! Prioritize essential actions and prepare to conclude."
            remaining <= 7 -> "\u26a0\ufe0f WARNING: $remaining iterations remaining. Plan efficiently and focus on core objectives."
            remaining <= 12 -> "Note: $remaining iterations remaining. Consider pacing your tool usage."
            else -> ""
        }

        return if (warning.isNotEmpty()) {
            """
<iteration_status>
Current iteration: $current / $max
${warning}
</iteration_status>
            """.trimIndent()
        } else {
            ""
        }
    }
}
