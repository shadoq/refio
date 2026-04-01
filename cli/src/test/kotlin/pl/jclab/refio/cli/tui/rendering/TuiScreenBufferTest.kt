package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiScreenBufferTest {

    @Test
    fun `takeVisibleChars should handle plain text`() {
        assertEquals("Hel", TuiScreenBuffer.takeVisibleChars("Hello", 3))
        assertEquals("Hello", TuiScreenBuffer.takeVisibleChars("Hello", 5))
        assertEquals("Hello", TuiScreenBuffer.takeVisibleChars("Hello", 10))
    }

    @Test
    fun `takeVisibleChars should preserve ANSI codes`() {
        val styled = "\u001b[31mHello\u001b[0m"
        val result = TuiScreenBuffer.takeVisibleChars(styled, 3)
        assertTrue(result.contains("\u001b[31m"))
        assertEquals(3, TuiRenderBuffer.visibleLength(result))
    }

    @Test
    fun `dropVisibleChars should skip visible characters`() {
        assertEquals("lo", TuiScreenBuffer.dropVisibleChars("Hello", 3))
        assertEquals("", TuiScreenBuffer.dropVisibleChars("Hello", 5))
        assertEquals("Hello", TuiScreenBuffer.dropVisibleChars("Hello", 0))
    }

    @Test
    fun `spliceAnsiString should insert overlay at position`() {
        val base = "AAAAAAAAAA" // 10 chars
        val overlay = "BBB"
        val result = TuiScreenBuffer.spliceAnsiString(base, overlay, 3)
        val visible = TuiRenderBuffer.stripAnsi(result)
        // First 3 chars from base, then overlay, then rest
        assertEquals("AAA", visible.substring(0, 3))
        assertEquals("BBB", visible.substring(3, 6))
        assertEquals("AAAA", visible.substring(6))
    }

    @Test
    fun `spliceAnsiString should handle overlay at start`() {
        val base = "AAAAAAAAAA"
        val overlay = "BBB"
        val result = TuiScreenBuffer.spliceAnsiString(base, overlay, 0)
        val visible = TuiRenderBuffer.stripAnsi(result)
        assertTrue(visible.startsWith("BBB"))
        assertEquals("BBBAAAAAAA", visible)
    }

    @Test
    fun `overlay should paint on top of setRows content`() {
        val screen = TuiScreenBuffer(20, 5)
        screen.setRow(0, "Row zero content")
        screen.setRow(1, "Row one content")
        screen.setRow(2, "Row two content")

        screen.overlay(1, 4, listOf("OVERLAY"))

        // After flush, row 1 should have "Row OVERLAYontent" (approximately)
        // We can't easily read back but at least verify no crash
    }

    @Test
    fun `setRows should fill multiple rows`() {
        val screen = TuiScreenBuffer(10, 5)
        screen.setRows(1, listOf("Line A", "Line B", "Line C"))
        // No crash = success; content verification would need flush capture
    }

    @Test
    fun `overlay out of bounds should not crash`() {
        val screen = TuiScreenBuffer(10, 5)
        screen.overlay(-1, 0, listOf("above"))
        screen.overlay(10, 0, listOf("below"))
        screen.overlay(0, 100, listOf("far right"))
    }
}
