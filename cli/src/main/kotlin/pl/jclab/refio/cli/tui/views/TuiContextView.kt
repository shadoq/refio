package pl.jclab.refio.cli.tui.views

import pl.jclab.refio.cli.tui.components.StatusBar
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiContextSection
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import pl.jclab.refio.core.llm.ContextWindowSource

/**
 * How full the context window is: used / window, a gauge split into three groups (conversation,
 * project analysis, overhead), one row per group, a warning while the window is only the provider's
 * default, then the sections with the selected one's preview. The window is the one the core resolves.
 */
object TuiContextView {

    /** The three groups the gauge shows, built from the section categories. */
    enum class Group(val label: String, val colour: ThemeColor, val categories: Set<String>) {
        CONVERSATION("conversation", ThemeColor.INFO, setOf("conversation", "user")),
        PROJECT("project analysis", ThemeColor.VIOLET, setOf("project", "rag")),
        OVERHEAD("overhead", ThemeColor.DIM, emptySet());

        companion object {
            fun of(section: TuiContextSection): Group = entries.firstOrNull { section.category in it.categories } ?: OVERHEAD
        }
    }

    private const val GAUGE_MAX = 50

    fun renderToBuffer(
        state: TuiState,
        width: Int,
        height: Int,
        theme: Theme = TuiColors.theme,
        glyphs: Glyphs = Glyphs.UNICODE,
    ): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        if (state.contextDetailVisible && state.contextSections.isNotEmpty()) {
            renderDetailView(buf, state, width, height)
            return buf
        }

        val sections = state.contextSections
        val used = if (state.contextUsedTokens > 0) state.contextUsedTokens else sections.sumOf { it.tokensUsed }
        val window = state.contextMaxTokens
        val headline = if (window > 0) {
            "${StatusBar.formatTokens(used.toLong())} / ${StatusBar.formatTokens(window.toLong())} tokens (${(used.toLong() * 100 / window).coerceIn(0, 100)}%)"
        } else {
            "${StatusBar.formatTokens(used.toLong())} tokens · window not known yet"
        }
        buf.addLine(theme.style(TextFit.truncateEnd(headline, width), fg = ThemeColor.TEXT, bold = true))

        val totals = Group.entries.associateWith { group -> sections.filter { Group.of(it) == group }.sumOf { it.tokensUsed } }
        buf.addLine(gauge(totals, used, window, width, theme, glyphs))
        for (group in Group.entries) {
            val tokens = totals.getValue(group)
            val share = if (window > 0) "%.1f%%".format(java.util.Locale.US, tokens * 100.0 / window) else "-"
            val label = theme.style(glyphs.dot, fg = group.colour) + " " + theme.style(group.label.padEnd(17), fg = ThemeColor.TEXT)
            buf.addLine(TuiRenderBuffer.fitToWidth(label + theme.style(StatusBar.formatTokens(tokens.toLong()).padStart(7) + "  " + share.padStart(6), fg = ThemeColor.DIM), width))
        }
        windowWarning(state)?.let { warning ->
            wrapWords(warning, width).forEach { buf.addLine(theme.style(it, fg = ThemeColor.WARN)) }
        }

