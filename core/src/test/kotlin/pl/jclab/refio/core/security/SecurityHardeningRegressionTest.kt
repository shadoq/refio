package pl.jclab.refio.core.security

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.mcp.MCPConnection
import pl.jclab.refio.core.context.mcp.MCPHttpTransport
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.context.mcp.MCPToolDefinition
import pl.jclab.refio.core.context.mcp.MCPToolResult
import pl.jclab.refio.core.context.mcp.MCPToolWrapper
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ProcessManager
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.implementations.CodeIntelligenceTool
import pl.jclab.refio.core.tools.implementations.HttpRequestTool
import pl.jclab.refio.core.tools.implementations.MonitorProcessTool
import java.net.InetAddress
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityHardeningRegressionTest {

    @Test
    fun `LLMClient enforces no-egress from NetworkPolicy even when caller omits noEgressEnabled`() = runBlocking {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(configService)
        val client = LLMClient(configService = configService, networkPolicy = policy)

        assertFailsWith<NoEgressViolationException> {
            client.complete(
                provider = "openai",
                model = "gpt-4o-mini",
                messages = listOf(LLMMessage(role = "user", content = "hi"))
                // noEgressEnabled intentionally omitted (defaults to false)
            )
        }
        Unit
    }

    @Test
    fun `CodeIntelligenceTool blocks get_diagnostics in PLAN mode`(@TempDir tempDir: Path) = runBlocking {
        val tool = CodeIntelligenceTool(PathSandbox(tempDir))
        val result = tool.execute(
            mapOf(
                "action" to "get_diagnostics",
                "language" to "kotlin",
                ToolInternalParams.MODE to "PLAN"
            )
        )
        assertFalse(result.success)
        assertTrue(result.error?.contains("disabled in PLAN mode", ignoreCase = true) == true)
    }

    @Test
    fun `MonitorProcessTool blocks action stop in PLAN mode`() = runBlocking {
        val pm = ProcessManager()
        try {
            val tool = MonitorProcessTool(pm)
            val result = tool.execute(
                mapOf(
                    "process_id" to "proc-1",
                    "action" to "stop",
                    ToolInternalParams.MODE to "PLAN"
                )
            )
            assertFalse(result.success)
            assertTrue(result.error?.contains("disabled in PLAN mode", ignoreCase = true) == true)
        } finally {
            pm.close()
        }
    }

    @Test
    fun `MCPToolWrapper strips internal Refio metadata params before calling remote MCP server`() = runBlocking {
        val connection = mockk<MCPConnection>()
        every { connection.serverId } returns "srv"
        coEvery { connection.callTool(any(), any()) } returns MCPToolResult(isError = false, content = emptyList())

        val wrapper = MCPToolWrapper(
            mcpConnection = connection,
            toolDefinition = MCPToolDefinition(name = "search", description = "Search", inputSchema = emptyMap()),
            toolMode = ToolMode.READ_ONLY
        )

        wrapper.execute(
            mapOf(
                "query" to "kotlin",
                ToolInternalParams.TASK_ID to "task-1",
                ToolInternalParams.MODE to "AGENT",
                ToolInternalParams.SESSION_ID to "sess-1",
                ToolInternalParams.SUBTASK_ID to "sub-1"
            )
        )

        coVerify(exactly = 1) {
            connection.callTool("search", mapOf("query" to "kotlin"))
        }
    }

    @Test
    fun `MCPHttpTransport enforces no-egress against remote servers and rejects cross-origin SSE endpoint`() = runBlocking {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(
            configService = configService,
            resolveAll = { arrayOf(InetAddress.getByName("93.184.216.34")) }
        )

        val remoteConfig = MCPServerConfig(
            id = "remote-mcp",
            type = MCPServerType.HTTP_STREAMABLE,
            url = "https://mcp.example.com/rpc"
        )
        val blockedTransport = MCPHttpTransport(
            config = remoteConfig,
            onMessage = {},
            onError = {},
            networkPolicy = policy
        )
        try {
            assertFailsWith<NoEgressViolationException> {
                blockedTransport.connect()
            }
        } finally {
            blockedTransport.disconnect()
        }

        // Cross-origin SSE endpoint must be rejected (postUrl must stay null, onError invoked).
        val errors = mutableListOf<Exception>()
        val sseEngine = MockEngine {
            respond(
                content = "event: endpoint\ndata: http://169.254.169.254/latest/meta-data\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            )
        }
        val sseConfig = MCPServerConfig(
            id = "sse-mcp",
            type = MCPServerType.HTTP_SSE,
            url = "https://mcp.example.com/sse"
        )
        val sseTransport = MCPHttpTransport(
            config = sseConfig,
            onMessage = {},
            onError = { errors.add(it) },
            engine = sseEngine,
            endpointWaitMs = 1_000L,
            endpointGraceMs = 300L
        )
        try {
            sseTransport.connect()
            assertNull(sseTransport.postUrl, "Cross-origin SSE endpoint must not overwrite postUrl")
            assertTrue(errors.any { it is SecurityException })
        } finally {
            sseTransport.disconnect()
        }
    }

    @Test
    fun `HttpRequestTool strips sensitive headers on cross-origin helper and rejects body_file without sandbox`() = runBlocking {
        assertFalse(HttpRequestTool.isSameOrigin("https://api.example.com/v1", "https://evil.example.net/v1"))
        assertTrue(HttpRequestTool.isSameOrigin("https://api.example.com:443/v1", "https://api.example.com/v2"))

        val stripped = HttpRequestTool.stripSensitiveHeaders(
            mapOf(
                "Authorization" to "Bearer secret",
                "cookie" to "sid=123",
                "Accept" to "application/json"
            )
        )
        assertEquals(mapOf("Accept" to "application/json"), stripped)

        val toolWithoutSandbox = HttpRequestTool(sandbox = null, urlPolicy = UrlPolicy(allowLoopback = { true }))
        val res = toolWithoutSandbox.execute(
            mapOf(
                "url" to "http://127.0.0.1:9999/upload",
                "method" to "POST",
                "body_file" to "secret.txt"
            )
        )
        assertFalse(res.success)
        assertTrue(res.error?.contains("sandbox", ignoreCase = true) == true)
    }
}
