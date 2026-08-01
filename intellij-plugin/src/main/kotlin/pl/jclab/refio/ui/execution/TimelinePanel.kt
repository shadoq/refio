package pl.jclab.refio.ui.execution

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRowView
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * The chat timeline: every tool call of the session as a 22 px row, with the turn's progress and
 * the stop action underneath.
 *
 * It exists for the wide panel only, where there is room for a column that answers "what has this
 * agent actually done" without scrolling the transcript. Clicking a row is the point of the whole
 * component - it jumps the transcript to that call - so rows stay one line and stay clickable.
 */
class TimelinePanel(
    private val onStop: () -> Unit,
    private val onStepSelected: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<TimelineStep>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(ROW_HEIGHT)
        cellRenderer = StepRenderer()
        background = UIUtil.getPanelBackground()
    }
    private val progress = JProgressBar().apply {
        isIndeterminate = false
        preferredSize = Dimension(0, JBUI.scale(2))
        putClientProperty("JProgressBar.flatEnds", true)
        isVisible = false
    }
    private val stepLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getInactiveTextColor()
    }
    private val stopButton = InplaceButton(IconButton("Stop", AllIcons.Actions.Suspend)) { onStop() }

    init {
        // A single click is the whole interaction; going through the mouse rather than the
        // selection listener keeps a re-selection of the same row working as a repeat jump.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index >= 0 && index < model.size()) {
                    onStepSelected(model.getElementAt(index).messageId)
                }
            }
        })

        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            add(
                JPanel(HorizontalLayout(JBUI.scale(6))).apply {
                    isOpaque = false
                    add(stepLabel)
                },
                BorderLayout.WEST
            )
            add(stopButton, BorderLayout.EAST)
        }

        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(progress, BorderLayout.NORTH)
            add(footer, BorderLayout.SOUTH)
        }

        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
        add(
            JBScrollPane(list).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER
        )
        add(south, BorderLayout.SOUTH)
        setRunning(TurnStateSnapshot())
    }

    /**
     * Replaces the rows. Selection is dropped on purpose: the list is rebuilt from the transcript
     * on every update, so keeping an index would silently point at a different call.
     */
    fun setSteps(steps: List<TimelineStep>) {
        val wasAtEnd = list.lastVisibleIndex >= model.size() - 1
        model.clear()
        steps.forEach { model.addElement(it) }
        if (wasAtEnd && !model.isEmpty) {
            list.ensureIndexIsVisible(model.size() - 1)
        }
    }

    /** Mirrors the turn state the "now running" bar would have shown, since only one is on screen. */
    fun setRunning(snapshot: TurnStateSnapshot) {
        val state = NowRunningState.from(snapshot)
        progress.isVisible = state.visible
        progress.isIndeterminate = state.busy
        stopButton.isVisible = state.visible
        stepLabel.text = if (state.visible) "${state.stepText} - ${state.detailText}" else ""
    }

    private inner class StepRenderer : ListCellRenderer<TimelineStep> {

        private val icon = JBLabel()
        // Not `name`: inside the row's `apply` block that would resolve to Component.getName().
        private val nameLabel = JBLabel().apply { font = JBUI.Fonts.create(Font.MONOSPACED, 11) }
        private val duration = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getInactiveTextColor()
        }
        private val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            border = JBUI.Borders.empty(0, 6)
            add(
                JPanel(HorizontalLayout(JBUI.scale(5))).apply {
                    isOpaque = false
                    add(icon)
                    add(nameLabel)
                },
                BorderLayout.WEST
            )
            add(duration, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out TimelineStep>,
            value: TimelineStep,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            icon.icon = when (value.state) {
                ToolCallRowView.State.OK -> AllIcons.General.InspectionsOK
                ToolCallRowView.State.FAILED -> AllIcons.General.Error
                ToolCallRowView.State.RUNNING -> AllIcons.Actions.Execute
            }
            nameLabel.text = value.name
            duration.text = value.durationText.orEmpty()
            row.background = if (isSelected) list.selectionBackground else list.background
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            return row
        }
    }

    private companion object {
        /** Handoff 07B: a step is one line - twenty of them have to fit without scrolling. */
        const val ROW_HEIGHT = 22
    }
}
