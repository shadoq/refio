package pl.jclab.refio.ui.settings

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.IntelliJSpacingConfiguration
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

/**
 * Shared layout support for the settings pages, which run in a docked tool window that is
 * frequently only a few hundred pixels wide.
 */
internal object NarrowDock {

    /**
     * Width the settings content is allowed to shrink to before it starts scrolling sideways.
     * Every table on a settings page keeps the sum of its column minimums under this number so
     * that no column can be pushed out of view while the content still fills the dock.
     */
    const val MIN_CONTENT_WIDTH = 320
}

/**
 * Builds a settings page with the platform's left indents trimmed.
 *
 * The DSL indents the content of every group by about a checkbox width, which reads well in a wide
 * dialog but costs a docked settings page a visible slice of the little width it has. Use this in
 * place of `panel { }` on every settings page so the grouping stays and the controls get the space
 * back.
 */
internal fun settingsForm(init: Panel.() -> Unit): DialogPanel = panel {
    customizeSpacingConfiguration(CompactSettingsSpacing) { init() }
}

/** Unscaled indents; the DSL scales them for the current display. */
private object CompactSettingsSpacing : IntelliJSpacingConfiguration() {
    override val horizontalIndent: Int = 8
    override val horizontalToggleButtonIndent: Int = 8
}

/**
 * Wraps a settings page in its scroll container.
 *
 * A plain [JScrollPane] lays a non-[Scrollable] view out at its preferred width and never below it.
 * In a narrow dock that silently cuts off the right-hand side of a form - with the horizontal
 * scrollbar disabled there is not even a way to reach it. This wrapper instead makes the form adopt
 * the viewport width for as long as it can honestly shrink to it, so wrapping comments wrap to what
 * the user can actually see, and only falls back to a horizontal scrollbar when the dock is too
 * narrow even for that. Either way nothing is lost without a way to get at it.
 */
internal fun settingsScrollPane(content: JComponent): JBScrollPane =
    JBScrollPane(ViewportWidthAwarePanel(content)).also { configureSettingsScroll(it) }

/**
 * Applies the settings scroll policy to [pane]. Separate from [settingsScrollPane] so the policy is
 * exercisable without constructing a [JBScrollPane], whose scrollbar UI needs a running IDE.
 */
internal fun configureSettingsScroll(pane: JScrollPane) {
    pane.border = LCATheme.emptyBorder()
    pane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    pane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
}

/**
 * Settings content that follows the viewport width instead of insisting on its preferred width.
 */
internal class ViewportWidthAwarePanel(
    content: JComponent
) : JBPanel<ViewportWidthAwarePanel>(BorderLayout()), Scrollable {

    init {
        isOpaque = false
        add(content, BorderLayout.CENTER)
    }

    override fun getScrollableTracksViewportWidth(): Boolean {
        val viewportWidth = (parent as? JViewport)?.width ?: return true
        return tracksWidth(viewportWidth, minimumSize.width)
    }

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(UNIT_INCREMENT)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    companion object {

        private const val UNIT_INCREMENT = 16

        /**
         * Whether the content should be squeezed into [viewportWidth] rather than keeping its
         * preferred width and scrolling.
         *
         * Content that can honestly shrink further keeps filling the dock, however narrow it gets.
         * The floor only covers content that reports a minimum wider than a settings page is
         * expected to need - squeezing that would clip it, and a scrollbar is the honest answer.
         */
        fun tracksWidth(viewportWidth: Int, contentMinimumWidth: Int): Boolean =
            viewportWidth >= minOf(contentMinimumWidth, JBUI.scale(NarrowDock.MIN_CONTENT_WIDTH))
    }
}

/** Width bounds of a single settings table column, in unscaled design pixels. */
internal data class ColumnWidth(val min: Int, val preferred: Int, val max: Int? = null)

/**
 * Applies [widths] to the leading columns of this table.
 *
 * Column widths are device pixels, so each value goes through [JBUI.scale]. Columns beyond
 * [widths] are left untouched, which is how the hidden id columns keep their zero width.
 */
internal fun JBTable.fitColumns(vararg widths: ColumnWidth) {
    widths.forEachIndexed { index, spec ->
        columnModel.getColumn(index).applyWidth(spec)
    }
}

private fun TableColumn.applyWidth(spec: ColumnWidth) {
    // maxWidth first: setMinWidth() raises a smaller maximum along with it, which would silently
    // pin the column to its minimum.
    spec.max?.let { maxWidth = JBUI.scale(it) }
    minWidth = JBUI.scale(spec.min)
    preferredWidth = JBUI.scale(spec.preferred)
}

/**
 * Makes truncated cell text readable on hover.
 *
 * At dock width most values do not fit their column, and a table gives no tooltip for elided text
 * on its own, so the full value would be unreachable. Columns with their own renderer (the short,
 * colour-coded ones) keep it.
 */
internal fun JBTable.showFullValueOnHover() {
    setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            toolTipText = value?.toString()?.takeIf { it.isNotBlank() }
            return component
        }
    })
}
