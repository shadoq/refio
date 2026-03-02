package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * DTO for a single executed step from previous subtasks.
 * Used in RECENT_WORK section to show detailed action history.
 *
 * Based on ADR 0041: Structured Recent Work Context
 *
 * @property subtaskId Source subtask ID for this executed step
 * @property file Optional file path affected by this step
 * @property tool Tool name that was executed
 * @property parameters Tool parameters
 * @property result Full raw output from the tool
 * @property summary Summarized output (may be null if not summarized)
 * @property timestamp When the step was completed
 */
data class ExecutedStepDTO(
    val subtaskId: String,
    val file: String?,
    val tool: String,
    val parameters: Map<String, Any>,
    val result: String,
    val summary: String?,
    val timestamp: Instant
) {
    /**
     * Get the appropriate content for display.
     * Uses summary if available, otherwise falls back to result.
     */
    val displayContent: String
        get() = summary ?: result
}
