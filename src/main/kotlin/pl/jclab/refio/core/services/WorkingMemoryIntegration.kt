package pl.jclab.refio.core.services

import pl.jclab.refio.core.services.context.WorkingMemoryEntry
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("WorkingMemoryIntegration")

/**
 * Integrates WorkingMemory into prompt building.
 *
 * Inspired by Claude Code's reminder injection pattern.
 * Provides relevance-filtered working memory sections for prompts.
 *
 * Reference: ADR-0028 - Enhanced Working Memory
 */
class WorkingMemoryIntegration(
    private val workingMemoryService: WorkingMemoryService
) {
    // Track statistics
    private var totalEntriesAdded = 0

    /**
     * Build working memory section for prompt.
     *
     * @param taskId Task ID
     * @param query Current user query for relevance filtering (optional, not used currently)
     * @param maxTokens Maximum tokens to allocate
     * @return Formatted working memory section or empty string
     */
    fun buildWorkingMemorySection(
        taskId: String,
        query: String,
        maxTokens: Int
    ): String {
        val section = workingMemoryService.buildWorkingMemorySection(taskId, maxTokens)

        if (section.isNotBlank()) {
            val entryCount = countEntries(section)
            totalEntriesAdded += entryCount
            logger.debug { "[WORKING_MEMORY] Added $entryCount entries ($maxTokens tokens max)" }
        }

        return section
    }

    /**
     * Extract and record knowledge from tool execution.
     *
     * Called after each successful tool call to capture important facts.
     *
     * @param taskId Task ID
     * @param toolName Tool that was executed
     * @param params Tool parameters
     * @param result Tool output
     * @param iteration Current iteration number
     */
    fun recordToolKnowledge(
        taskId: String,
        toolName: String,
        params: Map<String, Any>,
        result: String,
        iteration: Int
    ) {
        val entries = workingMemoryService.extractKnowledge(
            toolName = toolName,
            args = params,
            output = result,
            iteration = iteration
        )

        if (entries.isNotEmpty()) {
            workingMemoryService.recordEntries(taskId, entries)
            logger.debug {
                "[WORKING_MEMORY] Recorded ${entries.size} entries from $toolName: " +
                entries.map { "${it.key}=${it.value.take(30)}" }.joinToString(", ")
            }
        }
    }

    /**
     * Get relevant entries for a task (for debugging/inspection).
     *
     * @param taskId Task ID
     * @param query Query for relevance filtering
     * @param maxEntries Maximum entries to return
     * @return List of relevant working memory entries
     */
    fun getRelevantEntries(
        taskId: String,
        query: String,
        maxEntries: Int
    ): List<WorkingMemoryEntry> {
        // Currently WorkingMemoryService doesn't have filtering by query
        // So we just return empty list - the service handles filtering internally
        return emptyList()
    }

    /**
     * Get total entries added (for statistics).
     */
    fun getTotalEntriesAdded(): Int = totalEntriesAdded

    /**
     * Count entries in a working memory section string.
     */
    private fun countEntries(section: String): Int {
        // Count bullet points in the section
        return section.count { it == '-' }
    }
}
