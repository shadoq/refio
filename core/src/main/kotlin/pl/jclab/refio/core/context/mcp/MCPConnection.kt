package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = dualLogger("MCPConnection")

/**
 * Connection to an MCP (Model Context Protocol) server.
 */
class MCPConnection(
    private val config: MCPServerConfig
) {
    companion object {
        private const val RESOURCE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val TOOL_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val PROMPT_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    val serverId: String = config.id

    private var status: MCPServerStatus = MCPServerStatus.DISCONNECTED
    private var transport: MCPStdioTransport? = null
    private var httpTransport: MCPHttpTransport? = null
    private val gson = GsonInstance.gson
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<MCPSuccessResponse>>()

    private val cachedResources = mutableListOf<MCPResource>()
    private val cachedTools = mutableListOf<MCPToolDefinition>()
    private val cachedPrompts = mutableListOf<MCPPrompt>()
    private val cachedResourceContent = ConcurrentHashMap<String, Pair<MCPResourceContent, Long>>()
    private val subscribedResources = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var cachedResourcesAt: Long = 0L
    @Volatile private var cachedToolsAt: Long = 0L
    @Volatile private var cachedPromptsAt: Long = 0L
    private var capabilities: MCPServerCapabilities? = null

    var lastConnectedAt: java.time.Instant? = null
        private set
    var lastError: String? = null
        private set

    /** True while a transport (process or HTTP client) is still held by this connection. */
    internal val hasOpenTransport: Boolean
        get() = transport != null || httpTransport != null

    /** Number of requests still waiting for a response. Non-zero after a failure means a leak. */
    internal val pendingRequestCount: Int
        get() = pendingRequests.size

    suspend fun connect() {
        logger.info { "Connecting to MCP server: $serverId (${config.type})" }
        status = MCPServerStatus.CONNECTING

        try {
            when (config.type) {
                MCPServerType.STDIO -> connectStdio()
                MCPServerType.HTTP_SSE,
                MCPServerType.HTTP_STREAMABLE -> connectHttp()
            }

            val initResult = initialize()
            capabilities = initResult
            if (config.resourcesEnabled && capabilities?.resources == true) {
                refreshResources()
            }
            if (config.toolsEnabled && capabilities?.tools == true) {
                refreshTools()
            }
            if (config.promptsEnabled && capabilities?.prompts == true) {
                refreshPrompts()
            }

            status = MCPServerStatus.CONNECTED
            lastConnectedAt = java.time.Instant.now()
            lastError = null
            logger.info { "Connected to MCP server: $serverId" }
        } catch (e: Exception) {
            // The transport can already be up when the handshake fails (timeout, a server that
            // does not speak MCP, HTTP 401/404). Nothing else holds this connection yet, so
            // without releasing it here the child process or the HTTP clients live on forever.
            releaseResources()
            status = MCPServerStatus.ERROR
            lastError = e.message ?: e::class.simpleName
            logger.error(e) { "Failed to connect to MCP server: $serverId" }
            throw e
        }
    }

    fun disconnect() {
        logger.info { "Disconnecting from MCP server: $serverId" }
        releaseResources()
        status = MCPServerStatus.DISCONNECTED
    }

    /**
     * Drop the transport and everything tied to it. Callers still waiting for a response are
     * failed explicitly - clearing the map alone left them to wait out the full request timeout
     * and then blame a timeout for what was a disconnect.
     */
    private fun releaseResources() {
        runCatching { transport?.disconnect() }
            .onFailure { logger.warn(it) { "Failed to close stdio transport for $serverId" } }
        runCatching { httpTransport?.disconnect() }
            .onFailure { logger.warn(it) { "Failed to close HTTP transport for $serverId" } }
        transport = null
        httpTransport = null
        failPendingRequests()
        cachedResourceContent.clear()
        subscribedResources.clear()
        scope.cancel()
    }

    private fun failPendingRequests() {
        if (pendingRequests.isEmpty()) {
            return
        }
        val error = MCPTransportException("MCP server $serverId disconnected before the response arrived")
        pendingRequests.keys.toList().forEach { id ->
            pendingRequests.remove(id)?.completeExceptionally(error)
        }
    }

    fun getStatus(): MCPServerStatus = status

    suspend fun listResources(): List<MCPResource> {
        val response = sendRequest(MCPMethods.RESOURCES_LIST)
        return parseResourcesList(response.result)
    }

    suspend fun refreshResources(): List<MCPResource> {
        val resources = listResources()
        synchronized(cachedResources) {
            cachedResources.clear()
            cachedResources.addAll(resources)
            cachedResourcesAt = System.currentTimeMillis()
        }
        return resources
    }

    suspend fun readResource(uri: String, subscribe: Boolean = false): MCPResourceContent {
        cachedResourceContent[uri]?.let { (content, cachedAt) ->
            if (System.currentTimeMillis() - cachedAt <= RESOURCE_CACHE_TTL_MS) {
                if (subscribe) {
                    ensureResourceSubscription(uri)
                }
                return content
            }
        }

        if (subscribe) {
            ensureResourceSubscription(uri)
        }
        val response = sendRequest(MCPMethods.RESOURCES_READ, mapOf("uri" to uri))
        return parseResourceContent(response.result).also {
            cachedResourceContent[uri] = it to System.currentTimeMillis()
        }
    }

    suspend fun listTools(): List<MCPToolDefinition> {
        val response = sendRequest(MCPMethods.TOOLS_LIST)
        return parseToolsList(response.result)
    }

    suspend fun refreshTools(): List<MCPToolDefinition> {
        val tools = listTools()
        synchronized(cachedTools) {
            cachedTools.clear()
            cachedTools.addAll(tools)
            cachedToolsAt = System.currentTimeMillis()
        }
        return tools
    }

    suspend fun listPrompts(): List<MCPPrompt> {
        val response = sendRequest(MCPMethods.PROMPTS_LIST)
        return parsePromptsList(response.result)
    }

    suspend fun refreshPrompts(): List<MCPPrompt> {
        val prompts = listPrompts()
        synchronized(cachedPrompts) {
            cachedPrompts.clear()
            cachedPrompts.addAll(prompts)
            cachedPromptsAt = System.currentTimeMillis()
        }
        return prompts
    }

    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap()
    ): MCPPromptResult {
        val params = mutableMapOf<String, Any>("name" to name)
        if (arguments.isNotEmpty()) {
            params["arguments"] = arguments
        }
        val response = sendRequest(MCPMethods.PROMPTS_GET, params)
        return parsePromptResult(response.result, name)
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): MCPToolResult {
        if (!config.toolsEnabled) {
            throw MCPAccessDeniedException("Tool calls are disabled for server ${config.id}")
        }
        val response = sendRequest(
            MCPMethods.TOOLS_CALL,
            mapOf("name" to name, "arguments" to arguments)
        )
        return parseToolResult(response.result)
    }

    fun getCachedResources(): List<MCPResource> = synchronized(cachedResources) {
        if (System.currentTimeMillis() - cachedResourcesAt > RESOURCE_CACHE_TTL_MS) {
            emptyList()
        } else {
            cachedResources.toList()
        }
    }
    fun getCachedTools(): List<MCPToolDefinition> = synchronized(cachedTools) {
        if (System.currentTimeMillis() - cachedToolsAt > TOOL_CACHE_TTL_MS) {
            emptyList()
        } else {
            cachedTools.toList()
        }
    }
    fun getCachedPrompts(): List<MCPPrompt> = synchronized(cachedPrompts) {
        if (System.currentTimeMillis() - cachedPromptsAt > PROMPT_CACHE_TTL_MS) {
            emptyList()
        } else {
            cachedPrompts.toList()
        }
    }
    fun getCapabilities(): MCPServerCapabilities? = capabilities
    fun supportsResources(): Boolean = config.resourcesEnabled && capabilities?.resources == true
    fun supportsTools(): Boolean = config.toolsEnabled && capabilities?.tools == true
    fun supportsPrompts(): Boolean = config.promptsEnabled && capabilities?.prompts == true
    fun supportsResourceSubscriptions(): Boolean = config.resourcesEnabled && capabilities?.resourceSubscriptions == true

    private suspend fun initialize(): MCPServerCapabilities {
        val response = sendRequest(
            MCPMethods.INITIALIZE,
            mapOf(
                "protocolVersion" to "2025-06-18",
                "capabilities" to mapOf("roots" to mapOf("listChanged" to true)),
                "clientInfo" to mapOf("name" to "refio", "version" to "0.1.0")
            )
        )

        sendNotification(MCPMethods.INITIALIZED)
        return parseCapabilities(response.result)
    }

    private suspend fun connectStdio() {
        val transport = MCPStdioTransport(
            config = config,
            onMessage = { raw -> handleIncomingMessage(raw) },
            onError = { error -> logger.warn(error) { "MCP stdio error for ${config.id}" } }
        )
        transport.connect()
        this.transport = transport
    }

    private suspend fun connectHttp() {
        val transport = MCPHttpTransport(
            config = config,
            onMessage = { raw -> handleIncomingMessage(raw) },
            onError = { error -> logger.warn(error) { "MCP http error for ${config.id}" } }
        )
        transport.connect()
        this.httpTransport = transport
    }

    private fun handleIncomingMessage(raw: String) {
        try {
            val json = gson.fromJson(raw, JsonObject::class.java)
            val idElement: JsonElement? = json.get("id")

            if (idElement != null && !idElement.isJsonNull) {
                val id = idElement.asLong
                if (json.has("result")) {
                    val result = json.get("result")
                    pendingRequests.remove(id)?.complete(
                        MCPSuccessResponse(
                            id = id,
                            result = gson.fromJson(result, Any::class.java) ?: result
                        )
                    )
                } else if (json.has("error")) {
                    val errorObject = json.getAsJsonObject("error")
                    val error = MCPError(
                        code = errorObject.get("code")?.asInt ?: -1,
                        message = errorObject.get("message")?.asString ?: "Unknown MCP error",
                        data = errorObject.get("data")
                    )
                    pendingRequests.remove(id)?.completeExceptionally(
                        MCPTransportException("MCP error: ${error.message}")
                    )
                }
            } else if (json.has("method")) {
                val method = json.get("method")?.asString
                when (method) {
                    MCPMethods.RESOURCES_LIST_CHANGED -> scope.launch { refreshResources() }
                    MCPMethods.TOOLS_LIST_CHANGED -> scope.launch { refreshTools() }
                    MCPMethods.RESOURCES_UPDATED -> {
                        val params = json.getAsJsonObject("params")
                        val uri = params?.get("uri")?.asString
                        if (!uri.isNullOrBlank()) {
                            cachedResourceContent.remove(uri)
                            if (subscribedResources.contains(uri)) {
                                scope.launch {
                                    runCatching { readResource(uri, subscribe = false) }
                                }
                            }
                        }
                    }
                    else -> logger.debug { "Unhandled MCP notification: $method" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse MCP message for ${config.id}" }
        }
    }

    private suspend fun sendRequest(method: String, params: Map<String, Any?>? = null): MCPSuccessResponse {
        val id = requestId.getAndIncrement()
        val payload = MCPRequest(
            id = id,
            method = method,
            params = params?.filterValues { it != null }
        )
        val json = gson.toJson(payload)

        return when (config.type) {
            MCPServerType.STDIO -> {
                val deferred = CompletableDeferred<MCPSuccessResponse>()
                pendingRequests[id] = deferred
                // The entry is dropped in `finally` so a failed send or a cancelled turn cannot
                // leave it behind - a cancellation is not a timeout and never reached the catch.
                try {
                    val stdio = transport ?: throw MCPTransportException("Transport not connected")
                    stdio.send(json)
                    withTimeout(config.timeout) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    throw MCPTransportException("MCP request timed out: $method")
                } finally {
                    pendingRequests.remove(id)
                }
            }
            MCPServerType.HTTP_SSE, MCPServerType.HTTP_STREAMABLE -> {
                val responseJson = httpTransport?.request(json)
                    ?: throw MCPTransportException("HTTP transport not connected")
                parseDirectResponse(responseJson, id)
            }
        }
    }

    private suspend fun sendNotification(method: String, params: Map<String, Any?>? = null) {
        val payload = MCPNotification(
            method = method,
            params = params?.filterValues { it != null }
        )
        val json = gson.toJson(payload)
        when (config.type) {
            MCPServerType.STDIO -> transport?.send(json)
            MCPServerType.HTTP_SSE, MCPServerType.HTTP_STREAMABLE -> httpTransport?.request(json)
        }
    }

    private fun parseCapabilities(result: Any): MCPServerCapabilities {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val capabilities = json.getAsJsonObject("capabilities")

            fun hasCapability(name: String): Boolean {
                val element = capabilities?.get(name) ?: return false
                return when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
                    element.isJsonObject -> true  // Presence of object means capability is supported
                    else -> false
                }
            }

            MCPServerCapabilities(
                resources = hasCapability("resources"),
                tools = hasCapability("tools"),
                prompts = hasCapability("prompts"),
                resourceSubscriptions = capabilities
                    ?.getAsJsonObject("resources")
                    ?.get("subscribe")
                    ?.asBoolean
                    ?: false
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse capabilities for ${config.id}" }
            MCPServerCapabilities()
        }
    }

    private fun parseResourcesList(result: Any): List<MCPResource> {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val resourcesArray = json.getAsJsonArray("resources") ?: return emptyList()
            resourcesArray.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    MCPResource(
                        uri = obj.get("uri").asString,
                        name = obj.get("name")?.asString ?: obj.get("uri").asString,
                        description = obj.get("description")?.asString,
                        mimeType = obj.get("mimeType")?.asString
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse resources list for ${config.id}" }
            emptyList()
        }
    }

    private fun parseResourceContent(result: Any): MCPResourceContent {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val uri = json.get("uri")?.asString ?: ""
            val mimeType = json.get("mimeType")?.asString
            val contentArray = json.getAsJsonArray("contents")
            val firstContent = contentArray?.firstOrNull()?.asJsonObject
            val text = firstContent?.get("text")?.asString
            val blob = firstContent?.get("blob")?.asString
            MCPResourceContent(uri = uri, mimeType = mimeType, text = text, blob = blob)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse resource content for ${config.id}" }
            MCPResourceContent(uri = "", text = null, blob = null)
        }
    }

    private fun parseToolsList(result: Any): List<MCPToolDefinition> {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val toolsArray = json.getAsJsonArray("tools") ?: return emptyList()
            toolsArray.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    val inputSchemaElement = obj.get("inputSchema")
                    val schemaMap: Map<String, Any> = if (inputSchemaElement != null && inputSchemaElement.isJsonObject) {
                        val rawSchema = gson.fromJson(inputSchemaElement, Map::class.java) ?: emptyMap<Any?, Any?>()
                        rawSchema.entries.mapNotNull entry@{ (key, value) ->
                            val name = key as? String ?: return@entry null
                            value?.let { name to it }
                        }.toMap()
                    } else {
                        emptyMap()
                    }
                    MCPToolDefinition(
                        name = obj.get("name").asString,
                        description = obj.get("description")?.asString,
                        inputSchema = schemaMap
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tools list for ${config.id}" }
            emptyList()
        }
    }

    private fun parseToolResult(result: Any): MCPToolResult {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val isError = json.get("isError")?.asBoolean ?: false
            val contentArray = json.getAsJsonArray("content")
            val parts = contentArray?.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    MCPContentPart(
                        type = obj.get("type")?.asString ?: "text",
                        text = obj.get("text")?.asString,
                        blob = obj.get("blob")?.asString,
                        mimeType = obj.get("mimeType")?.asString
                    )
                }.getOrNull()
            } ?: emptyList()
            MCPToolResult(isError = isError, content = parts)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool result for ${config.id}" }
            MCPToolResult(isError = true, content = listOf(MCPContentPart(text = e.message)))
        }
    }

    internal fun parsePromptsList(result: Any): List<MCPPrompt> {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val promptsArray = json.getAsJsonArray("prompts") ?: return emptyList()
            promptsArray.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    val args = obj.getAsJsonArray("arguments")?.mapNotNull { argElement ->
                        val argObj = argElement.asJsonObject
                        val argName = argObj.get("name")?.asString ?: return@mapNotNull null
                        MCPPromptArgument(
                            name = argName,
                            description = argObj.get("description")?.asString,
                            required = argObj.get("required")?.asBoolean ?: false
                        )
                    } ?: emptyList()
                    MCPPrompt(
                        name = obj.get("name").asString,
                        description = obj.get("description")?.asString,
                        arguments = args
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse prompts list for ${config.id}" }
            emptyList()
        }
    }

    internal fun parsePromptResult(result: Any, fallbackName: String): MCPPromptResult {
        return try {
            val json = gson.toJsonTree(result).asJsonObject
            val messages = json.getAsJsonArray("messages")?.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    val role = obj.get("role")?.asString ?: "user"
                    val contentParts = parsePromptContentParts(obj.get("content"))
                    MCPPromptMessage(role = role, content = contentParts)
                }.getOrNull()
            } ?: emptyList()

            MCPPromptResult(
                name = json.get("name")?.asString ?: fallbackName,
                description = json.get("description")?.asString,
                messages = messages
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse prompt result for ${config.id}" }
            MCPPromptResult(name = fallbackName)
        }
    }

    private fun parsePromptContentParts(element: JsonElement?): List<MCPContentPart> {
        if (element == null || element.isJsonNull) return emptyList()

        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return listOf(MCPContentPart(text = element.asString))
        }

        if (element.isJsonObject) {
            val obj = element.asJsonObject
            return listOf(
                MCPContentPart(
                    type = obj.get("type")?.asString ?: "text",
                    text = obj.get("text")?.asString,
                    blob = obj.get("blob")?.asString,
                    mimeType = obj.get("mimeType")?.asString
                )
            )
        }

        if (!element.isJsonArray) return emptyList()

        return element.asJsonArray.mapNotNull { contentElement ->
            runCatching {
                val obj = contentElement.asJsonObject
                MCPContentPart(
                    type = obj.get("type")?.asString ?: "text",
                    text = obj.get("text")?.asString,
                    blob = obj.get("blob")?.asString,
                    mimeType = obj.get("mimeType")?.asString
                )
            }.getOrNull()
        }
    }

    private suspend fun ensureResourceSubscription(uri: String) {
        if (!supportsResourceSubscriptions() || subscribedResources.contains(uri)) {
            return
        }

        runCatching {
            sendRequest(MCPMethods.RESOURCES_SUBSCRIBE, mapOf("uri" to uri))
            subscribedResources.add(uri)
        }.onFailure {
            logger.debug { "Resource subscription failed for $uri on ${config.id}: ${it.message}" }
        }
    }

    private fun parseDirectResponse(raw: String, expectedId: Long): MCPSuccessResponse {
        val json = gson.fromJson(raw, JsonObject::class.java)
        if (json.has("error")) {
            val errorObj = json.getAsJsonObject("error")
            val message = errorObj.get("message")?.asString ?: "Unknown MCP error"
            throw MCPTransportException(message)
        }
        val result = json.get("result")
        val id = json.get("id")?.asLong ?: expectedId
        return MCPSuccessResponse(
            jsonrpc = json.get("jsonrpc")?.asString ?: "2.0",
            id = id,
            result = gson.fromJson(result, Any::class.java) ?: result
        )
    }
}
