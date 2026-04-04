package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.db.repositories.MCPServerRepository
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("MCPManager")
private const val GLOBAL_PROJECT_KEY = "_global"
private fun mapKey(projectId: String?) = projectId ?: GLOBAL_PROJECT_KEY
private const val RESOURCE_CACHE_TTL_MS = 5 * 60 * 1000L
private const val TOOL_CACHE_TTL_MS = 5 * 60 * 1000L

private data class CachedValue<T>(
    val value: T,
    val cachedAt: Long
) {
    fun isFresh(ttlMs: Long): Boolean = System.currentTimeMillis() - cachedAt < ttlMs
}

private data class MCPProjectState(
    val projectId: String?,
    var toolRegistry: ToolRegistry? = null,
    val connections: ConcurrentHashMap<String, MCPConnection> = ConcurrentHashMap(),
    val serverConfigs: ConcurrentHashMap<String, MCPServerConfig> = ConcurrentHashMap(),
    val registeredTools: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap(),
    val resourceCache: ConcurrentHashMap<String, CachedValue<List<MCPResource>>> = ConcurrentHashMap(),
    val toolCache: ConcurrentHashMap<String, CachedValue<List<MCPToolDefinition>>> = ConcurrentHashMap()
)

/**
 * Singleton manager for MCP (Model Context Protocol) server connections.
 *
 * Responsibilities:
 * - Load MCP server configurations from DB (per project)
 * - Establish and manage connections to MCP servers
 * - Register/unregister MCP context providers dynamically
 * - Register MCP tools for READ_WRITE servers (Agent mode)
 */
object MCPManager {

    private val repository = MCPServerRepository()
    private val projectStates = ConcurrentHashMap<String, MCPProjectState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initialize MCP manager for a project.
     */
    fun initialize(projectId: String? = null, toolRegistry: ToolRegistry? = null) {
        val state = projectStates.computeIfAbsent(mapKey(projectId)) {
            MCPProjectState(projectId = projectId, toolRegistry = toolRegistry)
        }
        if (toolRegistry != null) {
            state.toolRegistry = toolRegistry
        }

        val configs = repository.getAll(projectId)
        configs.forEach { config -> state.serverConfigs[config.id] = config }

        scope.launch {
            configs.filter { it.enabled }.forEach { config ->
                runCatching { connectServer(projectId, config.id) }
                    .onFailure { e -> logger.error(e) { "Failed to connect MCP server ${config.id}" } }
            }
        }

        logger.info { "MCP Manager initialized for projectId=$projectId with ${configs.size} servers, toolRegistry=${if (toolRegistry != null) "present" else "missing"}" }
    }

    /**
     * Update the ToolRegistry for a project. This is useful when:
     * - The project router was created after MCPManager initialization
     * - Need to re-register MCP tools after ToolRegistry becomes available
     */
    fun setToolRegistry(projectId: String?, toolRegistry: ToolRegistry) {
        val state = projectStates[mapKey(projectId)]
        if (state == null) {
            logger.warn { "Cannot set ToolRegistry - MCPManager not initialized for projectId=$projectId" }
            return
        }

        val hadRegistry = state.toolRegistry != null
        state.toolRegistry = toolRegistry

        if (!hadRegistry) {
            logger.info { "ToolRegistry set for projectId=$projectId - re-registering tools for connected servers" }
            // Re-register tools for already connected READ_WRITE servers
            scope.launch {
                state.connections.values.forEach { connection ->
                    val config = state.serverConfigs[connection.serverId]
                    if (config?.toolsEnabled == true && (config.toolsExposureMode ?: MCPToolsExposureMode.TOOLS) == MCPToolsExposureMode.TOOLS) {
                        runCatching { registerTools(state, connection) }
                            .onFailure { e -> logger.error(e) { "Failed to register tools for ${connection.serverId}" } }
                    }
                }
            }
        }
    }

    fun getAllServers(projectId: String? = null): List<MCPServerConfig> {
        return projectStates[mapKey(projectId)]?.serverConfigs?.values?.toList() ?: emptyList()
    }

    fun getConnectedServers(projectId: String? = null): List<String> {
        return projectStates[mapKey(projectId)]?.connections?.keys?.toList() ?: emptyList()
    }

    fun getConnection(projectId: String? = null, serverId: String): MCPConnection? {
        return projectStates[mapKey(projectId)]?.connections?.get(serverId)
    }

    fun addOrUpdateServer(projectId: String?, config: MCPServerConfig) {
        val state = projectStates.computeIfAbsent(mapKey(projectId)) {
            MCPProjectState(projectId = projectId)
        }
        state.serverConfigs[config.id] = config
        repository.upsert(projectId, config)
        if (config.enabled) {
            scope.launch {
                runCatching { connectServer(projectId, config.id) }
                    .onFailure { e -> logger.error(e) { "Failed to connect MCP server ${config.id}" } }
            }
        } else {
            disconnectServer(projectId, config.id)
        }
    }

    fun removeServer(projectId: String?, serverId: String) {
        repository.delete(projectId, serverId)
        disconnectServer(projectId, serverId)
        projectStates[mapKey(projectId)]?.serverConfigs?.remove(serverId)
    }

    fun getServerStatus(projectId: String?, serverId: String): MCPServerStatus {
        val connection = projectStates[mapKey(projectId)]?.connections?.get(serverId)
        val config = projectStates[mapKey(projectId)]?.serverConfigs?.get(serverId)
        if (config?.enabled == false) return MCPServerStatus.DISABLED
        return connection?.getStatus() ?: MCPServerStatus.DISCONNECTED
    }

