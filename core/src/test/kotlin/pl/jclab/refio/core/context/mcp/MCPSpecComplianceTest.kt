package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonObject
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.utils.GsonInstance
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server written to the MCP specification must connect, not only one that speaks Refio's own
 * dialect (a POST answered with a plain JSON body). The recorded answers below are the shapes the
 * specification prescribes; no network is involved.
 */
class MCPSpecComplianceTest {

    private val gson = GsonInstance.gson

    private val toolsListResult =
        """{"tools":[{"name":"query-docs","description":"Look up docs","inputSchema":{"type":"object"}}]}"""

    private fun resultFor(method: String?): String? = when (method) {
        MCPMethods.INITIALIZE ->
            """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"spec-stub"}}"""
        MCPMethods.TOOLS_LIST -> toolsListResult
        else -> null
    }

    private fun jsonRpc(request: HttpRequestData): JsonObject {
        val text = (request.body as? TextContent)?.text
            ?: String((request.body as OutgoingContent.ByteArrayContent).bytes())
        return gson.fromJson(text, JsonObject::class.java)
    }

    private fun responseFrame(id: Long, result: String) =
        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n"

    private fun MockRequestHandleScope.accepted() = respond("", HttpStatusCode.Accepted)

    @Test
    fun `a streamable HTTP server answering in event-stream frames connects and lists its tools`() {
        val engine = MockEngine { request ->
            val rpc = jsonRpc(request)
            if (!rpc.has("id")) return@MockEngine accepted()
            val id = rpc.get("id").asLong
            val method = rpc.get("method").asString
            respond(
                content = responseFrame(id, resultFor(method)!!),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "spec-http", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
            httpEngine = engine
        )

        runBlocking { connection.connect() }

        assertEquals(MCPServerStatus.CONNECTED, connection.getStatus())
        assertEquals(listOf("query-docs"), connection.getCachedTools().map { it.name })
        connection.disconnect()
    }

    @Test
    fun `an event stream carrying several messages yields the response to our request id`() {
        val body = buildString {
            append(": keep-alive comment\n\n")
            append("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n")
            append("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"other\":true}}\n\n")
            // A JSON value may be split over several data lines; they join with a newline.
            append("event: message\ndata: {\"jsonrpc\":\"2.0\",\n")
            append("data:  \"id\":7,\"result\":{\"mine\":true}}\n\n")
        }

        val picked = MCPSseFraming.selectResponse(body, expectedId = 7)

        assertNotNull(picked)
        assertEquals(7L, picked.get("id").asLong)
        assertTrue(picked.getAsJsonObject("result").get("mine").asBoolean)
    }

    @Test
    fun `a plain JSON body is not mistaken for an event stream`() {
        val raw = """{"jsonrpc":"2.0","id":1,"result":{}}"""

        assertTrue(!MCPSseFraming.isEventStream("application/json", raw))
        assertTrue(MCPSseFraming.isEventStream("text/event-stream; charset=utf-8", raw))
        assertTrue(MCPSseFraming.isEventStream(null, "event: message\ndata: {}\n\n"))
        assertTrue(MCPSseFraming.isEventStream(null, "data: {}\n\n"))
    }

    @Test
    fun `the session id from initialize is sent on every later request and never before it`() {
        val sessionHeaders = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val engine = MockEngine { request ->
            val rpc = jsonRpc(request)
            val method = rpc.get("method").asString
            sessionHeaders += method to request.headers["Mcp-Session-Id"]
            if (!rpc.has("id")) return@MockEngine accepted()
            val headers = if (method == MCPMethods.INITIALIZE) {
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "Mcp-Session-Id" to listOf("sess-42"))
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            }
            respond(
                content = """{"jsonrpc":"2.0","id":${rpc.get("id").asLong},"result":${resultFor(method)}}""",
                headers = headers
            )
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "session", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
            httpEngine = engine
        )

        runBlocking { connection.connect() }

        assertEquals(MCPServerStatus.CONNECTED, connection.getStatus())
        assertEquals(MCPMethods.INITIALIZE to null, sessionHeaders.first())
        val later = sessionHeaders.drop(1)
        assertTrue(later.isNotEmpty(), "initialize must be followed by more requests")
        later.forEach { (method, header) -> assertEquals("sess-42", header, "$method lost the session id") }
        connection.disconnect()
    }

