package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.tools.base.ToolSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Native function-calling tests for [AnthropicAdapter].
 *
 * Anthropic's wire format differs from OpenAI's in two key places:
 *  - Tools array: flat `{name, description, input_schema}` (no `function` wrapper, no `parameters`).
 *  - Tool calls come back as `content` array items with `type=tool_use`, where args live in `input`
 *    as a parsed object (not a JSON string).
 */
class AnthropicAdapterToolsTest {

    private val adapter = AnthropicAdapter(model = "claude-3-5-sonnet-20241022")

    private val readFileSchema = ToolSchema(
        name = "read_file",
        description = "Read a file from disk",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string")
            ),
            "required" to listOf("path")
        )
    )

    @Test
    fun `tools array uses Anthropic flat shape with input_schema`() {
        val arr = adapter.buildAnthropicToolsArray(listOf(readFileSchema))
        assertEquals(1, arr.size)
        val tool = arr[0]
        assertEquals("read_file", tool["name"])
        assertEquals("Read a file from disk", tool["description"])
        assertNotNull(tool["input_schema"], "Anthropic uses input_schema (not parameters)")
        assertFalse(tool.containsKey("function"), "Anthropic does not wrap tools in function block")
        assertFalse(tool.containsKey("parameters"), "Anthropic uses input_schema instead of parameters")
        assertFalse(tool.containsKey("type"), "Anthropic does not require type=function")
    }

    @Test
    fun `parse extracts tool_use blocks with id name and input`() {
        val contentBlocks = listOf(
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_01ABC",
                "name" to "read_file",
                "input" to mapOf("path" to "/tmp/a.txt")
            )
        )

        val parsed = adapter.parseNativeAnthropicToolCalls(contentBlocks)

        assertEquals(1, parsed.size)
        assertEquals("toolu_01ABC", parsed[0].id)
        assertEquals("read_file", parsed[0].name)
        assertTrue(parsed[0].argumentsJson.contains("\"path\""))
        assertTrue(parsed[0].argumentsJson.contains("/tmp/a.txt"))
    }

    @Test
    fun `parse ignores non tool_use content blocks like text`() {
        val contentBlocks = listOf(
            mapOf<String, Any?>(
                "type" to "text",
                "text" to "Let me read that file."
            ),
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_1",
                "name" to "read_file",
                "input" to mapOf("path" to "/x")
            ),
            mapOf<String, Any?>(
                "type" to "thinking",
                "thinking" to "Should I delegate?"
            )
        )

        val parsed = adapter.parseNativeAnthropicToolCalls(contentBlocks)

        assertEquals(1, parsed.size)
        assertEquals("toolu_1", parsed[0].id)
    }

    @Test
    fun `parse defaults to empty json object for null input`() {
        val contentBlocks = listOf(
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_2",
                "name" to "list_models",
                "input" to null
            )
        )

        val parsed = adapter.parseNativeAnthropicToolCalls(contentBlocks)

        assertEquals(1, parsed.size)
        assertEquals("{}", parsed[0].argumentsJson)
    }

    @Test
    fun `parse skips tool_use blocks missing id or name`() {
        val contentBlocks = listOf(
            mapOf<String, Any?>("type" to "tool_use", "name" to "x", "input" to mapOf("a" to 1)), // no id
            mapOf<String, Any?>("type" to "tool_use", "id" to "toolu_3", "input" to mapOf("a" to 1)), // no name
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_ok",
                "name" to "ok",
                "input" to mapOf("a" to 1)
            )
        )

        val parsed = adapter.parseNativeAnthropicToolCalls(contentBlocks)

        assertEquals(1, parsed.size)
        assertEquals("toolu_ok", parsed[0].id)
    }

    @Test
    fun `parse handles multiple parallel tool_use blocks preserving order`() {
        val contentBlocks = listOf(
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_a",
                "name" to "first",
                "input" to mapOf("v" to 1)
            ),
            mapOf<String, Any?>(
                "type" to "tool_use",
                "id" to "toolu_b",
                "name" to "second",
                "input" to mapOf("v" to 2)
            )
        )

        val parsed = adapter.parseNativeAnthropicToolCalls(contentBlocks)

        assertEquals(listOf("toolu_a", "toolu_b"), parsed.map { it.id })
        assertEquals(listOf("first", "second"), parsed.map { it.name })
    }

    @Test
    fun `parse returns empty list for empty content blocks`() {
        assertTrue(adapter.parseNativeAnthropicToolCalls(emptyList()).isEmpty())
    }
}
