package pl.jclab.refio.core.context.mcp

import com.google.gson.Gson
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("MCPContextProvider")

/**
 * Dynamic context provider for MCP (Model Context Protocol) servers.
 *
 * Usage: @serverId or @serverId:<resource-uri>
 */
class MCPContextProvider(
    private val mcpServerId: String,
    private val mcpServerConfig: MCPServerConfig,
    private val connection: MCPConnection
) : BaseContextProvider() {

    private val gson = GsonInstance.gson

    override val description = ContextProviderDescription(
        title = mcpServerId,
        displayTitle = mcpServerConfig.displayName ?: mcpServerId,
        description = mcpServerConfig.description ?: "MCP Server: $mcpServerId",
        type = ProviderType.QUERY,
        icon = "mcp"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        logger.debug { "MCP provider query: server=$mcpServerId, query=$query" }

        if (connection.supportsResources()) {
            val resources = runCatching {
                val cached = connection.getCachedResources()
                if (cached.isNotEmpty()) cached else connection.refreshResources()
            }.getOrElse {
                logger.warn(it) { "Failed to load MCP resources for $mcpServerId" }
                emptyList()
            }

            if (resources.isEmpty()) {
                return listOf(
                    ContextItem(
                        description = "MCP: $mcpServerId (no resources)",
                        content = "MCP server ${mcpServerConfig.displayName ?: mcpServerId} returned no resources.",
                        name = mcpServerId,
                        uri = ContextUri(type = "mcp", value = "$mcpServerId:$query")
                    )
                )
            }

            val matching = if (query.isBlank()) resources else resources.filter {
                it.uri.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
            }

            if (matching.isEmpty()) {
                return listOf(
                    ContextItem(
                        description = "MCP: $mcpServerId",
                        content = "No MCP resources matched query '$query'. Available: ${resources.size}",
                        name = mcpServerId,
                        uri = ContextUri(type = "mcp", value = "$mcpServerId:$query")
                    )
                )
            }

            return matching.map { resource ->
                val content = runCatching {
                    connection.readResource(resource.uri).text
                }.getOrElse {
                    logger.warn(it) { "Failed to read MCP resource ${resource.uri}" }
                    null
                }

                ContextItem(
                    description = resource.name,
                    content = content ?: resource.description.orEmpty(),
                    name = resource.name,
                    uri = ContextUri(type = "mcp", value = resource.uri)
                )
            }
        }

        val exposureMode = mcpServerConfig.toolsExposureMode ?: MCPToolsExposureMode.TOOLS
        if (exposureMode != MCPToolsExposureMode.CONTEXT || !connection.supportsTools()) {
            logger.debug { "MCP server $mcpServerId does not support tools context exposure" }
            return emptyList()
        }

        val tools = runCatching {
            val cached = connection.getCachedTools()
            if (cached.isNotEmpty()) cached else connection.refreshTools()
        }.getOrElse {
            logger.warn(it) { "Failed to load MCP tools for $mcpServerId" }
            emptyList()
        }

        if (tools.isEmpty()) {
            return listOf(
                ContextItem(
                    description = "MCP: $mcpServerId (no tools)",
                    content = "MCP server ${mcpServerConfig.displayName ?: mcpServerId} returned no tools.",
                    name = mcpServerId,
                    uri = ContextUri(type = "mcp", value = "$mcpServerId:$query")
                )
            )
        }

        val workflowConfig = mcpServerConfig.toolWorkflow?.takeIf { it.steps.isNotEmpty() }
        if (workflowConfig != null) {
            val workflowResult = MCPToolWorkflowExecutor.execute(
                workflow = workflowConfig,
                tools = tools,
                config = mcpServerConfig,
                query = query,
                gson = gson
            ) { toolName, arguments ->
                val result = runCatching {
                    connection.callTool(toolName, arguments)
                }.getOrElse { error ->
                    val content = "Failed to execute tool '$toolName': ${error.message}"
                    logger.warn(error) { "Failed to call MCP tool $toolName for $mcpServerId" }
                    return@execute MCPToolCallResult(content, true)
                }

                val output = formatMcpToolResult(result)
                MCPToolCallResult(output, result.isError)
            }

            val items = workflowResult.steps.map { step ->
                ContextItem(
                    description = "MCP tool: ${step.toolName}",
                    content = step.output,
                    name = step.toolName,
                    uri = ContextUri(type = "mcp", value = "$mcpServerId:${step.toolName}")
                )
            }

            if (workflowResult.error != null) {
                return items + ContextItem(
                    description = "MCP: $mcpServerId (workflow error)",
                    content = workflowResult.error,
                    name = mcpServerId,
                    uri = ContextUri(type = "mcp", value = "$mcpServerId:workflow-error")
                )
            }

            return items
        }

        val toolRequest = resolveToolRequest(query, tools)
            ?: return listOf(
                ContextItem(
                    description = "MCP: $mcpServerId (tools)",
                    content = buildToolsHelp(tools),
                    name = mcpServerId,
                    uri = ContextUri(type = "mcp", value = "$mcpServerId:$query")
                )
            )

        val result = runCatching {
            connection.callTool(toolRequest.toolName, toolRequest.arguments)
        }.getOrElse {
            logger.warn(it) { "Failed to call MCP tool ${toolRequest.toolName} for $mcpServerId" }
            return listOf(
                ContextItem(
                    description = "MCP: $mcpServerId (tool error)",
                    content = "Failed to execute tool '${toolRequest.toolName}': ${it.message}",
                    name = toolRequest.toolName,
                    uri = ContextUri(type = "mcp", value = "$mcpServerId:${toolRequest.toolName}")
                )
            )
        }

        val output = result.content.mapNotNull { it.text }.joinToString("\n").ifBlank {
            if (result.isError) "MCP tool returned an error with no content." else "MCP tool executed successfully."
        }

        return listOf(
            ContextItem(
                description = "MCP tool: ${toolRequest.toolName}",
                content = output,
                name = toolRequest.toolName,
                uri = ContextUri(type = "mcp", value = "$mcpServerId:${toolRequest.toolName}")
            )
        )
    }

    private fun formatMcpToolResult(result: MCPToolResult): String {
        val output = result.content.mapNotNull { it.text }.joinToString("\n").trim()
        if (output.isNotBlank()) {
            return output
        }
        return if (result.isError) {
            "MCP tool returned an error with no content."
        } else {
            "MCP tool executed successfully."
        }
    }

    private data class ToolRequest(
        val toolName: String,
        val arguments: Map<String, Any>
    )

    private fun resolveToolRequest(
        rawQuery: String,
        tools: List<MCPToolDefinition>
    ): ToolRequest? {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return null
        }

        val toolNames = tools.map { it.name }
        val explicitToolName = mcpServerConfig.contextToolName?.trim().orEmpty().ifBlank { null }
        val parsed = if (explicitToolName == null) parseToolSelection(query, toolNames) else null

        val toolName = explicitToolName ?: parsed?.toolName
            ?: if (tools.size == 1) tools.first().name else null
        if (toolName == null) {
            return null
        }

        val toolDef = tools.firstOrNull { it.name == toolName } ?: return null
        val rawArgs = if (explicitToolName != null) query else parsed?.rawArgs ?: query

        val arguments = parseToolArguments(rawArgs, toolDef)
            ?: return null

        return ToolRequest(toolName = toolName, arguments = arguments)
    }

    private data class ParsedToolSelection(
        val toolName: String,
        val rawArgs: String
    )

    private fun parseToolSelection(query: String, toolNames: List<String>): ParsedToolSelection? {
        val parts = query.split(":", limit = 2)
        if (parts.size < 2) {
            return null
        }
        val candidate = parts[0].trim()
        if (candidate.isBlank() || candidate !in toolNames) {
            return null
        }
        return ParsedToolSelection(candidate, parts[1].trim())
    }

    private fun parseToolArguments(rawArgs: String, toolDef: MCPToolDefinition): Map<String, Any>? {
        val result = MCPToolArgumentResolver.buildArguments(rawArgs, toolDef, mcpServerConfig, gson)
        return result.getOrElse { error ->
            logger.warn(error) { "Failed to parse MCP tool JSON arguments for ${toolDef.name}" }
            null
        }
    }

    private fun buildToolsHelp(tools: List<MCPToolDefinition>): String {
        val names = tools.joinToString(", ") { it.name }
        val paramName = mcpServerConfig.contextToolQueryParam?.trim().orEmpty().ifBlank { "query" }
        return buildString {
            append("Available MCP tools: ")
            append(names)
            append(". ")
            append("Use @")
            append(mcpServerId)
            append(":toolName:your query or @")
            append(mcpServerId)
            append(":")
            append(paramName)
            append(" if only one tool is available.")
        }
    }
}