    @Test
    fun `a reconnect starts without the previous session id`() {
        var issued = 0
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val engine = MockEngine { request ->
            val rpc = jsonRpc(request)
            seen += request.headers["Mcp-Session-Id"]
            val method = rpc.get("method").asString
            val headers = if (method == MCPMethods.INITIALIZE) {
                issued++
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "Mcp-Session-Id" to listOf("sess-$issued"))
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            }
            respond("""{"jsonrpc":"2.0","id":${rpc.get("id").asLong},"result":{}}""", headers = headers)
        }
        val transport = MCPHttpTransport(
            config = MCPServerConfig(id = "reconnect", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
            onMessage = { },
            onError = { },
            engine = engine
        )
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
        val listTools = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        runBlocking {
            transport.connect()
            transport.exchange(initialize, isInitialize = true)
            transport.exchange(listTools)
            transport.connect()
            transport.exchange(initialize, isInitialize = true)
            transport.exchange(listTools)
        }

        assertEquals(listOf(null, "sess-1", null, "sess-2"), seen.toList())
        transport.disconnect()
    }

    @Test
    fun `an SSE server announcing an endpoint receives the POSTs there and answers over the stream`() {
        val stream = ByteChannel(autoFlush = true)
        val postUrls = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                stream.writeStringUtf8("event: endpoint\ndata: /messages?sessionId=abc\n\n")
                return@MockEngine respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            postUrls += request.url.toString()
            val rpc = jsonRpc(request)
            if (rpc.has("id")) {
                stream.writeStringUtf8(responseFrame(rpc.get("id").asLong, resultFor(rpc.get("method").asString)!!))
            }
            accepted()
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "spec-sse", type = MCPServerType.HTTP_SSE, url = "http://stub:8932/sse", timeout = 5_000),
            httpEngine = engine
        )

        try {
            runBlocking { connection.connect() }

            assertEquals(MCPServerStatus.CONNECTED, connection.getStatus())
            assertEquals(listOf("query-docs"), connection.getCachedTools().map { it.name })
            assertTrue(postUrls.isNotEmpty())
            postUrls.forEach { assertEquals("http://stub:8932/messages?sessionId=abc", it) }
        } finally {
            connection.disconnect()
            stream.close(null)
        }
    }

    @Test
    fun `an SSE server without an endpoint event keeps receiving POSTs on its own URL`() {
        val stream = ByteChannel(autoFlush = true)
        val postUrls = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                return@MockEngine respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            postUrls += request.url.toString()
            val rpc = jsonRpc(request)
            if (!rpc.has("id")) return@MockEngine accepted()
            respond(
                """{"jsonrpc":"2.0","id":${rpc.get("id").asLong},"result":${resultFor(rpc.get("method").asString)}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "refio-sse", type = MCPServerType.HTTP_SSE, url = "http://stub:8932/", timeout = 5_000),
            httpEngine = engine
        )

        try {
            runBlocking { connection.connect() }

            assertEquals(MCPServerStatus.CONNECTED, connection.getStatus())
            assertEquals(listOf("query-docs"), connection.getCachedTools().map { it.name })
            postUrls.forEach { assertEquals("http://stub:8932/", it) }
        } finally {
            connection.disconnect()
            stream.close(null)
        }
    }

    @Test
    fun `an event stream without any JSON-RPC response is reported, not parsed as garbage`() {
        assertNull(MCPSseFraming.selectResponse("event: message\ndata: {\"method\":\"ping\"}\n\n", expectedId = 1))
    }
}
