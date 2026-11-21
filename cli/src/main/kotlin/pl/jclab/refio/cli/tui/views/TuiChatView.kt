package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.api.StreamProgressFormat
import pl.jclab.refio.cli.tui.components.ApprovalFrame
import pl.jclab.refio.cli.tui.components.OutputLimitNotice
import pl.jclab.refio.cli.tui.components.StatusBar
import pl.jclab.refio.cli.tui.components.TuiMessageBubble
import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.ToolCallRow
import pl.jclab.refio.cli.tui.state.ToolRowStatus
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TranscriptItem
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiMessageType
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The conversation: message headers (`YOU`, `REFIO`), answers, tool calls grouped into a nested TOOLS frame
 * (one row per call, output expanded under a gutter on demand) and the output-limit notice. Selection with
 * Ctrl+P / Ctrl+B moves over messages and tool rows alike.
 */
object TuiChatView {

    private const val WRAP_CACHE_MAX = 500
    private const val USER_PREVIEW_LINES = 2
    private const val EXPANDED_TOOL_LINES = 5
    private const val TOOL_NAME_MAX = 22
    private const val DURATION_WIDTH = 6

    // The zone is read on every call, so a header always shows the zone in force when it is drawn.
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private data class CachedLines(val hash: Int, val lines: List<String>)

