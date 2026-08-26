package pl.jclab.refio.core.context.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.testutil.TestDatabase
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How the manager keeps track of connections: one connection per server, tools that follow a
 * recreated project registry, and a failed connect that stays visible.
 *
 * Connections are faked - the point here is the manager's bookkeeping, not the MCP protocol.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MCPManagerConnectionStateTest {

    private val defaultFactory = MCPManager.connectionFactory
    private val usedProjectIds = mutableListOf<String>()
    private lateinit var database: TestDatabase.SharedInMemoryDb

    /**
     * [MCPManager.initialize] reads the stored servers, so some database has to answer. It gets its
     * own: the shared [pl.jclab.refio.core.db.DatabaseFactory] is process-wide and whichever test
     * initialized it first decides where it points - including at a temp file that test already
     * deleted.
     */
    @BeforeAll
    fun initDatabase() {
        database = TestDatabase.createSharedInMemory()
    }

    /** Unregistered on the way out, so this database does not become the next test's dead default. */
    @AfterAll
    fun closeDatabase() {
        TransactionManager.closeAndUnregister(database.database)
        database.keepAlive.close()
    }

    @AfterEach
    fun cleanup() {
        usedProjectIds.forEach { MCPManager.shutdown(it) }
        usedProjectIds.clear()
        MCPManager.connectionFactory = defaultFactory
    }

    @Test
    fun `a recreated project registry gets the tools of servers that are already connected`() {
        val projectId = newProjectId()
        MCPManager.connectionFactory = { config -> fakeConnection(config) }

        val firstRegistry = ToolRegistry()
        MCPManager.initialize(projectId, firstRegistry, listOf(serverConfig("tools-srv")))
        waitUntil { firstRegistry.hasTool(TOOL_NAME) }
        assertTrue(firstRegistry.hasTool(TOOL_NAME), "test setup: the first registry must hold the tool")

        val secondRegistry = ToolRegistry()
        MCPManager.setToolRegistry(projectId, secondRegistry)

        waitUntil { secondRegistry.hasTool(TOOL_NAME) }
        assertTrue(
            secondRegistry.hasTool(TOOL_NAME),
            "a registry swap must re-register the tools of live connections"
        )
        assertEquals(
            1,
            MCPManager.getConnectionInfo(projectId).single().toolCount,
            "the reported tool count must match what the registry really holds"
        )
    }

    @Test
    fun `two callers racing to connect the same server start it once`() {
        val projectId = newProjectId()
        val created = AtomicInteger()
        MCPManager.connectionFactory = { config ->
            created.incrementAndGet()
            fakeConnection(config, connectDelayMs = 300)
        }
        MCPManager.initialize(projectId, ToolRegistry(), listOf(serverConfig("race-srv", enabled = false)))

        runBlocking {
            val first = async(Dispatchers.IO) { MCPManager.connectServer(projectId, "race-srv") }
            val second = async(Dispatchers.IO) { MCPManager.connectServer(projectId, "race-srv") }
            first.await()
            second.await()
        }

        assertEquals(1, created.get(), "the second caller must wait for the first, not start a second server")
        assertEquals(listOf("race-srv"), MCPManager.getConnectedServers(projectId))
    }

    @Test
    fun `a server whose handshake fails is reported as failed, not as still starting`() {
        val projectId = newProjectId()
        MCPManager.connectionFactory = { config ->
            fakeConnection(config, failWith = MCPTransportException("handshake refused"))
        }
        MCPManager.initialize(projectId, ToolRegistry(), listOf(serverConfig("broken-srv")))

        runBlocking {
            assertFailsWith<MCPTransportException> { MCPManager.connectServer(projectId, "broken-srv") }
        }

        assertEquals(MCPServerStatus.ERROR, MCPManager.getServerStatus(projectId, "broken-srv"))
        val info = MCPManager.getConnectionInfo(projectId).single()
        assertEquals(MCPServerStatus.ERROR, info.status)
        assertEquals("handshake refused", info.lastError, "the reason must reach the settings UI")
    }

    private fun newProjectId(): String = "test-${UUID.randomUUID()}".also { usedProjectIds.add(it) }

    private fun serverConfig(id: String, enabled: Boolean = true) = MCPServerConfig(
        id = id,
        type = MCPServerType.STDIO,
        command = "unused-in-this-test",
        enabled = enabled
    )

    private fun fakeConnection(
        config: MCPServerConfig,
        connectDelayMs: Long = 0,
        failWith: Exception? = null
    ): MCPConnection {
        val connection = mockk<MCPConnection>(relaxed = true)
        every { connection.serverId } returns config.id
        coEvery { connection.connect() } coAnswers {
            if (connectDelayMs > 0) {
                delay(connectDelayMs)
            }
            failWith?.let { throw it }
        }
        every { connection.getStatus() } returns MCPServerStatus.CONNECTED
        every { connection.getCapabilities() } returns MCPServerCapabilities(tools = true)
        every { connection.getCachedTools() } returns listOf(MCPToolDefinition(name = "echo"))
        return connection
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
    }

    private companion object {
        const val TOOL_NAME = "mcp_tools-srv_echo"
    }
}
