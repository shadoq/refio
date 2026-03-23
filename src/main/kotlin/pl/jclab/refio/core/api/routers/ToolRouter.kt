package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.models.api.SetToolPermissionRequest
import pl.jclab.refio.core.models.api.ToolPermissionDto
import pl.jclab.refio.core.models.api.ToolPermissionsResponse
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ToolRouter")

/**
 * Router for tool-related operations.
 * Handles tool registry, permissions, and access control.
 *
 * This router is responsible for:
 * - Tool registry and catalog management
 * - Tool permission management (plan/agent modes)
 * - Tool availability checks
 *
 * @property toolRegistry Tool catalog registry
 * @property toolPermissionsService Tool permission management service
 */
class ToolRouter(
    private val toolRegistry: ToolRegistry?,
    private val toolPermissionsService: ToolPermissionsService
) : Router {

    override suspend fun initialize() {
        val toolCount = toolRegistry?.getAllTools()?.size ?: 0
        logger.info { "[ToolRouter] Initialized with $toolCount tools" }
    }

    override suspend fun shutdown() {
        logger.info { "[ToolRouter] Shutting down" }
    }

    // ===== Tool Registry =====

    /**
     * Get the ToolRegistry instance.
     * Used by MCPManager to register MCP tools.
     *
     * @return ToolRegistry instance
     * @throws IllegalStateException if ToolRegistry not available
     */
    fun getToolRegistry(): ToolRegistry {
        return toolRegistry ?: throw IllegalStateException("ToolRegistry not available for this router")
    }

    // ===== Tool Permissions =====

    /**
     * Get permissions for all tools.
     *
     * @param taskId Optional task ID for task-level permissions
     * @return Tool permissions response with all tool configurations
     */
    fun getToolPermissions(taskId: String? = null): ToolPermissionsResponse {
        return try {
            val permissions = toolPermissionsService.getPermissions(taskId)

            val tools = permissions.map { (toolName, config) ->
                ToolPermissionDto(
                    toolName = toolName,
                    planMode = config.planMode.name,
                    agentMode = config.agentMode.name
                )
            }

            ToolPermissionsResponse(tools = tools)
        } catch (e: Exception) {
            logger.error(e) { "[ToolRouter] Failed to get tool permissions" }
            throw e
        }
    }

    /**
     * Set permission for a specific tool.
     *
     * @param toolName Name of the tool
     * @param request Permission levels for plan and agent modes
     * @param taskId Optional task ID for task-level permissions
     * @throws IllegalArgumentException if permission level is invalid
     */
    fun setToolPermission(
        toolName: String,
        request: SetToolPermissionRequest,
        taskId: String? = null
    ) {
        try {
            val planMode = PermissionLevel.valueOf(request.planMode.uppercase())
            val agentMode = PermissionLevel.valueOf(request.agentMode.uppercase())

            toolPermissionsService.setPermission(
                toolName = toolName,
                planMode = planMode,
                agentMode = agentMode,
                taskId = taskId
            )

            logger.info { "[ToolRouter] Set permission for $toolName: plan=$planMode, agent=$agentMode" }
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "[ToolRouter] Invalid permission level: ${e.message}" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ToolRouter] Failed to set tool permission" }
            throw e
        }
    }

    /**
     * Reset tool permissions to smart defaults.
     *
     * @param taskId Optional task ID for task-level permissions
     */
    fun resetToolPermissions(taskId: String? = null) {
        try {
            toolPermissionsService.resetToDefaults(taskId)
            logger.info { "[ToolRouter] Reset tool permissions to defaults" }
        } catch (e: Exception) {
            logger.error(e) { "[ToolRouter] Failed to reset tool permissions" }
            throw e
        }
    }
}
