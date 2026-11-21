package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Text from tools, files and models is drawn as text: it never reaches the terminal as a command. */
class TerminalSafetyTest {

    private val esc = "\u001b"

    @Test
    fun `tool output cannot move the cursor, set the title or write the clipboard`() {
        val gradle = "BUILD$esc[1A$esc[2K OK$esc]0;pwned\u0007$esc]52;c;ZXZpbA==$esc\\ done\u009b2J\r"
        assertEquals("BUILD OK done", TuiRenderBuffer.cleanText(gradle))
    }

    @Test
    fun `tabs become spaces and line breaks stay, so widths and lines are right`() {
        assertEquals("a    b\nc", TuiRenderBuffer.cleanText("a\tb\r\nc"))
    }

    @Test
    fun `plain text is returned as it is`() {
        val text = "nothing to clean here: ąę 中文"
        assertSame(text, TuiRenderBuffer.cleanText(text))
    }

    @Test
    fun `a drawn row keeps its colours and loses anything else`() {
        val row = "$esc[38;2;1;2;3mok$esc[0m$esc]8;;http://x$esc\\link$esc[5n"
        assertEquals("$esc[38;2;1;2;3mok$esc[0mlink", TuiRenderBuffer.keepOnlyColours(row))
    }

    @Test
    fun `the screen sends no command hidden in a row`() {
        val screen = TuiScreenBuffer(20, 1)
        screen.setRow(0, "x$esc]52;c;ZXZpbA==\u0007y")
        val out = StringWriter().also { screen.flush(it) }.toString()
        assertFalse(out.contains("]52;"), out)
        assertTrue(out.contains("xy"))
    }
}
