package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MessageMetadataExtractor] - the family of parsers that decide which bubble UI to
 * render and whether approval buttons appear. Every extractor is wrapped in try/catch -> null, so a
 * broken parser fails SILENTLY (the feature just disappears). These tests pin the type gates,
 * numeric coercion, and the two tool-call content dialects.
 */
class MessageMetadataExtractorTest {

    private fun msg(
        content: String = "",
        metadata: String? = null,
        toolCallInfo: ToolCallDisplayInfo? = null
    ) = Message(
        id = "m1",
        taskId = "t1",
        role = "assistant",
        content = content,
        createdAt = 0L,
        metadata = metadata,
        toolCallInfo = toolCallInfo
    )

    // ---- pure string helpers ----

    @Test
    fun `extractSubtaskId pulls the id from the marker line`() {
        assertEquals("abc-123", MessageMetadataExtractor.extractSubtaskId("**Subtask ID:** `abc-123`\nmore text"))
    }

    @Test
    fun `extractSubtaskId returns null when the marker is absent`() {
        assertNull(MessageMetadataExtractor.extractSubtaskId("no marker here"))
    }

    @Test
    fun `isPlanJson is true only for objects carrying plan keys`() {
        assertTrue(MessageMetadataExtractor.isPlanJson("""{"plan":"x","subtasks":[]}"""))
        assertFalse(MessageMetadataExtractor.isPlanJson("""{"foo":"bar"}"""))
        assertFalse(MessageMetadataExtractor.isPlanJson("not json"))
    }

    @Test
    fun `toSafeInt and toSafeDouble coerce numbers and strings and fall back otherwise`() {
        assertEquals(3, (3.0 as Any?).toSafeInt(defaultValue = -1))
        assertEquals(7, ("7" as Any?).toSafeInt(defaultValue = -1))
        assertEquals(-1, (null as Any?).toSafeInt(defaultValue = -1))
        assertEquals(-1, ("nope" as Any?).toSafeInt(defaultValue = -1))
        assertEquals(1.5, (1.5 as Any?).toSafeDouble(defaultValue = 0.0))
        assertEquals(0.0, ("x" as Any?).toSafeDouble(defaultValue = 0.0))
    }

    // ---- question data (drives the approval buttons) ----

    @Test
    fun `extractQuestionData returns the question only while awaiting a response`() {
        val awaiting = msg(
            metadata = """{"type":"orchestrator_question","question_id":"q1","awaiting_response":true,"options":["a","b"]}"""
        )
        val data = MessageMetadataExtractor.extractQuestionData(awaiting)
        assertEquals("q1", data?.questionId)
        assertEquals(listOf("a", "b"), data?.options)
    }

    @Test
    fun `extractQuestionData returns null once the response is no longer awaited`() {
        val answered = msg(
            metadata = """{"type":"orchestrator_question","question_id":"q1","awaiting_response":false}"""
        )
        assertNull(MessageMetadataExtractor.extractQuestionData(answered))
    }

    @Test
    fun `extractQuestionData ignores messages of another type`() {
        assertNull(MessageMetadataExtractor.extractQuestionData(msg(metadata = """{"type":"code_changes"}""")))
    }

    // ---- code changes + guardian nudge type gates ----

    @Test
    fun `extractCodeChanges parses a code_changes payload and skips other types`() {
        val changes = MessageMetadataExtractor.extractCodeChanges(
            msg(metadata = """{"type":"code_changes","file_path":"src/A.kt","added_lines":4,"removed_lines":1,"snapshot_id":"s1"}""")
        )
        assertEquals("src/A.kt", changes?.filePath)
        assertEquals(4, changes?.addedLines)
        assertEquals(1, changes?.removedLines)
        assertNull(MessageMetadataExtractor.extractCodeChanges(msg(metadata = """{"type":"execution_summary"}""")))
    }

    @Test
    fun `isGuardianNudge is true only for the guardian_nudge type`() {
        assertTrue(MessageMetadataExtractor.isGuardianNudge(msg(metadata = """{"type":"guardian_nudge"}""")))
        assertFalse(MessageMetadataExtractor.isGuardianNudge(msg(metadata = """{"type":"code_changes"}""")))
        assertFalse(MessageMetadataExtractor.isGuardianNudge(msg(metadata = null)))
    }

    // ---- tool-call content dialects ----

    @Test
    fun `extractToolCallInfo parses the TOOL_CALL ARGUMENTS dialect`() {
        val info = MessageMetadataExtractor.extractToolCallInfo(
            msg(content = "TOOL_CALL: read_file\nARGUMENTS: {\"path\": \"README.md\"}")
        )
        assertEquals("read_file", info?.toolName)
        assertEquals("README.md", info?.parameters?.get("path"))
    }

    @Test
    fun `extractToolCallInfo parses the markdown dialect only for known tools`() {
        val known = MessageMetadataExtractor.extractToolCallInfo(
            msg(content = "**read_file**\n```\npath: README.md\n```")
        )
        assertEquals("read_file", known?.toolName)
        assertEquals("README.md", known?.parameters?.get("path"))

        // An unknown tool name in the markdown dialect must not be surfaced as a tool call.
        assertNull(MessageMetadataExtractor.extractToolCallInfo(msg(content = "**totally_made_up**\n```\npath: x\n```")))
    }
}
