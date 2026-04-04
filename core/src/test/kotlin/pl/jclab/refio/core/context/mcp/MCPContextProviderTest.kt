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

class MCPContextProviderTest {

    @Test
    fun `should list prompts for prompt query`() = runTest {
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

        val items = provider.getContextItems("prompt", ContextProviderExtras())

        assertEquals(1, items.size)
        assertTrue(items.first().content.contains("summarize"))
        assertTrue(items.first().content.contains("text*"))
    }

    @Test
    fun `should subscribe while reading matched resource`() = runTest {
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

        val items = provider.getContextItems("guide", ContextProviderExtras())

        assertEquals(1, items.size)
        assertEquals("Guide content", items.first().content)
        coVerify(exactly = 1) { connection.readResource("file://guide.md", true) }
    }
}
