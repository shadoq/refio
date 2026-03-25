package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * API Logs tab view — API call table with statistics.
 */
object TuiApiLogsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        buf.addLine(TuiColors.highlight("API Logs"))
        buf.addLine()

        if (state.apiLogs.isEmpty()) {
            buf.addLine(TuiColors.muted("No API calls logged yet."))
            return buf
        }

        // Summary
        val totalCalls = state.apiLogs.size
        val totalCost = state.apiLogs.sumOf { it.costUsd }
        val totalTokens = state.apiLogs.sumOf { it.tokensIn + it.tokensOut }
        buf.addLine("Total: $totalCalls calls, \$${String.format("%.4f", totalCost)}, $totalTokens tok")
        buf.addLine()

        // Column widths adapted to panel width
        val timeW = 8
        val provW = 8
        val tokW = 12
        val costW = 10
        val modelW = (width - timeW - provW - tokW - costW - 8).coerceAtLeast(10) // remaining for model

        // Header
        val header = " ${"Time".padEnd(timeW)} ${"Prov".padEnd(provW)} ${"Model".padEnd(modelW)} ${"Tok In/Out".padEnd(tokW)} ${"Cost".padEnd(costW)}"
        buf.addLine(TuiColors.highlight(header))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        val maxRows = height - 8
        val visible = state.apiLogs.takeLast(maxRows)
        for (log in visible) {
            if (buf.lineCount >= height - 1) break
            val time = log.timestamp.takeLast(8)
            val prov = log.provider.take(provW)
            val model = log.model.take(modelW)
            val tok = "${log.tokensIn}/${log.tokensOut}"
            val cost = "\$${String.format("%.4f", log.costUsd)}"
            buf.addLine(" ${time.padEnd(timeW)} ${prov.padEnd(provW)} ${model.padEnd(modelW)} ${tok.padEnd(tokW)} ${cost.padEnd(costW)}")
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
