package pl.jclab.refio.core.context.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.context.ContextProviderExtras
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connection stubs are built OUTSIDE runTest on purpose. MockK's first call in a JVM
 * pays a one-off ~3s of framework instrumentation, and this class holds no mocks anywhere
 * else, so its stubbing could be the call that pays it. Inside runTest that cost is charged
 * to the test coroutine's real-time watchdog (10s in kotlinx-coroutines-test 1.7.3), which
 * on a loaded machine fails the test with a misleading "the test coroutine is not
 * completing" instead of anything about the code under test.
 */
class MCPContextProviderTest {

    @Test
    fun `should list prompts for prompt query`() {
        val connection = mockk<MCPConnection>()
        every { connection.supportsPrompts() } returns true
        every { connection.supportsResources() } returns false
        every { connection.supportsTools() } returns false
        every { connection.getCachedPrompts() } returns listOf(
            MCPPrompt(
                name = "summarize",
                description = "Summarize text",
                arguments = listOf(MCPPromptArgument("text", required = true))
            )
        )

        val provider = MCPContextProvider(
            mcpServerId = "docs",
            mcpServerConfig = MCPServerConfig(id = "docs", type = MCPServerType.STDIO, promptsEnabled = true),
            connection = connection
        )

        runTest {
            val items = provider.getContextItems("prompt", ContextProviderExtras())

            assertEquals(1, items.size)
            assertTrue(items.first().content.contains("summarize"))
            assertTrue(items.first().content.contains("text*"))
        }
    }

    @Test
    fun `should subscribe while reading matched resource`() {
        val connection = mockk<MCPConnection>()
        every { connection.supportsPrompts() } returns false
        every { connection.supportsResources() } returns true
        every { connection.getCachedResources() } returns listOf(
            MCPResource(uri = "file://guide.md", name = "guide.md")
        )
        coEvery { connection.readResource("file://guide.md", true) } returns MCPResourceContent(
            uri = "file://guide.md",
            text = "Guide content"
        )

        val provider = MCPContextProvider(
            mcpServerId = "docs",
            mcpServerConfig = MCPServerConfig(id = "docs", type = MCPServerType.STDIO),
            connection = connection
        )

        runTest {
            val items = provider.getContextItems("guide", ContextProviderExtras())

            assertEquals(1, items.size)
            assertEquals("Guide content", items.first().content)
            coVerify(exactly = 1) { connection.readResource("file://guide.md", true) }
        }
    }
}
