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
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Two-line renderer for the session list: status icon + title + mode on the first line,
 * timestamp / duration / tokens on the second.
 *
 * One shared instance paints every row, so nothing may be constructed here per call - the
 * components are cleared and refilled instead.
 */
class SessionListRenderer : ListCellRenderer<SessionRow> {

    private val statusIcon = JBLabel()
    private val pinIcon = JBLabel()
    private val title = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.emptyInsets()
    }
    private val meta = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.emptyInsets()
    }

    private val line1 = JPanel(HorizontalLayout(JBUI.scale(4))).apply { isOpaque = false }
    private val panel = JPanel(VerticalLayout(JBUI.scale(1))).apply {
        border = JBUI.Borders.empty(3, 8)
        isOpaque = true
    }

    init {
        line1.add(statusIcon)
        line1.add(title)
        line1.add(pinIcon)
        panel.add(line1)
        panel.add(meta)
    }

    override fun getListCellRendererComponent(
        list: JList<out SessionRow>,
        value: SessionRow?,
        index: Int,
        selected: Boolean,
        focused: Boolean
    ): Component {
        if (value == null) return panel

        val bg = if (selected) JBUI.CurrentTheme.List.Selection.background(focused) else list.background
        val fg = if (selected) JBUI.CurrentTheme.List.Selection.foreground(focused) else list.foreground

        panel.background = bg
        title.background = bg
        meta.background = bg

        statusIcon.icon = when (value.status) {
            SessionRow.Status.OK -> AllIcons.RunConfigurations.TestPassed
            SessionRow.Status.FAILED -> AllIcons.RunConfigurations.TestFailed
            SessionRow.Status.PARTIAL -> AllIcons.General.Warning
            SessionRow.Status.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            SessionRow.Status.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
        }
        statusIcon.toolTipText = value.statusName

        pinIcon.icon = if (value.pinned) AllIcons.Nodes.Favorite else null

        title.clear()
        title.append(
            StringUtil.shortenTextWithEllipsis(value.title, 42, 10, true),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg)
        )
        title.append(
            "  ${value.kind.name}",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, kindColor(value.kind))
        )

        meta.clear()
        meta.append(value.metaText, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        return panel
    }

    private fun kindColor(kind: TaskMode) = when (kind) {
        TaskMode.AGENT -> JBColor.namedColor("Plugins.tagForeground", JBColor.ORANGE)
        TaskMode.PLAN -> JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        TaskMode.CHAT -> JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
    }
}
