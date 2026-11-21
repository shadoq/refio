package pl.jclab.refio.cli.tui.screens

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiHelpScreenTest {

    private val theme = Theme(ColorDepth.NONE)

    private fun text(state: TuiState, width: Int, height: Int) =
        TuiHelpScreen.renderToLines(state, width, height, theme, Glyphs.UNICODE).map(TuiRenderBuffer::stripAnsi)

    @Test
    fun `three columns on a wide screen show everything without scrolling`() {
        val out = text(TuiState(), 200, 49)
        assertEquals(3, TuiHelpScreen.columnsFor(200))
        assertTrue(out.last().contains("/ filter · Esc or F1 close"), out.last())
        assertTrue(out.any { it.contains("NAVIGATION") } && out.any { it.contains("STATUS BAR") }, out.joinToString("\n"))
    }

    @Test
    fun `narrow screen falls back to one scrolling column`() {
        assertEquals(1, TuiHelpScreen.columnsFor(80))
        assertEquals(2, TuiHelpScreen.columnsFor(140))
        assertTrue(text(TuiState(), 80, 23).last().contains("scroll"))
    }

    @Test
    fun `slash filters the rows and keeps only matching sections`() {
        val out = text(TuiState(helpFilter = "diff"), 200, 49)
        assertTrue(out.any { it.contains("Open the full diff first") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("@git_diff") })
        assertFalse(out.any { it.contains("Ctrl+Q") })
        assertTrue(out.last().startsWith("/ diff_"))
    }

    @Test
    fun `every slash action is listed with its key`() {
        val commands = TuiHelpScreen.filter("/model").flatMap { it.rows }
        assertTrue(commands.any { it.key == "/model" && it.text.contains("^O") })
    }

    @Test
    fun `every line fills the width`() {
        for (w in listOf(80, 140, 200)) {
            text(TuiState(), w, 30).forEach { assertEquals(w, TuiRenderBuffer.visibleLength(it)) }
        }
    }
}
