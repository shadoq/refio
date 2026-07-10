package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Debug tab view — session state, core health, API statistics, context budget, recent API logs.
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
        val separator = TuiColors.border("─".repeat((width - 2).coerceAtLeast(10)))

        // === Active Session ===
        lines.add(bold("Active Session"))
        lines.add(separator)
        lines.add("  ID:         ${debug.sessionId.ifEmpty { "N/A" }}")
        lines.add("  Mode:       ${debug.mode}")
        lines.add("  Model:      ${debug.model}")
        lines.add("  Status:     ${debug.status}")
        lines.add("  Created At: ${if (debug.sessionCreatedAt > 0) formatTimestamp(debug.sessionCreatedAt) else "N/A"}")
        lines.add("  Tokens In:  ${formatNumber(debug.tokensIn)}")
        lines.add("  Tokens Out: ${formatNumber(debug.tokensOut)}")
        lines.add("  Cost (USD): \$${String.format(java.util.Locale.US, "%.6f", debug.costUsd)}")
        lines.add("")

        // === Conversation State ===
        lines.add(bold("Conversation State"))
        lines.add(separator)
        lines.add("  Messages:       ${debug.messageCount}")
        lines.add("  Subtasks:       ${debug.subtaskCount}")
        lines.add("  Selected Model: ${debug.selectedModel}")
        lines.add("")

        // === Core Connection ===
        lines.add(bold("Core Health"))
        lines.add(separator)
        val connStatus = if (debug.connected) {
            TuiColors.statusSuccess("Connected")
        } else {
            TuiColors.statusFailed("Disconnected")
        }
        lines.add("  Connection: $connStatus")
        lines.add("  Type:       In-process (embedded Kotlin core)")
        lines.add("  Latency:    < 1ms")
        lines.add("")

        // === LLM Statistics (Global) ===
        lines.add(bold("LLM Statistics (Global)"))
        lines.add(separator)
        if (debug.totalApiCalls > 0) {
            lines.add("  Total API Calls: ${debug.totalApiCalls}")
            lines.add("  Total Tokens In: ${formatNumber(debug.globalTokensIn)}")
            lines.add("  Total Tokens Out:${formatNumber(debug.globalTokensOut)}")
            lines.add("  Total Cost:      \$${String.format(java.util.Locale.US, "%.6f", debug.globalCost)}")
            lines.add("  Avg Latency:     ${debug.avgLatencyMs}ms")
            val errColor = if (debug.errorCount > 0) TuiColors.statusFailed else TuiColors.statusSuccess
            val errPct = if (debug.totalApiCalls > 0) String.format(java.util.Locale.US, "%.1f", debug.errorCount * 100.0 / debug.totalApiCalls) else "0.0"
            lines.add("  Error Count:     ${errColor("${debug.errorCount}")} ($errPct%)")
        } else {
            lines.add("  ${TuiColors.muted("No API calls yet")}")
        }
        lines.add("")

        // === API Statistics from current session logs ===
        if (state.apiLogs.isNotEmpty()) {
            lines.add(bold("Session API Breakdown"))
            lines.add(separator)
            val byProvider = state.apiLogs.groupBy { it.provider }
            for ((prov, logs) in byProvider) {
                val provCost = logs.sumOf { it.costUsd }
                val provTok = logs.sumOf { it.tokensIn + it.tokensOut }
                lines.add("  ${TuiColors.accent(prov)}: ${logs.size} calls, \$${String.format(java.util.Locale.US, "%.4f", provCost)}, ${provTok} tok")
            }
            lines.add("")

            // === Recent API Logs (table) ===
            lines.add(bold("Recent API Logs (Last 10)"))
            lines.add(separator)
            lines.add(String.format("  %-8s %-12s %-15s %6s %6s %6s %-8s %s",
                "Time", "Provider", "Model", "In", "Out", "Lat", "Cost", "Status"))
            lines.add("  " + TuiColors.border("─".repeat((width - 4).coerceAtLeast(10))))

            val recentLogs = state.apiLogs.takeLast(10).reversed()
            for (log in recentLogs) {
                val time = log.timestamp.takeLast(8) // HH:mm:ss
                val provider = if (log.provider.length > 12) log.provider.take(9) + "..." else log.provider
                val model = if (log.model.length > 15) log.model.take(12) + "..." else log.model
                val tokIn = formatTokensShort(log.tokensIn.toInt())
                val tokOut = formatTokensShort(log.tokensOut.toInt())
                val latency = "${log.latencyMs}ms"
                val cost = String.format("\$%.4f", log.costUsd)
                val status = when {
                    log.errorMessage != null -> TuiColors.statusFailed("ERR")
                    log.httpStatus != null && log.httpStatus in 200..299 -> TuiColors.statusSuccess("OK")
                    log.httpStatus != null -> TuiColors.statusFailed("${log.httpStatus}")
                    else -> "?"
                }
                lines.add(String.format("  %-8s %-12s %-15s %6s %6s %6s %-8s %s",
                    time, provider, model, tokIn, tokOut, latency, cost, status))
                if (log.errorMessage != null) {
                    lines.add("    └─ ${TuiColors.statusFailed(log.errorMessage.take(80))}")
                }
            }
            lines.add("")
        }

        // === Context Budget ===
        if (state.contextMaxTokens > 0) {
            lines.add(bold("Context Budget"))
            lines.add(separator)
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
                    val secPct = if (section.percentage > 0) " (${String.format(java.util.Locale.US, "%.1f", section.percentage)}%)" else ""
                    lines.add("    ${section.name.padEnd(25)} ${section.tokensUsed} tok$secPct")
                }
            }
            lines.add("")
        }

        // === Debug Info ===
        lines.add(bold("Debug Info"))
        lines.add(separator)
        lines.add("  Project Root: ${debug.projectRoot.ifEmpty { "N/A" }}")
        lines.add("  DB Path:      ${debug.dbPath.ifEmpty { "N/A" }}")
        lines.add("  Last Update:  ${if (debug.lastUpdate > 0) formatTimestamp(debug.lastUpdate) else "N/A"}")
        lines.add("")

        // === Agent flow (when multi-agent session is active) ===
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

    private fun formatNumber(num: Long): String = when {
        num >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", num / 1_000_000.0)
        num >= 1_000 -> String.format(java.util.Locale.US, "%.2fK", num / 1_000.0)
        else -> num.toString()
    }

    private fun formatTokensShort(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
    }
}
