package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Debug tab view — session state, core health, LLM statistics.
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

        return buf
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
