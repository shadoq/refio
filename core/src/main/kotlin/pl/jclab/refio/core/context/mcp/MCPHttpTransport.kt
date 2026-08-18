package pl.jclab.refio.core.context.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger

private val httpLogger = dualLogger("MCPHttpTransport")

/**
 * Resolves environment variable placeholders in the format ${VAR_NAME}.
 */
private fun resolveEnvVars(value: String): String {
    val envVarPattern = Regex("""\$\{([^}]+)\}""")
    return envVarPattern.replace(value) { matchResult ->
        val varName = matchResult.groupValues[1]
        System.getenv(varName) ?: matchResult.value  // Keep placeholder if not found
    }
}

private fun buildAuthHeaders(config: MCPServerConfig): List<MCPHttpHeader> {
    val auth = config.auth ?: return emptyList()
    if (auth.type != MCPAuthType.BEARER) {
        return emptyList()
    }
    val apiKey = auth.apiKey?.trim().orEmpty()
    if (apiKey.isBlank()) {
        return emptyList()
    }
    return listOf(
        MCPHttpHeader(
            name = "Authorization",
            value = "Bearer $apiKey",
            isSecret = auth.isSecret
        )
    )
}

private fun mergeHeaders(config: MCPServerConfig): List<MCPHttpHeader> {
    val existing = config.httpHeaders.map { it.name.lowercase() }.toSet()
    val authHeaders = buildAuthHeaders(config).filterNot { it.name.lowercase() in existing }
    return authHeaders + config.httpHeaders
}

/**
 * HTTP/SSE transport for MCP servers.
 */
class MCPHttpTransport(
    private val config: MCPServerConfig,
    private val onMessage: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout
            connectTimeoutMillis = 30000  // 30s connection timeout
            socketTimeoutMillis = config.timeout
        }
    }

    private val sseClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = null  // No timeout for long-lived SSE connections
            connectTimeoutMillis = 30000  // 30s connection timeout only
            socketTimeoutMillis = null
        }
    }

    private var scope: CoroutineScope? = null
    private var sseJob: Job? = null

    companion object {
        /**
         * Cap on one response body. A remote MCP server is a trust boundary and a broken local one
         * can answer with an unbounded result; either way the whole body would otherwise be read
         * into memory before anything looks at it.
         */
        private const val MAX_RESPONSE_BYTES = 10L * 1024 * 1024
    }

    suspend fun connect() {
        if (config.type == MCPServerType.HTTP_SSE) {
            startSse()
        }
    }

    suspend fun request(payload: String): String {
        val url = config.url ?: throw IllegalArgumentException("HTTP transport requires url")
        val startTime = System.currentTimeMillis()
        var httpStatus: Int? = null
        var loggedError = false
        httpLogger.debug { "[${config.id}] HTTP POST to $url" }
        httpLogger.debug { "[${config.id}] Request payload: $payload" }

        return try {
            withContext(Dispatchers.IO) {
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    headers {
                        mergeHeaders(config).forEach { header ->
                            val resolvedValue = resolveEnvVars(header.value)
                            append(header.name, resolvedValue)
                            if (header.isSecret) {
                                httpLogger.debug { "[${config.id}] Header ${header.name}: [REDACTED]" }
                            } else {
                                httpLogger.debug { "[${config.id}] Header ${header.name}: $resolvedValue" }
                            }
                        }
                    }
                }
                val body = readBoundedBody(response)
                httpStatus = response.status.value
                httpLogger.debug { "[${config.id}] HTTP ${response.status.value} response: $body" }

                if (response.status.value >= 400) {
                    val error = MCPTransportException("HTTP error ${response.status.value}: $body")
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                    loggedError = true
                    httpLogger.apiError(
                        provider = "mcp",
                        model = config.id,
                        endpoint = url,
                        requestJson = payload,
                        httpStatus = response.status.value,
                        error = error,
                        latencyMs = latencyMs,
                        source = "MCP_HTTP"
                    )
                    throw error
                }
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                httpLogger.apiResponse(
                    provider = "mcp",
                    model = config.id,
                    endpoint = url,
                    requestJson = payload,
                    responseJson = body,
                    httpStatus = response.status.value,
                    inputTokens = 0,
                    outputTokens = 0,
                    costUsd = 0.0,
                    latencyMs = latencyMs,
                    source = "MCP_HTTP"
                )
                body
            }
        } catch (e: Exception) {
            if (!loggedError) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                httpLogger.apiError(
                    provider = "mcp",
                    model = config.id,
                    endpoint = url,
                    requestJson = payload,
                    httpStatus = httpStatus,
                    error = e,
                    latencyMs = latencyMs,
                    source = "MCP_HTTP"
                )
            }
            httpLogger.error(e) { "[${config.id}] HTTP request failed" }
            onError(MCPTransportException("Failed HTTP request for MCP server ${config.id}", e))
            throw e
        }
    }

    /**
     * Reads the body, refusing anything past [MAX_RESPONSE_BYTES] with a message that names the
     * server, so an oversized answer fails as a clear MCP error instead of an out-of-memory kill.
     */
    private suspend fun readBoundedBody(response: HttpResponse): String {
        val declaredLength = response.contentLength()
        if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) {
            throw tooLarge(declaredLength)
        }

        val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readBytes()
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw tooLarge(bytes.size.toLong())
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun tooLarge(size: Long): MCPTransportException = MCPTransportException(
        "MCP server ${config.id} returned a response of at least $size bytes, " +
            "over the ${MAX_RESPONSE_BYTES} byte limit"
    )

    private fun startSse() {
        val url = config.url ?: return
        scope = CoroutineScope(Dispatchers.IO)
        sseJob = scope?.launch {
            try {
                val response = sseClient.get(url) {
                    accept(ContentType.Text.EventStream)
                    headers {
                        mergeHeaders(config).forEach { header ->
                            val resolvedValue = resolveEnvVars(header.value)
                            append(header.name, resolvedValue)
                        }
                    }
                }
                val channel = response.bodyAsChannel()
                val buffer = StringBuilder()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue
                    if (line.startsWith("data:")) {
                        buffer.append(line.removePrefix("data:").trim())
                    } else if (line.isBlank() && buffer.isNotEmpty()) {
                        val message = buffer.toString()
                        buffer.clear()
                        onMessage(message)
                    }
                }
            } catch (e: Exception) {
                httpLogger.warn(e) { "SSE closed for MCP server ${config.id}" }
                onError(MCPTransportException("SSE error for MCP server ${config.id}", e))
            }
        }
    }

    fun disconnect() {
        sseJob?.cancel()
        scope?.cancel()
        client.close()
        sseClient.close()
    }
}
