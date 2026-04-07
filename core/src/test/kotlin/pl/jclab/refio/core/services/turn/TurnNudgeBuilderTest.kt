package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that nudge messages stay short and contain a usable JSON example.
 *
 * Long, all-caps "MUST/NEVER" instructions push thinking-capable models (qwen3, deepseek-r1)
 * into long internal reasoning that produces empty `content`. Short, example-driven nudges
 * are the difference between a recoverable iteration and a dead loop.
 */
class TurnNudgeBuilderTest {

    private fun assertSubstring(haystack: String, needle: String) {
        assertTrue(haystack.contains(needle), "Expected '$needle' in:\n$haystack")
    }

    @Test
    fun `plain text nudge is short and contains JSON example`() {
        val msg = TurnNudgeBuilder.buildPlainTextNudgeMessage()
        // Short = under ~250 chars. Previous version was ~600 chars.
        assertTrue(msg.length < 250, "plain-text nudge too long: ${msg.length} chars")
        assertSubstring(msg, "\"actions\"")
        assertSubstring(msg, "\"intent\"")
    }

    @Test
    fun `plain text nudge avoids loud all-caps imperatives`() {
        val msg = TurnNudgeBuilder.buildPlainTextNudgeMessage()
        // The old nudge contained "MUST" and "NEVER" which pushed qwen3 into thinking loops.
        // We don't ban every uppercase word (NAME and JSON-style placeholders are fine), but
        // we forbid the specific imperatives that caused the regression.
        assertFalse(msg.contains("MUST"), "nudge should not yell MUST: $msg")
        assertFalse(msg.contains("NEVER"), "nudge should not yell NEVER: $msg")
    }

    @Test
    fun `empty content nudge exists and is distinct from plain text nudge`() {
        val empty = TurnNudgeBuilder.buildEmptyContentNudgeMessage()
        val plain = TurnNudgeBuilder.buildPlainTextNudgeMessage()
        assertTrue(empty.isNotBlank())
        assertTrue(empty != plain, "empty-content and plain-text nudges should be distinct")
        assertSubstring(empty, "empty")
        assertSubstring(empty, "\"actions\"")
        assertTrue(empty.length < 250, "empty-content nudge too long: ${empty.length}")
    }

    @Test
    fun `invalid format AGENT message is short and example-driven`() {
        val msg = TurnNudgeBuilder.buildInvalidFormatMessage("AGENT")
        assertTrue(msg.length < 250, "invalid-format nudge too long: ${msg.length}")
        assertSubstring(msg, "\"actions\"")
        assertSubstring(msg, "\"intent\"")
        assertFalse(msg.contains("IMPORTANT"), "nudge should not yell IMPORTANT: $msg")
    }

    @Test
    fun `invalid format PLAN message contains plan and subtasks placeholders`() {
        val msg = TurnNudgeBuilder.buildInvalidFormatMessage("PLAN")
        assertTrue(msg.length < 250)
        assertSubstring(msg, "\"actions\"")
        assertSubstring(msg, "\"plan\"")
        assertSubstring(msg, "\"subtasks\"")
    }
}
