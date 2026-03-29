package pl.jclab.refio.core.context.mcp

/**
 * JSON-RPC 2.0 request.
 */
data class MCPRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: Map<String, Any?>? = null
)

/**
 * JSON-RPC 2.0 notification.
 */
data class MCPNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Map<String, Any?>? = null
)

/**
 * Success response.
 */
data class MCPSuccessResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: Any
)

/**
 * Error response.
 */
data class MCPErrorResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val error: MCPError
)

/**
 * Error payload.
 */
data class MCPError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/**
 * MCP method names.
 */
object MCPMethods {
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"

    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val RESOURCES_SUBSCRIBE = "resources/subscribe"
    const val RESOURCES_UNSUBSCRIBE = "resources/unsubscribe"

    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"

    const val RESOURCES_UPDATED = "notifications/resources/updated"
    const val RESOURCES_LIST_CHANGED = "notifications/resources/list_changed"
    const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
}
