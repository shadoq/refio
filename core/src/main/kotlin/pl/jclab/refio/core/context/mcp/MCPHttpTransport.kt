package pl.jclab.refio.core.context.mcp

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.logging.dualLogger
import java.net.URI

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

/** One HTTP answer from an MCP server. [contentType] tells a plain JSON body from an event stream. */
data class MCPHttpResponse(val status: Int, val body: String, val contentType: String?)

/**
 * HTTP/SSE transport for MCP servers.
 *
 * Speaks both what the MCP specification prescribes and Refio's older dialect: an SSE server may
 * announce a separate POST address with an `endpoint` event (otherwise POSTs go to the configured
 * URL), and a server may hand out an `Mcp-Session-Id` on initialize, which is then sent with every
 * later request.
 *
 * [engine] replaces the network engine in tests; production passes nothing and gets CIO.
 */
class MCPHttpTransport(
    private val config: MCPServerConfig,
    private val onMessage: (String) -> Unit,
    private val onError: (Exception) -> Unit,
    private val engine: HttpClientEngine? = null,
    private val endpointWaitMs: Long = DEFAULT_ENDPOINT_WAIT_MS
) {
    private val client = buildClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout
            connectTimeoutMillis = 30000  // 30s connection timeout
            socketTimeoutMillis = config.timeout
        }
    }

    private val sseClient = buildClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = null  // No timeout for long-lived SSE connections
            connectTimeoutMillis = 30000  // 30s connection timeout only
            socketTimeoutMillis = null
        }
    }

    private fun buildClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
        engine?.let { HttpClient(it, block) } ?: HttpClient(CIO, block)

    private var scope: CoroutineScope? = null
    private var sseJob: Job? = null

    /** Session assigned by the server on initialize; sent back on every later request. */
    @Volatile
    internal var sessionId: String? = null
        private set

    /** POST address announced by an SSE server's `endpoint` event; null means the configured URL. */
    @Volatile
    internal var postUrl: String? = null
        private set

    /** Completes once the SSE stream has either announced an endpoint or shown it will not. */
    @Volatile
    private var endpointSettled = CompletableDeferred<Unit>()

    companion object {
        /**
         * Cap on one response body. A remote MCP server is a trust boundary and a broken local one
         * can answer with an unbounded result; either way the whole body would otherwise be read
         * into memory before anything looks at it.
         */
        private const val MAX_RESPONSE_BYTES = 10L * 1024 * 1024

        /**
         * How long connect waits for an SSE server's `endpoint` event. A compliant server sends it
         * first thing; a server in Refio's own dialect never does, and POSTs then keep going to the
         * configured URL once this runs out.
         */
        const val DEFAULT_ENDPOINT_WAIT_MS = 2_000L

        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    /** Opens the transport. A reconnect starts a fresh session: nothing from the last one is reused. */
    suspend fun connect() {
        sessionId = null
        postUrl = null
        if (config.type == MCPServerType.HTTP_SSE) {
            sseJob?.cancel()
            scope?.cancel()
            endpointSettled = CompletableDeferred()
            startSse()
            withTimeoutOrNull(endpointWaitMs) { endpointSettled.await() }
        }
    }

    /** Sends [payload] and returns the raw body, for callers that only need the body. */
    suspend fun request(payload: String): String = exchange(payload).body

    /**
     * Sends [payload] and returns the whole answer. With [isInitialize] a session id handed out in
     * the response header is remembered for every request that follows.
     */
    suspend fun exchange(payload: String, isInitialize: Boolean = false): MCPHttpResponse {
        val url = postUrl ?: config.url ?: throw IllegalArgumentException("HTTP transport requires url")
        val startTime = System.currentTimeMillis()
        var httpStatus: Int? = null
        var loggedError = false
        httpLogger.debug { "[${config.id}] HTTP POST to $url" }
        httpLogger.debug { "[${config.id}] Request payload: $payload" }

        return try {
            withContext(Dispatchers.IO) {
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    // A Streamable HTTP server may answer in either form and may reject a client
                    // that does not accept both.
                    header("Accept", "application/json, text/event-stream")
                    sessionId?.let { header(SESSION_HEADER, it) }
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
                if (isInitialize && response.status.value < 400) {
                    response.headers[SESSION_HEADER]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                }
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
                MCPHttpResponse(response.status.value, body, response.headers["Content-Type"])
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
        val settled = endpointSettled
        val url = config.url ?: run {
            settled.complete(Unit)
            return
        }
        scope = CoroutineScope(Dispatchers.IO)
        sseJob = scope?.launch {
            try {
                // prepareGet + execute streams the body; a plain get buffers it until the server
                // closes the stream, and an SSE server never does.
                sseClient.prepareGet(url) {
                    accept(ContentType.Text.EventStream)
                    headers {
                        mergeHeaders(config).forEach { header ->
                            val resolvedValue = resolveEnvVars(header.value)
                            append(header.name, resolvedValue)
                        }
                    }
                }.execute { response ->
                    if (response.status.value >= 400) {
                        httpLogger.warn { "[${config.id}] SSE stream refused with HTTP ${response.status.value}" }
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    val reader = SseEventReader()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        val event = reader.feed(line) ?: continue
                        if (event.event == "endpoint") {
                            postUrl = resolveEndpoint(url, event.data)
                            httpLogger.debug { "[${config.id}] SSE endpoint announced: $postUrl" }
                        } else {
                            onMessage(event.data)
                        }
                        settled.complete(Unit)
                    }
                }
            } catch (e: Exception) {
                httpLogger.warn(e) { "SSE closed for MCP server ${config.id}" }
                onError(MCPTransportException("SSE error for MCP server ${config.id}", e))
            } finally {
                settled.complete(Unit)
            }
        }
    }

    private fun resolveEndpoint(base: String, announced: String): String =
        runCatching { URI(base).resolve(announced.trim()).toString() }.getOrElse { announced.trim() }

    fun disconnect() {
        sseJob?.cancel()
        scope?.cancel()
        client.close()
        sseClient.close()
    }
}
