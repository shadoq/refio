package pl.jclab.refio.core.context.mcp

import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

/**
 * Wrapper exposing MCP tool as internal Tool.
 */
class MCPToolWrapper(
    private val mcpConnection: MCPConnection,
    private val toolDefinition: MCPToolDefinition,
    private val toolMode: ToolMode
) : Tool {

    override val name: String = "mcp_${mcpConnection.serverId}_${toolDefinition.name}"

    override val description: String = "[MCP:${mcpConnection.serverId}] ${toolDefinition.description ?: toolDefinition.name}"

    override val mode: ToolMode = toolMode

    override val category: ToolCategory = ToolCategory.EXECUTION

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val result = mcpConnection.callTool(toolDefinition.name, params)
            if (result.isError) {
                ToolResult.error(result.content.firstOrNull()?.text ?: "MCP tool error")
            } else {
                val output = result.content.mapNotNull { it.text }.joinToString("\n")
                ToolResult.success(
                    output = output.ifBlank { "MCP tool executed successfully" },
                    metadata = mapOf(
                        "mcp_server" to mcpConnection.serverId,
                        "mcp_tool" to toolDefinition.name
                    )
                )
            }
        } catch (e: MCPAccessDeniedException) {
            ToolResult.error("Access denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("MCP tool execution failed: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return toolDefinition.inputSchema
    }
}
