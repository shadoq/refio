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
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry

class ToolCallParserTest {

    private val parser = ToolCallParser(
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
        toolPermissionsService = mockk<ToolPermissionsService>(relaxed = true)
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
}
