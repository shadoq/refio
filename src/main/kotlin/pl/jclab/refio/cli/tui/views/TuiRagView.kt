package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * RAG tab view — indexed files, search, statistics.
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

        buf.addLine(TuiColors.highlight("  Commands"))
        buf.addLine(TuiColors.muted("    /rag-search <query>    — search indexed content"))
        buf.addLine(TuiColors.muted("    /rag-refresh            — re-index project files"))
        buf.addLine(TuiColors.muted("    /rag-chunks <file>      — show chunks for file"))

        return buf
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
