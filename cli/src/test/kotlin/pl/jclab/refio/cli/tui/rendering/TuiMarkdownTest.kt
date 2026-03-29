package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TuiMarkdownTest {

    private val recorder = TerminalRecorder()
    private val terminal = Terminal(terminalInterface = recorder)

    @Test
    fun `should render GFM table`() {
        val markdown = """
            | Header 1 | Header 2 |
            |----------|----------|
            | Cell A   | Cell B   |
            | Cell C   | Cell D   |
        """.trimIndent()

        val result = TuiMarkdown.renderToString(terminal, markdown)
        // Mordant renders tables with box-drawing characters
        assertTrue(result.contains("Header 1"), "Should contain header text: $result")
        assertTrue(result.contains("Cell A"), "Should contain cell text: $result")
    }

    @Test
    fun `should render bold and italic`() {
        val result = TuiMarkdown.renderToString(terminal, "**bold** and *italic*")
        assertTrue(result.contains("bold"))
        assertTrue(result.contains("italic"))
    }

    @Test
    fun `should render code blocks`() {
        val md = "```kotlin\nfun hello() {}\n```"
        val result = TuiMarkdown.renderToString(terminal, md)
        assertTrue(result.contains("fun hello()"))
    }

    @Test
    fun `should render lists`() {
        val md = "- item 1\n- item 2\n- item 3"
        val result = TuiMarkdown.renderToString(terminal, md)
        assertTrue(result.contains("item 1"))
        assertTrue(result.contains("item 3"))
    }

    @Test
    fun `should handle empty content`() {
        val result = TuiMarkdown.renderToString(terminal, "")
        assertTrue(result.isEmpty())
    }
}
