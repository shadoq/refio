package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.tools.base.ToolSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Native function-calling regression tests for [OllamaAdapter].
 *
 * Covers two halves of the contract:
 *  - Request side: tool schemas serialize into the request body in the shape Ollama expects.
 *  - Response side: `message.tool_calls` is extracted and mapped to [pl.jclab.refio.core.llm.NativeToolCall],
 *    handling arguments as both inline JSON strings and pre-parsed maps.
 *
 * No live network — pure unit tests against internal helpers.
 */
class OllamaAdapterToolsTest {

    private val adapter = OllamaAdapter(model = "qwen3.5:35b")

    private val sampleMessages = listOf(
        mapOf("role" to "system", "content" to "You are a helpful agent."),
        mapOf("role" to "user", "content" to "Read /tmp/foo.txt")
    )

    private val readFileSchema = ToolSchema(
        name = "read_file",
        description = "Read a file from disk",
        parametersJsonSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Absolute path")
            ),
            "required" to listOf("path")
        )
    )

    @Test
    fun `tools key is omitted when no tools are supplied`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 1024,
            temperature = 0.0,
            tools = null
        )
        assertFalse(body.containsKey("tools"), "tools key must be absent when no tools given")
    }

    @Test
    fun `tools key is omitted when empty list supplied`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 1024,
            temperature = 0.0,
            tools = emptyList()
        )
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun `tool schemas are serialized in Ollama's type=function shape`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = false,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 1024,
            temperature = 0.0,
            tools = listOf(readFileSchema)
        )

        @Suppress("UNCHECKED_CAST")
        val tools = body["tools"] as? List<Map<String, Any>>
        assertNotNull(tools, "tools array must be present")
        assertEquals(1, tools.size)
        val toolEntry = tools[0]
        assertEquals("function", toolEntry["type"])

        @Suppress("UNCHECKED_CAST")
        val function = toolEntry["function"] as Map<String, Any>
        assertEquals("read_file", function["name"])
        assertEquals("Read a file from disk", function["description"])
        assertEquals(readFileSchema.parametersJsonSchema, function["parameters"])
    }

    @Test
    fun `request can carry tools and json mode together`() {
        val body = adapter.buildOllamaRequestBody(
            ollamaMessages = sampleMessages,
            jsonMode = true,
            thinkingRequested = false,
            streaming = false,
            maxTokens = 1024,
            temperature = 0.0,
            tools = listOf(readFileSchema)
        )
        assertEquals("json", body["format"])
        assertTrue(body.containsKey("tools"))
    }

    @Test
    fun `parseNativeOllamaToolCalls handles arguments as inline json string`() {
        val raw = listOf(
            mapOf(
                "function" to mapOf(
                    "name" to "read_file",
                    "arguments" to "{\"path\":\"/tmp/foo.txt\"}"
                )
            )
        )

        val parsed = adapter.parseNativeOllamaToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("read_file", parsed[0].name)
        assertEquals("{\"path\":\"/tmp/foo.txt\"}", parsed[0].argumentsJson)
        assertTrue(parsed[0].id.isNotBlank(), "tool call must get a synthesized id")
    }

    @Test
    fun `parseNativeOllamaToolCalls re-serializes arguments map to json`() {
        val raw = listOf(
            mapOf(
                "function" to mapOf(
                    "name" to "read_file",
                    "arguments" to mapOf("path" to "/tmp/foo.txt")
                )
            )
        )

        val parsed = adapter.parseNativeOllamaToolCalls(raw)

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].argumentsJson.contains("\"path\""))
        assertTrue(parsed[0].argumentsJson.contains("/tmp/foo.txt"))
    }

    @Test
    fun `parseNativeOllamaToolCalls returns empty arguments for null args`() {
        val raw = listOf(
            mapOf(
                "function" to mapOf(
                    "name" to "list_dir",
                    "arguments" to null
                )
            )
        )

        val parsed = adapter.parseNativeOllamaToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("{}", parsed[0].argumentsJson)
    }

    @Test
    fun `parseNativeOllamaToolCalls skips entries missing function or name`() {
        val raw = listOf<Map<String, Any?>>(
            mapOf("function" to mapOf("arguments" to "{}")), // no name
            mapOf("type" to "function"), // no function block
            mapOf(
                "function" to mapOf(
                    "name" to "valid_tool",
                    "arguments" to "{}"
                )
            )
        )

        val parsed = adapter.parseNativeOllamaToolCalls(raw)

        assertEquals(1, parsed.size)
        assertEquals("valid_tool", parsed[0].name)
    }

    @Test
    fun `parseNativeOllamaToolCalls produces unique ids for each call`() {
        val raw = listOf(
            mapOf("function" to mapOf("name" to "t1", "arguments" to "{}")),
            mapOf("function" to mapOf("name" to "t2", "arguments" to "{}"))
        )

        val parsed = adapter.parseNativeOllamaToolCalls(raw)

        assertEquals(2, parsed.size)
        assertTrue(parsed[0].id != parsed[1].id, "each tool call must have its own id")
    }

    @Test
    fun `extractOllamaToolCalls returns empty when tool_calls missing`() {
        val message = mapOf<String, Any?>("role" to "assistant", "content" to "no tools")
        val calls = adapter.extractOllamaToolCalls(message)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `extractOllamaToolCalls returns tool_calls array when present`() {
        val message = mapOf<String, Any?>(
            "role" to "assistant",
            "content" to "",
            "tool_calls" to listOf(
                mapOf("function" to mapOf("name" to "read_file", "arguments" to "{}"))
            )
        )
        val calls = adapter.extractOllamaToolCalls(message)
        assertEquals(1, calls.size)
    }
}
