package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * stdio framing under concurrency.
 *
 * Read-only MCP tools run in parallel in PLAN and AGENT mode, so several requests can hit the
 * same transport at once. One JSON-RPC message must still arrive as exactly one line - a message
 * split across lines, or two messages merged into one, makes the server drop both requests and
 * both callers wait out their timeout.
 */
class MCPStdioTransportFramingTest {

    @Test
    fun `concurrent sends keep one message per line`() {
        assumeTrue(isPosix(), "needs a POSIX shell to echo the framed messages back")

        val received = CopyOnWriteArrayList<String>()
        val transport = MCPStdioTransport(
            config = MCPServerConfig(id = "echo", type = MCPServerType.STDIO, command = "cat"),
            onMessage = { line -> received.add(line) },
            onError = { }
        )

        val senders = 8
        val perSender = 40
        val sent = (0 until senders).flatMap { sender ->
            (0 until perSender).map { index -> """{"jsonrpc":"2.0","id":$sender$index,"method":"tools/list"}""" }
        }

        try {
            runBlocking { transport.connect() }

            val start = CountDownLatch(1)
            val done = CountDownLatch(senders)
            (0 until senders).forEach { sender ->
                Thread {
                    start.await()
                    (0 until perSender).forEach { index ->
                        transport.send(sent[sender * perSender + index])
                    }
                    done.countDown()
                }.start()
            }
            start.countDown()
            done.await(10, TimeUnit.SECONDS)

            val deadline = System.currentTimeMillis() + 5_000
            while (received.size < sent.size && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            assertEquals(
                sent.sorted(),
                received.sorted(),
                "every message must come back whole and on its own line"
            )
        } finally {
            transport.disconnect()
        }
    }

    private fun isPosix(): Boolean = !System.getProperty("os.name").lowercase().contains("win")
}
