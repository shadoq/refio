package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.utils.GsonInstance

data class MCPTestResult(
    val requestDetails: String,
    val responseDetails: String?,
    val errorMessage: String?
)

object MCPTestRunner {

    private val gson = GsonInstance.gson

    suspend fun test(config: MCPServerConfig): MCPTestResult {
        val requestId = 1L
        val request = MCPRequest(
            id = requestId,
            method = MCPMethods.INITIALIZE,
            params = buildInitializeParams()
        )
        val requestJson = gson.toJson(request)
        val requestDetails = buildRequestDetails(config, requestJson)

        return when (config.type) {
            MCPServerType.STDIO -> testStdio(config, requestId, requestJson, requestDetails)
            MCPServerType.HTTP_SSE, MCPServerType.HTTP_STREAMABLE -> testHttp(config, requestJson, requestDetails)
        }
    }

    private fun buildInitializeParams(): Map<String, Any> {
        return mapOf(
            "protocolVersion" to "2025-06-18",
            "capabilities" to mapOf("roots" to mapOf("listChanged" to true)),
            "clientInfo" to mapOf("name" to "refio", "version" to "0.1.0")
        )
    }

    private suspend fun testStdio(
        config: MCPServerConfig,
        requestId: Long,
        requestJson: String,
        requestDetails: String
    ): MCPTestResult {
        val responseDeferred = CompletableDeferred<String>()
        val transport = MCPStdioTransport(
            config = config,
            onMessage = { raw ->
                val json = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
                val id = json?.get("id")?.asLong
                if (id == requestId && !responseDeferred.isCompleted) {
                    responseDeferred.complete(raw)
                }
            },
            onError = { error ->
                if (!responseDeferred.isCompleted) {
                    responseDeferred.completeExceptionally(error)
                }
            }
        )

        return try {
            transport.connect()
            transport.send(requestJson)
            val response = try {
                withTimeout(config.timeout) { responseDeferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw MCPTransportException("MCP request timed out: initialize", e)
            }
            sendInitializedNotification(transport)
            MCPTestResult(
                requestDetails = requestDetails,
                responseDetails = response,
                errorMessage = extractErrorMessage(response)
            )
        } catch (e: Exception) {
            MCPTestResult(
                requestDetails = requestDetails,
                responseDetails = null,
                errorMessage = e.message ?: "Unknown error"
            )
        } finally {
            transport.disconnect()
        }
    }

    private suspend fun testHttp(
        config: MCPServerConfig,
        requestJson: String,
        requestDetails: String
    ): MCPTestResult {
        val transport = MCPHttpTransport(
            config = config,
            onMessage = {},
            onError = {}
        )

        return try {
            transport.connect()
            val response = transport.request(requestJson)
            MCPTestResult(
                requestDetails = requestDetails,
                responseDetails = response,
                errorMessage = extractErrorMessage(response)
            )
        } catch (e: Exception) {
            MCPTestResult(
                requestDetails = requestDetails,
                responseDetails = null,
                errorMessage = e.message ?: "Unknown error"
            )
        } finally {
            transport.disconnect()
        }
    }

    private fun sendInitializedNotification(transport: MCPStdioTransport) {
        val notification = MCPNotification(method = MCPMethods.INITIALIZED)
        val payload = gson.toJson(notification)
        transport.send(payload)
    }

    private fun extractErrorMessage(rawResponse: String): String? {
        val json = runCatching { gson.fromJson(rawResponse, JsonObject::class.java) }.getOrNull() ?: return null
        if (!json.has("error")) {
            return null
        }
        val errorObj = json.getAsJsonObject("error")
        val message = errorObj?.get("message")?.asString
        return message?.ifBlank { null }
    }

    private fun buildRequestDetails(config: MCPServerConfig, requestJson: String): String {
        val details = StringBuilder()
        details.append("Transport: ").append(config.type).append("\n")
        details.append("Timeout: ").append(config.timeout).append("ms\n")
        when (config.type) {
            MCPServerType.STDIO -> {
                details.append("Command: ").append(config.command ?: "(not set)").append("\n")
                details.append("Args: ").append(config.args.joinToString(" ").ifBlank { "(none)" }).append("\n")
                details.append("Working Dir: ").append(config.workingDirectory ?: "(default)").append("\n")
                val envLines = buildEnvLines(config)
                details.append("Env:\n")
                details.append(envLines.joinToString("\n").ifBlank { "(none)" }).append("\n")
            }
            MCPServerType.HTTP_SSE, MCPServerType.HTTP_STREAMABLE -> {
                details.append("URL: ").append(config.url ?: "(not set)").append("\n")
                details.append("Headers:\n")
                val headerLines = buildHeaderLines(config)
                details.append(headerLines.joinToString("\n").ifBlank { "(none)" }).append("\n")
            }
        }
        details.append("Payload:\n").append(requestJson)
        return details.toString()
    }

    private fun buildEnvLines(config: MCPServerConfig): List<String> {
        return config.env.mapNotNull { env ->
            if (env.name.isBlank() || env.value == null) {
                null
            } else {
                val value = if (env.isSecret) "[REDACTED]" else env.value
                "${env.name}=$value"
            }
        }
    }

    private fun buildHeaderLines(config: MCPServerConfig): List<String> {
        val headers = mutableListOf<MCPHttpHeader>()
        headers.addAll(config.httpHeaders)
        buildAuthHeader(config)?.let { authHeader ->
            val existing = headers.any { it.name.equals(authHeader.name, ignoreCase = true) }
            if (!existing) {
                headers.add(0, authHeader)
            }
        }
        return headers.map { header ->
            val value = if (header.isSecret) {
                "[REDACTED]"
            } else {
                resolveEnvVars(header.value)
            }
            "${header.name}: $value"
        }
    }

    private fun buildAuthHeader(config: MCPServerConfig): MCPHttpHeader? {
        val auth = config.auth ?: return null
        if (auth.type != MCPAuthType.BEARER) {
            return null
        }
        val apiKey = auth.apiKey?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return null
        }
        return MCPHttpHeader(
            name = "Authorization",
            value = "Bearer $apiKey",
            isSecret = auth.isSecret
        )
    }

    private fun resolveEnvVars(value: String): String {
        val envVarPattern = Regex("""\$\{([^}]+)\}""")
        return envVarPattern.replace(value) { matchResult ->
            val varName = matchResult.groupValues[1]
            System.getenv(varName) ?: matchResult.value
        }
    }
}
