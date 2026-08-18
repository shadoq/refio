package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.context.mcp.*
import pl.jclab.refio.core.models.context.MCPContextResourceDTO
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("McpContextLoader")

class McpContextLoader {

    private data class McpToolCacheKey(
        val projectId: String,
        val serverId: String,
        val toolName: String,
        val query: String
    )

    private data class McpToolCacheEntry(
        val expiresAtMs: Long,
        val content: String,
        val isError: Boolean
    )

    private val mcpToolCache = ConcurrentHashMap<McpToolCacheKey, McpToolCacheEntry>()
    private val mcpToolCacheTtlMs = 30_000L

    suspend fun loadMcpResources(projectRoot: Path, query: String?): List<MCPContextResourceDTO> {
        val projectId = ProjectIdGenerator.generate(projectRoot)
        val serverIds = MCPManager.getConnectedServers(projectId)
        if (serverIds.isEmpty()) {
            return emptyList()
        }

        val configsById = MCPManager.getAllServers(projectId).associateBy { it.id }
        val gson = GsonInstance.gson
        val toolQuery = query?.trim().orEmpty()

        return serverIds.flatMap { serverId ->
            val connection = MCPManager.getConnection(projectId, serverId) ?: return@flatMap emptyList()
            val config = configsById[serverId]
            val outputs = mutableListOf<MCPContextResourceDTO>()

            if (connection.supportsResources()) {
                val resources = runCatching {
                    val cached = connection.getCachedResources()
                    if (cached.isNotEmpty()) cached else connection.refreshResources()
                }.getOrElse { error ->
                    logger.warn(error) { "[CONTEXT] Failed to load MCP resources for $serverId" }
                    emptyList()
                }

                outputs.addAll(
                    resources.map {
                        MCPContextResourceDTO(
                            serverId = serverId,
                            uri = it.uri,
                            name = it.name,
                            description = it.description,
                            mimeType = it.mimeType
                        )
                    }
                )
            } else {
                logger.debug { "[CONTEXT] MCP server $serverId does not support resources capability" }
            }

            val serverConfig = config
            val toolsExposure = serverConfig?.toolsExposureMode ?: MCPToolsExposureMode.TOOLS
            val shouldUseTools = serverConfig?.toolsEnabled == true && toolsExposure == MCPToolsExposureMode.CONTEXT
            val hasWorkflow = (serverConfig?.toolWorkflow?.steps?.isNotEmpty()) == true
            // Skip ad-hoc tool invocations when there is nothing to ask for and no workflow is configured.
            // Calling e.g. context7's resolve-library-id with empty query just yields validation errors.
            if (shouldUseTools && serverConfig != null && connection.supportsTools() && (hasWorkflow || toolQuery.isNotBlank())) {
                val tools = runCatching {
                    val cached = connection.getCachedTools()
                    if (cached.isNotEmpty()) cached else connection.refreshTools()
                }.getOrElse { error ->
                    logger.warn(error) { "[CONTEXT] Failed to load MCP tools for $serverId" }
                    emptyList()
                }

                val workflowConfig = serverConfig.toolWorkflow?.takeIf { it.steps.isNotEmpty() }
                if (workflowConfig != null) {
                    val workflowResult = MCPToolWorkflowExecutor.execute(
                        workflow = workflowConfig,
                        tools = tools,
                        config = serverConfig,
                        query = toolQuery,
                        gson = gson
                    ) { toolName, arguments ->
                        val cacheKey = McpToolCacheKey(
                            projectId = projectId,
                            serverId = serverId,
                            toolName = toolName,
                            query = toolQuery
                        )
                        val cached = getCachedMcpToolOutput(cacheKey)
                        if (cached != null) {
                            return@execute MCPToolCallResult(cached.content, cached.isError)
                        }

                        val result = runCatching {
                            connection.callTool(toolName, arguments)
                        }.getOrElse { error ->
                            val content = "Failed to execute tool $toolName: ${error.message}"
                            logger.warn(error) { "[CONTEXT] $content" }
                            val entry = McpToolCacheEntry(
                                expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                                content = content,
                                isError = true
                            )
                            putCachedMcpToolOutput(cacheKey, entry)
                            return@execute MCPToolCallResult(content, true)
                        }

                        val content = formatMcpToolResult(result)
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = result.isError
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        MCPToolCallResult(content, result.isError)
                    }

                    workflowResult.steps.forEach { step ->
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${step.toolName}",
                                name = "tool:${step.toolName}",
                                description = step.output,
                                mimeType = "text/plain"
                            )
                        )
                        logger.info { "[CONTEXT] MCP tool context added: server=$serverId tool=${step.toolName}" }
                    }

                    if (workflowResult.error != null) {
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "workflow:error",
                                name = "workflow:error",
                                description = workflowResult.error,
                                mimeType = "text/plain"
                            )
                        )
                    }

