package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import java.text.SimpleDateFormat
import java.util.Date

/**
 * History screen — interactive session browser.
 * Navigate with Up/Down, Enter=load, p=pin, d=delete, c/l/a/asterisk=filter, Esc=back.
 */
object TuiHistoryScreen {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun renderToLines(state: TuiState, width: Int, contentHeight: Int): List<String> {
        val buf = TuiRenderBuffer(width, contentHeight)

        val filterLabel = when (state.historyFilter) {
            "*" -> "All"
            else -> state.historyFilter
        }
        buf.addLine(bold("Session History") + TuiColors.muted("  [Filter: $filterLabel]"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        val allSessions = state.sessions
        val filtered = if (state.historyFilter == "*") allSessions
            else if (state.historyFilter.startsWith("/")) {
                // Search by name
                val query = state.historyFilter.removePrefix("/").lowercase()
                allSessions.filter { it.name.lowercase().contains(query) || it.id.lowercase().contains(query) }
            } else {
                allSessions.filter { it.mode == state.historyFilter }
            }
        val sessions = filtered

        if (sessions.isEmpty()) {
            buf.addLine()
            buf.addLine(TuiColors.muted("  No sessions found."))
            buf.addLine(TuiColors.muted("  Send a message to start a new session."))
        } else {
            buf.addLine()
            // Header
            val idW = 8
            val modeW = 6
            val statusW = 9
            val dateW = 16
            val tokW = 12
            val costW = 10
            val nameW = (width - idW - modeW - statusW - dateW - tokW - costW - 16).coerceAtLeast(10)
            buf.addLine(TuiColors.highlight(
                "    ${"ID".padEnd(idW)} ${"Mode".padEnd(modeW)} ${"Status".padEnd(statusW)} ${"Date".padEnd(dateW)} ${"Tokens".padEnd(tokW)} ${"Cost".padEnd(costW)} Name"
            ))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))

            // Session rows
            val maxRows = contentHeight - 10
            val selectedIdx = state.selectedHistoryIndex
            val activeId = state.activeSessionId

            for ((i, session) in sessions.take(maxRows).withIndex()) {
                val isSelected = i == selectedIdx
                val isActive = session.id == activeId
                val prefix = when {
                    isSelected -> "> "
                    else -> "  "
                }
                val id = session.id.take(idW)
                val mode = session.mode.take(modeW)
                val statusColor = when (session.status) {
                    "SUCCESS" -> TuiColors.statusSuccess
                    "FAILED" -> TuiColors.statusFailed
                    "RUNNING" -> TuiColors.statusRunning
                    else -> TuiColors.statusPending
                }
                val status = statusColor(session.status.take(statusW).padEnd(statusW))
                val date = dateFormat.format(Date(session.updatedAt))
                val tokens = "${(session.tokensIn + session.tokensOut)}".padEnd(tokW)
                val cost = "\$${String.format("%.4f", session.costUsd)}".padEnd(costW)
                val pin = if (session.pinned) "📌" else "  "
                val activeMarker = if (isActive) TuiColors.statusRunning("●") else " "
                val name = session.name.take(nameW)

                val line = "$prefix$activeMarker$pin${id.padEnd(idW)} ${mode.padEnd(modeW)} $status ${date.padEnd(dateW)} $tokens $cost $name"
                if (isSelected) {
                    buf.addLine(TuiColors.tabActive(line))
                } else {
                    buf.addLine(line)
                }
            }

            if (sessions.size > maxRows) {
                buf.addLine(TuiColors.muted("  ... and ${sessions.size - maxRows} more sessions"))
            }
        }

        buf.addLine()
        buf.addLine(TuiColors.muted("  [↑↓] Navigate  [Enter] Load  [p] Pin/Unpin  [d] Delete  [r] Refresh"))
        buf.addLine(TuiColors.muted("  [c] Chat  [l] Plan  [a] Agent  [*] All  [/] Search  [Esc] Back"))

        return buf.getLines()
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        for (line in renderToLines(state, 200, contentHeight)) {
            terminal.println(line)
        }
    }
}
