package pl.jclab.refio.ui.components.steps

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * One-line renderer for execution steps: status icon, number, tool, description on the left and
 * the duration right-aligned. `SimpleColoredComponent` cannot right-align, hence the BorderLayout.
 */
class StepListRenderer : ListCellRenderer<StepRowView> {

    private val text = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.emptyInsets()
    }
    private val duration = JBLabel().apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        foreground = UIUtil.getInactiveTextColor()
        horizontalAlignment = SwingConstants.RIGHT
    }
    private val panel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        border = JBUI.Borders.empty(3, 8)
        isOpaque = true
        add(text, BorderLayout.CENTER)
        add(duration, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out StepRowView>,
        value: StepRowView?,
        index: Int,
        selected: Boolean,
        focused: Boolean
    ): Component {
        if (value == null) return panel

        val bg = if (selected) JBUI.CurrentTheme.List.Selection.background(focused) else list.background
        val fg = if (selected) JBUI.CurrentTheme.List.Selection.foreground(focused) else list.foreground

        panel.background = bg
        text.background = bg

        text.clear()
        text.icon = when (value.state) {
            StepRowView.State.OK -> AllIcons.RunConfigurations.TestPassed
            StepRowView.State.FAILED -> AllIcons.RunConfigurations.TestFailed
            StepRowView.State.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            StepRowView.State.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
            StepRowView.State.PENDING -> AllIcons.RunConfigurations.TestNotRan
        }
        text.toolTipText = value.statusName

        text.append("${value.number} ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        text.append(value.kind, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg))
        text.append(
            "  " + StringUtil.shortenTextWithEllipsis(value.title, 48, 12, true),
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        )

        duration.text = StepRowView.formatDuration(value.durationMs)

        return panel
    }
}
