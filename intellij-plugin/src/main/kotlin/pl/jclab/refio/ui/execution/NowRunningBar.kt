package pl.jclab.refio.ui.execution

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.Alarm
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Sticky bar shown above the chat transcript while a turn is running.
 *
 * Answers the three questions a waiting user has - which tool, which step, how long - and offers
 * the stop action without scrolling. Hidden whenever the engine is idle.
 */
class NowRunningBar(
    parent: Disposable,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val spinner = AsyncProcessIcon("refio-now")
    private val stepLabel = JBLabel().apply { font = JBUI.Fonts.smallFont() }
    private val detailLabel = JBLabel().apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        foreground = JBColor.namedColor("Plugins.tagForeground", JBColor.GRAY)
    }
    private val elapsedLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getInactiveTextColor()
    }
    private val stopButton = InplaceButton(
        IconButton("Stop", AllIcons.Actions.Suspend)
    ) { onStop() }
    private val progress = JProgressBar().apply {
        isIndeterminate = true
        preferredSize = Dimension(0, JBUI.scale(2))
        putClientProperty("JProgressBar.flatEnds", true)
    }

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var startedAt = 0L
    private var state: NowRunningState = NowRunningState.HIDDEN

    init {
        val row = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            add(spinner)
            add(stepLabel)
            add(detailLabel)
        }
        val trailing = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            add(elapsedLabel)
            add(stopButton)
        }

        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLineBottom(JBColor.border())
        add(row, BorderLayout.WEST)
        add(trailing, BorderLayout.EAST)
        add(progress, BorderLayout.SOUTH)
        isVisible = false

        Disposer.register(parent, this)
    }

    /** Applies a turn snapshot. Safe to call on every emission; only real changes touch the UI. */
    fun update(snapshot: TurnStateSnapshot) {
        val next = NowRunningState.from(snapshot)

        if (next.visible && !state.visible) {
            startedAt = System.currentTimeMillis()
            scheduleTick()
        }

        state = next

        if (!next.visible) {
            isVisible = false
            spinner.suspend()
            alarm.cancelAllRequests()
            return
        }

        stepLabel.text = next.stepText
        detailLabel.text = next.detailText
        progress.isIndeterminate = next.busy
        if (next.busy) spinner.resume() else spinner.suspend()
        spinner.isVisible = next.busy
        isVisible = true
    }

    /** Elapsed time is local wall clock, so it ticks even between engine emissions. */
    private fun scheduleTick() {
        if (alarm.isDisposed) return
        elapsedLabel.text = NowRunningState.formatElapsed(System.currentTimeMillis() - startedAt)
        alarm.addRequest({ if (state.visible) scheduleTick() }, TICK_MS)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        spinner.suspend()
    }

    private companion object {
        const val TICK_MS = 250
    }
}
