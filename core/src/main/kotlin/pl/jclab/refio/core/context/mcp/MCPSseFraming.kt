package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonObject
import pl.jclab.refio.core.utils.GsonInstance

/** One dispatched Server-Sent Event: its type (`message` when the server named none) and data. */
internal data class SseEvent(val event: String, val data: String)

/**
 * Line-by-line Server-Sent Events reader. Feed it lines as they arrive; a blank line dispatches
 * the event collected so far. Multi-line `data:` fields join with a newline, comments (`:`) and
 * unknown fields are ignored, as the SSE format prescribes.
 */
internal class SseEventReader {
    private var eventType: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            return dispatch()
        }
        if (line.startsWith(":")) {
            return null
        }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) {
            value = value.substring(1)
        }
        when (field) {
            "event" -> eventType = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Dispatches a trailing event that was not followed by a blank line. */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(eventType?.ifBlank { null } ?: "message", data.toString()) else null
        eventType = null
        data.clear()
        hasData = false
        return event
    }
}

/**
 * The MCP specification lets a server answer a POST with a `text/event-stream` body instead of a
 * plain JSON one. These helpers recognise that framing and pull the JSON-RPC response out of it.
 */
internal object MCPSseFraming {

    fun isEventStream(contentType: String?, body: String): Boolean {
        if (contentType?.lowercase()?.startsWith("text/event-stream") == true) {
            return true
        }
        val head = body.trimStart()
        return head.startsWith("event:") || head.startsWith("data:")
    }

    fun parse(raw: String): List<SseEvent> {
        val reader = SseEventReader()
        val events = raw.lines().mapNotNull { reader.feed(it.removeSuffix("\r")) }
        return events + listOfNotNull(reader.finish())
    }

    /**
     * The JSON-RPC response for [expectedId] among the `message` events of [raw]. A stream may also
     * carry server notifications or answers to other requests; when no id matches, the last
     * response in the stream is used, since a server that omits or rewrites ids still answered us.
     * Null when the stream holds no response at all.
     */
    fun selectResponse(raw: String, expectedId: Long): JsonObject? {
        val responses = parse(raw)
            .filter { it.event == "message" && it.data.isNotBlank() }
            .mapNotNull { runCatching { GsonInstance.gson.fromJson(it.data, JsonObject::class.java) }.getOrNull() }
            .filter { it.has("result") || it.has("error") }
        return responses.firstOrNull { idOf(it) == expectedId } ?: responses.lastOrNull()
    }

    private fun idOf(json: JsonObject): Long? =
        runCatching { json.get("id")?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()
}
