package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Logs tab view — scrollable log list with color-coded levels,
 * pause support, selection, and level filtering.
 * Detail view uses the content viewer overlay (opened via Enter).
 */
object TuiLogsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        // Apply level filter
        val filteredLogs = if (state.logsFilter != null) {
            state.logs.filter { it.level == state.logsFilter }
        } else {
            state.logs
        }

        // Header
        val pauseLabel = if (state.logsPaused) TuiColors.statusPending(" [PAUSED]") else ""
        val filterLabel = if (state.logsFilter != null) TuiColors.accent(" [${state.logsFilter}]") else ""
        buf.addLine(TuiColors.highlight("Logs") + " ${TuiColors.muted("(${filteredLogs.size})")}" + filterLabel + pauseLabel)
        buf.addLine()

        if (filteredLogs.isEmpty()) {
            buf.addLine(TuiColors.muted("No logs yet."))
            buf.addLine()
            buf.addLine(TuiColors.muted("[p] Pause  [f] Filter  [Enter] Details  [↑↓] Navigate"))
            return buf
        }

        // List view with selection
        val toolbarHeight = 2
        val maxVisible = (height - 4 - toolbarHeight).coerceAtLeast(1)

        // Center visible window around selectedLogIndex
        val selectedIdx = state.selectedLogIndex.coerceIn(0, filteredLogs.size - 1)
        val startIdx = when {
            filteredLogs.size <= maxVisible -> 0
            selectedIdx < maxVisible / 2 -> 0
            selectedIdx > filteredLogs.size - maxVisible / 2 -> (filteredLogs.size - maxVisible).coerceAtLeast(0)
            else -> selectedIdx - maxVisible / 2
        }
        val visible = filteredLogs.subList(startIdx, (startIdx + maxVisible).coerceAtMost(filteredLogs.size))

        for ((i, log) in visible.withIndex()) {
            if (buf.lineCount >= height - toolbarHeight - 1) break
            val globalIdx = startIdx + i
            val isSelected = globalIdx == selectedIdx
            val cursor = if (isSelected) ">" else " "
            val color = levelColor(log.level)
            val maxMsgWidth = (width - 22).coerceAtLeast(10) // timestamp(8) + level(7) + cursor(1) + padding
            val truncMsg = if (log.message.length > maxMsgWidth) log.message.take(maxMsgWidth - 1) + "…" else log.message
            val line = "$cursor${log.timestamp} [${log.level.padEnd(5)}] $truncMsg"
            if (isSelected) {
                buf.addLine(TuiColors.accent(line))
            } else {
                buf.addLine(color(line))
            }
        }

        // Scroll indicator
        if (filteredLogs.size > maxVisible) {
            buf.addLine(TuiColors.muted("  ${selectedIdx + 1}/${filteredLogs.size}"))
        }

        // Toolbar
        buf.addLine()
        buf.addLine(TuiColors.muted("[p] Pause  [f] Filter  [Enter] Details  [↑↓] Navigate"))

        return buf
    }

    private fun levelColor(level: String) = when (level) {
        "DEBUG" -> TuiColors.logDebug
        "INFO" -> TuiColors.logInfo
        "WARN" -> TuiColors.logWarn
        "ERROR" -> TuiColors.logError
        else -> TuiColors.logInfo
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
