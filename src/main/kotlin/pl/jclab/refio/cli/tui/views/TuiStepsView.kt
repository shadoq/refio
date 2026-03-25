package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Steps tab view — list of subtasks with expand/collapse.
 */
object TuiStepsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        if (state.steps.isEmpty()) {
            buf.addLine(TuiColors.muted("No active steps."))
            buf.addLine(TuiColors.muted("Start a task in PLAN or AGENT mode."))
            return buf
        }

        buf.addLine(TuiColors.highlight("Steps (${state.steps.size})"))
        buf.addLine()

        for (step in state.steps) {
            if (buf.lineCount >= height - 2) break
            val statusStyle = when (step.status) {
                "NEW" -> TuiColors.statusNew
                "PENDING" -> TuiColors.statusPending
                "RUNNING" -> TuiColors.statusRunning
                "COMPLETED", "OK" -> TuiColors.statusSuccess
                "FAILED" -> TuiColors.statusFailed
                else -> TuiColors.muted
            }
            buf.addLine("${statusStyle("[${step.status}]")} ${step.name}")

            if (step.expanded && step.details.isNotBlank()) {
                for (line in step.details.lines()) {
                    if (buf.lineCount >= height - 1) break
                    buf.addLine(TuiColors.muted("  $line"))
                }
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
