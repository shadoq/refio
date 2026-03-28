package pl.jclab.refio.cli.tui.views

import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Full-screen content viewer overlay — displays file content, log details, API payloads, etc.
 * Supports scrolling, line numbers (optional), soft-wrap, copy to clipboard, add as context.
 */
object TuiFileViewerOverlay {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val title = state.fileViewerPath
        val content = state.fileViewerContent
        val scrollOffset = state.fileViewerScrollOffset
        val showLineNums = state.fileViewerShowLineNumbers
        val allowAdd = state.fileViewerAllowAddContext

        // Header
        val displayTitle = if (title.length > width - 10) "...${title.takeLast(width - 13)}" else title
        buf.addLine(TuiColors.highlight(displayTitle))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        if (content.isEmpty()) {
            buf.addLine(TuiColors.muted("(empty)"))
            return buf
        }

        // Soft-wrap lines to fit width
        val rawLines = content.lines()
        val maxLineWidth = if (showLineNums) {
            val lineNumWidth = rawLines.size.toString().length
            (width - lineNumWidth - 4).coerceAtLeast(10)
        } else {
            (width - 4).coerceAtLeast(10)
        }

        val wrappedLines = mutableListOf<Pair<Int, String>>() // (original line number, text)
        for ((idx, line) in rawLines.withIndex()) {
            if (line.length <= maxLineWidth) {
                wrappedLines.add(idx + 1 to line)
            } else {
                // Soft-wrap: break long lines into chunks
                var remaining = line
                var first = true
                while (remaining.isNotEmpty()) {
                    val chunk = remaining.take(maxLineWidth)
                    remaining = remaining.drop(maxLineWidth)
                    wrappedLines.add((if (first) idx + 1 else -1) to chunk) // -1 = continuation
                    first = false
                }
            }
        }

        val totalLines = wrappedLines.size
        val contentHeight = height - 5 // header(2) + footer(2) + separator
        val lineNumWidth = if (showLineNums) rawLines.size.toString().length else 0

        // Show lines with optional line numbers
        val visibleLines = wrappedLines.drop(scrollOffset).take(contentHeight)
        for ((lineNum, lineText) in visibleLines) {
            if (showLineNums) {
                val numStr = if (lineNum > 0) lineNum.toString().padStart(lineNumWidth) else " ".repeat(lineNumWidth)
                val sep = if (lineNum > 0) "│" else "·"
                buf.addLine("${TuiColors.muted("$numStr $sep")} $lineText")
            } else {
                buf.addLine("  $lineText")
            }
        }

        // Fill remaining space
        val remaining = contentHeight - visibleLines.size
        repeat(remaining) { buf.addLine("") }

        // Footer
        val scrollInfo = "Line ${scrollOffset + 1}-${(scrollOffset + visibleLines.size).coerceAtMost(totalLines)}/$totalLines"
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        val addHint = if (allowAdd) "  [a] Add as context" else ""
        buf.addLine(TuiColors.muted("  [Esc] Close  [↑↓] Scroll  [PgUp/PgDn] Fast scroll$addHint  [c] Copy  $scrollInfo"))

        return buf
    }
}
