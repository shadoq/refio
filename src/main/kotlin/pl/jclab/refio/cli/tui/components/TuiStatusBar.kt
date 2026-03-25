package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Status bar component — renders at the bottom of the terminal.
 * Shows: mode, model, streaming status, cost/tokens, quit hint.
 */
object TuiStatusBar {
    fun render(terminal: Terminal, state: TuiState, width: Int): String {
        val mode = TuiColors.accent("[${state.mode}|${state.model ?: "default"}]")
        val streaming = if (state.isStreaming) TuiColors.statusRunning(" streaming...") else ""
        val cost = TuiColors.muted(" \$${String.format("%.4f", state.totalCostUsd)}")
        val tokens = TuiColors.muted(" | ${formatTokens(state.totalTokens)} tok")
        val quit = TuiColors.muted(" [Ctrl+Q]")
        return "$mode$streaming$cost$tokens$quit"
    }

    private fun formatTokens(tokens: Long): String {
        return if (tokens > 1000) "${String.format("%.1f", tokens / 1000.0)}K" else tokens.toString()
    }
}
