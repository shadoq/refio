package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LayoutModeTest {

    @Test
    fun `width picks the layout at the exact breakpoints`() {
        assertEquals(LayoutMode.TooSmall, LayoutMode.of(79, 50))
        assertEquals(LayoutMode.Narrow, LayoutMode.of(80, 50))
        assertEquals(LayoutMode.Narrow, LayoutMode.of(119, 50))
        assertEquals(LayoutMode.Medium, LayoutMode.of(120, 50))
        assertEquals(LayoutMode.Medium, LayoutMode.of(179, 50))
        assertEquals(LayoutMode.Wide, LayoutMode.of(180, 50))
    }

    @Test
    fun `too few rows is too small whatever the width`() {
        assertEquals(LayoutMode.TooSmall, LayoutMode.of(200, 23))
        assertEquals(LayoutMode.Wide, LayoutMode.of(200, 24))
    }

    @Test
    fun `columns and the gaps between them always fill the terminal width`() {
        for (width in listOf(80, 100, 119, 120, 140, 179, 180, 200, 320)) {
            val mode = LayoutMode.of(width, 40)
            for (rightOpen in listOf(true, false)) {
                val c = LayoutMode.columns(mode, width, rightOpen)
                assertEquals(width, c.sidebar + c.chat + c.right + c.gaps, "$mode at $width, right column open: $rightOpen")
            }
        }
    }

    @Test
    fun `with nothing open on the right the chat takes the right column's width`() {
        assertEquals(LayoutMode.Columns(sidebar = 26, chat = 173, right = 0), LayoutMode.columns(LayoutMode.Wide, 200, rightOpen = false))
        assertEquals(LayoutMode.Columns(sidebar = 0, chat = 140, right = 0), LayoutMode.columns(LayoutMode.Medium, 140, rightOpen = false))
    }

    @Test
    fun `side columns keep their fixed widths and chat takes the rest`() {
        assertEquals(LayoutMode.Columns(sidebar = 26, chat = 114, right = 58), LayoutMode.columns(LayoutMode.Wide, 200))
        assertEquals(LayoutMode.Columns(sidebar = 0, chat = 91, right = 48), LayoutMode.columns(LayoutMode.Medium, 140))
        assertEquals(LayoutMode.Columns(sidebar = 0, chat = 80, right = 0), LayoutMode.columns(LayoutMode.Narrow, 80))
    }
}