    fun getConnectionInfo(projectId: String?): List<MCPConnectionInfo> {
        val state = projectStates[mapKey(projectId)] ?: return emptyList()
        return state.serverConfigs.map { (serverId, config) ->
            val connection = state.connections[serverId]
            val resourceCount = state.resourceCache[serverId]
                ?.takeIf { it.isFresh(RESOURCE_CACHE_TTL_MS) }
                ?.value
                ?.size
                ?: connection?.getCachedResources()?.size
                ?: 0
            MCPConnectionInfo(
                serverId = serverId,
                displayName = config.displayName ?: serverId,
                status = if (!config.enabled) MCPServerStatus.DISABLED
                         else connection?.getStatus() ?: MCPServerStatus.DISCONNECTED,
                lastConnectedAt = connection?.lastConnectedAt,
                lastError = connection?.lastError,
                toolCount = state.registeredTools[serverId]?.size ?: 0,
                resourceCount = resourceCount,
                promptsEnabled = config.promptsEnabled
            )
        }
    }

    fun shutdown(projectId: String? = null) {
        val state = projectStates[mapKey(projectId)] ?: return
        state.connections.values.forEach { connection ->
            runCatching { connection.disconnect() }
        }
        state.connections.clear()
        state.resourceCache.clear()
        state.toolCache.clear()
        unregisterTools(state, null)
        projectStates.remove(mapKey(projectId))
    }

    private fun disconnectServer(projectId: String?, serverId: String) {
        val state = projectStates[mapKey(projectId)] ?: return
        state.connections.remove(serverId)?.disconnect()
        state.resourceCache.remove(serverId)
        state.toolCache.remove(serverId)
        ContextProviderRegistry.unregister(serverId)
        unregisterTools(state, serverId)
    }

    suspend fun getResources(projectId: String?, serverId: String): List<MCPResource> {
        val state = projectStates[mapKey(projectId)] ?: return emptyList()
        val cached = state.resourceCache[serverId]
        if (cached != null && cached.isFresh(RESOURCE_CACHE_TTL_MS)) {
            return cached.value
        }

        val connection = state.connections[serverId] ?: return emptyList()
        val resources = connection.getCachedResources().ifEmpty { connection.refreshResources() }
        state.resourceCache[serverId] = CachedValue(resources, System.currentTimeMillis())
        return resources
    }

    suspend fun getTools(projectId: String?, serverId: String): List<MCPToolDefinition> {
        val state = projectStates[mapKey(projectId)] ?: return emptyList()
        val cached = state.toolCache[serverId]
        if (cached != null && cached.isFresh(TOOL_CACHE_TTL_MS)) {
            return cached.value
        }

        val connection = state.connections[serverId] ?: return emptyList()
        val tools = connection.getCachedTools().ifEmpty { connection.refreshTools() }
        state.toolCache[serverId] = CachedValue(tools, System.currentTimeMillis())
        return tools
    }

    fun disconnect(projectId: String?, serverId: String) {
        disconnectServer(projectId, serverId)
    }

    private fun unregisterTools(state: MCPProjectState, serverId: String?) {
        val registry = state.toolRegistry ?: return
        if (serverId != null) {
            val toolNames = state.registeredTools.remove(serverId).orEmpty()
            toolNames.forEach { registry.unregister(it) }
        } else {
            state.registeredTools.values.flatten().forEach { registry.unregister(it) }
            state.registeredTools.clear()
        }
    }

    suspend fun connectServer(projectId: String?, serverId: String) {
        val state = projectStates[mapKey(projectId)]
            ?: throw IllegalStateException("MCPManager not initialized for projectId=$projectId")
        val config = state.serverConfigs[serverId]
            ?: throw IllegalArgumentException("MCP server not found: $serverId")
        if (state.connections.containsKey(serverId)) {
            logger.debug { "MCP server $serverId already connected for projectId=$projectId" }
            return
        }

        val connection = MCPConnection(config)
        connection.connect()
        state.connections[serverId] = connection

        val provider = MCPContextProvider(serverId, config, connection)
        ContextProviderRegistry.register(provider)

        if (config.toolsEnabled && (config.toolsExposureMode ?: MCPToolsExposureMode.TOOLS) == MCPToolsExposureMode.TOOLS) {
            if (state.toolRegistry == null) {
                logger.warn { "Connected MCP server $serverId but ToolRegistry not available - tools will NOT be registered. Tools will be registered when session is created." }
            }
            registerTools(state, connection)
        }
    }

    private suspend fun registerTools(state: MCPProjectState, connection: MCPConnection) {
        val registry = state.toolRegistry ?: return
        if (connection.getCapabilities()?.tools != true) {
            return
        }
        val toolDefs = getTools(state.projectId, connection.serverId)
        val toolMode = if (state.serverConfigs[connection.serverId]?.accessMode == MCPAccessMode.READ) {
            pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY
        } else {
            pl.jclab.refio.core.tools.base.ToolMode.WRITE
        }
        val registered = mutableListOf<String>()
        toolDefs.forEach { toolDef ->
            val wrapper = MCPToolWrapper(connection, toolDef, toolMode)
            if (!registry.hasTool(wrapper.name)) {
                runCatching {
                    registry.register(wrapper)
                    registered.add(wrapper.name)
                }.onFailure { e -> logger.warn(e) { "Failed to register MCP tool ${wrapper.name}" } }
            }
        }
        if (registered.isNotEmpty()) {
            state.registeredTools[connection.serverId] = registered
        }
    }
}
