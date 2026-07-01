package pl.jclab.refio.ui.components.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [ContentSegmentParser] - the pure function that splits a streamed assistant message
 * into ordered typed segments and drives every chat bubble's rendering. A regression here silently
 * corrupts the chat view (leaked <thinking> markup, duplicated code, plan JSON shown as prose), so
 * the branching (closed vs streaming-unclosed anchors, JSON-vs-plan, gap walking) is pinned here.
 */
class ContentSegmentParserTest {

    @Test
    fun `blank content yields no segments`() {
        assertTrue(ContentSegmentParser.parse("   \n  ").isEmpty())
    }

    @Test
    fun `plain prose becomes a single markdown segment`() {
        val segments = ContentSegmentParser.parse("just some plain text")
        assertEquals(1, segments.size)
        val md = assertIs<ContentSegment.Markdown>(segments[0])
        assertEquals("just some plain text", md.content)
    }

    @Test
    fun `closed thinking tag is split from the trailing prose`() {
        val segments = ContentSegmentParser.parse("<thinking>weighing options</thinking>\n\nhere is the answer")
        assertEquals(2, segments.size)
        assertEquals("weighing options", assertIs<ContentSegment.Thinking>(segments[0]).content)
        assertTrue(assertIs<ContentSegment.Markdown>(segments[1]).content.contains("here is the answer"))
    }

    @Test
    fun `fenced code block captures language, file path and normalizes line endings`() {
        val segments = ContentSegmentParser.parse("```kotlin:src/A.kt\r\nval x = 1\r\n```")
        assertEquals(1, segments.size)
        val code = assertIs<ContentSegment.Code>(segments[0]).codeBlock
        assertEquals("kotlin", code.language)
        assertEquals("src/A.kt", code.filePath)
        assertEquals("val x = 1", code.content)
    }

    @Test
    fun `fence without a language defaults to text`() {
        val code = assertIs<ContentSegment.Code>(ContentSegmentParser.parse("```\nplain\n```")[0]).codeBlock
        assertEquals("text", code.language)
    }

    @Test
    fun `a standalone json object becomes a json segment`() {
        val segments = ContentSegmentParser.parse("""{"key": "value"}""")
        assertEquals(1, segments.size)
        assertIs<ContentSegment.Json>(segments[0])
    }

    @Test
    fun `json carrying plan keys becomes a plan segment with parsed subtasks`() {
        val json = """{"plan": "do the thing", "subtasks": [{"id": 1, "title": "step one"}]}"""
        val plan = assertIs<ContentSegment.Plan>(ContentSegmentParser.parse(json)[0])
        assertEquals("do the thing", plan.description)
        assertEquals(1, plan.subtasks.size)
        assertEquals("step one", plan.subtasks[0]["title"])
    }

    @Test
    fun `json without plan keys stays a json segment, not a plan`() {
        assertIs<ContentSegment.Json>(ContentSegmentParser.parse("""{"foo": "bar"}""")[0])
    }

    @Test
    fun `streaming unclosed thinking tag yields a partial thinking segment`() {
        val segments = ContentSegmentParser.parse("<think>still reasoning", isStreaming = true)
        assertEquals(1, segments.size)
        assertEquals("still reasoning", assertIs<ContentSegment.Thinking>(segments[0]).content)
    }

    @Test
    fun `streaming unclosed code fence yields a partial code segment`() {
        val segments = ContentSegmentParser.parse("```python\nprint(1)", isStreaming = true)
        assertEquals(1, segments.size)
        val code = assertIs<ContentSegment.Code>(segments[0]).codeBlock
        assertEquals("python", code.language)
        assertTrue(code.content.contains("print(1)"))
    }

    @Test
    fun `an unclosed thinking tag is NOT split when not streaming`() {
        // Without the streaming flag an unterminated tag must fall through to markdown, not vanish.
        val segments = ContentSegmentParser.parse("<think>dangling")
        assertEquals(1, segments.size)
        assertIs<ContentSegment.Markdown>(segments[0])
    }
}
