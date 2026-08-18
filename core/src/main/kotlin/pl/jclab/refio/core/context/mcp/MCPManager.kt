package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val toolCache: ConcurrentHashMap<String, CachedValue<List<MCPToolDefinition>>> = ConcurrentHashMap(),
    /**
     * Why the last connect attempt failed, per server. A failed connection is never stored in
     * [connections], so without this the server would report a transient DISCONNECTED and the
     * reason would be lost.
     */
    val connectErrors: ConcurrentHashMap<String, String> = ConcurrentHashMap()
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

    /** One lock per project+server, so a slow connect cannot be started twice in parallel. */
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Creates the connection for a server config. Overridable so the manager's connection and
     * tool bookkeeping can be exercised without spawning real MCP servers.
     */
    internal var connectionFactory: (MCPServerConfig) -> MCPConnection = { config -> MCPConnection(config) }

    /**
     * Initialize MCP manager for a project.
     *
     * [runScopeServers] are servers declared for this process only - they are used and connected
     * like any other, but never written to the database. That keeps a headless test run from
     * leaving servers behind in the shared database. On an id collision the run-scope server wins,
     * so a test can shadow a stored server without editing it.
     */
    fun initialize(
        projectId: String? = null,
        toolRegistry: ToolRegistry? = null,
        runScopeServers: List<MCPServerConfig> = emptyList()
    ) {
        val state = projectStates.computeIfAbsent(mapKey(projectId)) {
            MCPProjectState(projectId = projectId, toolRegistry = toolRegistry)
        }
        if (toolRegistry != null) {
            applyToolRegistry(state, toolRegistry)
        }

        val stored = repository.getAll(projectId).filterNot { s -> runScopeServers.any { it.id == s.id } }
        val configs = stored + runScopeServers
        configs.forEach { config -> state.serverConfigs[config.id] = config }

        scope.launch {
            configs.filter { it.enabled }.forEach { config ->
                runCatching { connectServer(projectId, config.id) }
                    .onFailure { e -> logger.error(e) { "Failed to connect MCP server ${config.id}" } }
            }
            // Fan in tools from already-connected global servers (their single connection lives
            // under _global and would otherwise never reach this project's ToolRegistry).
            registerGlobalToolsInto(state)
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

        applyToolRegistry(state, toolRegistry)
    }

    /**
     * Point the project at a registry and make sure it actually holds the tools of the servers
     * that are already connected.
     *
     * A recreated project router brings a brand new, empty [ToolRegistry] while the connections
     * stay up, so every registry swap has to re-register - checking only whether a registry was
     * present before silently dropped every MCP tool for the rest of the session.
     */
    private fun applyToolRegistry(state: MCPProjectState, toolRegistry: ToolRegistry) {
        if (state.toolRegistry === toolRegistry) {
            return
        }

        state.toolRegistry = toolRegistry
        // The recorded names belong to the previous registry; the new one starts empty.
        state.registeredTools.clear()
        logger.info { "ToolRegistry set for projectId=${state.projectId} - registering tools for connected servers (incl. global)" }
        scope.launch { registerConnectedTools(state) }
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
        val state = projectStates[mapKey(projectId)]
        val config = state?.serverConfigs?.get(serverId)
        if (config?.enabled == false) return MCPServerStatus.DISABLED
        return resolveStatus(state, serverId)
    }

    /**
     * Status of an enabled server. A server whose connect attempt failed has no connection object
     * to ask, so the recorded failure decides - reporting DISCONNECTED there reads as "still
     * starting" and makes callers wait out their whole readiness timeout.
     */
    private fun resolveStatus(state: MCPProjectState?, serverId: String): MCPServerStatus {
        state?.connections?.get(serverId)?.let { return it.getStatus() }
        if (state?.connectErrors?.containsKey(serverId) == true) {
            return MCPServerStatus.ERROR
        }
        return MCPServerStatus.DISCONNECTED
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
                status = if (!config.enabled) MCPServerStatus.DISABLED else resolveStatus(state, serverId),
                lastConnectedAt = connection?.lastConnectedAt,
                lastError = connection?.lastError ?: state.connectErrors[serverId],
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
        state.connectErrors.clear()
        unregisterTools(state, null)
        projectStates.remove(mapKey(projectId))
    }

    private fun disconnectServer(projectId: String?, serverId: String) {
        val state = projectStates[mapKey(projectId)] ?: return
        state.connections.remove(serverId)?.disconnect()
        state.resourceCache.remove(serverId)
        state.toolCache.remove(serverId)
        state.connectErrors.remove(serverId)
        ContextProviderRegistry.unregister(serverId)
        unregisterTools(state, serverId)
        if (projectId == null) {
            // Global server tools were fanned out into every project registry — remove them there too.
            projectStates.values.filter { it.projectId != null }.forEach { ps -> unregisterTools(ps, serverId) }
        }
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

        // Serialized per server: connect() suspends for as long as a handshake takes, so without
        // this both callers pass the "already connected" check and start a second server process
        // that nothing ever holds a reference to.
        connectLock(projectId, serverId).withLock {
            if (state.connections.containsKey(serverId)) {
                logger.debug { "MCP server $serverId already connected for projectId=$projectId" }
                return
            }

            val connection = connectionFactory(config)
            try {
                connection.connect()
            } catch (e: Exception) {
                state.connectErrors[serverId] = e.message ?: e::class.simpleName ?: "connect failed"
                throw e
            }
            state.connections[serverId] = connection
            state.connectErrors.remove(serverId)

            val provider = MCPContextProvider(serverId, config, connection)
            ContextProviderRegistry.register(provider)

            if (projectId == null) {
                // Global server: its single connection feeds every project's ToolRegistry.
                propagateGlobalToolsToProjects(connection, config)
            } else {
                val registry = state.toolRegistry
                if (registry == null) {
                    logger.warn { "Connected MCP server '$serverId' but project ToolRegistry not available yet - tools register once the project router/session is created." }
                } else {
                    registerToolsInto(registry, connection, config, state.registeredTools)
                }
            }
        }
    }

    private fun connectLock(projectId: String?, serverId: String): Mutex =
        connectLocks.computeIfAbsent("${mapKey(projectId)}/$serverId") { Mutex() }

    /**
     * Register every tool the given project state should expose: its own connected servers
     * plus all globally-scoped connected servers (a global server's single connection fans out
     * to each project's ToolRegistry).
     */
    private suspend fun registerConnectedTools(state: MCPProjectState) {
        val registry = state.toolRegistry ?: return
        state.connections.values.forEach { connection ->
            runCatching { registerToolsInto(registry, connection, state.serverConfigs[connection.serverId], state.registeredTools) }
                .onFailure { e -> logger.error(e) { "Failed to register tools for ${connection.serverId}" } }
        }
        registerGlobalToolsInto(state)
    }

    /** Register all connected global servers' tools into [targetState]'s registry. No-op for the global state itself. */
    private suspend fun registerGlobalToolsInto(targetState: MCPProjectState) {
        val registry = targetState.toolRegistry ?: return
        if (targetState.projectId == null) return
        val global = projectStates[GLOBAL_PROJECT_KEY] ?: return
        global.connections.values.forEach { connection ->
            runCatching { registerToolsInto(registry, connection, global.serverConfigs[connection.serverId], targetState.registeredTools) }
                .onFailure { e -> logger.error(e) { "Failed to register global tool ${connection.serverId} into project ${targetState.projectId}" } }
        }
    }

    /** Register a freshly-connected global server's tools into every already-initialized project registry. */
    private suspend fun propagateGlobalToolsToProjects(connection: MCPConnection, config: MCPServerConfig) {
        val targets = projectStates.values.filter { it.projectId != null && it.toolRegistry != null }
        if (targets.isEmpty()) {
            logger.info { "Global MCP server '${connection.serverId}' connected; no project registries yet - tools register when a project opens." }
            return
        }
        targets.forEach { ps ->
            runCatching { registerToolsInto(ps.toolRegistry!!, connection, config, ps.registeredTools) }
                .onFailure { e -> logger.error(e) { "Failed to register global tool ${connection.serverId} into project ${ps.projectId}" } }
        }
    }

    /**
     * Register a single connection's tools into [registry], recording the registered names under
     * the serverId in [bookkeeping] for later unregistration. When the server is connected but
     * exposes no agent tools (CONTEXT mode, disabled, or no `tools` capability) nothing is registered
     * and the reason is logged at WARN — see [MCPToolExposure]. Previously this gate was silent.
     */
    private suspend fun registerToolsInto(
        registry: ToolRegistry,
        connection: MCPConnection,
        config: MCPServerConfig?,
        bookkeeping: ConcurrentHashMap<String, List<String>>
    ) {
        if (config == null) return
        val unavailableReason = MCPToolExposure.agentToolUnavailableReason(config, connection.getCapabilities())
        if (unavailableReason != null) {
            logger.warn { "MCP server '${connection.serverId}' is connected but exposes no agent tools: $unavailableReason" }
            return
        }
        val toolMode = if (config.accessMode == MCPAccessMode.READ) {
            pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY
        } else {
            pl.jclab.refio.core.tools.base.ToolMode.WRITE
        }
        val toolDefs = connection.getCachedTools().ifEmpty { connection.refreshTools() }
        val registered = mutableListOf<String>()
        // Bookkeeping records everything this server exposes in the registry, not only what this
        // call added - it is what the UI reports as the tool count, so it has to match the registry.
        val exposed = mutableListOf<String>()
        toolDefs.forEach { toolDef ->
            val wrapper = MCPToolWrapper(connection, toolDef, toolMode)
            if (registry.hasTool(wrapper.name)) {
                exposed.add(wrapper.name)
                return@forEach
            }
            runCatching { registry.register(wrapper) }
                .onSuccess {
                    registered.add(wrapper.name)
                    exposed.add(wrapper.name)
                }
                .onFailure { e ->
                    if (registry.hasTool(wrapper.name)) {
                        exposed.add(wrapper.name)
                    } else {
                        logger.warn(e) { "Failed to register MCP tool ${wrapper.name}" }
                    }
                }
        }
        if (exposed.isNotEmpty()) {
            bookkeeping[connection.serverId] = exposed.distinct()
        }
        if (registered.isNotEmpty()) {
            logger.info { "Registered ${registered.size} agent tool(s) from MCP server '${connection.serverId}': ${registered.joinToString()}" }
        }
    }
}