        if (sections.isEmpty()) {
            if (buf.lineCount < height) buf.addLine(theme.style("Sections appear after the first message.", fg = ThemeColor.DIM))
            return buf
        }
        if (buf.lineCount + 3 > height) return buf
        buf.addLine()
        val footer = theme.style(TextFit.truncateEnd("↑↓ section · Enter detail · i inspect", width), fg = ThemeColor.DIM)
        val listSpace = height - buf.lineCount - 1
        val selected = state.selectedContextIndex.coerceIn(0, sections.lastIndex)
        val first = (selected - listSpace + 1).coerceIn(0, (sections.size - listSpace).coerceAtLeast(0))
        for ((index, section) in sections.withIndex().drop(first)) {
            if (buf.lineCount >= height - 1) break
            val marker = if (index == selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
            val tokens = StatusBar.formatTokens(section.tokensUsed.toLong()).padStart(7)
            val nameWidth = (width - 2 - 2 - 7).coerceAtLeast(1)
            val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(section.name, nameWidth, glyphs.ellipsis), nameWidth)
            val nameColour = if (index == selected) ThemeColor.ACCENT else ThemeColor.TEXT
            buf.addLine(marker + theme.style(glyphs.dot, fg = Group.of(section).colour) + " " + theme.style(name, fg = nameColour) + "  " + theme.style(tokens, fg = ThemeColor.DIM))
        }
        if (buf.lineCount < height) buf.addLine(footer)
        return buf
    }

    /** Shown while the window is the provider's built-in default rather than a size someone set. */
    fun windowWarning(state: TuiState): String? {
        if (state.contextWindowSource != ContextWindowSource.PROVIDER_DEFAULT) return null
        val provider = state.model?.substringBefore('/', missingDelimiterValue = "")?.takeIf { it.isNotBlank() } ?: "provider"
        val size = windowSize(state.contextMaxTokens)
        return if (provider.equals("ollama", ignoreCase = true)) {
            "num_ctx not set: Ollama default $size in use. Set the context size in Settings, Providers."
        } else {
            "Context size not set: $provider default $size in use. Set it in Settings, Providers."
        }
    }

    /** Window sizes are powers of two in practice: 32768 reads as `32K`, anything else as usual. */
    fun windowSize(tokens: Int): String =
        if (tokens >= 1024 && tokens % 1024 == 0) "${tokens / 1024}K" else StatusBar.formatTokens(tokens.toLong())

    /** [width] cells (at most 50): the three groups in their colours, then the free part. */
    private fun gauge(totals: Map<Group, Int>, used: Int, window: Int, width: Int, theme: Theme, glyphs: Glyphs): String {
        val cells = width.coerceAtMost(GAUGE_MAX).coerceAtLeast(1)
        val capacity = if (window > 0) window else maxOf(used, 1)
        val sb = StringBuilder()
        var filled = 0
        for (group in Group.entries) {
            val n = (totals.getValue(group).toLong() * cells / capacity).toInt().coerceIn(0, cells - filled)
            if (n > 0) sb.append(theme.style(glyphs.gaugeFull.repeat(n), fg = group.colour))
            filled += n
        }
        // Tokens the sections do not account for (prompt framing) still use the window.
        val unaccounted = ((used.toLong() * cells / capacity).toInt() - filled).coerceIn(0, cells - filled)
        if (unaccounted > 0) sb.append(theme.style(glyphs.gaugeFull.repeat(unaccounted), fg = Group.OVERHEAD.colour))
        filled += unaccounted
        sb.append(theme.style(glyphs.gaugeEmpty.repeat(cells - filled), fg = ThemeColor.FRAME))
        return sb.toString()
    }

    private fun wrapWords(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (candidate.length > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.map { TextFit.truncateEnd(it, width) }
    }

    private fun renderDetailView(buf: TuiRenderBuffer, state: TuiState, width: Int, height: Int) {
        val section = state.contextSections.getOrNull(state.selectedContextIndex) ?: run {
            buf.addLine(TuiColors.muted("No section selected."))
            return
        }

        val group = Group.of(section)
        val color = { text: String -> TuiColors.theme.style(text, fg = group.colour) }
        val categoryColor = TuiColors.highlight

        // Header
        buf.addLine(TuiColors.highlight("Context Detail"))
        buf.addLine(TuiColors.border("\u2500".repeat((width - 2).coerceAtLeast(10))))
        buf.addLine("  ${color("\u25cf")} ${categoryColor(section.name)}")
        val tokens = formatTokens(section.tokensUsed)
        val percent = if (section.percentage > 0) String.format(java.util.Locale.US, "%.1f%%", section.percentage) else "0.0%"
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

    private fun formatTokens(tokens: Int): String = StatusBar.formatTokens(tokens.toLong())
}
