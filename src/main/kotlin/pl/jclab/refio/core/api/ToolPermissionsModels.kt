package pl.jclab.refio.core.api

/**
 * DTOs for Tool Permissions API endpoints
 */

/**
 * Response containing all tool permissions
 */
data class ToolPermissionsResponse(
    val tools: List<ToolPermissionDto>
)

/**
 * Single tool permission DTO
 */
data class ToolPermissionDto(
    val toolName: String,
    val planMode: String,     // ASK | ON | OFF
    val agentMode: String     // ASK | ON | OFF
)

/**
 * Request to set tool permission
 */
data class SetToolPermissionRequest(
    val planMode: String,     // ASK | ON | OFF
    val agentMode: String     // ASK | ON | OFF
)
