package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Debug tab view — session state, core health, API statistics, context budget.
 */
object TuiDebugView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        // Build all content lines first, then apply scroll
        val allLines = buildDebugLines(state, width)

        // Apply scroll offset
        val scrollOffset = state.debugScrollOffset.coerceIn(0, (allLines.size - height + 2).coerceAtLeast(0))
        val visible = allLines.drop(scrollOffset).take(height - 1) // -1 for toolbar

        for (line in visible) {
            buf.addLine(line)
        }

        // Fill remaining
        val remaining = (height - 1) - visible.size
        repeat(remaining.coerceAtLeast(0)) { buf.addLine("") }

        // Toolbar
        if (allLines.size > height - 1) {
            val pct = if (allLines.size > height) ((scrollOffset.toDouble() / (allLines.size - height + 2).coerceAtLeast(1)) * 100).toInt() else 0
            buf.addLine(TuiColors.muted("  [↑↓] Scroll  [PgUp/PgDn] Fast scroll  ${scrollOffset + 1}/${allLines.size} ($pct%)"))
        } else {
            buf.addLine(TuiColors.muted("  [↑↓] Scroll"))
        }

        return buf
    }

    private fun buildDebugLines(state: TuiState, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val debug = state.debugInfo

        lines.add(bold("Session State"))
        lines.add(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        lines.add("  ID:       ${debug.sessionId.ifEmpty { "N/A" }}")
        lines.add("  Mode:     ${debug.mode}")
        lines.add("  Model:    ${debug.model}")
        lines.add("  Status:   ${debug.status}")
        lines.add("  Tokens:   ${debug.tokensIn} in / ${debug.tokensOut} out")
        lines.add("  Cost:     \$${String.format("%.4f", debug.costUsd)}")
        lines.add("  Messages: ${debug.messageCount}")
        lines.add("")

        lines.add(bold("Core Health"))
        lines.add(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        val connStatus = if (debug.connected) {
            TuiColors.statusSuccess("Connected")
        } else {
            TuiColors.statusFailed("Disconnected")
        }
        lines.add("  Connection: $connStatus")
        lines.add("  DB Path:    ${debug.dbPath.ifEmpty { "N/A" }}")
        lines.add("")

        // API Statistics (from api logs)
        if (state.apiLogs.isNotEmpty()) {
            lines.add(bold("API Statistics"))
            lines.add(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
            val totalCalls = state.apiLogs.size
            val totalCost = state.apiLogs.sumOf { it.costUsd }
            val totalTokensIn = state.apiLogs.sumOf { it.tokensIn }
            val totalTokensOut = state.apiLogs.sumOf { it.tokensOut }
            val avgLatency = state.apiLogs.map { it.latencyMs }.average().toInt()
            val errors = state.apiLogs.count { it.errorType != null }
            val byProvider = state.apiLogs.groupBy { it.provider }

            lines.add("  Total calls:    $totalCalls")
            lines.add("  Total cost:     \$${String.format("%.4f", totalCost)}")
            lines.add("  Total tokens:   ${totalTokensIn} in / ${totalTokensOut} out")
            lines.add("  Avg latency:    ${avgLatency}ms")
            val errColor = if (errors > 0) TuiColors.statusFailed else TuiColors.statusSuccess
            lines.add("  Errors:         ${errColor(errors.toString())}")

            // Per-provider breakdown
            lines.add("  Providers:")
            for ((prov, logs) in byProvider) {
                val provCost = logs.sumOf { it.costUsd }
                val provTok = logs.sumOf { it.tokensIn + it.tokensOut }
                lines.add("    ${TuiColors.accent(prov)}: ${logs.size} calls, \$${String.format("%.4f", provCost)}, ${provTok} tok")
            }
            lines.add("")
        }

        // Context budget
        if (state.contextMaxTokens > 0) {
            lines.add(bold("Context Budget"))
            lines.add(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
            val pct = (state.contextUsedTokens.toDouble() / state.contextMaxTokens * 100).toInt()
            val pctColor = when {
                pct >= 90 -> TuiColors.statusFailed
                pct >= 75 -> TuiColors.statusPending
                else -> TuiColors.statusSuccess
            }
            lines.add("  Used:     ${state.contextUsedTokens} / ${state.contextMaxTokens} tokens (${pctColor("$pct%")})")
            lines.add("  Session:  ${state.sessionTokensIn} in / ${state.sessionTokensOut} out")
            if (state.contextSections.isNotEmpty()) {
                lines.add("  Sections: ${state.contextSections.size}")
                for (section in state.contextSections.take(8)) {
                    val secPct = if (section.percentage > 0) " (${String.format("%.1f", section.percentage)}%)" else ""
                    lines.add("    ${section.name.padEnd(25)} ${section.tokensUsed} tok$secPct")
                }
            }
            lines.add("")
        }

        // Agent flow (when multi-agent session is active)
        if (state.agents.isNotEmpty()) {
            val agentBuf = TuiAgentFlowView.renderToBuffer(state, width, 30)
            for (line in agentBuf.getLines()) {
                lines.add(line)
            }
        }

        return lines
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
