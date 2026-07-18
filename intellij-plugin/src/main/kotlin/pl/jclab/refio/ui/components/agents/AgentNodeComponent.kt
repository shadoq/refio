package pl.jclab.refio.ui.components.agents

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JPanel

/**
 * Visual representation of a single agent in the execution graph.
 * Shows agent name, status, iteration count, and duration.
 */
class AgentNodeComponent(
    val agentName: String,
    val depth: Int
) : JPanel() {

    var status: AgentNodeStatus = AgentNodeStatus.PENDING
        set(value) { field = value; repaint() }

    var iterationCount: Int = 0
        set(value) { field = value; repaint() }

    var tokensUsed: Long = 0
    var durationMs: Long = 0
    var providerName: String? = null
    var queuePosition: Int = 0  // 0 = not queued, >0 = position in queue

    init {
        preferredSize = Dimension(220, 48)
        minimumSize = Dimension(180, 42)
        isOpaque = false
    }

    // Cap height so BoxLayout.Y_AXIS does not stretch nodes vertically
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val color = when (status) {
            AgentNodeStatus.PENDING -> JBColor(Color(110, 110, 110), Color(150, 150, 150))
            AgentNodeStatus.RUNNING -> JBColor(Color(59, 130, 246), Color(100, 160, 255))
            AgentNodeStatus.COMPLETED -> JBColor(Color(22, 160, 74), Color(80, 200, 120))
            AgentNodeStatus.FAILED -> JBColor(Color(220, 50, 50), Color(240, 100, 100))
            AgentNodeStatus.WAITING -> JBColor(Color(200, 140, 20), Color(251, 191, 36))
            AgentNodeStatus.QUEUED -> JBColor(Color(140, 70, 220), Color(190, 130, 255))  // purple
        }

        // Background rounded rect
        g2.color = Color(color.red, color.green, color.blue, 30)
        g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)

        // Border
        g2.color = color
        g2.stroke = BasicStroke(1.5f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)

        // Status indicator dot
        g2.color = color
        g2.fillOval(8, (height - 8) / 2, 8, 8)

        // Agent name
        g2.color = UIUtil.getLabelForeground()
        g2.font = g2.font.deriveFont(Font.BOLD, 12f)
        g2.drawString(agentName, 22, height / 2 - 3)

        // Metrics line
        g2.font = g2.font.deriveFont(Font.PLAIN, 10f)
        g2.color = UIUtil.getContextHelpForeground()
        val metrics = buildString {
            append("iter: $iterationCount")
            if (durationMs > 0) append(" | ${durationMs / 1000}s")
            if (tokensUsed > 0) append(" | ${tokensUsed}t")
            providerName?.let { append(" | $it") }
            if (queuePosition > 0) append(" | queued #$queuePosition")
        }
        g2.drawString(metrics, 22, height / 2 + 12)

        g2.dispose()
    }
}

enum class AgentNodeStatus {
    PENDING, RUNNING, COMPLETED, FAILED, WAITING, QUEUED
}
