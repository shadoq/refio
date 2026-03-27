package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiApiLogEntry
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * API Logs tab view — API call table with selection and detail inspection.
 *
 * Two modes:
 * - **Table mode** (default): Shows API call list with ↑/↓ selection
 * - **Detail mode** (Enter on selected row): Shows full metadata, payload preview, error info
 */
object TuiApiLogsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        if (state.apiLogs.isEmpty()) {
            buf.addLine(TuiColors.highlight("API Logs"))
            buf.addLine()
            buf.addLine(TuiColors.muted("No API calls logged yet."))
            return buf
        }

        // Apply filter
        val filteredLogs = if (state.apiLogsFilter != null) {
            state.apiLogs.filter { it.provider == state.apiLogsFilter }
        } else state.apiLogs

        if (state.apiLogDetailVisible) {
            renderDetailView(buf, state, filteredLogs, width, height)
        } else {
            renderTableView(buf, state, filteredLogs, width, height)
        }

        return buf
    }

    private fun renderTableView(buf: TuiRenderBuffer, state: TuiState, logs: List<TuiApiLogEntry>, width: Int, height: Int) {
        buf.addLine(TuiColors.highlight("API Logs"))
        buf.addLine()

        // Summary
        val totalCalls = logs.size
        val totalCost = logs.sumOf { it.costUsd }
        val totalTokens = logs.sumOf { it.tokensIn + it.tokensOut }
        val avgLatency = if (logs.isNotEmpty()) logs.map { it.latencyMs }.average().toInt() else 0
        val errors = logs.count { it.errorType != null }
        val filterLabel = if (state.apiLogsFilter != null) " (${state.apiLogsFilter})" else ""
        buf.addLine("Calls: $totalCalls$filterLabel  Cost: \$${String.format("%.4f", totalCost)}  Tokens: $totalTokens  Avg: ${avgLatency}ms  Errors: $errors")

        // Provider stats
        val byProvider = state.apiLogs.groupBy { it.provider }
        val stats = byProvider.entries.joinToString("  ") { (prov, provLogs) ->
            if (prov == state.apiLogsFilter) TuiColors.accent("[${prov}:${provLogs.size}]") else TuiColors.muted("${prov}:${provLogs.size}")
        }
        buf.addLine("$stats  ${TuiColors.muted("[f] Filter  [Enter] Details  [↑↓] Select")}")
        buf.addLine()

        // Column widths
        val timeW = 8
        val provW = 8
        val statusW = 5
        val latW = 6
        val tokW = 12
        val costW = 10
        val modelW = (width - timeW - provW - statusW - latW - tokW - costW - 10).coerceAtLeast(8)

        // Header
        buf.addLine(TuiColors.highlight(
            " ${"Time".padEnd(timeW)} ${"Prov".padEnd(provW)} ${"St".padEnd(statusW)} ${"ms".padEnd(latW)} ${"Model".padEnd(modelW)} ${"Tok In/Out".padEnd(tokW)} ${"Cost".padEnd(costW)}"
        ))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        val maxRows = height - 9
        val visible = logs.takeLast(maxRows.coerceAtLeast(1))
        val baseIdx = (logs.size - visible.size).coerceAtLeast(0)

        for ((i, log) in visible.withIndex()) {
            if (buf.lineCount >= height - 1) break
            val globalIdx = baseIdx + i
            val cursor = if (globalIdx == state.selectedApiLogIndex) ">" else " "
            val time = log.timestamp.takeLast(8)
            val prov = log.provider.take(provW)
            val st = log.httpStatus?.toString() ?: "-"
            val stColor = when {
                log.errorType != null -> TuiColors.statusFailed
                log.httpStatus != null && log.httpStatus >= 400 -> TuiColors.statusFailed
                log.httpStatus != null && log.httpStatus >= 300 -> TuiColors.statusPending
                else -> TuiColors.statusSuccess
            }
            val lat = "${log.latencyMs}".take(latW)
            val model = log.model.take(modelW)
            val tok = "${log.tokensIn}/${log.tokensOut}"
            val cost = "\$${String.format("%.4f", log.costUsd)}"
            buf.addLine("$cursor${time.padEnd(timeW)} ${prov.padEnd(provW)} ${stColor(st.padEnd(statusW))} ${lat.padEnd(latW)} ${model.padEnd(modelW)} ${tok.padEnd(tokW)} ${cost.padEnd(costW)}")
        }
    }

    private fun renderDetailView(buf: TuiRenderBuffer, state: TuiState, logs: List<TuiApiLogEntry>, width: Int, height: Int) {
        val log = logs.getOrNull(state.selectedApiLogIndex) ?: run {
            buf.addLine(TuiColors.muted("No log selected."))
            return
        }

        buf.addLine(TuiColors.highlight("API Log Detail"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        // Metadata
        buf.addLine("  ${TuiColors.muted("Time:")}       ${log.timestamp}")
        buf.addLine("  ${TuiColors.muted("Provider:")}   ${log.provider}")
        buf.addLine("  ${TuiColors.muted("Model:")}      ${log.model}")
        buf.addLine("  ${TuiColors.muted("Endpoint:")}   ${log.endpoint}")
        buf.addLine("  ${TuiColors.muted("Source:")}     ${log.source ?: "-"}")
        val stColor = if (log.httpStatus != null && log.httpStatus >= 400) TuiColors.statusFailed else TuiColors.statusSuccess
        buf.addLine("  ${TuiColors.muted("HTTP:")}       ${stColor((log.httpStatus?.toString() ?: "-"))}")
        buf.addLine()

        // Metrics
        buf.addLine("  ${TuiColors.highlight("Metrics")}")
        buf.addLine("  ${TuiColors.muted("Tokens:")}     ${log.tokensIn} in / ${log.tokensOut} out (${log.tokensIn + log.tokensOut} total)")
        buf.addLine("  ${TuiColors.muted("Cost:")}       \$${String.format("%.6f", log.costUsd)}")
        buf.addLine("  ${TuiColors.muted("Latency:")}    ${log.latencyMs}ms")
        if (log.taskId != null) buf.addLine("  ${TuiColors.muted("Task:")}       ${log.taskId}")
        if (log.subtaskId != null) buf.addLine("  ${TuiColors.muted("Subtask:")}    ${log.subtaskId}")
        buf.addLine()

        // Error (if present)
        if (log.errorType != null || log.errorMessage != null) {
            buf.addLine("  ${TuiColors.statusFailed("Error")}")
            if (log.errorType != null) buf.addLine("  ${TuiColors.muted("Type:")}       ${TuiColors.statusFailed(log.errorType)}")
            if (log.errorMessage != null) {
                for (line in log.errorMessage.take(300).lines().take(3)) {
                    buf.addLine("  ${TuiColors.statusFailed(line)}")
                }
            }
            buf.addLine()
        }

        // Request payload preview
        if (log.requestPayload.isNotBlank()) {
            buf.addLine("  ${TuiColors.highlight("Request")} ${TuiColors.muted("(${log.requestPayload.length} chars)")}")
            val preview = formatJsonPreview(log.requestPayload, width - 4, 6)
            for (line in preview) {
                if (buf.lineCount >= height - 3) break
                buf.addLine("  $line")
            }
            buf.addLine()
        }

        // Response payload preview
        if (log.responsePayload.isNotBlank()) {
            buf.addLine("  ${TuiColors.highlight("Response")} ${TuiColors.muted("(${log.responsePayload.length} chars)")}")
            val preview = formatJsonPreview(log.responsePayload, width - 4, 6)
            for (line in preview) {
                if (buf.lineCount >= height - 2) break
                buf.addLine("  $line")
            }
        }

        buf.addLine()
        buf.addLine(TuiColors.muted("  [Enter/Esc] Back to list  [↑↓] Navigate logs"))
    }

    /** Format JSON/text for preview - show first N lines, truncated to width */
    private fun formatJsonPreview(payload: String, maxWidth: Int, maxLines: Int): List<String> {
        val lines = payload.lines().take(maxLines).map { line ->
            val trimmed = line.take(maxWidth)
            TuiColors.muted(trimmed)
        }
        if (payload.lines().size > maxLines) {
            return lines + TuiColors.muted("... (${payload.lines().size} lines total)")
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
