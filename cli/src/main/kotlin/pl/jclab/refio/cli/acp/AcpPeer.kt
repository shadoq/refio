package pl.jclab.refio.cli.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pl.jclab.refio.core.logging.dualLogger
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = dualLogger("AcpPeer")

/** A JSON-RPC error a handler raises on purpose; it goes back to the other side as is. */
class AcpError(val code: Int, message: String) : Exception(message) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** The other side answered our request with an error object. */
class AcpRemoteError(val code: Int, message: String) : Exception(message)

/**
 * One end of a JSON-RPC 2.0 connection over a pair of streams, one message per line.
 *
 * ACP is symmetric: the IDE calls the agent (`session/prompt`) and the agent calls the IDE
 * (`session/request_permission`) over the same pipe, so this class both serves and sends requests.
 *
 * Every outgoing message goes through one channel drained by one writer. Turn listeners report
 * from plain (non-suspending) callbacks; a channel keeps their notifications in the order they
 * happened and before the response that ends the turn, which separate launches would not.
 *
 * Incoming requests and notifications are handled concurrently, because `session/cancel` must be
 * able to arrive while a `session/prompt` handler is still running.
 */
class AcpPeer(
    input: InputStream,
    output: OutputStream,
    private val scope: CoroutineScope,
) {
    private val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
    private val writer = OutputStreamWriter(output, Charsets.UTF_8)
    private val outbox = Channel<JsonObject>(Channel.UNLIMITED)
    private val nextId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** Handles a request; returns its `result`. Throw [AcpError] to answer with an error. */
    var requestHandler: suspend (method: String, params: JsonObject) -> JsonElement = { method, _ ->
        throw AcpError(AcpError.METHOD_NOT_FOUND, "Method not found: $method")
    }

    /** Handles a notification. Unknown notifications are ignored, as JSON-RPC allows. */
    var notificationHandler: suspend (method: String, params: JsonObject) -> Unit = { _, _ -> }

    /**
     * Reads messages until the input closes, then fails every request still waiting for an answer.
     * Suspends for the life of the connection.
     */
    suspend fun run() {
        val writerJob = scope.launch(Dispatchers.IO) {
            for (message in outbox) {
                writer.write(json.encodeToString(JsonObject.serializer(), message))
                writer.write("\n")
                writer.flush()
            }
        }
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isBlank()) continue
                dispatch(line)
            }
        } finally {
            logger.info { "[ACP] input closed" }
            pendingRequests.values.forEach { it.completeExceptionally(AcpRemoteError(AcpError.INTERNAL_ERROR, "Connection closed")) }
            pendingRequests.clear()
            outbox.close()
            writerJob.join()
        }
    }

    /** Sends a request to the other side and suspends until it answers; returns its `result`. */
    suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        pendingRequests[JsonPrimitive(id).toString()] = answer
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        val response = try {
            answer.await()
        } finally {
            pendingRequests.remove(JsonPrimitive(id).toString())
        }
        response["error"]?.let { error ->
            val obj = error.jsonObject
            throw AcpRemoteError(
                obj["code"]?.jsonPrimitive?.int ?: AcpError.INTERNAL_ERROR,
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "error"
            )
        }
        return response["result"] ?: JsonNull
    }

    /** Queues a notification; never suspends, so plain callbacks can call it. */
    fun notify(method: String, params: JsonObject) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    private fun send(message: JsonObject) {
        if (outbox.trySend(message).isFailure) {
            logger.warn { "[ACP] dropped outgoing message after close: ${message["method"] ?: message["id"]}" }
        }
    }

    private fun dispatch(line: String) {
        val message = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (message == null) {
            logger.warn { "[ACP] unparseable message (${line.length} chars)" }
            sendError(JsonNull, AcpError.PARSE_ERROR, "Parse error")
            return
        }

        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        when {
            method != null && id != null && id !is JsonNull -> scope.launch { serve(id, method, params) }
            method != null -> scope.launch {
                try {
                    notificationHandler(method, params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] notification $method failed" }
                }
            }
            id != null && id !is JsonNull -> {
                val waiter = pendingRequests[id.toString()]
                if (waiter == null) {
                    logger.warn { "[ACP] response for unknown request id=$id" }
                } else {
                    waiter.complete(message)
                }
            }
            else -> sendError(JsonNull, AcpError.INVALID_REQUEST, "Invalid request")
        }
    }

    private suspend fun serve(id: JsonElement, method: String, params: JsonObject) {
        try {
            val result = requestHandler(method, params)
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            })
        } catch (e: AcpError) {
            sendError(id, e.code, e.message ?: "error")
        } catch (e: CancellationException) {
            sendError(id, AcpError.INTERNAL_ERROR, "Request cancelled")
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ACP] $method failed" }
            sendError(id, AcpError.INTERNAL_ERROR, e.message ?: e::class.simpleName ?: "Internal error")
        }
    }

    private fun sendError(id: JsonElement, code: Int, message: String) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
