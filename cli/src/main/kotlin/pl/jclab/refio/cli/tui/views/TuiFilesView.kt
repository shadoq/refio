package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiFileEntry
import pl.jclab.refio.cli.tui.state.TuiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * File browser tab view — dual-panel inspired file manager (Total Commander / Midnight Commander).
 * Shows directory listing with navigation, file info, and quick actions.
 */
object TuiFilesView {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val innerWidth = (width - 4).coerceAtLeast(20)

        // Header: current path
        val pathDisplay = if (state.fileBrowserPath.length > innerWidth - 6) {
            "...${state.fileBrowserPath.takeLast(innerWidth - 9)}"
        } else {
            state.fileBrowserPath
        }
        buf.addLine(TuiColors.border("┌─") + TuiColors.highlight(" $pathDisplay ") + TuiColors.border("─".repeat((innerWidth - pathDisplay.length - 2).coerceAtLeast(0)) + "┐"))

        // Column headers
        val nameCol = (innerWidth - 24).coerceAtLeast(10)
        val header = "  ${"Name".padEnd(nameCol)} ${"Size".padStart(8)} ${"Modified".padStart(12)}"
        buf.addLine(TuiColors.border("│") + TuiColors.muted(header.take(innerWidth).padEnd(innerWidth)) + TuiColors.border("│"))
        buf.addLine(TuiColors.border("├") + TuiColors.border("─".repeat(innerWidth)) + TuiColors.border("┤"))

        val entries = state.fileBrowserEntries
        if (entries.isEmpty()) {
            buf.addLine(TuiColors.border("│") + TuiColors.muted("  (empty directory)".padEnd(innerWidth)) + TuiColors.border("│"))
        } else {
            // Calculate visible range with scrolling
            val listHeight = (height - 7).coerceAtLeast(3) // header(3) + footer(3) + separator
            val selectedIdx = state.fileBrowserSelectedIndex
            val scrollStart = when {
                selectedIdx < listHeight / 2 -> 0
                selectedIdx > entries.size - listHeight / 2 -> (entries.size - listHeight).coerceAtLeast(0)
                else -> selectedIdx - listHeight / 2
            }
            val visibleEntries = entries.drop(scrollStart).take(listHeight)

            for ((i, entry) in visibleEntries.withIndex()) {
                val actualIdx = scrollStart + i
                val isSelected = actualIdx == selectedIdx
                val cursor = if (isSelected) ">" else " "

                val icon = when {
                    entry.name == ".." -> TuiColors.accent("↑ ")
                    entry.isDirectory -> TuiColors.accent("📁")
                    entry.isSymlink -> TuiColors.muted("🔗")
                    isSourceFile(entry.name) -> "📄"
                    isImageFile(entry.name) -> "🖼 "
                    isArchiveFile(entry.name) -> "📦"
                    else -> "  "
                }

                val nameText = entry.name.take(nameCol - 3)
                val sizeText = if (entry.isDirectory) "<DIR>" else formatSize(entry.size)
                val dateText = if (entry.lastModified > 0) {
                    dateFormatter.format(Instant.ofEpochMilli(entry.lastModified))
                } else ""

                val line = "$cursor $icon ${nameText.padEnd(nameCol - 3)} ${sizeText.padStart(8)} ${dateText.padStart(12)}"

                val styledLine = when {
                    isSelected && entry.isDirectory -> TuiColors.tabActive(line.take(innerWidth).padEnd(innerWidth))
                    isSelected -> TuiColors.tabActive(line.take(innerWidth).padEnd(innerWidth))
                    entry.isDirectory -> TuiColors.accent(line.take(innerWidth).padEnd(innerWidth))
                    entry.isSymlink -> TuiColors.muted(line.take(innerWidth).padEnd(innerWidth))
                    entry.name.startsWith(".") -> TuiColors.muted(line.take(innerWidth).padEnd(innerWidth))
                    else -> line.take(innerWidth).padEnd(innerWidth)
                }
                buf.addLine(TuiColors.border("│") + styledLine + TuiColors.border("│"))
            }

            // Scroll indicator
            if (entries.size > listHeight) {
                val scrollInfo = " ${selectedIdx + 1}/${entries.size}"
                val scrollBar = renderScrollIndicator(scrollStart, listHeight, entries.size)
                val infoLine = "$scrollBar$scrollInfo".padEnd(innerWidth)
                buf.addLine(TuiColors.border("│") + TuiColors.muted(infoLine.take(innerWidth)) + TuiColors.border("│"))
            }
        }

        // Bottom border
        buf.addLine(TuiColors.border("├") + TuiColors.border("─".repeat(innerWidth)) + TuiColors.border("┤"))

        // Footer: keyboard hints
        val hiddenHint = if (state.fileBrowserShowHidden) "h:Hide" else "h:Show"
        val hints = "[Enter] Open  [Bksp] Up  [$hiddenHint]  [a] Add ctx  [o] Open ext  [i] Info"
        buf.addLine(TuiColors.border("│") + TuiColors.muted(" $hints".take(innerWidth).padEnd(innerWidth)) + TuiColors.border("│"))
        buf.addLine(TuiColors.border("└") + TuiColors.border("─".repeat(innerWidth)) + TuiColors.border("┘"))

        return buf
    }

    private fun renderScrollIndicator(scrollStart: Int, viewHeight: Int, totalEntries: Int): String {
        val barLen = 8
        val thumbPos = if (totalEntries > viewHeight) {
            (scrollStart.toDouble() / (totalEntries - viewHeight) * (barLen - 1)).toInt().coerceIn(0, barLen - 1)
        } else 0
        return (0 until barLen).joinToString("") { i ->
            if (i == thumbPos) TuiColors.accent("█") else TuiColors.muted("░")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.1fG", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1fM", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(java.util.Locale.US, "%.1fK", bytes / 1_024.0)
        else -> "${bytes}B"
    }

    private fun isSourceFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp", "h", "swift", "rb", "php", "scala", "groovy", "kts", "gradle", "xml", "json", "yaml", "yml", "toml", "html", "css", "scss", "md", "txt", "sh", "bat", "sql")
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp")
    }

    private fun isArchiveFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("zip", "tar", "gz", "bz2", "7z", "jar", "war", "rar")
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