                    return@flatMap outputs
                }

                selectContextTools(serverId, serverConfig, tools).forEach { toolDef ->
                    val cacheKey = McpToolCacheKey(
                        projectId = projectId,
                        serverId = serverId,
                        toolName = toolDef.name,
                        query = toolQuery
                    )
                    val cached = getCachedMcpToolOutput(cacheKey)
                    if (cached != null) {
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = cached.content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val argsResult = MCPToolArgumentResolver.buildArguments(toolQuery, toolDef, serverConfig, gson)
                    if (argsResult.isFailure) {
                        val error = argsResult.exceptionOrNull()
                        val content =
                            "Failed to parse tool arguments for ${toolDef.name}: ${error?.message ?: "unknown error"}"
                        if (error != null) {
                            logger.warn(error) { "[CONTEXT] $content" }
                        } else {
                            logger.warn { "[CONTEXT] $content" }
                        }
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = true
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val arguments = argsResult.getOrNull().orEmpty()
                    val result = runCatching {
                        connection.callTool(toolDef.name, arguments)
                    }.getOrElse { error ->
                        val content = "Failed to execute tool ${toolDef.name}: ${error.message}"
                        logger.warn(error) { "[CONTEXT] $content" }
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = true
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val content = formatMcpToolResult(result)
                    val entry = McpToolCacheEntry(
                        expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                        content = content,
                        isError = result.isError
                    )
                    putCachedMcpToolOutput(cacheKey, entry)
                    outputs.add(
                        MCPContextResourceDTO(
                            serverId = serverId,
                            uri = "tool:${toolDef.name}",
                            name = "tool:${toolDef.name}",
                            description = content,
                            mimeType = "text/plain"
                        )
                    )
                    logger.info { "[CONTEXT] MCP tool context added: server=$serverId tool=${toolDef.name}" }
                }
            }

            outputs
        }
    }

    /**
     * Which tools a CONTEXT-mode server may be asked to run while context is assembled.
     *
     * These calls are made on the user's raw prompt, without the tool permission and approval
     * layers the agent goes through, so they stay narrow. The configured context tool wins - the
     * settings UI presents it as "run this one tool" and it has to mean that. With no such tool,
     * only a server the user declared read-only is invoked: a READ_WRITE server also exposes
     * writing tools and nothing in the tool definition tells them apart.
     */
    private fun selectContextTools(
        serverId: String,
        config: MCPServerConfig,
        tools: List<MCPToolDefinition>
    ): List<MCPToolDefinition> {
        val configuredTool = config.contextToolName?.trim().orEmpty().ifBlank { null }
        if (configuredTool != null) {
            val selected = tools.firstOrNull { it.name == configuredTool }
            if (selected == null) {
                logger.warn {
                    "[CONTEXT] MCP server $serverId does not expose the configured context tool '$configuredTool' - no tool called"
                }
                return emptyList()
            }
            return listOf(selected)
        }

        if (config.accessMode != MCPAccessMode.READ) {
            logger.warn {
                "[CONTEXT] MCP server $serverId has read/write access and no context tool configured - " +
                    "no tool called (set the context tool to pick the one that may run)"
            }
            return emptyList()
        }

        return tools
    }

    private fun getCachedMcpToolOutput(key: McpToolCacheKey): McpToolCacheEntry? {
        val entry = mcpToolCache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            mcpToolCache.remove(key)
            return null
        }
        return entry
    }

    private fun putCachedMcpToolOutput(key: McpToolCacheKey, entry: McpToolCacheEntry) {
        mcpToolCache[key] = entry
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
}
