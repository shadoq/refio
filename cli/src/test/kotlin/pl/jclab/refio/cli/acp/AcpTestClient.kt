package pl.jclab.refio.cli.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Plays the IDE side of an ACP connection against an [AcpPeer] over in-memory pipes.
 * Writes raw lines into the agent's input and reads the agent's output one message at a time.
 */
class AcpTestClient : AutoCloseable {
    private val toAgent = PipedOutputStream()
    val agentInput = PipedInputStream(toAgent, 1 shl 16)
    private val fromAgentIn = PipedInputStream(1 shl 20)
    val agentOutput = PipedOutputStream(fromAgentIn)
    private val reader = fromAgentIn.bufferedReader()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val peer = AcpPeer(agentInput, agentOutput, scope)
    private var runJob: Job? = null

    fun start() {
        runJob = scope.launch { peer.run() }
    }

    fun sendRaw(line: String) {
        toAgent.write((line + "\n").toByteArray())
        toAgent.flush()
    }

    fun request(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = sendRaw(
        buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }.toString()
    )

    fun notification(method: String, params: JsonObject) = sendRaw(
        buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) }.toString()
    )

    fun respond(id: JsonElement, result: JsonObject) = sendRaw(
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()
    )

    suspend fun next(timeoutMs: Long = 10_000): JsonObject = withTimeout(timeoutMs) {
        val line = runInterruptible(Dispatchers.IO) { reader.readLine() } ?: error("agent closed its output")
        AcpPeer.json.parseToJsonElement(line).jsonObject
    }

    /** Reads messages until one matches; returns everything read, the match last. */
    suspend fun readUntil(predicate: (JsonObject) -> Boolean): List<JsonObject> {
        val seen = mutableListOf<JsonObject>()
        while (true) {
            val message = next()
            seen += message
            if (predicate(message)) return seen
        }
    }

    override fun close() {
        runCatching { toAgent.close() }
        scope.cancel()
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
fun JsonObject.isResponse(id: Int) = this["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } == id.toString() && "method" !in this
fun JsonObject.updateKind(): String? = if (str("method") == "session/update") obj("params").obj("update").str("sessionUpdate") else null
