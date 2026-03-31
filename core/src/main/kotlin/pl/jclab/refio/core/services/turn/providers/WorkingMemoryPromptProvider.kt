package pl.jclab.refio.core.services.turn.providers

import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.services.turn.PromptBuildContext
import pl.jclab.refio.core.services.turn.PromptSectionProvider

/**
 * Provides the working memory section for the system prompt.
 * Extracted from TurnPromptBuilder's working memory handling.
 */
class WorkingMemoryPromptProvider(
    private val workingMemoryService: WorkingMemoryService?
) : PromptSectionProvider {

    override suspend fun build(context: PromptBuildContext): String? {
        val wm = workingMemoryService ?: return null
        val section = wm.buildWorkingMemorySection(context.taskId, DEFAULT_WORKING_MEMORY_BUDGET)
        if (section.isBlank()) return null
        return "<working_memory>\n$section\n</working_memory>"
    }

    companion object {
        private const val DEFAULT_WORKING_MEMORY_BUDGET = 3000
    }
}
