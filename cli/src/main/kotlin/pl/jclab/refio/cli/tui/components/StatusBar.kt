package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import java.util.Locale

/**
 * The bottom row of every screen: mode badge, model, what Refio is doing and the session numbers on the left,
 * the keys that matter for the focused pane on the right. When the row is too short the least useful segments
 * go first (effort, exec, requests, tokens, ...); the badge and the key hints stay.
 */
object StatusBar {

    /** Providers whose models run on the user's machine; the same two core treats as local. */
    private val LOCAL_PROVIDERS = setOf("ollama", "lmstudio")

    private class Segment(val text: String, val dropRank: Int) {
        val width: Int get() = TuiRenderBuffer.visibleLength(text)
    }

    fun render(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): String {
        if (width <= 0) return ""
        val segments = when (state.screen) {
            TuiScreen.SETTINGS -> settingsSegments(state, theme)
            else -> mainSegments(state, theme, glyphs)
        }.toMutableList()
        val hints = hintsFor(state).toMutableList()
        val sep = theme.style(" ${glyphs.separator} ", fg = ThemeColor.FRAME)

        fun leftText() = " " + segments.joinToString(sep) { it.text }
        fun hintsText() = hints.joinToString(theme.style(" · ", fg = ThemeColor.DIM)) { (key, label) ->
            theme.style(key, fg = ThemeColor.ACCENT) + " " + theme.style(label, fg = ThemeColor.DIM)
        }
        fun total() = TuiRenderBuffer.visibleLength(leftText()) + 2 + TuiRenderBuffer.visibleLength(hintsText()) + 1

        while (total() > width) {
            val droppable = segments.filter { it.dropRank > 0 }.minByOrNull { it.dropRank } ?: break
            segments.remove(droppable)
        }
        while (total() > width && hints.size > 1) hints.removeAt(hints.lastIndex)

        val left = leftText()
        val right = hintsText()
        val gap = width - TuiRenderBuffer.visibleLength(left) - TuiRenderBuffer.visibleLength(right) - 1
        val line = if (gap >= 2) left + " ".repeat(gap) + right + " " else left
        return TuiRenderBuffer.fitToWidth(line, width)
    }

    /** `local · $0` for models on this machine, `$0.0123` for paid ones, `-` when nothing is known. */
    fun formatCost(costUsd: Double?, isLocal: Boolean): String = when {
        costUsd == null -> "-"
        costUsd == 0.0 && isLocal -> "local · \$0"
        costUsd == 0.0 -> "\$0"
        else -> "\$" + String.format(Locale.US, "%.4f", costUsd)
    }

    fun isLocalModel(model: String?): Boolean =
        model?.substringBefore('/', missingDelimiterValue = "")?.lowercase() in LOCAL_PROVIDERS

    fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }

    /** Context use as 10 cells: full blocks, a half block when at least half a cell remains, light shade for the rest. */
    fun gauge(percent: Int, glyphs: Glyphs, cells: Int = 10): String {
        val halves = percent.coerceIn(0, 100) * cells * 2 / 100
        val full = halves / 2
        val half = halves % 2
        return glyphs.gaugeFull.repeat(full) + glyphs.gaugeHalf.repeat(half) + glyphs.gaugeEmpty.repeat(cells - full - half)
    }

    private fun mainSegments(state: TuiState, theme: Theme, glyphs: Glyphs): List<Segment> {
        val list = mutableListOf<Segment>()
        list += Segment(theme.badge(state.mode), dropRank = 0)
        list += Segment(theme.style(TextFit.truncateMiddle(state.model ?: "default model", 32, glyphs.ellipsis), fg = ThemeColor.INFO), dropRank = 0)
        list += Segment(activity(state, theme, glyphs), dropRank = 0)

        val max = state.contextMaxTokens
        if (max > 0) {
            val pct = (state.contextUsedTokens.toLong() * 100 / max).toInt().coerceIn(0, 100)
            val colour = when {
                pct >= 90 -> ThemeColor.ERR
                pct >= 75 -> ThemeColor.WARN
                else -> ThemeColor.OK
            }
            list += Segment(theme.style("ctx ", fg = ThemeColor.DIM) + theme.style(gauge(pct, glyphs), fg = colour) + " $pct%", dropRank = 7)
            list += Segment("${formatTokens(state.contextUsedTokens.toLong())}/${formatTokens(max.toLong())}", dropRank = 6)
        } else {
            list += Segment(theme.style("ctx -", fg = ThemeColor.DIM), dropRank = 7)
        }
        val cached = if (state.sessionCachedTokens > 0) theme.style(" cache ${formatTokens(state.sessionCachedTokens)}", fg = ThemeColor.DIM) else ""
        list += Segment("${glyphs.arrowDown}${formatTokens(state.sessionTokensIn)} ${glyphs.arrowUp}${formatTokens(state.sessionTokensOut)}$cached", dropRank = 4)
        list += Segment("${state.apiLogs.size} req", dropRank = 3)
        list += Segment(formatCost(state.totalCostUsd, isLocalModel(state.model)), dropRank = 5)
        if (state.noEgressEnabled) list += Segment(theme.style("no-egress", fg = ThemeColor.WARN), dropRank = 8)
        list += Segment(theme.style("effort ", fg = ThemeColor.DIM) + state.reasoningEffort.name.lowercase(), dropRank = 1)
        list += Segment(theme.style("exec ", fg = ThemeColor.DIM) + state.executionMode.lowercase(), dropRank = 2)
        return list
    }

    private fun activity(state: TuiState, theme: Theme, glyphs: Glyphs): String = when {
        !state.coreConnected -> theme.style("${glyphs.failed} core offline", fg = ThemeColor.ERR)
        state.pendingToolApproval != null || state.pendingApprovals.isNotEmpty() ->
            theme.style("${glyphs.attention} waiting for approval", fg = ThemeColor.WARN)
        state.pendingQuestionId != null -> theme.style("${glyphs.attention} waiting for your answer", fg = ThemeColor.WARN)
        state.isStreaming -> {
            val steps = state.activePlan ?: state.subtasks
            val running = steps.indexOfFirst { it.status.equals("RUNNING", ignoreCase = true) }
            val label = if (running >= 0) "step ${running + 1}/${steps.size}" else "streaming${glyphs.ellipsis}"
            theme.style("${glyphs.running} $label", fg = ThemeColor.WARN)
        }
        else -> theme.style("${glyphs.dot} idle", fg = ThemeColor.OK)
    }

    private fun settingsSegments(state: TuiState, theme: Theme): List<Segment> = listOfNotNull(
        Segment(theme.style(" SETTINGS ", fg = ThemeColor.ACCENT, bold = true, reverse = true), dropRank = 0),
        Segment(theme.style(TuiSettingsScreen.breadcrumb(state), fg = ThemeColor.TEXT), dropRank = 0),
        state.settingsNotice?.let { Segment(theme.style(it.text, fg = if (it.error) ThemeColor.ERR else ThemeColor.OK), dropRank = 1) }
    )

    /** The 4-6 keys that act on the focused pane right now. */
    fun hintsFor(state: TuiState): List<Pair<String, String>> = when {
        state.pendingToolApproval != null || state.pendingApprovals.isNotEmpty() ->
            listOf("y" to "yes", "a" to "allow", "d" to "diff", "n" to "no", "↑↓" to "choose")
        state.screen == TuiScreen.SETTINGS && state.settingsEditingField != null ->
            listOf("Enter" to "save", "Esc" to "cancel")
        state.screen == TuiScreen.SETTINGS && TuiSettingsScreen.tabTitle(state.settingsTab) == "Tools" ->
            listOf("↑↓" to "tool", "Enter" to "cycle", "Tab" to "group", "a/k/x" to "group ON/ASK/OFF", "Esc" to "back")
        state.screen == TuiScreen.SETTINGS ->
            listOf("↑↓" to "field", "Enter" to "toggle/edit", "←→ 1-0" to "section", "Esc" to "back")
        state.screen == TuiScreen.HELP -> listOf("↑↓" to "scroll", "Esc" to "back")
        state.screen == TuiScreen.HISTORY -> listOf("↑↓" to "select", "Enter" to "open", "Esc" to "back")
        state.isStreaming -> listOf("^C" to "cancel", "Tab" to "focus", "^O" to "model", "^Q" to "quit")
        state.selectedMessageIndex >= 0 -> listOf("^P/^B" to "message", "^Y" to "copy", "Esc" to "prompt")
        state.panelFocused && state.activeTab != TuiTab.CHAT ->
            listOf("Tab" to "prompt", "↑↓" to "select", "Enter" to "open", "F1" to "help")
        else -> listOf("Shift+Tab" to "mode", "Tab" to "focus", "^O" to "model", "^C" to "cancel", "^Q" to "quit")
    }
}
