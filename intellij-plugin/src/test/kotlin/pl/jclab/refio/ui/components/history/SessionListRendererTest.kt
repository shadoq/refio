package pl.jclab.refio.ui.components.history

import com.intellij.util.ui.JBUI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session row changes shape with the available width: stacked while docked, and metadata
 * beside the title once the panel is wide. The cell height has to follow the shape, otherwise a
 * single-line row sits in a double-height cell and the list looks half empty - which is what an
 * undocked panel used to show.
 */
class SessionListRendererTest {

    @Test
    fun `a docked-width row keeps the tall stacked cell`() {
        val height = SessionListRenderer.rowHeight(JBUI.scale(360))

        assertEquals(JBUI.scale(38), height, "two stacked lines need the full cell height")
    }

    @Test
    fun `a wide row switches to the short single-line cell`() {
        // At this width the metadata fits beside the title, so the second line is gone and
        // keeping its height would waste half of every row.
        val height = SessionListRenderer.rowHeight(JBUI.scale(900))

        assertEquals(JBUI.scale(22), height, "a single-line row must not keep the stacked height")
    }

    @Test
    fun `the shape switches once, at the same width the tool window calls wide`() {
        val justBelow = SessionListRenderer.rowHeight(JBUI.scale(560) - 1)
        val atThreshold = SessionListRenderer.rowHeight(JBUI.scale(560))

        assertTrue(justBelow > atThreshold, "the row must get shorter exactly at the wide boundary")
    }

    @Test
    fun `an unrealised list falls back to the stacked cell`() {
        // Before the first layout the width is 0; guessing the wide shape there would flash a
        // wrong-height list on every open.
        assertEquals(JBUI.scale(38), SessionListRenderer.rowHeight(0))
    }
}
