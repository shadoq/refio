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
        brightCyan,      // 0: project overview / semantic summary
        brightBlue,      // 1: instructions / project structure
        magenta,         // 2: technologies / dependencies
        brightMagenta,   // 3: code analysis / framework analysis
        brightRed,       // 4: current task / subtasks
        brightYellow,    // 5: conversation / recent work
        yellow,          // 6: RAG fragments
        brightGreen,     // 7: user context / user requirements
        cyan,            // 8: key components / domain analysis
        green,           // 9: working memory / mcp resources
        blue,            // 10: system prompt / system messages
        red,             // 11: assistant messages
        white,           // 12: navigation map / patterns
        brightWhite,     // 13: html/css/ts analysis
        gray,            // 14: context overhead / free space
    )

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        if (state.contextSections.isEmpty()) {
            buf.addLine(TuiColors.muted("No context loaded."))
            buf.addLine(TuiColors.muted("Send a message to populate context."))
            return buf
        }

        // Detail view for selected context section
        if (state.contextDetailVisible) {
            renderDetailView(buf, state, width, height)
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

        // === Legend (all non-zero sections with color indicators, two-column) ===
        val legendSections = state.contextSections.filter { it.tokensUsed > 0 }
        val colWidth = ((width - 4) / 2).coerceAtLeast(30)
        val pairs = legendSections.chunked(2)
        for (pair in pairs) {
            if (buf.lineCount >= height - 2) break
            val sb = StringBuilder()
            for ((i, section) in pair.withIndex()) {
                val color = sectionColors[section.colorIndex % sectionColors.size]
                val percent = if (section.percentage > 0) String.format("(%.1f%%)", section.percentage) else ""
                val tokens = formatTokens(section.tokensUsed)
                val entry = "${color("██")} ${section.name}: $tokens $percent"
                // Pad first column
                if (i == 0 && pair.size > 1) {
                    // Approximate visible length (without ANSI) for padding
                    val visibleLen = 4 + section.name.length + tokens.length + percent.length + 3
                    val pad = (colWidth - visibleLen).coerceAtLeast(1)
                    sb.append("  $entry${" ".repeat(pad)}")
                } else {
                    sb.append("  $entry")
                }
            }
            buf.addLine(sb.toString())
        }
        val zeroSections = state.contextSections.count { it.tokensUsed == 0 }
        if (zeroSections > 0) {
            buf.addLine(TuiColors.muted("  ... +$zeroSections empty sections"))
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
        buf.addLine(TuiColors.muted("  [↑↓] Navigate  [Enter] Detail view  [i] Inspect content"))

        return buf
    }

    private fun renderDetailView(buf: TuiRenderBuffer, state: TuiState, width: Int, height: Int) {
        val section = state.contextSections.getOrNull(state.selectedContextIndex) ?: run {
            buf.addLine(TuiColors.muted("No section selected."))
            return
        }

        val color = sectionColors[section.colorIndex % sectionColors.size]
        val categoryColor = when (section.category) {
            "project" -> TuiColors.contextProject
            "user" -> TuiColors.contextUser
            "rag" -> TuiColors.contextRag
            "conversation" -> TuiColors.contextConversation
            "tools" -> TuiColors.contextTools
            else -> TuiColors.muted
        }

        // Header
        buf.addLine(TuiColors.highlight("Context Detail"))
        buf.addLine(TuiColors.border("\u2500".repeat((width - 2).coerceAtLeast(10))))
        buf.addLine("  ${color("\u25cf")} ${categoryColor(section.name)}")
        val tokens = formatTokens(section.tokensUsed)
        val percent = if (section.percentage > 0) String.format("%.1f%%", section.percentage) else "0.0%"
        buf.addLine("  ${TuiColors.muted("Category:")} ${section.category}  ${TuiColors.muted("Tokens:")} $tokens  ${TuiColors.muted("Share:")} $percent")
        buf.addLine()

        // Full content with scroll support
        val content = section.content
        if (content.isNullOrBlank()) {
            buf.addLine(TuiColors.muted("  (no content available)"))
        } else {
            val contentLines = content.lines()
            val headerLines = 7 // lines used by header + toolbar
            val contentHeight = (height - headerLines).coerceAtLeast(3)
            val scrollOffset = state.contextDetailScrollOffset.coerceIn(0, (contentLines.size - contentHeight).coerceAtLeast(0))
            val visible = contentLines.drop(scrollOffset).take(contentHeight)

            for (line in visible) {
                if (buf.lineCount >= height - 2) break
                buf.addLine("  ${TuiColors.muted(line)}")
            }

            if (contentLines.size > contentHeight) {
                val scrollPercent = if (contentLines.size > contentHeight) {
                    ((scrollOffset.toDouble() / (contentLines.size - contentHeight).coerceAtLeast(1)) * 100).toInt()
                } else 0
                buf.addLine(TuiColors.muted("  --- ${contentLines.size} lines total, scroll: $scrollPercent% ---"))
            }
        }

        buf.addLine()
        buf.addLine(TuiColors.muted("[Enter/Esc] Back  [\u2191\u2193] Scroll"))
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
