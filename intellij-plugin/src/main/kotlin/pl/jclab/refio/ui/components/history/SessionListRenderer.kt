package pl.jclab.refio.ui.components.history

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import pl.jclab.refio.api.models.TaskMode
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Row renderer for the session list, in two shapes.
 *
 * Docked, the row stacks: status icon + title + mode on the first line, timestamp / duration /
 * tokens on the second. Undocked the panel is wide enough that stacking wastes most of the row,
 * so the metadata moves to the right edge of the same line and the row halves in height.
 *
 * Each shape owns its components and one shared instance paints every row, so nothing is
 * constructed or reparented per call - the components are cleared and refilled instead.
 */
class SessionListRenderer : ListCellRenderer<SessionRow> {

    private val stacked = Row(singleLine = false)
    private val inline = Row(singleLine = true)

    override fun getListCellRendererComponent(
        list: JList<out SessionRow>,
        value: SessionRow?,
        index: Int,
        selected: Boolean,
        focused: Boolean
    ): Component {
        val row = if (isSingleLine(list.width)) inline else stacked
        if (value == null) return row.panel

        val bg = if (selected) JBUI.CurrentTheme.List.Selection.background(focused) else list.background
        val fg = if (selected) JBUI.CurrentTheme.List.Selection.foreground(focused) else list.foreground

        row.panel.background = bg
        row.title.background = bg
        row.meta.background = bg

        row.statusIcon.icon = when (value.status) {
            SessionRow.Status.OK -> AllIcons.RunConfigurations.TestPassed
            SessionRow.Status.FAILED -> AllIcons.RunConfigurations.TestFailed
            SessionRow.Status.PARTIAL -> AllIcons.General.Warning
            SessionRow.Status.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            SessionRow.Status.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
        }
        row.statusIcon.toolTipText = value.statusName

        row.pinIcon.icon = if (value.pinned) AllIcons.Nodes.Favorite else null

        row.meta.clear()
        row.meta.append(value.metaText, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        row.title.clear()
        row.title.append(
            row.fitTitle(value.title, list.width, value.metaText),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg)
        )
        row.title.append(
            "  ${value.kind.name}",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, kindColor(value.kind))
        )

        return row.panel
    }

    private fun kindColor(kind: TaskMode) = when (kind) {
        TaskMode.AGENT -> JBColor.namedColor("Plugins.tagForeground", JBColor.ORANGE)
        TaskMode.PLAN -> JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        TaskMode.CHAT -> JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
    }

    /** One row shape with its own components, so switching shapes never reparents anything. */
    private class Row(private val singleLine: Boolean) {

        val statusIcon = JBLabel()
        val pinIcon = JBLabel()
        val title = SimpleColoredComponent().apply {
            isOpaque = false
            ipad = JBUI.emptyInsets()
        }
        val meta = SimpleColoredComponent().apply {
            isOpaque = false
            ipad = JBUI.emptyInsets()
        }

        private val leading = JPanel(HorizontalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(statusIcon)
            add(title)
            add(pinIcon)
        }

        val panel: JPanel = if (singleLine) {
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 8)
                isOpaque = true
                add(leading, BorderLayout.WEST)
                add(meta, BorderLayout.EAST)
            }
        } else {
            JPanel(VerticalLayout(JBUI.scale(1))).apply {
                border = JBUI.Borders.empty(3, 8)
                isOpaque = true
                add(leading)
                add(meta)
            }
        }

        /**
         * Title shortened to what the row can actually show.
         *
         * Was a flat 42 characters regardless of width, which left an undocked panel showing
         * ellipsised titles next to hundreds of empty pixels. The budget is derived from the row
         * width instead, minus the metadata when it shares the line.
         */
        fun fitTitle(fullTitle: String, rowWidth: Int, metaText: String): String {
            if (rowWidth <= 0) {
                return StringUtil.shortenTextWithEllipsis(fullTitle, FALLBACK_MAX_CHARS, KEEP_TRAILING, true)
            }

            val metaWidth = if (singleLine) meta.getFontMetrics(meta.font).stringWidth(metaText) else 0
            val available = rowWidth - JBUI.scale(ROW_CHROME_WIDTH) - metaWidth
            val charWidth = title.getFontMetrics(title.font).charWidth('n').coerceAtLeast(1)
            val maxChars = (available / charWidth).coerceIn(MIN_MAX_CHARS, HARD_MAX_CHARS)

            return StringUtil.shortenTextWithEllipsis(fullTitle, maxChars, KEEP_TRAILING, true)
        }
    }

    companion object {
        /**
         * Cell height for a list of the given width.
         *
         * Lives here because it follows from the row shape: the single-line row is half the
         * height, and a list left at the stacked height would render it floating in empty space.
         */
        fun rowHeight(rowWidth: Int): Int =
            JBUI.scale(if (isSingleLine(rowWidth)) SINGLE_LINE_ROW_HEIGHT else STACKED_ROW_HEIGHT)

        /**
         * Row width from which the metadata fits beside the title. Matches the boundary the tool
         * window itself uses to switch to its wide layout.
         */
        private const val SINGLE_LINE_MIN_WIDTH = 560

        private const val STACKED_ROW_HEIGHT = 38
        private const val SINGLE_LINE_ROW_HEIGHT = 22

        /** Icons, gaps, borders and the mode suffix - everything on the row that is not the title. */
        private const val ROW_CHROME_WIDTH = 120

        private const val KEEP_TRAILING = 10
        private const val FALLBACK_MAX_CHARS = 42
        private const val MIN_MAX_CHARS = 16
        private const val HARD_MAX_CHARS = 200

        private fun isSingleLine(rowWidth: Int): Boolean = rowWidth >= JBUI.scale(SINGLE_LINE_MIN_WIDTH)
    }
}
