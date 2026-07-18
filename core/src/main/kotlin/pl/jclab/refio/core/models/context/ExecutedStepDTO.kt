package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * DTO for a single executed step from previous subtasks.
 * Used in RECENT_WORK section to show detailed action history.
 *
 * @property subtaskId Source subtask ID for this executed step
 * @property file Optional file path affected by this step
 * @property tool Tool name that was executed
 * @property parameters Tool parameters
 * @property result Full raw output from the tool
 * @property rawResultSize Original size in chars of the raw output before any truncation
 * @property summary Summarized output (may be null if not summarized)
 * @property timestamp When the step was completed
 * @property success Whether the underlying subtask finished successfully.
 *   Failed steps are still kept in RECENT_WORK so the agent can see its own
 *   prior errors and avoid re-running the same thing.
 */
data class ExecutedStepDTO(
    val subtaskId: String,
    val file: String?,
    val tool: String,
    val parameters: Map<String, Any>,
    val result: String,
    val rawResultSize: Int = 0,
    val summary: String?,
    val timestamp: Instant,
    val success: Boolean = true
) {
    /**
     * Get the appropriate content for display.
     * Uses summary if available, otherwise falls back to result.
     */
    val displayContent: String
        get() = summary ?: result
}
