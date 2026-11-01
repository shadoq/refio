package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonObject
import io.ktor.client.engine.mock.MockEngine
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-world MCP server behaviour the connection has to survive: a session that the server forgets,
 * a settings-screen connection test against a server that frames its answer as an event stream,
 * and a legacy SSE server that never announces an endpoint.
 */
class MCPTransportGapsTest {

    private val gson = GsonInstance.gson

    private val initResult =
        """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"stub"}}"""
    private val toolsResult = """{"tools":[{"name":"query-docs","description":"d","inputSchema":{"type":"object"}}]}"""

    private fun rpc(request: HttpRequestData): JsonObject {
        val text = (request.body as? TextContent)?.text
            ?: String((request.body as OutgoingContent.ByteArrayContent).bytes())
        return gson.fromJson(text, JsonObject::class.java)
    }

    @Test
    fun `a server that forgot the session gets a fresh initialize and the request is retried once`() {
        var sessionsIssued = 0
        var expiredOnce = false
        val toolsListSessions = Collections.synchronizedList(mutableListOf<String?>())
        val engine = MockEngine { request ->
            val body = rpc(request)
            val method = body.get("method").asString
            if (!body.has("id")) return@MockEngine respond("", HttpStatusCode.Accepted)
            val id = body.get("id").asLong
            when (method) {
                MCPMethods.INITIALIZE -> {
                    sessionsIssued++
                    respond(
                        """{"jsonrpc":"2.0","id":$id,"result":$initResult}""",
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            "Mcp-Session-Id" to listOf("sess-$sessionsIssued")
                        )
                    )
                }
                else -> {
                    toolsListSessions += request.headers["Mcp-Session-Id"]
                    // The first list during connect works; the next one finds the session gone.
                    if (toolsListSessions.size == 2 && !expiredOnce) {
                        expiredOnce = true
                        respond("session not found", HttpStatusCode.NotFound)
                    } else {
                        respond(
                            """{"jsonrpc":"2.0","id":$id,"result":$toolsResult}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "expiring", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
            httpEngine = engine
        )

        runBlocking {
            connection.connect()
            val tools = connection.refreshTools()

            assertEquals(listOf("query-docs"), tools.map { it.name })
        }

        assertEquals(2, sessionsIssued, "the expired session must be replaced by exactly one new initialize")
        assertEquals(listOf("sess-1", "sess-1", "sess-2"), toolsListSessions.toList())
        connection.disconnect()
    }

    @Test
    fun `a 404 on a server that never issued a session is an error and not retried`() {
        var initializes = 0
        val engine = MockEngine { request ->
            val body = rpc(request)
            if (!body.has("id")) return@MockEngine respond("", HttpStatusCode.Accepted)
            if (body.get("method").asString == MCPMethods.INITIALIZE) {
                initializes++
                respond(
                    """{"jsonrpc":"2.0","id":${body.get("id").asLong},"result":$initResult}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("nope", HttpStatusCode.NotFound)
            }
        }
        val connection = MCPConnection(
            MCPServerConfig(id = "no-session", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
            httpEngine = engine
        )

        assertFailsWith<MCPTransportException> { runBlocking { connection.connect() } }

        assertEquals(1, initializes)
    }

    @Test
    fun `the connection test reads an initialize answer framed as an event stream`() {
        val engine = MockEngine { request ->
            val body = rpc(request)
            if (!body.has("id")) return@MockEngine respond("", HttpStatusCode.Accepted)
            respond(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":${body.get("id").asLong},\"result\":$initResult}\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val result = runBlocking {
            MCPTestRunner.test(
                MCPServerConfig(id = "t", type = MCPServerType.HTTP_STREAMABLE, url = "http://stub/mcp"),
                engine = engine
            )
        }

        assertNull(result.errorMessage)
        val response = assertNotNull(result.responseDetails)
        assertEquals("2025-06-18", gson.fromJson(response, JsonObject::class.java).getAsJsonObject("result").get("protocolVersion").asString)
    }

    @Test
    fun `the connection test of an SSE server takes the answer from the stream when the POST is only acknowledged`() {
        val stream = ByteChannel(autoFlush = true)
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                stream.writeStringUtf8("event: endpoint\ndata: /messages\n\n")
                return@MockEngine respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            val body = rpc(request)
            if (body.has("id")) {
                stream.writeStringUtf8(
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":${body.get("id").asLong},\"result\":$initResult}\n\n"
                )
            }
            respond("", HttpStatusCode.Accepted)
        }

        try {
            val result = runBlocking {
                MCPTestRunner.test(
                    MCPServerConfig(id = "t", type = MCPServerType.HTTP_SSE, url = "http://stub/sse", timeout = 5_000),
                    engine = engine
                )
            }

            assertNull(result.errorMessage)
            assertTrue(result.responseDetails?.contains("protocolVersion") == true, "got: ${result.responseDetails}")
        } finally {
            stream.close(null)
        }
    }

    @Test
    fun `a legacy SSE server without an endpoint event connects in well under the old two second wait`() {
        val stream = ByteChannel(autoFlush = true)
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                return@MockEngine respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            val body = rpc(request)
            if (!body.has("id")) return@MockEngine respond("", HttpStatusCode.Accepted)
            val result = if (body.get("method").asString == MCPMethods.INITIALIZE) initResult else toolsResult
            respond(
                """{"jsonrpc":"2.0","id":${body.get("id").asLong},"result":$result}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val transport = MCPHttpTransport(
            config = MCPServerConfig(id = "legacy", type = MCPServerType.HTTP_SSE, url = "http://stub/sse"),
            onMessage = { },
            onError = { },
            engine = engine
        )

        try {
            val started = System.nanoTime()
            runBlocking { transport.connect() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 1_200, "connect waited ${elapsedMs}ms for an endpoint that never comes")
            assertNull(transport.postUrl)
        } finally {
            transport.disconnect()
            stream.close(null)
        }
    }
}
