package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * Execution metadata DTO
 * Based on Python ExecutionMetadataDTO from context_dto.py
 */
data class ExecutionMetadataDTO(
    val executionTimestamp: Instant = Instant.now(),
    val workspacePath: String? = null,
    val agentMode: String? = null,
    val interactiveMode: Boolean = true,
    val executionMode: String? = null
)
