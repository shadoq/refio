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
        val debug = state.debugInfo

        buf.addLine(bold("Session State"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        buf.addLine("  ID:       ${debug.sessionId.ifEmpty { "N/A" }}")
        buf.addLine("  Mode:     ${debug.mode}")
        buf.addLine("  Model:    ${debug.model}")
        buf.addLine("  Status:   ${debug.status}")
        buf.addLine("  Tokens:   ${debug.tokensIn} in / ${debug.tokensOut} out")
        buf.addLine("  Cost:     \$${String.format("%.4f", debug.costUsd)}")
        buf.addLine("  Messages: ${debug.messageCount}")
        buf.addLine()

        buf.addLine(bold("Core Health"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        val connStatus = if (debug.connected) {
            TuiColors.statusSuccess("Connected")
        } else {
            TuiColors.statusFailed("Disconnected")
        }
        buf.addLine("  Connection: $connStatus")
        buf.addLine("  DB Path:    ${debug.dbPath.ifEmpty { "N/A" }}")
        buf.addLine()

        // API Statistics (from api logs)
        if (state.apiLogs.isNotEmpty()) {
            buf.addLine(bold("API Statistics"))
            buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
            val totalCalls = state.apiLogs.size
            val totalCost = state.apiLogs.sumOf { it.costUsd }
            val totalTokensIn = state.apiLogs.sumOf { it.tokensIn }
            val totalTokensOut = state.apiLogs.sumOf { it.tokensOut }
            val avgLatency = state.apiLogs.map { it.latencyMs }.average().toInt()
            val errors = state.apiLogs.count { it.errorType != null }
            val byProvider = state.apiLogs.groupBy { it.provider }

            buf.addLine("  Total calls:    $totalCalls")
            buf.addLine("  Total cost:     \$${String.format("%.4f", totalCost)}")
            buf.addLine("  Total tokens:   ${totalTokensIn} in / ${totalTokensOut} out")
            buf.addLine("  Avg latency:    ${avgLatency}ms")
            val errColor = if (errors > 0) TuiColors.statusFailed else TuiColors.statusSuccess
            buf.addLine("  Errors:         ${errColor(errors.toString())}")

            // Per-provider breakdown
            buf.addLine("  Providers:")
            for ((prov, logs) in byProvider) {
                val provCost = logs.sumOf { it.costUsd }
                val provTok = logs.sumOf { it.tokensIn + it.tokensOut }
                buf.addLine("    ${TuiColors.accent(prov)}: ${logs.size} calls, \$${String.format("%.4f", provCost)}, ${provTok} tok")
            }
            buf.addLine()
        }

        // Context budget
        if (state.contextMaxTokens > 0) {
            buf.addLine(bold("Context Budget"))
            buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
            val pct = (state.contextUsedTokens.toDouble() / state.contextMaxTokens * 100).toInt()
            val pctColor = when {
                pct >= 90 -> TuiColors.statusFailed
                pct >= 75 -> TuiColors.statusPending
                else -> TuiColors.statusSuccess
            }
            buf.addLine("  Used:     ${state.contextUsedTokens} / ${state.contextMaxTokens} tokens (${pctColor("$pct%")})")
            buf.addLine("  Session:  ${state.sessionTokensIn} in / ${state.sessionTokensOut} out")
            if (state.contextSections.isNotEmpty()) {
                buf.addLine("  Sections: ${state.contextSections.size}")
                for (section in state.contextSections.take(8)) {
                    val secPct = if (section.percentage > 0) " (${String.format("%.1f", section.percentage)}%)" else ""
                    buf.addLine("    ${section.name.padEnd(25)} ${section.tokensUsed} tok$secPct")
                }
            }
            buf.addLine()
        }

        // Agent flow (when multi-agent session is active)
        if (state.agents.isNotEmpty()) {
            val remainingHeight = (height - buf.lineCount).coerceAtLeast(5)
            val agentBuf = TuiAgentFlowView.renderToBuffer(state, width, remainingHeight)
            for (line in agentBuf.getLines()) {
                if (buf.lineCount >= height - 1) break
                buf.addLine(line)
            }
        }

        return buf
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
