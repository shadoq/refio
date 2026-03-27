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
    private var capabilities: MCPServerCapabilities? = null

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

            status = MCPServerStatus.CONNECTED
            logger.info { "Connected to MCP server: $serverId" }
        } catch (e: Exception) {
            status = MCPServerStatus.ERROR
            logger.error(e) { "Failed to connect to MCP server: $serverId" }
            throw e
        }
    }

    fun disconnect() {
        logger.info { "Disconnecting from MCP server: $serverId" }
        transport?.disconnect()
        httpTransport?.disconnect()
        transport = null
        httpTransport = null
        pendingRequests.clear()
        scope.cancel()
        status = MCPServerStatus.DISCONNECTED
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
        }
        return resources
    }

    suspend fun readResource(uri: String): MCPResourceContent {
        val response = sendRequest(MCPMethods.RESOURCES_READ, mapOf("uri" to uri))
        return parseResourceContent(response.result)
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
        }
        return tools
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

    fun getCachedResources(): List<MCPResource> = synchronized(cachedResources) { cachedResources.toList() }
    fun getCachedTools(): List<MCPToolDefinition> = synchronized(cachedTools) { cachedTools.toList() }
    fun getCapabilities(): MCPServerCapabilities? = capabilities
    fun supportsResources(): Boolean = config.resourcesEnabled && capabilities?.resources == true
    fun supportsTools(): Boolean = config.toolsEnabled && capabilities?.tools == true

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
                transport?.send(json) ?: throw MCPTransportException("Transport not connected")
                try {
                    withTimeout(config.timeout) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    pendingRequests.remove(id)
                    throw MCPTransportException("MCP request timed out: $method")
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
                prompts = hasCapability("prompts")
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
            val text = contentArray?.firstOrNull()?.asJsonObject?.get("text")?.asString
            MCPResourceContent(uri = uri, mimeType = mimeType, text = text)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse resource content for ${config.id}" }
            MCPResourceContent(uri = "", text = null)
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
                        text = obj.get("text")?.asString
                    )
                }.getOrNull()
            } ?: emptyList()
            MCPToolResult(isError = isError, content = parts)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool result for ${config.id}" }
            MCPToolResult(isError = true, content = listOf(MCPContentPart(text = e.message)))
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
