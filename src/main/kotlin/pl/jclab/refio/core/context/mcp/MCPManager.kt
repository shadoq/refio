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

private data class MCPProjectState(
    val projectId: String?,
    var toolRegistry: ToolRegistry? = null,
    val connections: ConcurrentHashMap<String, MCPConnection> = ConcurrentHashMap(),
    val serverConfigs: ConcurrentHashMap<String, MCPServerConfig> = ConcurrentHashMap(),
    val registeredTools: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap()
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
        return connection?.getStatus() ?: MCPServerStatus.DISCONNECTED
    }

    fun shutdown(projectId: String? = null) {
        val state = projectStates[mapKey(projectId)] ?: return
        state.connections.values.forEach { connection ->
            runCatching { connection.disconnect() }
        }
        state.connections.clear()
        unregisterTools(state, null)
        projectStates.remove(mapKey(projectId))
    }

    private fun disconnectServer(projectId: String?, serverId: String) {
        val state = projectStates[mapKey(projectId)] ?: return
        state.connections.remove(serverId)?.disconnect()
        ContextProviderRegistry.unregister(serverId)
        unregisterTools(state, serverId)
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
        val toolDefs = connection.getCachedTools().ifEmpty { connection.refreshTools() }
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