    /**
     * Rendered and wrapped lines per message, keyed by message id. A message re-renders only when it, the
     * width or its selection changed; others reuse cached lines. Access is confined to the render path
     * (rendering is synchronized). LRU-evicted so long sessions stay bounded.
     */
    private val cache = object : LinkedHashMap<String, CachedLines>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLines>): Boolean = size > WRAP_CACHE_MAX
    }

    /**
     * Renders the transcript into a buffer of [width] x [height]. [compact] = the narrow layout: finished
     * tool groups fold to one line there.
     */
    fun renderMessages(
        terminal: Terminal,
        state: TuiState,
        width: Int,
        height: Int,
        theme: Theme,
        glyphs: Glyphs,
        compact: Boolean = false,
    ): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val visible = TranscriptBuilder.visibleMessages(state.messages, state.agentFilter)
        val approval = ApprovalFrame.isPending(state)
        if (visible.isEmpty() && !approval) {
            welcome(state, theme).take(height).forEach { buf.addLine(it) }
            return buf
        }

        val items = TranscriptBuilder.build(visible)
        val selectedKey = TranscriptBuilder.units(items).getOrNull(state.selectedMessageIndex)?.key
        val lines = mutableListOf<String>()
        var selectedLine = -1
        items.forEachIndexed { index, item ->
            if (index > 0) lines += ""
            when (item) {
                is TranscriptItem.Message -> {
                    if (item.message.id == selectedKey) selectedLine = lines.size
                    val isLast = index == items.lastIndex
                    lines += messageLines(terminal, state, item.message, width, theme, glyphs, item.message.id == selectedKey, isLast)
                }
                is TranscriptItem.Tools -> {
                    val group = toolGroupLines(item.rows, state, width, theme, glyphs, selectedKey, compact)
                    if (group.selectedLine >= 0) selectedLine = lines.size + group.selectedLine
                    lines += group.lines
                }
            }
        }

        // While the model streams a native tool call's arguments, show which tool is coming.
        state.toolCallProgress?.let { progress ->
            val args = TextFit.truncateEnd(progress.accumulatedArguments.replace('\n', ' '), (width - 20).coerceAtLeast(10), glyphs.ellipsis)
            lines += ""
            lines += theme.style(glyphs.running, fg = ThemeColor.INFO) + " " + theme.style(progress.name ?: "tool", fg = ThemeColor.WARN) + " " + theme.style(args, fg = ThemeColor.DIM)
        }

        // A pending approval sits at the end of the transcript and keeps the view at the bottom, so it is always seen.
        if (approval) {
            if (lines.isNotEmpty()) lines += ""
            lines += ApprovalFrame.render(state, width, theme, glyphs, maxRows = height)
        }

        val window = visibleWindow(lines.size, height, if (approval) 0 else state.scrollOffset, if (approval) -1 else selectedLine)
        for (line in lines.subList(window.first, window.last + 1)) {
            if (buf.lineCount >= height) break
            buf.addLine(line)
        }
        return buf
    }

    /** Lines shown for [height] rows: the bottom by default, scrolled by [scrollOffset], and always the selection. */
    internal fun visibleWindow(total: Int, height: Int, scrollOffset: Int, selectedLine: Int): IntRange {
        if (total == 0 || height <= 0) return IntRange.EMPTY
        val offset = scrollOffset.coerceIn(0, (total - height).coerceAtLeast(0))
        var end = total - offset
        var start = (end - height).coerceAtLeast(0)
        if (selectedLine in 0 until total) {
            if (selectedLine < start) {
                start = selectedLine
                end = (start + height).coerceAtMost(total)
            } else if (selectedLine >= end) {
                // Keep a few lines under a selected tool row so its expanded output shows too.
                end = (selectedLine + EXPANDED_TOOL_LINES + 2).coerceAtMost(total)
                start = (end - height).coerceAtLeast(0)
            }
        }
        return start until end
    }

    private fun welcome(state: TuiState, theme: Theme): List<String> = listOf(
        theme.style("Welcome to Refio", fg = ThemeColor.ACCENT, bold = true),
        "",
        "  " + theme.style("Model  ", fg = ThemeColor.DIM) + theme.style(state.model?.takeIf { it.isNotBlank() } ?: "not selected (Ctrl+O)", fg = ThemeColor.INFO),
        "  " + theme.style("Mode   ", fg = ThemeColor.DIM) + state.mode + theme.style("  Shift+Tab cycles CHAT / PLAN / AGENT", fg = ThemeColor.DIM),
        "",
        theme.style("  Type a message and press Enter to start.", fg = ThemeColor.DIM),
        theme.style("  F1 help · F9 settings · Ctrl+O model · Ctrl+Q quit", fg = ThemeColor.DIM),
    )

    // --- Messages ---

    private fun messageLines(
        terminal: Terminal,
        state: TuiState,
        msg: TuiChatMessage,
        width: Int,
        theme: Theme,
        glyphs: Glyphs,
        selected: Boolean,
        isLast: Boolean,
    ): List<String> {
        if (OutputLimitNotice.applies(msg)) {
            // Not cached: the notice depends on the limits and the window, not only on the message.
            return listOf(header("REFIO", msg, width, theme, glyphs, selected, "")) +
                OutputLimitNotice.render(state, width, theme, glyphs, keysActive = isLast && state.inputBuffer.isEmpty() && !state.isStreaming)
        }
        val hash = arrayOf(msg, width, selected, theme.depth, glyphs.isAscii).contentHashCode()
        cache[msg.id]?.takeIf { it.hash == hash }?.let { return it.lines }
        val clean = msg.copy(content = TuiRenderBuffer.cleanText(msg.content))
        val lines = when {
            msg.role == "user" && msg.messageType == TuiMessageType.TEXT -> userLines(clean, width, theme, glyphs, selected)
            msg.role == "assistant" && msg.messageType == TuiMessageType.TEXT -> assistantLines(terminal, clean, width, theme, glyphs, selected)
            msg.role == "system" -> systemLines(clean, width, theme, glyphs, selected)
            else -> {
                // Agent events, plans, summaries and questions keep their own layout.
                val own = TuiMessageBubble.renderToLines(terminal, clean).flatMap { wrapLine(it, width) }
                if (selected && own.isNotEmpty()) listOf(theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) + " " + own.first()) + own.drop(1) else own
            }
        }
        cache[msg.id] = CachedLines(hash, lines)
        return lines
    }

    private fun userLines(msg: TuiChatMessage, width: Int, theme: Theme, glyphs: Glyphs, selected: Boolean): List<String> {
        val all = msg.content.trimEnd().lines()
        val clipped = all.size > USER_PREVIEW_LINES
        val hint = if (selected) (if (clipped) "v view all · ^Y copy" else "^Y copy") else ""
        val body = all.take(USER_PREVIEW_LINES).flatMap { wrapLine(it, width) }.map { theme.style(it, fg = ThemeColor.TEXT) }
        val more = if (clipped) listOf(theme.style("${glyphs.ellipsis} ${all.size - USER_PREVIEW_LINES} more lines   v view all", fg = ThemeColor.DIM)) else emptyList()
        return listOf(header("YOU", msg, width, theme, glyphs, selected, hint)) + body + more
    }

    private fun assistantLines(terminal: Terminal, msg: TuiChatMessage, width: Int, theme: Theme, glyphs: Glyphs, selected: Boolean): List<String> {
        val meta = when {
            selected -> "^Y copy"
            msg.isStreaming || (msg.tokensIn == 0 && msg.tokensOut == 0) -> ""
            else -> "${glyphs.arrowDown}${StatusBar.formatTokens(msg.tokensIn.toLong())} ${glyphs.arrowUp}${StatusBar.formatTokens(msg.tokensOut.toLong())}" +
                (if (msg.costUsd > 0) " · ${StatusBar.formatCost(msg.costUsd, isLocal = false)}" else "")
        }
        val label = (msg.agentName ?: "Refio").uppercase()
        val body = TuiMessageBubble.assistantBody(terminal, msg).flatMap { wrapLine(it, width) }
        val streaming = if (msg.isStreaming) listOf(theme.style("${glyphs.running} streaming${glyphs.ellipsis}", fg = ThemeColor.DIM)) else emptyList()
        return listOf(header(label, msg, width, theme, glyphs, selected, meta)) + body + streaming
    }

    private fun systemLines(msg: TuiChatMessage, width: Int, theme: Theme, glyphs: Glyphs, selected: Boolean): List<String> {
        val failed = msg.messageType == TuiMessageType.AGENT_FAILED
        val colour = if (failed) ThemeColor.ERR else ThemeColor.DIM
        val body = msg.content.trimEnd().lines().flatMap { wrapLine(it, width) }.map { theme.style(it, fg = colour) }
        return listOf(header(if (failed) "ERROR" else "NOTE", msg, width, theme, glyphs, selected, if (selected) "^Y copy" else "")) + body
    }

    /** `YOU  15:03:58` on the left (bold label, dim time), [meta] dim on the right when it fits. */
    private fun header(label: String, msg: TuiChatMessage, width: Int, theme: Theme, glyphs: Glyphs, selected: Boolean, meta: String): String {
        val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) + " " else ""
        val labelColour = if (msg.role == "system") ThemeColor.DIM else ThemeColor.ACCENT
        val left = marker + theme.style(label, fg = labelColour, bold = true) + "  " + theme.style(timeFormat.format(Instant.ofEpochMilli(msg.timestamp).atZone(ZoneId.systemDefault())), fg = ThemeColor.DIM)
        val leftWidth = TuiRenderBuffer.visibleLength(left)
        if (meta.isEmpty() || leftWidth + 2 + TuiRenderBuffer.visibleLength(meta) > width) return left
        return left + " ".repeat(width - leftWidth - TuiRenderBuffer.visibleLength(meta)) + theme.style(meta, fg = ThemeColor.DIM)
    }

    // --- Tool groups ---

    internal class GroupLines(val lines: List<String>, val selectedLine: Int)

    internal fun toolGroupLines(
        rows: List<ToolCallRow>,
        state: TuiState,
        width: Int,
        theme: Theme,
        glyphs: Glyphs,
        selectedKey: String?,
        compact: Boolean,
    ): GroupLines {
        val total = rows.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
        val meta = listOfNotNull("${rows.size}", total?.let { formatDuration(it) }).joinToString(" · ")
        val touched = rows.any { it.key == selectedKey || it.key in state.expandedToolRows }
        val finished = rows.none { it.status == ToolRowStatus.RUNNING }
        if (compact && finished && !touched) {
            val line = "${glyphs.collapsed} ${rows.size} ${if (rows.size == 1) "tool" else "tools"}" + (total?.let { "  ${formatDuration(it)}" } ?: "")
            return GroupLines(listOf(theme.style(line, fg = ThemeColor.DIM)), -1)
        }

        val frame = Frame(theme, glyphs)
        val inner = frame.innerSize(width, 3).first
        val columns = ToolColumns.of(rows, inner)
        val body = mutableListOf<String>()
        var selectedLine = -1
        for (row in rows) {
            if (row.key == selectedKey) selectedLine = body.size + 1 // + top border
            body += rowLine(row, columns, theme, glyphs, row.key == selectedKey)
            if (row.key in state.expandedToolRows) body += expandedLines(row, inner, theme, glyphs)
        }
        return GroupLines(frame.render(width, body.size + 2, "tools", meta, FrameState.INACTIVE, body), selectedLine)
    }

    /** Column widths of one TOOLS frame: marker, status, name, argument, result, duration. */
    internal class ToolColumns(val name: Int, val argument: Int, val result: Int, val duration: Int) {
        companion object {
            fun of(rows: List<ToolCallRow>, inner: Int): ToolColumns {
                val name = rows.maxOf { it.toolName.length }.coerceAtMost(TOOL_NAME_MAX).coerceAtMost((inner / 3).coerceAtLeast(4))
                val duration = if (rows.any { it.durationMs != null }) DURATION_WIDTH else 0
                // marker + status + space, two-column gaps, and the space before the duration
                val rest = (inner - 3 - name - 2 - (if (duration > 0) duration + 1 else 0)).coerceAtLeast(0)
                if (rest < 24) return ToolColumns(name, rest, 0, duration)
                val argument = rest * 45 / 100
                return ToolColumns(name, argument, rest - argument - 2, duration)
            }
        }
    }

    private fun rowLine(row: ToolCallRow, c: ToolColumns, theme: Theme, glyphs: Glyphs, selected: Boolean): String {
        val recovered = row.status == ToolRowStatus.RECOVERED
        val dim = recovered || row.status == ToolRowStatus.NO_RESULT
        val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
        val (glyph, glyphColour) = when (row.status) {
            ToolRowStatus.RUNNING -> glyphs.running to ThemeColor.INFO
            ToolRowStatus.DONE -> glyphs.done to ThemeColor.OK
            ToolRowStatus.FAILED -> glyphs.failed to ThemeColor.ERR
            ToolRowStatus.RECOVERED -> glyphs.failed to ThemeColor.DIM
            ToolRowStatus.NO_RESULT -> glyphs.pending to ThemeColor.DIM
        }
        val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(row.toolName, c.name, glyphs.ellipsis), c.name)
        val argument = TuiRenderBuffer.fitToWidth(TextFit.truncateMiddle(row.argument, c.argument, glyphs.ellipsis), c.argument)
        val resultText = when {
            row.streamedChars != null -> "generating ${StreamProgressFormat.counterSuffix(row.streamedChars)}"
            row.status == ToolRowStatus.RUNNING -> "running${glyphs.ellipsis}"
            row.status == ToolRowStatus.NO_RESULT -> "no result"
            recovered -> listOf(row.shortResult, "${glyphs.arrowRight} retried as ${row.retriedAs}").filter { it.isNotBlank() }.joinToString(" ")
            else -> row.shortResult
        }
        val resultColour = when (row.status) {
            ToolRowStatus.FAILED -> ThemeColor.ERR
            else -> ThemeColor.DIM
        }
        val result = if (c.result > 0) "  " + theme.style(TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(resultText, c.result, glyphs.ellipsis), c.result), fg = resultColour) else ""
        val duration = if (c.duration > 0) " " + theme.style((row.durationMs?.let { formatDuration(it) } ?: "").padStart(c.duration), fg = ThemeColor.DIM) else ""
        val textColour = if (dim) ThemeColor.DIM else null
        return marker + theme.style(glyph, fg = glyphColour) + " " +
            theme.style(name, fg = textColour ?: ThemeColor.WARN) + "  " +
            theme.style(argument, fg = textColour ?: ThemeColor.TEXT) + result + duration
    }

    private fun expandedLines(row: ToolCallRow, inner: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val output = if (row.result.isBlank()) emptyList() else row.result.trimEnd().lines()
        val text = when {
            row.status == ToolRowStatus.RUNNING -> listOf("(still running)")
            row.status == ToolRowStatus.NO_RESULT -> listOf("(no result: the call was not run, or the turn stopped before it finished)")
            output.isEmpty() -> listOf("(no output)")
            else -> output.take(EXPANDED_TOOL_LINES)
        }
        val lines = text.mapIndexed { i, line ->
            val prefix = if (i == 0) "   ${glyphs.gutter} " else "     "
            theme.style(TextFit.truncateEnd(prefix + line.replace('\t', ' '), inner, glyphs.ellipsis), fg = ThemeColor.DIM)
        }
        val hidden = output.size - EXPANDED_TOOL_LINES
        val more = if (hidden > 0) {
            listOf(theme.style(TextFit.truncateEnd("     ${glyphs.ellipsis} $hidden more lines   o open   ^Y copy", inner, glyphs.ellipsis), fg = ThemeColor.DIM))
        } else {
            listOf(theme.style(TextFit.truncateEnd("     o open   ^Y copy", inner, glyphs.ellipsis), fg = ThemeColor.DIM))
        }
        return lines + more
    }

    /** `0.4s`, `12.0s`, `2m05s`. */
    fun formatDuration(ms: Long): String = when {
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m%02ds".format(Locale.US, (ms / 1000) % 60)
    }

    /** A line that fits is kept with its styling; a longer one is wrapped between words as plain text. */
    private fun wrapLine(line: String, maxWidth: Int): List<String> {
        if (maxWidth <= 0 || TuiRenderBuffer.visibleLength(line) <= maxWidth) return listOf(line)
        return TextFit.wrapWords(line, maxWidth)
    }
}
