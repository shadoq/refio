package pl.jclab.refio.ui.settings

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings pages live in a tool window a user can narrow to a few hundred pixels. The rules
 * under test are what keeps them usable there: content adapts to the dock instead of being cut off
 * at the edge, anything that genuinely cannot shrink stays reachable by scrolling, and a table's
 * column bounds never collapse a column to a sliver.
 */
class SettingsLayoutTest {

    private val floor = JBUI.scale(NarrowDock.MIN_CONTENT_WIDTH)

    /** [cannotShrinkBelow] defaults to [preferred], modelling content that refuses to compress. */
    private fun form(preferred: Int, cannotShrinkBelow: Int = preferred) = JPanel().apply {
        preferredSize = Dimension(preferred, 100)
        minimumSize = Dimension(cannotShrinkBelow, 100)
    }

    /**
     * A plain [JScrollPane] stands in for [settingsScrollPane]: the IDE's own scrollbar UI cannot be
     * instantiated outside a running IDE, while the layout under test - the scroll policy plus the
     * width-aware view - is identical.
     */
    private fun laidOut(content: JPanel, viewportWidth: Int) =
        JScrollPane(ViewportWidthAwarePanel(content)).apply {
            configureSettingsScroll(this)
            setSize(viewportWidth, 400)
            doLayout()
            viewport.doLayout()
        }

    @Test
    fun `a form wider than the dock is squeezed into it rather than overflowing`() {
        val content = form(preferred = floor * 3)

        val scroll = laidOut(content, viewportWidth = floor * 2)

        // Without this the viewport would hand the form its full preferred width and everything
        // past the dock edge would simply not be drawn.
        assertEquals(scroll.viewport.width, scroll.viewport.view.width)
        assertFalse(scroll.horizontalScrollBar.isVisible, "no sideways scrolling while the form fits")
    }

    @Test
    fun `below the usable width the form scrolls instead of losing its right-hand side`() {
        val content = form(preferred = floor * 3)

        val scroll = laidOut(content, viewportWidth = floor / 2)

        assertTrue(
            scroll.viewport.view.width > scroll.viewport.width,
            "content keeps its width so that scrolling can reach it"
        )
        assertTrue(scroll.horizontalScrollBar.isVisible, "the cut-off part has to stay reachable")
    }

    @Test
    fun `a form whose parts can compress keeps filling even a very narrow dock`() {
        // Wrapping text and squeezable table columns prefer to be wide but shrink on demand, so
        // they should never trade the dock width for a scrollbar.
        val content = form(preferred = floor * 3, cannotShrinkBelow = floor / 8)

        val scroll = laidOut(content, viewportWidth = floor / 2)

        assertEquals(scroll.viewport.width, scroll.viewport.view.width)
        assertFalse(scroll.horizontalScrollBar.isVisible)
    }

    @Test
    fun `a short form still fills the dock so nothing floats in a gap`() {
        val content = form(preferred = floor / 4)

        val scroll = laidOut(content, viewportWidth = floor * 2)

        assertEquals(scroll.viewport.width, scroll.viewport.view.width)
    }

    @Test
    fun `a capped column keeps its own minimum instead of collapsing to it`() {
        val table = JBTable(DefaultTableModel(arrayOf("a", "b"), 0))

        // Swing lowers minWidth to fit a smaller maxWidth, so a naive min-then-max order would pin
        // the column at 110 and it would never shrink with the dock.
        table.fitColumns(
            ColumnWidth(min = 46, preferred = 66, max = 110),
            ColumnWidth(min = 70, preferred = 150)
        )

        val capped = table.columnModel.getColumn(0)
        assertEquals(JBUI.scale(46), capped.minWidth)
        assertEquals(JBUI.scale(66), capped.preferredWidth)
        assertEquals(JBUI.scale(110), capped.maxWidth)

        val open = table.columnModel.getColumn(1)
        assertEquals(JBUI.scale(70), open.minWidth)
        assertEquals(JBUI.scale(150), open.preferredWidth)
    }

    @Test
    fun `columns left out of the spec are untouched so hidden id columns stay hidden`() {
        val table = JBTable(DefaultTableModel(arrayOf("a", "id"), 0))
        table.columnModel.getColumn(1).apply {
            minWidth = 0
            maxWidth = 0
        }

        table.fitColumns(ColumnWidth(min = 70, preferred = 150))

        assertEquals(0, table.columnModel.getColumn(1).maxWidth)
    }
}
