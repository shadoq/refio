package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.tools.base.ToolRegistry

class ToolCallParserTest {

    private val parser = ToolCallParser(
        toolRegistry = mockk<ToolRegistry>(relaxed = true)
    )

    @Test
    fun `should recover create new file tool call from malformed envelope with long markdown content`() {
        val malformed = """
            {
              "actions": [
                {"tool": "create_new_file", "arguments": {"path": "0001-idsx.md", "content": "# Title

            ## Example

            ```javascript
            const message = "Hello";
            ```

            Final line
            "}}
              ],
              "response": "Creating file",
              "intent": "implementation"
            }
        """.trimIndent()

        val toolCalls = parser.extractToolCalls(malformed, TaskMode.AGENT)

        assertEquals(1, toolCalls.size)
        assertEquals("create_new_file", toolCalls.first().name)

        val arguments = Json.parseToJsonElement(toolCalls.first().arguments).jsonObject
        assertEquals("0001-idsx.md", arguments["path"]?.jsonPrimitive?.content)

        val content = arguments["content"]?.jsonPrimitive?.content
        assertNotNull(content)
        assertTrue(content.contains("""const message = "Hello";"""))
        assertTrue(content.contains("Final line"))
    }

    @Test
    fun `should recover fenced create new file tool call from malformed envelope with long markdown content`() {
        val malformed = """
            ```json
            {
              "thinking": "Preparing file",
              "intent": "implementation",
              "response": "Creating file",
              "actions": [
                {"tool": "create_new_file", "arguments": {"path": "0001-idsx.md", "content": "# Title

            ## Example

            ```javascript
            const message = "Hello";
            ```

            Final line
            "}}
              ]
            }
            ```
        """.trimIndent()

        val inspection = parser.inspectJsonEnvelope(malformed)
        assertTrue(inspection.hasJsonEnvelope)
        assertTrue(inspection.isComplete)
        assertTrue(inspection.isFenced)

        val toolCalls = parser.extractToolCalls(malformed, TaskMode.AGENT)

        assertEquals(1, toolCalls.size)
        assertEquals("create_new_file", toolCalls.first().name)

        val arguments = Json.parseToJsonElement(toolCalls.first().arguments).jsonObject
        assertEquals("0001-idsx.md", arguments["path"]?.jsonPrimitive?.content)

        val content = arguments["content"]?.jsonPrimitive?.content
        assertNotNull(content)
        assertTrue(content.contains("""const message = "Hello";"""))
        assertTrue(content.contains("Final line"))
    }

    @Test
    fun `should map flat action shape where args are siblings of the tool key`() {
        // Weaker models on the JSON-in-text path (observed: gemma4:31b) emit tool params
        // as siblings of `tool` instead of nesting them under `args`. Without recovery the
        // parser produced empty `{}` args and the tool failed with
        // "Missing required parameter: 'path'". The flat shape must still map cleanly.
        val flat = """
            {
              "response": "Creating the landing page.",
              "actions": [
                {"tool": "advance_code_editing", "path": "website.html", "edit_description": "premium landing page"}
              ]
            }
        """.trimIndent()

        val toolCalls = parser.extractToolCalls(flat, TaskMode.AGENT)

        assertEquals(1, toolCalls.size)
        assertEquals("advance_code_editing", toolCalls.first().name)

        val arguments = Json.parseToJsonElement(toolCalls.first().arguments).jsonObject
        assertEquals("website.html", arguments["path"]?.jsonPrimitive?.content)
        assertEquals("premium landing page", arguments["edit_description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should prefer nested args wrapper over stray sibling keys`() {
        // When a proper `args` wrapper is present, sibling keys must NOT leak into the
        // arguments — the wrapper stays authoritative (guards the flat-shape fallback).
        val mixed = """
            {
              "actions": [
                {"tool": "read_file", "note": "ignore me", "args": {"path": "src/App.kt"}}
              ],
              "response": "Reading"
            }
        """.trimIndent()

        val toolCalls = parser.extractToolCalls(mixed, TaskMode.AGENT)

        assertEquals(1, toolCalls.size)
        val arguments = Json.parseToJsonElement(toolCalls.first().arguments).jsonObject
        assertEquals("src/App.kt", arguments["path"]?.jsonPrimitive?.content)
        assertFalse(arguments.containsKey("note"))
    }

    @Test
    fun `should recover generic tool calls with malformed quoted strings in arguments`() {
        val malformed = """
            {
              "actions": [
                {
                  "tool": "code_editing",
                  "arguments": {
                    "path": "notes.txt",
                    "old_string": "before "quoted" value",
                    "new_string": "after line 1
            after line 2"
                  }
                }
              ],
              "response": "Updating file",
              "intent": "implementation"
            }
        """.trimIndent()

        val toolCalls = parser.extractToolCalls(malformed, TaskMode.AGENT)

        assertEquals(1, toolCalls.size)
        assertEquals("code_editing", toolCalls.first().name)

        val arguments = Json.parseToJsonElement(toolCalls.first().arguments).jsonObject
        assertEquals("notes.txt", arguments["path"]?.jsonPrimitive?.content)
        assertEquals("""before "quoted" value""", arguments["old_string"]?.jsonPrimitive?.content)
        assertEquals("after line 1\nafter line 2", arguments["new_string"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should detect complete fenced json envelope`() {
        val content = """
            ```json
            {
              "actions": [],
              "response": "Done",
              "intent": "implementation"
            }
            ```
        """.trimIndent()

        val inspection = parser.inspectJsonEnvelope(content)

        assertTrue(inspection.hasJsonEnvelope)
        assertTrue(inspection.isComplete)
        assertTrue(inspection.isFenced)
        assertEquals("Done", parser.extractTextResponse(content))
    }

    @Test
    fun `should detect incomplete fenced json envelope`() {
        val content = """
            ```json
            {
              "actions": [
                {"tool": "http_request", "args": {"url": "https://example.com"}}
              ],
              "response": "Working",
              "intent": "implementation"
        """.trimIndent()

        val inspection = parser.inspectJsonEnvelope(content)

        assertTrue(inspection.hasJsonEnvelope)
        assertFalse(inspection.isComplete)
        assertTrue(inspection.isFenced)
        val toolCalls = parser.extractToolCalls(content, TaskMode.AGENT)
        assertEquals(1, toolCalls.size)
        assertEquals("http_request", toolCalls.first().name)
    }

    @Test
    fun `should detect incomplete raw json envelope`() {
        val content = """{"actions":[{"tool":"read_file","args":{"path":"a.txt"}}],"response":"Working""""
            .dropLast(1)

        val inspection = parser.inspectJsonEnvelope(content)

        assertTrue(inspection.hasJsonEnvelope)
        assertFalse(inspection.isComplete)
        assertFalse(inspection.isFenced)
        val toolCalls = parser.extractToolCalls(content, TaskMode.AGENT)
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls.first().name)
    }
}
