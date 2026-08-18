package pl.jclab.refio.core.context.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import pl.jclab.refio.core.services.context.McpContextLoader
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Which tools a CONTEXT-mode MCP server is allowed to run while context is being assembled.
 *
 * These calls carry the user's raw prompt and bypass the tool permission and approval layers,
 * so they must stay limited to what the user configured or to a server declared read-only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpContextLoaderToolSelectionTest {

    private val defaultFactory = MCPManager.connectionFactory
    private val usedProjectIds = mutableListOf<String>()
    private lateinit var projectRoot: Path
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
    fun `only the configured context tool runs, never the rest of the server`() {
        val connection = connect(
            serverConfig(accessMode = MCPAccessMode.READ, contextToolName = "search_docs")
        )

        runBlocking { McpContextLoader().loadMcpResources(projectRoot, "how do I paginate") }

        coVerify(exactly = 1) { connection.callTool("search_docs", any()) }
        coVerify(exactly = 0) { connection.callTool("delete_docs", any()) }
    }

    @Test
    fun `a read-write server with no configured context tool runs nothing`() {
        val connection = connect(
            serverConfig(accessMode = MCPAccessMode.READ_WRITE, contextToolName = null)
        )

        val resources = runBlocking { McpContextLoader().loadMcpResources(projectRoot, "how do I paginate") }

        coVerify(exactly = 0) { connection.callTool(any(), any()) }
        assertTrue(resources.isEmpty(), "nothing may be pulled into context by guessing which tool is safe")
    }

    @Test
    fun `a server declared read-only still contributes its tools`() {
        val connection = connect(
            serverConfig(accessMode = MCPAccessMode.READ, contextToolName = null)
        )

        runBlocking { McpContextLoader().loadMcpResources(projectRoot, "how do I paginate") }

        coVerify(exactly = 1) { connection.callTool("search_docs", any()) }
        coVerify(exactly = 1) { connection.callTool("delete_docs", any()) }
    }

    private fun serverConfig(accessMode: MCPAccessMode, contextToolName: String?) = MCPServerConfig(
        id = SERVER_ID,
        type = MCPServerType.STDIO,
        command = "unused-in-this-test",
        enabled = false,
        accessMode = accessMode,
        toolsEnabled = true,
        toolsExposureMode = MCPToolsExposureMode.CONTEXT,
        contextToolName = contextToolName
    )

    /** Registers a faked, connected server for a fresh project and returns its connection. */
    private fun connect(config: MCPServerConfig): MCPConnection {
        projectRoot = Files.createTempDirectory("mcp-context-project")
        val projectId = ProjectIdGenerator.generate(projectRoot).also { usedProjectIds.add(it) }

        val connection = mockk<MCPConnection>(relaxed = true)
        every { connection.serverId } returns config.id
        coEvery { connection.connect() } returns Unit
        every { connection.getStatus() } returns MCPServerStatus.CONNECTED
        every { connection.supportsResources() } returns false
        every { connection.supportsTools() } returns true
        every { connection.getCachedTools() } returns listOf(
            MCPToolDefinition(name = "search_docs", inputSchema = mapOf("properties" to mapOf("query" to mapOf("type" to "string")))),
            MCPToolDefinition(name = "delete_docs", inputSchema = mapOf("properties" to mapOf("query" to mapOf("type" to "string"))))
        )
        coEvery { connection.callTool(any(), any()) } returns MCPToolResult(
            content = listOf(MCPContentPart(text = "result"))
        )

        MCPManager.connectionFactory = { connection }
        MCPManager.initialize(projectId, ToolRegistry(), listOf(config))
        runBlocking { MCPManager.connectServer(projectId, config.id) }
        return connection
    }

    private companion object {
        const val SERVER_ID = "docs-srv"
    }
}
