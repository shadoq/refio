package pl.jclab.refio.cli.tui.views

import pl.jclab.refio.cli.tui.components.StatusBar
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiRagFile
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import java.util.Locale

/**
 * RAG panel: the state of the index in one line, the indexed files table and search results. Every statement
 * about the index comes from the indexed files list, the same list the table shows, so the panel cannot say
 * "nothing indexed" above a table of files.
 */
object TuiRagView {

    enum class IndexState { INDEXING, NO_INDEX, DISABLED, READY }

    private const val CLEAR_QUESTION = "c again clears the whole index; any other key keeps it"
    private const val ACTIONS = "Enter open · v chunks · r reindex · e embeddings · s stop · c clear · ↑↓ file"

    fun indexState(state: TuiState): IndexState = when {
        state.ragIndexingProgress in 0.0..1.0 -> IndexState.INDEXING
        state.ragIndexedFiles.isEmpty() -> IndexState.NO_INDEX
        state.ragEnabled == false -> IndexState.DISABLED
        else -> IndexState.READY
    }

    /** One plain sentence about the index. */
    fun headline(state: TuiState): String {
        val files = state.ragIndexedFiles.size
        return when (indexState(state)) {
            IndexState.INDEXING -> "indexing ${(state.ragIndexingProgress * 100).toInt()}%" +
                state.ragIndexingStatus.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            IndexState.NO_INDEX -> if (state.ragEnabled == false) "RAG off, nothing indexed" else "no index yet: r starts indexing"
            IndexState.DISABLED -> "index ready, RAG off: $files files indexed but not used"
            IndexState.READY -> "ready · $files files indexed"
        }
    }

    /** Short value for the navigation badge. */
    fun badge(state: TuiState): String = when (indexState(state)) {
        IndexState.INDEXING -> "${(state.ragIndexingProgress * 100).toInt()}%"
        IndexState.NO_INDEX -> if (state.ragEnabled == false) "off" else "-"
        IndexState.DISABLED -> "off"
        IndexState.READY -> "${state.ragIndexedFiles.size}"
    }

    fun renderToBuffer(state: TuiState, width: Int, height: Int, theme: Theme = TuiColors.theme, glyphs: Glyphs = Glyphs.UNICODE): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val files = state.ragIndexedFiles
        val (glyph, colour) = when (indexState(state)) {
            IndexState.INDEXING -> glyphs.running to ThemeColor.INFO
            IndexState.NO_INDEX -> glyphs.pending to ThemeColor.DIM
            IndexState.DISABLED -> glyphs.attention to ThemeColor.WARN
            IndexState.READY -> glyphs.done to ThemeColor.OK
        }
        if (state.ragClearArmed) {
            buf.addLine(theme.style(TextFit.truncateEnd(CLEAR_QUESTION, width, glyphs.ellipsis), fg = ThemeColor.ERR, bold = true))
        } else {
            buf.addLine(theme.style(glyph, fg = colour) + " " + theme.style(TextFit.truncateEnd(headline(state), (width - 2).coerceAtLeast(1), glyphs.ellipsis), fg = colour))
        }
        if (files.isNotEmpty()) {
            val chunks = files.sumOf { it.chunks }
            val embeds = files.sumOf { it.embeddings }
            val pct = if (chunks > 0) embeds * 100 / chunks else 0
            buf.addLine(theme.style("$chunks chunks · $embeds embeddings ($pct%)", fg = ThemeColor.DIM))
        }
        if (indexState(state) == IndexState.INDEXING) {
            val cells = (width - 6).coerceIn(10, 40)
            buf.addLine(theme.style(StatusBar.gauge((state.ragIndexingProgress * 100).toInt(), glyphs, cells), fg = ThemeColor.INFO) + " ${(state.ragIndexingProgress * 100).toInt()}%")
        }
        buf.addLine()

        val results = state.ragSearchResults.take(5)
        val reserved = 2 + if (results.isNotEmpty()) results.size + 2 else 0
        if (files.isNotEmpty()) {
            val rows = (height - buf.lineCount - reserved - 2).coerceAtLeast(1)
            fileTable(files, state.ragSelectedFileIndex, width, rows, theme, glyphs).forEach { buf.addLine(it) }
            buf.addLine()
        }
        if (results.isNotEmpty()) {
            buf.addLine(theme.style(TextFit.truncateEnd("search \"${state.ragSearchQuery}\"", width, glyphs.ellipsis), fg = ThemeColor.ACCENT, bold = true))
            results.forEach { buf.addLine(TextFit.truncateEnd(it.replace('\n', ' '), width, glyphs.ellipsis)) }
            buf.addLine()
        }
        while (buf.lineCount < height - 1) buf.addLine()
        buf.addLine(theme.style(TextFit.truncateEnd(ACTIONS, width, glyphs.ellipsis), fg = ThemeColor.DIM))
        return buf
    }

    /** Table of indexed files around the selected one: directories dim, sizes with units. */
    fun fileTable(files: List<TuiRagFile>, selected: Int, width: Int, rows: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val numbers = 6 + 1 + 6 + 1 + 8
        val nameWidth = (width - 2 - numbers - 1).coerceAtLeast(8)
        val out = mutableListOf(
            theme.style("  " + "file".padEnd(nameWidth) + " " + "chunks".padStart(6) + " " + "embeds".padStart(6) + " " + "size".padStart(8), fg = ThemeColor.DIM)
        )
        val body = (rows - 2).coerceAtLeast(1)
        val start = (selected - body + 1).coerceAtLeast(0).coerceAtMost((files.size - body).coerceAtLeast(0))
        for ((i, file) in files.withIndex().drop(start).take(body)) {
            val marker = if (i == selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) + " " else "  "
            val path = TextFit.truncateMiddle(file.filePath, nameWidth, glyphs.ellipsis)
            val slash = path.lastIndexOf('/')
            val name = theme.style(path.substring(0, slash + 1), fg = ThemeColor.DIM) + path.substring(slash + 1)
            val embedsColour = if (file.embeddings >= file.chunks) ThemeColor.OK else ThemeColor.WARN
            out += marker + TuiRenderBuffer.fitToWidth(name, nameWidth) + " " + file.chunks.toString().padStart(6) + " " +
                theme.style(file.embeddings.toString().padStart(6), fg = embedsColour) + " " + formatFileSize(file.sizeBytes).padStart(8)
        }
        val below = files.size - start - body
        if (below > 0) out += theme.style("  ${glyphs.arrowDown} $below more", fg = ThemeColor.DIM)
        return out
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1fM", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(Locale.US, "%.1fK", bytes / 1_024.0)
        else -> "${bytes}B"
    }
}
