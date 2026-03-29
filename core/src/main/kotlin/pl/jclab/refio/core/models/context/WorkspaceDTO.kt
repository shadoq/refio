package pl.jclab.refio.core.models.context

/**
 * Workspace paths DTO
 * Based on Python WorkspaceDTO from context_dto.py
 */
data class WorkspaceDTO(
    val path: String,
    val taskId: String? = null,
    val projectId: String? = null,
    val projectName: String? = null
)