internal object MCPToolArgumentResolver {
    fun buildArguments(
        rawArgs: String,
        toolDef: MCPToolDefinition,
        config: MCPServerConfig,
        gson: Gson
    ): Result<Map<String, Any>> {
        val trimmed = rawArgs.trim()
        if (trimmed.isBlank()) {
            return Result.success(emptyMap())
        }
        if (trimmed.startsWith("{")) {
            return runCatching {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(trimmed, Map::class.java) as Map<String, Any>
            }
        }

        val paramName = resolveQueryParam(toolDef, config)
        return Result.success(mapOf(paramName to trimmed))
    }

    private fun resolveQueryParam(toolDef: MCPToolDefinition, config: MCPServerConfig): String {
        val mapped = config.toolParamMapping[toolDef.name]?.trim().orEmpty().ifBlank { null }
        val configured = config.contextToolQueryParam?.trim().orEmpty().ifBlank { null }
        val properties = toolDef.inputSchema["properties"] as? Map<*, *>

        if (mapped != null && properties?.containsKey(mapped) == true) {
            return mapped
        }
        if (configured != null && properties?.containsKey(configured) == true) {
            return configured
        }
        if (properties?.containsKey("query") == true) {
            return "query"
        }
        if (properties?.containsKey("input") == true) {
            return "input"
        }
        if (properties?.containsKey("text") == true) {
            return "text"
        }
        if (properties?.size == 1) {
            return properties.keys.first().toString()
        }
        return configured ?: "query"
    }
}
