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
        buf.addLine("Calls: $totalCalls$filterLabel  Cost: \$${String.format(java.util.Locale.US, "%.4f", totalCost)}  Tokens: $totalTokens  Avg: ${avgLatency}ms  Errors: $errors")

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
        // Center visible window around selectedApiLogIndex
        val selectedIdx = state.selectedApiLogIndex.coerceIn(0, (logs.size - 1).coerceAtLeast(0))
        val startIdx = when {
            logs.size <= maxRows -> 0
            selectedIdx < maxRows / 2 -> 0
            selectedIdx > logs.size - maxRows / 2 -> (logs.size - maxRows).coerceAtLeast(0)
            else -> selectedIdx - maxRows / 2
        }
        val visible = logs.subList(startIdx, (startIdx + maxRows).coerceAtMost(logs.size))

        for ((i, log) in visible.withIndex()) {
            if (buf.lineCount >= height - 1) break
            val globalIdx = startIdx + i
            val cursor = if (globalIdx == selectedIdx) ">" else " "
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
            val cost = "\$${String.format(java.util.Locale.US, "%.4f", log.costUsd)}"
            val line = "$cursor${time.padEnd(timeW)} ${prov.padEnd(provW)} ${stColor(st.padEnd(statusW))} ${lat.padEnd(latW)} ${model.padEnd(modelW)} ${tok.padEnd(tokW)} ${cost.padEnd(costW)}"
            if (globalIdx == selectedIdx) {
                buf.addLine(TuiColors.accent(line))
            } else {
                buf.addLine(line)
            }
        }
    }

    private fun renderDetailView(buf: TuiRenderBuffer, state: TuiState, logs: List<TuiApiLogEntry>, width: Int, height: Int) {
        val log = logs.getOrNull(state.selectedApiLogIndex) ?: run {
            buf.addLine(TuiColors.muted("No log selected."))
            return
        }

        // Build all detail lines, then apply scroll offset
        val detailLines = mutableListOf<String>()

        detailLines.add(TuiColors.highlight("API Log Detail"))
        detailLines.add(TuiColors.border("\u2500".repeat((width - 2).coerceAtLeast(10))))

        // Metadata
        detailLines.add("  ${TuiColors.muted("Time:")}       ${log.timestamp}")
        detailLines.add("  ${TuiColors.muted("Provider:")}   ${log.provider}")
        detailLines.add("  ${TuiColors.muted("Model:")}      ${log.model}")
        detailLines.add("  ${TuiColors.muted("Endpoint:")}   ${log.endpoint}")
        detailLines.add("  ${TuiColors.muted("Source:")}     ${log.source ?: "-"}")
        val stColor = if (log.httpStatus != null && log.httpStatus >= 400) TuiColors.statusFailed else TuiColors.statusSuccess
        detailLines.add("  ${TuiColors.muted("HTTP:")}       ${stColor((log.httpStatus?.toString() ?: "-"))}")
        detailLines.add("")

        // Metrics
        detailLines.add("  ${TuiColors.highlight("Metrics")}")
        detailLines.add("  ${TuiColors.muted("Tokens:")}     ${log.tokensIn} in / ${log.tokensOut} out (${log.tokensIn + log.tokensOut} total)")
        detailLines.add("  ${TuiColors.muted("Cost:")}       \$${String.format(java.util.Locale.US, "%.6f", log.costUsd)}")
        detailLines.add("  ${TuiColors.muted("Latency:")}    ${log.latencyMs}ms")
        if (log.taskId != null) detailLines.add("  ${TuiColors.muted("Task:")}       ${log.taskId}")
        if (log.subtaskId != null) detailLines.add("  ${TuiColors.muted("Subtask:")}    ${log.subtaskId}")
        detailLines.add("")

        // Error (if present)
        if (log.errorType != null || log.errorMessage != null) {
            detailLines.add("  ${TuiColors.statusFailed("Error")}")
            if (log.errorType != null) detailLines.add("  ${TuiColors.muted("Type:")}       ${TuiColors.statusFailed(log.errorType)}")
            if (log.errorMessage != null) {
                for (line in log.errorMessage.take(500).lines().take(5)) {
                    detailLines.add("  ${TuiColors.statusFailed(line)}")
                }
            }
            detailLines.add("")
        }

        // Request payload with pretty JSON
        if (log.requestPayload.isNotBlank()) {
            detailLines.add("  ${TuiColors.highlight("Request")} ${TuiColors.muted("(${log.requestPayload.length} chars)")}")
            val formatted = formatJsonPretty(log.requestPayload, width - 4)
            for (line in formatted) {
                detailLines.add("  $line")
            }
            detailLines.add("")
        }

        // Response payload with pretty JSON
        if (log.responsePayload.isNotBlank()) {
            detailLines.add("  ${TuiColors.highlight("Response")} ${TuiColors.muted("(${log.responsePayload.length} chars)")}")
            val formatted = formatJsonPretty(log.responsePayload, width - 4)
            for (line in formatted) {
                detailLines.add("  $line")
            }
        }

        // Apply scroll offset
        val toolbarHeight = 2
        val contentHeight = (height - toolbarHeight).coerceAtLeast(3)
        val maxScroll = (detailLines.size - contentHeight).coerceAtLeast(0)
        val scrollOffset = state.apiLogDetailScrollOffset.coerceIn(0, maxScroll)
        val visible = detailLines.drop(scrollOffset).take(contentHeight)

        for (line in visible) {
            buf.addLine(line)
        }

        // Scroll indicator
        if (detailLines.size > contentHeight) {
            val scrollPercent = if (maxScroll > 0) ((scrollOffset.toDouble() / maxScroll) * 100).toInt() else 0
            buf.addLine(TuiColors.muted("  --- scroll: $scrollPercent% (${detailLines.size} lines) ---"))
        }

        buf.addLine(TuiColors.muted("  [Enter/Esc] Back  [\u2191\u2193] Scroll  [c] Copy"))
    }

    /**
     * Pretty-print JSON payload with ANSI color for keys, or fall back to raw text.
     * Uses simple indentation-based formatting without external JSON library dependency.
     */
    private fun formatJsonPretty(payload: String, maxWidth: Int): List<String> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Attempt simple JSON pretty-printing by re-indenting
        return try {
            val formatted = simpleJsonPrettyPrint(trimmed)
            formatted.lines().map { line ->
                val truncated = line.take(maxWidth)
                colorizeJsonLine(truncated)
            }
        } catch (_: Exception) {
            // Not valid JSON or formatting failed, show as plain text
            payload.lines().map { line ->
                TuiColors.muted(line.take(maxWidth))
            }
        }
    }

    /** Simple JSON pretty-printer that re-indents JSON without external dependencies */
    private fun simpleJsonPrettyPrint(json: String): String {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escaped = false
        var i = 0

        while (i < json.length) {
            val c = json[i]

            if (escaped) {
                sb.append(c)
                escaped = false
                i++
                continue
            }

            if (c == '\\' && inString) {
                sb.append(c)
                escaped = true
                i++
                continue
            }

            if (c == '"') {
                inString = !inString
                sb.append(c)
                i++
                continue
            }

            if (inString) {
                sb.append(c)
                i++
                continue
            }

            when (c) {
                '{', '[' -> {
                    sb.append(c)
                    // Check if empty object/array
                    val next = json.substring(i + 1).trimStart()
                    val closeChar = if (c == '{') '}' else ']'
                    if (next.firstOrNull() == closeChar) {
                        sb.append(closeChar)
                        i = json.indexOf(closeChar, i + 1) + 1
                        continue
                    }
                    indent++
                    sb.append('\n')
                    sb.append("  ".repeat(indent))
                }
                '}', ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    sb.append('\n')
                    sb.append("  ".repeat(indent))
                    sb.append(c)
                }
                ',' -> {
                    sb.append(c)
                    sb.append('\n')
                    sb.append("  ".repeat(indent))
                }
                ':' -> {
                    sb.append(": ")
                }
                ' ', '\n', '\r', '\t' -> {
                    // Skip whitespace outside strings
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Colorize a single JSON line: keys in accent color, values in muted */
    private fun colorizeJsonLine(line: String): String {
        // Match JSON key pattern: "key":
        val keyPattern = Regex("^(\\s*)(\"[^\"]+\")(\\s*:)(.*)")
        val match = keyPattern.matchEntire(line)
        return if (match != null) {
            val (indent, key, colon, rest) = match.destructured
            "$indent${TuiColors.accent(key)}${TuiColors.muted(colon)}${TuiColors.muted(rest)}"
        } else {
            TuiColors.muted(line)
        }
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
