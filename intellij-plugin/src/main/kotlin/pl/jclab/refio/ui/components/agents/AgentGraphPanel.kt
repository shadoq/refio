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

    /** Raise iteration count to at least [iteration] (TurnStarted events use 1-based monotonic iteration). */
    fun bumpIterations(agentId: String, iteration: Int) {
        nodes[agentId]?.apply {
            if (iteration > iterationCount) {
                iterationCount = iteration
                repaint()
            }
        }
    }

    fun addTokens(agentId: String, delta: Long) {
        if (delta <= 0) return
        nodes[agentId]?.apply {
            tokensUsed += delta
            repaint()
        }
    }

    fun addDuration(agentId: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        nodes[agentId]?.apply {
            durationMs += deltaMs
            repaint()
        }
    }

    fun iterationsOf(agentId: String): Int = nodes[agentId]?.iterationCount ?: 0

    fun clear() {
        nodes.clear()
        removeAll()
        revalidate()
        repaint()
    }

    fun toText(): String = buildString {
        if (nodes.isEmpty()) {
            appendLine("(no agents)")
            return@buildString
        }
        for ((id, node) in nodes) {
            val indent = "  ".repeat(node.depth)
            val metrics = buildString {
                append("iter: ${node.iterationCount}")
                if (node.durationMs > 0) append(" | ${node.durationMs / 1000}s")
                if (node.tokensUsed > 0) append(" | ${node.tokensUsed}t")
                node.providerName?.let { append(" | $it") }
                if (node.queuePosition > 0) append(" | queued #${node.queuePosition}")
            }
            appendLine("${indent}[${node.status}] ${node.agentName}  ($metrics)")
        }
    }

    override fun getPreferredSize(): Dimension {
        val h = (nodes.size * 54).coerceAtLeast(100)
        return Dimension(250, h)
    }
}
