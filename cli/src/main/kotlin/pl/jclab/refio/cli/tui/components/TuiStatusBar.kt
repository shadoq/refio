package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Status bar component — renders at the bottom of the terminal.
 * Shows: health, mode, model, execution status, context usage, session/global metrics, quit hint.
 */
object TuiStatusBar {
    fun render(terminal: Terminal, state: TuiState, width: Int): String {
        // Health indicator
        val health = if (state.coreConnected) TuiColors.statusSuccess("●") else TuiColors.statusFailed("●")

        val mode = TuiColors.accent("[${state.mode}|${state.model ?: "default"}]")

        // Execution status
        val execStatus = if (state.executionStatus != "Idle" && state.isStreaming) {
            TuiColors.statusRunning(" ${state.executionStatus}")
        } else if (state.isStreaming) {
            TuiColors.statusRunning(" streaming...")
        } else ""

        // Context usage bar
        val ctxBar = renderContextBar(state)

        // Session metrics
        val sessionIn = formatTokens(state.sessionTokensIn)
        val sessionOut = formatTokens(state.sessionTokensOut)
        val sessionMetrics = TuiColors.muted(" ⬇$sessionIn ⬆$sessionOut")

        // Requests count
        val reqCount = state.apiLogs.size
        val requests = TuiColors.muted(" ${reqCount}req")

        // Cost (global)
        val cost = TuiColors.muted(" \$${String.format(java.util.Locale.US, "%.4f", state.totalCostUsd)}")

        val newSession = TuiColors.muted(" [Ctrl+W:New]")
        val quit = TuiColors.muted(" [Ctrl+Q]")
        return "$health $mode$execStatus $ctxBar$sessionMetrics$requests$cost$newSession$quit"
    }

    private fun renderContextBar(state: TuiState): String {
        val used = state.contextUsedTokens
        val max = state.contextMaxTokens
        if (max <= 0) return ""

        val pct = (used.toDouble() / max * 100).toInt().coerceIn(0, 100)
        val barWidth = 10
        val filled = (barWidth * pct / 100).coerceIn(0, barWidth)
        val empty = barWidth - filled

        val barColor = when {
            pct >= 90 -> TuiColors.statusFailed
            pct >= 75 -> TuiColors.statusPending
            pct >= 50 -> TuiColors.statusPending
            else -> TuiColors.statusSuccess
        }
        return "[${barColor("█".repeat(filled))}${TuiColors.muted("░".repeat(empty))} ${pct}%]"
    }

    private fun formatTokens(tokens: Long): String {
        return if (tokens > 1000) "${String.format(java.util.Locale.US, "%.1f", tokens / 1000.0)}K" else tokens.toString()
    }
}
