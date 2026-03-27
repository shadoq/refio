package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * RAG tab view — indexed files, search, statistics, interactive actions.
 * Mirrors RagViewPanel from the IntelliJ plugin.
 */
object TuiRagView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        buf.addLine(TuiColors.highlight("RAG — Retrieval Augmented Generation"))
        buf.addLine()

        // Show real stats from contextSections if available
        val ragIndex = state.contextSections.find { it.category == "rag" }
        val ragFiles = state.contextSections.find { it.category == "rag_files" }

        if (ragIndex != null || ragFiles != null) {
            buf.addLine(TuiColors.highlight("  Index Statistics"))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))

            if (ragFiles != null) {
                buf.addLine("    Files indexed:     ${TuiColors.accent(ragFiles.tokensUsed.toString())}")
            }
            if (ragIndex != null) {
                val chunks = ragIndex.tokensMax
                val embeddings = ragIndex.tokensUsed
                val completionPct = if (chunks > 0) (embeddings * 100.0 / chunks) else 0.0
                val pctColor = when {
                    completionPct >= 90 -> TuiColors.statusSuccess
                    completionPct >= 50 -> TuiColors.statusPending
                    else -> TuiColors.statusFailed
                }
                buf.addLine("    Chunks:            ${TuiColors.accent(chunks.toString())}")
                buf.addLine("    Embeddings:        ${TuiColors.accent(embeddings.toString())} ${pctColor("(${String.format("%.0f", completionPct)}%)")}")
            }
            buf.addLine()
        } else {
            buf.addLine(TuiColors.muted("  No indexed files. Indexing may not be configured."))
            buf.addLine(TuiColors.muted("  Ensure embedding model is available (e.g. ollama/nomic-embed-text)."))
            buf.addLine()
        }

        // Progress bar (when indexing)
        if (state.ragIndexingProgress >= 0) {
            buf.addLine(TuiColors.highlight("  Indexing Progress"))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))

            val barWidth = (width - 20).coerceIn(10, 40)
            val filled = (barWidth * state.ragIndexingProgress).toInt()
            val empty = barWidth - filled
            val pctText = "${(state.ragIndexingProgress * 100).toInt()}%"
            val bar = TuiColors.statusRunning("█".repeat(filled)) + TuiColors.muted("░".repeat(empty))
            buf.addLine("    [$bar] $pctText")

            if (state.ragIndexingStatus.isNotBlank()) {
                buf.addLine("    ${TuiColors.muted(state.ragIndexingStatus)}")
            }
            buf.addLine()
        } else if (state.ragIndexingStatus.isNotBlank()) {
            buf.addLine("    ${TuiColors.muted(state.ragIndexingStatus)}")
            buf.addLine()
        }

        // Indexed files table
        val files = state.ragIndexedFiles
        if (files.isNotEmpty()) {
            buf.addLine(TuiColors.highlight("  Indexed Files (${files.size})"))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))
            // Header
            buf.addLine(TuiColors.muted("    ${"File".padEnd(40)} ${"Chunks".padStart(6)} ${"Embeds".padStart(6)} ${"Size".padStart(8)}"))
            // Show up to 15 files
            val maxFiles = (height - buf.lineCount - 4).coerceIn(0, 15)
            for ((idx, file) in files.take(maxFiles).withIndex()) {
                val cursor = if (idx == state.ragSelectedFileIndex) "> " else "  "
                val name = if (file.filePath.length > 36) "..." + file.filePath.takeLast(35) else file.filePath
                val sizeStr = formatFileSize(file.sizeBytes)
                val embColor = if (file.embeddings >= file.chunks) TuiColors.statusSuccess else TuiColors.statusPending
                buf.addLine("  $cursor${name.padEnd(38)} ${file.chunks.toString().padStart(6)} ${embColor(file.embeddings.toString().padStart(6))} ${sizeStr.padStart(8)}")
            }
            if (files.size > maxFiles) {
                buf.addLine(TuiColors.muted("    ... and ${files.size - maxFiles} more"))
            }
            buf.addLine()
        }

        // RAG search results
        if (state.ragSearchResults.isNotEmpty()) {
            buf.addLine(TuiColors.highlight("  Search Results: \"${state.ragSearchQuery}\""))
            buf.addLine(TuiColors.border("  ${"─".repeat((width - 4).coerceAtLeast(10))}"))
            for (result in state.ragSearchResults.take(5)) {
                if (buf.lineCount >= height - 3) break
                buf.addWrapped("    $result")
            }
            buf.addLine()
        }

        // Toolbar
        buf.addLine()
        buf.addLine(TuiColors.muted("  [r] Reindex  [e] Embeddings  [s] Stop  [c] Clear  [v] View chunks  [q] Search  [↑↓] Navigate"))

        return buf
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${String.format("%.1f", bytes / 1_048_576.0)}M"
        bytes >= 1_024 -> "${String.format("%.1f", bytes / 1_024.0)}K"
        else -> "${bytes}B"
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
