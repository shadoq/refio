package pl.jclab.refio.core.context.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCPConnectionTest {

    private val connection = MCPConnection(
        MCPServerConfig(
            id = "test",
            type = MCPServerType.STDIO,
            promptsEnabled = true
        )
    )

    @Test
    fun `should parse prompts list`() {
        val prompts = connection.parsePromptsList(
            mapOf(
                "prompts" to listOf(
                    mapOf(
                        "name" to "summarize",
                        "description" to "Summarize content",
                        "arguments" to listOf(
                            mapOf("name" to "text", "required" to true)
                        )
                    )
                )
            )
        )

        assertEquals(1, prompts.size)
        assertEquals("summarize", prompts.first().name)
        assertEquals("text", prompts.first().arguments.first().name)
        assertTrue(prompts.first().arguments.first().required)
    }

    @Test
    fun `should parse prompt result with text and image parts`() {
        val result = connection.parsePromptResult(
            result = mapOf(
                "name" to "review",
                "description" to "Review screenshot",
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to "Inspect this image"),
                            mapOf("type" to "image", "mimeType" to "image/png", "blob" to "Zm9v")
                        )
                    )
                )
            ),
            fallbackName = "review"
        )

        assertEquals("review", result.name)
        assertEquals(1, result.messages.size)
        assertEquals(2, result.messages.first().content.size)
        assertEquals("Inspect this image", result.messages.first().content.first().text)
        assertEquals("image/png", result.messages.first().content.last().mimeType)
    }
}
