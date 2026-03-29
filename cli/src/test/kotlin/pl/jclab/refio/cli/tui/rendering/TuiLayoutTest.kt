package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiLayoutTest {

    @Test
    fun `fromTerminal should enforce minimum dimensions`() {
        val layout = TuiLayoutRegions.fromTerminal(40, 10)
        assertEquals(80, layout.width)
        assertEquals(24, layout.height)
    }

    @Test
    fun `fromTerminal should use actual dimensions when above minimum`() {
        val layout = TuiLayoutRegions.fromTerminal(120, 40)
        assertEquals(120, layout.width)
        assertEquals(40, layout.height)
    }

    @Test
    fun `contentHeight should be positive`() {
        val layout = TuiLayoutRegions.fromTerminal(80, 24)
        assertTrue(layout.contentHeight > 0)
    }

    @Test
    fun `contentWidth should be positive`() {
        val layout = TuiLayoutRegions.fromTerminal(80, 24)
        assertTrue(layout.contentWidth > 0)
    }

    @Test
    fun `contentHeight should account for tab bar and separator`() {
        val layout = TuiLayoutRegions(width = 80, height = 30)
        // height - tabBarHeight(1) - separatorHeight(1) = 28 (no status bar)
        assertEquals(28, layout.contentHeight)
    }

    @Test
    fun `split mode should have left and right panels`() {
        val layout = TuiLayoutRegions(width = 100, height = 30, isSplitMode = true)
        assertTrue(layout.leftPanelWidth > 0)
        assertTrue(layout.rightPanelWidth > 0)
        assertEquals(100, layout.leftPanelWidth + layout.rightPanelWidth + 1) // +1 for separator
    }

    @Test
    fun `non-split mode should have full width left panel`() {
        val layout = TuiLayoutRegions(width = 100, height = 30, isSplitMode = false)
        assertEquals(100, layout.leftPanelWidth)
        assertEquals(0, layout.rightPanelWidth)
    }
}
