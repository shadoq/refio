package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failure-path lifecycle of a single MCP connection: what must be released when a handshake
 * fails, and what must happen to in-flight requests when the connection goes away.
 *
 * These are the paths that leaked a process or an HTTP client for the lifetime of the IDE.
 */
class MCPConnectionLifecycleTest {

    @Test
    fun `a handshake that never reaches the server releases the transport and reports the failure`() {
        val connection = MCPConnection(
            MCPServerConfig(
                id = "unreachable",
                type = MCPServerType.HTTP_STREAMABLE,
                url = "http://127.0.0.1:${closedPort()}/mcp",
                timeout = 3_000
            )
        )

        runBlocking { assertFailsWith<Exception> { connection.connect() } }

        assertFalse(connection.hasOpenTransport, "a failed connect must not keep the HTTP clients alive")
        assertEquals(MCPServerStatus.ERROR, connection.getStatus())
        assertNotNull(connection.lastError, "the reason must survive for the settings UI")
    }

    @Test
    fun `a request that cannot be sent leaves no pending entry behind`() {
        val connection = MCPConnection(
            MCPServerConfig(id = "not-connected", type = MCPServerType.STDIO, timeout = 1_000)
        )

        runBlocking { assertFailsWith<MCPTransportException> { connection.listTools() } }

        assertEquals(0, connection.pendingRequestCount, "a request that never left must not stay pending")
    }

    @Test
    fun `disconnecting wakes waiting requests instead of leaving them to time out`() {
        assumeTrue(isPosix(), "needs a POSIX shell to stand in for an MCP server")

        val connection = MCPConnection(
            MCPServerConfig(
                id = "one-shot",
                type = MCPServerType.STDIO,
                command = "sh",
                // Answers only the initialize request, then swallows everything else, so the
                // next request stays in flight until the connection is torn down.
                args = listOf(
                    "-c",
                    "IFS= read -r line; printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}\\n'; cat > /dev/null"
                ),
                timeout = 8_000
            )
        )

        // Own scope so the in-flight failure lands on await() instead of cancelling the test body.
        val worker = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            connection.connect()
            assertEquals(MCPServerStatus.CONNECTED, connection.getStatus())

            val inFlight = worker.async { connection.listTools() }
            waitUntil(2_000) { connection.pendingRequestCount == 1 }
            assertEquals(1, connection.pendingRequestCount, "test setup: the request must be in flight")

            val startedAt = System.currentTimeMillis()
            connection.disconnect()
            val failure = assertFailsWith<Exception> { inFlight.await() }
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue(elapsed < 3_000, "waiting request must fail on disconnect, not after the request timeout (took ${elapsed}ms)")
            assertTrue(
                failure.message?.contains("disconnect", ignoreCase = true) == true,
                "the caller must learn the server went away, got: ${failure.message}"
            )
            assertEquals(0, connection.pendingRequestCount)
        }
        worker.cancel()
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            delay(20)
        }
    }

    private fun isPosix(): Boolean = !System.getProperty("os.name").lowercase().contains("win")
}
