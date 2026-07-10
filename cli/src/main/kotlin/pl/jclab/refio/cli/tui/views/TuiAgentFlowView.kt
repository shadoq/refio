package pl.jclab.refio.cli.tui.views

import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiAgentState
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * ASCII DAG visualization for multi-agent sessions.
 * Shows agents as boxes with dependency arrows, status, and cost.
 *
 * Example:
 * ```
 * ┌──────────────┐     ┌───────────────┐
 * │ architect     │────>│ api-designer  │
 * │ ✓ COMPLETED   │     │ ● RUNNING     │
 * │ $0.023        │     │ $0.012        │
 * └──────────────┘     └───────┬───────┘
 *                              │
 *                       ┌──────▼───────┐
 *                       │ code-reviewer│
 *                       │ ○ PENDING    │
 *                       └──────────────┘
 * ```
 */
object TuiAgentFlowView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val agents = state.agents

        if (agents.isEmpty()) {
            buf.addLine(TuiColors.muted("No active agents."))
            return buf
        }

        buf.addLine(TuiColors.highlight("Agent Flow (${agents.size} agents)"))
        buf.addLine()

        // Topological sort by dependencies
        val sorted = topologicalSort(agents)

        // Render each agent as a compact box
        for (agent in sorted) {
            val statusIcon = when (agent.status) {
                "RUNNING" -> TuiColors.statusRunning("●")
                "COMPLETED" -> TuiColors.statusSuccess("✓")
                "FAILED" -> TuiColors.statusFailed("✗")
                "WAITING_APPROVAL" -> TuiColors.statusPending("?")
                else -> TuiColors.muted("○")
            }
            val statusColor = when (agent.status) {
                "RUNNING" -> TuiColors.statusRunning
                "COMPLETED" -> TuiColors.statusSuccess
                "FAILED" -> TuiColors.statusFailed
                "WAITING_APPROVAL" -> TuiColors.statusPending
                else -> TuiColors.muted
            }
            val agentColor = TuiColors.forAgent(agent.colorIndex)

            val nameStr = agent.name.take(20)
            val boxWidth = (nameStr.length + 4).coerceAtLeast(20)
            val border = "─".repeat(boxWidth - 2)

            // Dependencies line
            if (agent.dependsOn.isNotEmpty()) {
                val deps = agent.dependsOn.joinToString(", ") { it.take(12) }
                buf.addLine(TuiColors.muted("  ↑ depends on: $deps"))
            }

            buf.addLine(TuiColors.border("  ┌$border┐"))
            buf.addLine(TuiColors.border("  │") + agentColor(" ${nameStr.padEnd(boxWidth - 4)} ") + TuiColors.border("│"))

            val statusLine = " $statusIcon ${statusColor(agent.status.padEnd(boxWidth - 8))} "
            buf.addLine(TuiColors.border("  │") + statusLine + TuiColors.border("│"))

            if (agent.costUsd > 0 || agent.tokensUsed > 0) {
                val metrics = " \$${String.format(java.util.Locale.US, "%.4f", agent.costUsd)} ${formatTokens(agent.tokensUsed)}"
                val metricsPadded = metrics.take(boxWidth - 4).padEnd(boxWidth - 4)
                buf.addLine(TuiColors.border("  │") + TuiColors.muted(" $metricsPadded ") + TuiColors.border("│"))
            }

            if (agent.currentPhase != null) {
                val phasePadded = agent.currentPhase.take(boxWidth - 4).padEnd(boxWidth - 4)
                buf.addLine(TuiColors.border("  │") + TuiColors.muted(" $phasePadded ") + TuiColors.border("│"))
            }

            buf.addLine(TuiColors.border("  └$border┘"))

            if (buf.lineCount >= height - 2) break
        }

        return buf
    }

    private fun topologicalSort(agents: List<TuiAgentState>): List<TuiAgentState> {
        val byId = agents.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<TuiAgentState>()

        fun visit(agent: TuiAgentState) {
            if (agent.id in visited) return
            visited.add(agent.id)
            for (depId in agent.dependsOn) {
                byId[depId]?.let { visit(it) }
            }
            result.add(agent)
        }

        for (agent in agents) {
            visit(agent)
        }
        return result
    }

    private fun formatTokens(tokens: Long): String {
        return if (tokens > 1000) "${String.format(java.util.Locale.US, "%.1f", tokens / 1000.0)}K tok" else "$tokens tok"
    }
}
