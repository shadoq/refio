package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * DTO for a single executed step from previous subtasks.
 * Used in RECENT_WORK section to show detailed action history.
 *
 * Based on ADR 0041: Structured Recent Work Context
 */
data class ExecutedStepDTO(
    val file: String?,
    val tool: String,
    val parameters: Map<String, Any>,
    val result: String,
    val timestamp: Instant
)