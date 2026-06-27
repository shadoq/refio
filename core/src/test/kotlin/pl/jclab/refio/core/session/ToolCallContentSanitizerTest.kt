package pl.jclab.refio.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Display sanitization for assistant content (`ToolCallContentSanitizer.sanitize`).
 *
 * Regression: a model without native function-calling (e.g. qwen3.6:35b) emits tool calls as ad-hoc
 * text — a bare JSON array `[{...}]` or a `[TOOL]` marker wrapping a {response,actions} envelope.
 * Extraction lifts the calls into `tool_calls_json` and runs them, but the textual residue used to
 * leak into the chat bubble as a stray `[` or a raw JSON envelope. These cases lock the cleanup
 * (display only — extraction is unaffected).
 */
class ToolCallContentSanitizerTest {

    @Test
    fun `a lone bracket left after tool-call extraction renders as nothing`() {
        // Observed DB content (session accf93dc, message bb1fc8a4): the array opener survived after
        // the tool-call object was lifted into tool_calls_json. It is not user-facing text.
        assertEquals("", ToolCallContentSanitizer.sanitize("["))
    }

    @Test
    fun `content of only JSON structural punctuation is blanked`() {
        assertEquals("", ToolCallContentSanitizer.sanitize("[]"))
        assertEquals("", ToolCallContentSanitizer.sanitize("[\n]"))
        assertEquals("", ToolCallContentSanitizer.sanitize("  [ , ]  "))
        assertEquals("", ToolCallContentSanitizer.sanitize("{}"))
    }

    @Test
    fun `a TOOL-marker wrapper around a response envelope unwraps to the response text`() {
        // Observed DB content (message 44d337d8): the real answer sits in the envelope's `response`
        // field, but the leading "[" + "[TOOL]" prefix hid it behind a raw JSON dump.
        val raw = """
            [

            [TOOL]
            {
              "response": "Reading root build.gradle.kts and exploring the project structure.",
              "actions": [
                {"tool": "read_file", "args": {"path": "build.gradle.kts"}}
              ]
            }
        """.trimIndent()

        assertEquals(
            "Reading root build.gradle.kts and exploring the project structure.",
            ToolCallContentSanitizer.sanitize(raw)
        )
    }

    @Test
    fun `plain prose is left untouched`() {
        assertEquals("Hello, this is a normal answer.", ToolCallContentSanitizer.sanitize("Hello, this is a normal answer."))
    }

    @Test
    fun `prose that merely contains brackets is not blanked`() {
        // The residue rule must only fire on content that is ENTIRELY structural punctuation.
        assertEquals("See [docs] for details.", ToolCallContentSanitizer.sanitize("See [docs] for details."))
    }

    @Test
    fun `a clean response envelope still unwraps to its response text`() {
        assertEquals(
            "hi there",
            ToolCallContentSanitizer.sanitize("""{"response":"hi there","actions":[]}""")
        )
    }

    @Test
    fun `plan JSON is preserved verbatim`() {
        val plan = """{"plan":[{"step":"do x"}]}"""
        assertEquals(plan, ToolCallContentSanitizer.sanitize(plan))
    }
}
