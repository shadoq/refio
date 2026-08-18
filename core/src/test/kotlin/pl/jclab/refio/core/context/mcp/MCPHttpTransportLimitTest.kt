package pl.jclab.refio.core.context.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A remote MCP server is a trust boundary: its answer must not be read into memory unbounded.
 */
class MCPHttpTransportLimitTest {

    @Test
    fun `an oversized response fails with a readable error instead of being buffered whole`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        server.createContext("/mcp") { exchange ->
            // Length 0 = chunked, so the size is only known while reading.
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { body ->
                    repeat(12 * 16) { body.write(chunk) }
                }
            }
            exchange.close()
        }
        server.start()

        val transport = MCPHttpTransport(
            config = MCPServerConfig(
                id = "flooding",
                type = MCPServerType.HTTP_STREAMABLE,
                url = "http://127.0.0.1:${server.address.port}/mcp",
                timeout = 30_000
            ),
            onMessage = { },
            onError = { }
        )

        try {
            val failure = assertFailsWith<MCPTransportException> {
                runBlocking { transport.request("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""") }
            }
            assertTrue(
                failure.message?.contains("limit") == true && failure.message?.contains("flooding") == true,
                "the error must name the server and the limit, got: ${failure.message}"
            )
        } finally {
            transport.disconnect()
            server.stop(0)
        }
    }
}
