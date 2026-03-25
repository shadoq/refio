package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Logs tab view — scrollable log list with color-coded levels.
 */
object TuiLogsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        buf.addLine(TuiColors.highlight("Logs"))
        buf.addLine()

        if (state.logs.isEmpty()) {
            buf.addLine(TuiColors.muted("No logs yet."))
            return buf
        }

        val visible = state.logs.takeLast(height - 3)
        for (log in visible) {
            val color = when (log.level) {
                "DEBUG" -> TuiColors.logDebug
                "INFO" -> TuiColors.logInfo
                "WARN" -> TuiColors.logWarn
                "ERROR" -> TuiColors.logError
                else -> TuiColors.logInfo
            }
            buf.addLine(color("${log.timestamp} [${log.level.padEnd(5)}] ${log.message}"))
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
