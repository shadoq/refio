package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiContextSection
import pl.jclab.refio.cli.tui.state.TuiState
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle

/**
 * Context tab view — token usage visualization + sections list.
 * Adapted from plugin's ContextPanel with colored token usage bar.
 */
object TuiContextView {

    /** Color palette for context sections (matches plugin's ContextSectionColorPalette) */
    private val sectionColors: List<TextStyle> = listOf(
        brightCyan,      // 0: project overview
        brightBlue,      // 1: instructions
        magenta,         // 2: technologies
        brightMagenta,   // 3: code analysis
        brightRed,       // 4: current task
        brightYellow,    // 5: conversation
        yellow,          // 6: RAG
        brightGreen,     // 7: user context
        cyan,            // 8: key components
        green,           // 9: working memory
    )

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        if (state.contextSections.isEmpty()) {
            buf.addLine(TuiColors.muted("No context loaded."))
            buf.addLine(TuiColors.muted("Send a message to populate context."))
            return buf
        }

        buf.addLine(TuiColors.highlight("Project Context"))
        buf.addLine()

        // === Token usage bar (colored segments like plugin's TokenUsageVisualizationPanel) ===
        val totalTokens = state.contextSections.sumOf { it.tokensUsed }
        val contextLimit = state.contextSections.firstOrNull()?.tokensMax?.coerceAtLeast(1) ?: 128_000
        val usedPercent = ((totalTokens.toDouble() / contextLimit) * 100).toInt().coerceIn(0, 100)
        val barWidth = (width - 4).coerceIn(20, 60)

        // Header: "Total: 45,230 / 128,000 tokens (35%)"
        buf.addLine(TuiColors.highlight("Total: ${formatTokens(totalTokens)} / ${formatTokens(contextLimit)} tokens ($usedPercent%)"))

        // Build the colored bar
        val usedBarWidth = ((totalTokens.toDouble() / contextLimit) * barWidth).toInt().coerceIn(0, barWidth)
        val emptyBarWidth = barWidth - usedBarWidth

        if (usedBarWidth > 0 && state.contextSections.isNotEmpty()) {
            // Colored segments proportional to each section's share
            val sb = StringBuilder()
            sb.append(TuiColors.border("["))
            var consumed = 0
            for (section in state.contextSections) {
                if (consumed >= usedBarWidth) break
                val sectionWidth = if (totalTokens > 0)
                    ((section.tokensUsed.toDouble() / totalTokens) * usedBarWidth).toInt().coerceAtLeast(if (section.tokensUsed > 0) 1 else 0)
                else 0
                val actual = sectionWidth.coerceAtMost(usedBarWidth - consumed)
                if (actual > 0) {
                    val color = sectionColors[section.colorIndex % sectionColors.size]
                    sb.append(color("█".repeat(actual)))
                    consumed += actual
                }
            }
            // Fill remaining used area
            if (consumed < usedBarWidth) {
                sb.append(TuiColors.progressFilled("█".repeat(usedBarWidth - consumed)))
            }
            sb.append(TuiColors.progressEmpty("░".repeat(emptyBarWidth)))
            sb.append(TuiColors.border("]"))
            buf.addLine(sb.toString())
        } else {
            buf.addLine(TuiColors.border("[") + TuiColors.progressEmpty("░".repeat(barWidth)) + TuiColors.border("]"))
        }

        buf.addLine()

        // === Legend (top sections with color indicators) ===
        val maxLegend = 8.coerceAtMost(state.contextSections.size)
        val legendSections = state.contextSections.take(maxLegend)
        for (section in legendSections) {
            if (buf.lineCount >= height - 2) break
            val color = sectionColors[section.colorIndex % sectionColors.size]
            val colorBlock = color("██")
            val percent = if (section.percentage > 0) String.format("%.1f%%", section.percentage) else ""
            val tokens = formatTokens(section.tokensUsed)
            buf.addLine("  $colorBlock ${section.name}  ${TuiColors.muted("$tokens tok  $percent")}")
        }
        if (state.contextSections.size > maxLegend) {
            buf.addLine(TuiColors.muted("  +${state.contextSections.size - maxLegend} more sections"))
        }

        buf.addLine()

        // === Section detail list ===
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))
        val selectedCtxIdx = state.selectedContextIndex
        for ((idx, section) in state.contextSections.withIndex()) {
            if (buf.lineCount >= height - 3) break
            val color = sectionColors[section.colorIndex % sectionColors.size]
            val categoryColor = when (section.category) {
                "project" -> TuiColors.contextProject
                "user" -> TuiColors.contextUser
                "rag" -> TuiColors.contextRag
                "conversation" -> TuiColors.contextConversation
                "tools" -> TuiColors.contextTools
                else -> TuiColors.muted
            }

            val marker = if (idx == selectedCtxIdx) ">" else " "
            val tokens = formatTokens(section.tokensUsed)
            val percent = if (section.percentage > 0) " (${String.format("%.1f", section.percentage)}%)" else ""
            buf.addLine("$marker${color("●")} ${categoryColor(section.name)} ${TuiColors.muted("$tokens$percent")}")

            // Show content preview for selected section
            if (idx == selectedCtxIdx && section.content != null) {
                val preview = section.content.take(200).lines().take(4)
                for (line in preview) {
                    if (buf.lineCount >= height - 3) break
                    buf.addLine(TuiColors.muted("    $line"))
                }
                if (section.content.length > 200) {
                    buf.addLine(TuiColors.muted("    ... (${section.content.length} chars total)"))
                }
            }
        }

        buf.addLine()
        buf.addLine(TuiColors.muted("  [↑↓] Navigate  [i] Inspect content"))

        return buf
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }

    private fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
}
