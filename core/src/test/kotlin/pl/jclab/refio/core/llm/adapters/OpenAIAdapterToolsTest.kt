package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.tools.base.ToolSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Native function-calling tests for [OpenAIAdapter].
 *
 *  - Chat Completions tools array: `type=function`, nested `function.{name,description,parameters}`.
 *  - Responses API tools array: flat `{type, name, description, parameters, strict}`.
 *  - `parseNativeOpenAIToolCalls` extracts `id`, `function.name`, `function.arguments` and
 *    preserves the OpenAI-issued id (unlike Ollama, which has to synthesize one).
 *  - Tool name normalization is applied so wrapper/proxy renames don't leak through.
 */
class OpenAIAdapterToolsTest {

    private val adapter = OpenAIAdapter(model = "gpt-4o-mini")

    private val readFileSchema = ToolSchema(
        name = "read_file",
        description = "Read a file from disk",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string")
            ),
            "required" to listOf("path"),
            "additionalProperties" to false
        )
    )

    @Test
    fun `chat completions tools array nests function block`() {
        val arr = adapter.buildOpenAIToolsArray(listOf(readFileSchema))
        assertEquals(1, arr.size)
        assertEquals("function", arr[0]["type"])

        @Suppress("UNCHECKED_CAST")
        val fn = arr[0]["function"] as Map<String, Any>
        assertEquals("read_file", fn["name"])
        assertEquals("Read a file from disk", fn["description"])
        assertNotNull(fn["parameters"])
    }

    @Test
    fun `responses api tools array is flat with strict flag`() {
        val arr = adapter.buildResponsesToolsArray(listOf(readFileSchema))
        assertEquals(1, arr.size)
        val tool = arr[0]
        assertEquals("function", tool["type"])
        assertEquals("read_file", tool["name"])
        assertEquals("Read a file from disk", tool["description"])
        assertNotNull(tool["parameters"])
        assertTrue(tool.containsKey("strict"), "responses API requires explicit strict flag")
    }

    @Test
    fun `parse extracts id name and arguments string`() {
        val raw = listOf(
            mapOf(
                "id" to "call_abc123",
                "type" to "function",
                "function" to mapOf(
                    "name" to "read_file",
                    "arguments" to "{\"path\":\"/tmp/a.txt\"}"
                )
            )
        )

        val parsed = adapter.parseNativeOpenAIToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("call_abc123", parsed[0].id)
        assertEquals("read_file", parsed[0].name)
        assertEquals("{\"path\":\"/tmp/a.txt\"}", parsed[0].argumentsJson)
    }

    @Test
    fun `parse defaults arguments to empty object when missing`() {
        val raw = listOf(
            mapOf(
                "id" to "call_xyz",
                "function" to mapOf("name" to "list_dir")
            )
        )

        val parsed = adapter.parseNativeOpenAIToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("{}", parsed[0].argumentsJson)
    }

    @Test
    fun `parse skips entries missing id or function or name`() {
        val raw = listOf<Map<String, Any?>>(
            mapOf("function" to mapOf("name" to "no_id", "arguments" to "{}")), // missing id
            mapOf("id" to "call_1"), // missing function block
            mapOf(
                "id" to "call_2",
                "function" to mapOf("arguments" to "{}") // missing name
            ),
            mapOf(
                "id" to "call_3",
                "function" to mapOf("name" to "valid", "arguments" to "{}")
            )
        )

        val parsed = adapter.parseNativeOpenAIToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("call_3", parsed[0].id)
    }

    @Test
    fun `parse returns empty list when input is null or wrong type`() {
        assertTrue(adapter.parseNativeOpenAIToolCalls(null).isEmpty())
        assertTrue(adapter.parseNativeOpenAIToolCalls("not a list").isEmpty())
        assertTrue(adapter.parseNativeOpenAIToolCalls(emptyList<Map<String, Any?>>()).isEmpty())
    }

    @Test
    fun `parse preserves order of multiple tool calls`() {
        val raw = listOf(
            mapOf("id" to "c1", "function" to mapOf("name" to "first", "arguments" to "{}")),
            mapOf("id" to "c2", "function" to mapOf("name" to "second", "arguments" to "{}")),
            mapOf("id" to "c3", "function" to mapOf("name" to "third", "arguments" to "{}"))
        )

        val parsed = adapter.parseNativeOpenAIToolCalls(raw)

        assertEquals(listOf("c1", "c2", "c3"), parsed.map { it.id })
        assertEquals(listOf("first", "second", "third"), parsed.map { it.name })
    }
}
