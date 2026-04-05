package pl.jclab.refio.ui.components.agents

import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Panel displaying the agent execution DAG as a vertical list of AgentNodeComponents.
 * Nodes are indented by depth level.
 */
class AgentGraphPanel : JPanel() {

    private val nodes = mutableMapOf<String, AgentNodeComponent>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    fun addOrUpdateAgent(agentId: String, name: String, depth: Int, status: AgentNodeStatus) {
        val existing = nodes[agentId]
        if (existing != null) {
            existing.status = status
            return
        }

        val node = AgentNodeComponent(name, depth).apply {
            this.status = status
            alignmentX = LEFT_ALIGNMENT
            // Indent by depth
            border = javax.swing.BorderFactory.createEmptyBorder(2, depth * 24, 2, 4)
        }
        nodes[agentId] = node
        add(node)
        revalidate()
        repaint()
    }

    fun updateMetrics(agentId: String, iterations: Int, tokens: Long, durationMs: Long) {
        nodes[agentId]?.apply {
            iterationCount = iterations
            tokensUsed = tokens
            this.durationMs = durationMs
            repaint()
        }
    }

    fun clear() {
        nodes.clear()
        removeAll()
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val h = (nodes.size * 54).coerceAtLeast(100)
        return Dimension(250, h)
    }
}
