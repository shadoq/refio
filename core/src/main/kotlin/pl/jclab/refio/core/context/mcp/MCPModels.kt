package pl.jclab.refio.core.context.mcp

import java.time.Instant
import kotlin.jvm.Transient

/**
 * MCP server transport types.
 */
enum class MCPServerType {
    STDIO,
    HTTP_SSE,
    HTTP_STREAMABLE
}

/**
 * Access mode for MCP servers.
 */
enum class MCPAccessMode {
    READ,
    READ_WRITE
}

/**
 * Authentication type for HTTP-based MCP servers.
 */
enum class MCPAuthType {
    NONE,
    BEARER
}

/**
 * Authentication configuration for HTTP-based MCP servers.
 */
data class MCPAuthConfig(
    val type: MCPAuthType = MCPAuthType.NONE,
    val apiKey: String? = null,
    val isSecret: Boolean = true
)

/**
 * How MCP tools are exposed.
 */
enum class MCPToolsExposureMode {
    TOOLS,
    CONTEXT
}

/**
 * OAuth configuration for HTTP-based MCP servers.
 */
data class MCPOAuthConfig(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val redirectUri: String? = null
)

/**
 * Additional HTTP header definition.
 */
data class MCPHttpHeader(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

/**
 * Environment variable definition.
 */
data class MCPEnvVariable(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

/**
 * Full MCP server configuration.
 */
data class MCPServerConfig(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val type: MCPServerType,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val url: String? = null,
    val oauth: MCPOAuthConfig? = null,
    val auth: MCPAuthConfig? = null,
    val httpHeaders: List<MCPHttpHeader> = emptyList(),
    val env: List<MCPEnvVariable> = emptyList(),
    val serverInstructions: String? = null,
    val accessMode: MCPAccessMode = MCPAccessMode.READ,
    @Transient val enabled: Boolean = true,
    val timeout: Long = 30_000,
    val retryAttempts: Int = 3,
    val retryDelayMs: Long = 5_000,
    val resourcesEnabled: Boolean = true,
    val toolsEnabled: Boolean = true,
    val toolsExposureMode: MCPToolsExposureMode? = MCPToolsExposureMode.TOOLS,
    val toolParamMapping: Map<String, String> = emptyMap(),
    val toolWorkflow: MCPToolWorkflowConfig? = null,
    val contextToolName: String? = null,
    val contextToolQueryParam: String? = "query",
    val promptsEnabled: Boolean = true
)

/**
 * MCP tool workflow configuration.
 */
data class MCPToolWorkflowConfig(
    val steps: List<MCPToolWorkflowStep> = emptyList()
)

/**
 * Single workflow step mapping inputs to outputs for a tool.
 */
data class MCPToolWorkflowStep(
    val toolName: String,
    val inputMapping: Map<String, String> = emptyMap(),
    val outputMapping: Map<String, String> = emptyMap()
)

/**
 * Capabilities advertised by the MCP server.
 */
data class MCPServerCapabilities(
    val resources: Boolean = false,
    val tools: Boolean = false,
    val prompts: Boolean = false
)

/**
 * Resource metadata returned by MCP servers.
 */
data class MCPResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

/**
 * Resource content payload.
 */
data class MCPResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null
)

/**
 * Tool definition advertised by MCP servers.
 */
data class MCPToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, Any> = emptyMap()
)

/**
 * Content part from tool result.
 */
data class MCPContentPart(
    val type: String = "text",
    val text: String? = null
)

/**
 * Tool execution result.
 */
data class MCPToolResult(
    val isError: Boolean = false,
    val content: List<MCPContentPart> = emptyList()
)

/**
 * Connection status.
 */
enum class MCPServerStatus {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NEEDS_AUTH,
    STALE,
    ERROR;

    val isUsable: Boolean
        get() = this == CONNECTED || this == STALE

    val isTerminal: Boolean
        get() = this == DISABLED || this == ERROR || this == NEEDS_AUTH
}

/**
 * Runtime metadata of an MCP connection for UI display and debugging.
 */
data class MCPConnectionInfo(
    val serverId: String,
    val displayName: String,
    val status: MCPServerStatus,
    val lastConnectedAt: Instant? = null,
    val lastError: String? = null,
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptsEnabled: Boolean = false
)

class MCPAccessDeniedException(message: String) : Exception(message)
class MCPTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
