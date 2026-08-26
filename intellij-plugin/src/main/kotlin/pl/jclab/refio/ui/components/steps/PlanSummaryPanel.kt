package pl.jclab.refio.ui.components.steps

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Header above the step list: how the plan as a whole went.
 *
 * Without it the user had to read every step to learn whether anything failed and how long the
 * run took.
 */
class PlanSummaryPanel : JPanel(VerticalLayout(JBUI.scale(4))) {

    private val line = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.emptyInsets()
    }
    private val bar = SegmentedProgress()

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(6, 8)
        )
        background = UIUtil.getPanelBackground()
        add(line)
        add(bar)
    }

    fun update(steps: List<StepRowView>) {
        val summary = PlanSummaryModel.from(steps)

        line.clear()
        if (summary.total == 0) {
            line.append("No steps planned", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            line.append("Plan  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            line.append("${summary.total} steps  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            line.append(
                "${summary.ok} ok  ",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GREEN)
            )
            if (summary.hasFailures) {
                line.append(
                    "${summary.failed} failed  ",
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED)
                )
            }
            line.append(
                StepRowView.formatDuration(summary.totalDurationMs),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            )
        }

        bar.setSteps(steps)
        revalidate()
        repaint()
    }
}

/**
 * Four-pixel strip with one segment per step: width proportional to how long the step took,
 * colour by outcome. Shows at a glance where a run spent its time.
 */
class SegmentedProgress : JComponent() {

    private var steps: List<StepRowView> = emptyList()

    init {
        preferredSize = Dimension(0, JBUI.scale(4))
    }

    fun setSteps(steps: List<StepRowView>) {
        this.steps = steps
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (steps.isEmpty() || width <= 0) return

        val g2 = g as Graphics2D
        GraphicsUtil.setupAAPainting(g2)

        val gap = JBUI.scale(2)
        // A step with no measured duration still gets a sliver, so pending steps stay visible.
        val total = steps.sumOf { maxOf(it.durationMs, 1L) }.toDouble()
        val available = (width - gap * maxOf(steps.size - 1, 0)).coerceAtLeast(1)
        var x = 0f

        steps.forEach { step ->
            val segmentWidth = (available * (maxOf(step.durationMs, 1L) / total)).toFloat()
            g2.color = when (step.state) {
                StepRowView.State.OK -> JBColor.GREEN
                StepRowView.State.FAILED -> JBColor.RED
                StepRowView.State.RUNNING -> JBUI.CurrentTheme.Focus.focusColor()
                else -> JBColor.border()
            }
            g2.fill(
                RoundRectangle2D.Float(
                    x, 0f, segmentWidth, height.toFloat(),
                    JBUIScale.scale(2f), JBUIScale.scale(2f)
                )
            )
            x += segmentWidth + gap
        }
    }
}
