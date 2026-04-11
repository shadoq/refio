package pl.jclab.refio.ui.components.agents

import java.awt.*
import javax.swing.JPanel
import javax.swing.UIManager

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

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val color = when (status) {
            AgentNodeStatus.PENDING -> Color(128, 128, 128)
            AgentNodeStatus.RUNNING -> Color(59, 130, 246)
            AgentNodeStatus.COMPLETED -> Color(34, 197, 94)
            AgentNodeStatus.FAILED -> Color(239, 68, 68)
            AgentNodeStatus.WAITING -> Color(251, 191, 36)
            AgentNodeStatus.QUEUED -> Color(168, 85, 247)  // purple
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
        g2.color = UIManager.getColor("Label.foreground") ?: Color.WHITE
        g2.font = g2.font.deriveFont(Font.BOLD, 12f)
        g2.drawString(agentName, 22, height / 2 - 3)

        // Metrics line
        g2.font = g2.font.deriveFont(Font.PLAIN, 10f)
        g2.color = Color(150, 150, 150)
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
