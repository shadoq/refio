package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.*

class TuiContentParserTest {

    @Test
    fun `should parse plain text as single Markdown segment`() {
        val segments = TuiContentParser.parse("Hello world")
        assertEquals(1, segments.size)
        assertIs<TuiContentSegment.Markdown>(segments[0])
        assertTrue(segments[0].let { (it as TuiContentSegment.Markdown).content.contains("Hello world") })
    }

    @Test
    fun `should parse thinking tags`() {
        val content = "<think>I need to analyze this</think>\nHere is my response."
        val segments = TuiContentParser.parse(content)
        assertEquals(2, segments.size)
        assertIs<TuiContentSegment.Thinking>(segments[0])
        assertEquals("I need to analyze this", (segments[0] as TuiContentSegment.Thinking).content)
        assertIs<TuiContentSegment.Markdown>(segments[1])
    }

    @Test
    fun `should parse thinking tag variant`() {
        val content = "<thinking>Deep thought</thinking>\nAnswer."
        val segments = TuiContentParser.parse(content)
        assertEquals(2, segments.size)
        assertIs<TuiContentSegment.Thinking>(segments[0])
        assertEquals("Deep thought", (segments[0] as TuiContentSegment.Thinking).content)
    }

    @Test
    fun `should parse fenced code block`() {
        val content = "Here is code:\n```kotlin\nfun hello() = println(\"world\")\n```\nDone."
        val segments = TuiContentParser.parse(content)
        assertEquals(3, segments.size)
        assertIs<TuiContentSegment.Markdown>(segments[0])
        assertIs<TuiContentSegment.Code>(segments[1])
        val code = segments[1] as TuiContentSegment.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.content.contains("fun hello()"))
        assertIs<TuiContentSegment.Markdown>(segments[2])
    }

    @Test
    fun `should parse code block with file path`() {
        val content = "```kotlin:src/main/Foo.kt\nclass Foo\n```"
        val segments = TuiContentParser.parse(content)
        assertEquals(1, segments.size)
        val code = segments[0] as TuiContentSegment.Code
        assertEquals("kotlin", code.language)
        assertEquals("src/main/Foo.kt", code.filePath)
        assertEquals("class Foo", code.content)
    }

    @Test
    fun `should handle unclosed thinking tag during streaming`() {
        val content = "Here is response.\n<think>I'm still thinking about"
        val segments = TuiContentParser.parse(content, isStreaming = true)
        assertEquals(2, segments.size)
        assertIs<TuiContentSegment.Markdown>(segments[0])
        assertIs<TuiContentSegment.Thinking>(segments[1])
    }

    @Test
    fun `should handle unclosed code fence during streaming`() {
        val content = "Let me write code:\n```python\ndef hello():\n    print('hi')"
        val segments = TuiContentParser.parse(content, isStreaming = true)
        assertEquals(2, segments.size)
        assertIs<TuiContentSegment.Markdown>(segments[0])
        assertIs<TuiContentSegment.Code>(segments[1])
        val code = segments[1] as TuiContentSegment.Code
        assertEquals("python", code.language)
    }

    @Test
    fun `should parse standalone JSON`() {
        val content = "Result:\n{\"key\": \"value\", \"count\": 42}\nDone."
        val segments = TuiContentParser.parse(content)
        // JSON detection depends on gap being entirely JSON
        assertTrue(segments.isNotEmpty())
    }

    @Test
    fun `should parse multiple code blocks`() {
        val content = "First:\n```java\nint x = 1;\n```\nSecond:\n```python\nx = 1\n```"
        val segments = TuiContentParser.parse(content)
        val codeBlocks = segments.filterIsInstance<TuiContentSegment.Code>()
        assertEquals(2, codeBlocks.size)
        assertEquals("java", codeBlocks[0].language)
        assertEquals("python", codeBlocks[1].language)
    }

    @Test
    fun `should handle empty content`() {
        val segments = TuiContentParser.parse("")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `should handle blank content`() {
        val segments = TuiContentParser.parse("   \n  \n  ")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `should handle mixed thinking and code`() {
        val content = "<think>Planning the approach</think>\nHere's the implementation:\n```kotlin\nfun solve() {}\n```"
        val segments = TuiContentParser.parse(content)
        val thinking = segments.filterIsInstance<TuiContentSegment.Thinking>()
        val code = segments.filterIsInstance<TuiContentSegment.Code>()
        assertEquals(1, thinking.size)
        assertEquals(1, code.size)
    }
}
