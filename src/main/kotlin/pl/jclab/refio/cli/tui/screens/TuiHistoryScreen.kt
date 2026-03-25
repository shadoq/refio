package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import java.text.SimpleDateFormat
import java.util.Date

/**
 * History screen — browse sessions with real data from core.
 * Adapted from plugin's HistoryPanel (551 ln).
 */
object TuiHistoryScreen {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun renderToLines(state: TuiState, width: Int, contentHeight: Int): List<String> {
        val buf = TuiRenderBuffer(width, contentHeight)

        buf.addLine(bold("Session History"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        val sessions = state.sessions
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
            val nameW = (width - idW - modeW - statusW - dateW - tokW - costW - 14).coerceAtLeast(10)
            buf.addLine(TuiColors.highlight(
                "  ${"ID".padEnd(idW)} ${"Mode".padEnd(modeW)} ${"Status".padEnd(statusW)} ${"Date".padEnd(dateW)} ${"Tokens".padEnd(tokW)} ${"Cost".padEnd(costW)} Name"
            ))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))

            // Session rows
            val maxRows = contentHeight - 10
            for (session in sessions.take(maxRows)) {
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
                val pin = if (session.pinned) "📌 " else "  "
                val name = session.name.take(nameW)

                buf.addLine("$pin${id.padEnd(idW)} ${mode.padEnd(modeW)} $status ${date.padEnd(dateW)} $tokens $cost $name")
            }

            if (sessions.size > maxRows) {
                buf.addLine(TuiColors.muted("  ... and ${sessions.size - maxRows} more sessions"))
            }
        }

        buf.addLine()
        buf.addLine(TuiColors.muted("  Commands:"))
        buf.addLine(TuiColors.muted("    /history-delete <id>  — delete session"))
        buf.addLine(TuiColors.muted("    Esc = back to main"))

        return buf.getLines()
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        for (line in renderToLines(state, 200, contentHeight)) {
            terminal.println(line)
        }
    }
}
