package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/**
 * Left column of the wide layout: REFIO (where you can go, with live badges), SESSIONS (current and recent
 * conversations) and SYSTEM (provider, connection and the session toggles with their keys).
 */
object Sidebar {

    private class NavItem(val icon: String, val label: String, val key: String, val active: (TuiState) -> Boolean, val badge: (TuiState) -> String)

    private val NAV = listOf(
        NavItem("›_", "Chat", "Esc", { it.screen == TuiScreen.MAIN && it.activeTab == TuiTab.CHAT }, { "" }),
        NavItem("≡", "Steps", "F2", { it.isMain(TuiTab.STEPS) }, ::stepsBadge),
        NavItem("◔", "Context", "F3", { it.isMain(TuiTab.CONTEXT) }, ::contextBadge),
        NavItem("⌕", "RAG", "F4", { it.isMain(TuiTab.RAG) }, { s -> pl.jclab.refio.cli.tui.views.TuiRagView.badge(s) }),
        NavItem("☰", "Logs", "F5", { it.isMain(TuiTab.LOGS) }, { "" }),
        NavItem("◇", "Debug", "F6", { it.isMain(TuiTab.DEBUG) }, { "" }),
        NavItem("⇄", "API", "F7", { it.isMain(TuiTab.API_LOGS) }, { s -> "${s.apiLogs.size}" }),
        NavItem("▤", "Files", "F8", { it.isMain(TuiTab.FILES) }, { "" }),
        NavItem("✱", "Settings", "F9", { it.screen == TuiScreen.SETTINGS }, { "" }),
        NavItem("?", "Help", "F1", { it.screen == TuiScreen.HELP }, { "" })
    )

    private const val SESSION_ROWS = 4

    fun render(state: TuiState, width: Int, height: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val frame = Frame(theme, glyphs)
        val navHeight = NAV.size + 2
        val sessionsHeight = SESSION_ROWS + 2
        val systemHeight = (height - navHeight - sessionsHeight).coerceAtLeast(0)
        val inner = frame.innerSize(width, navHeight).first
        return frame.render(width, navHeight, "refio", null, FrameState.INACTIVE, navRows(state, inner, theme, glyphs)) +
            frame.render(width, sessionsHeight, "sessions", "Alt+H", FrameState.INACTIVE, sessionRows(state, inner, theme, glyphs)) +
            frame.render(width, systemHeight, "system", null, FrameState.INACTIVE, systemRows(state, inner, theme, glyphs))
    }

    private fun navRows(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> = NAV.map { item ->
        val active = item.active(state)
        val icon = if (glyphs.isAscii) item.label.take(1) else item.icon
        val marker = if (active) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
        val label = TuiRenderBuffer.fitToWidth(" ${icon.padEnd(2)} ${item.label}", 12)
        val labelStyled = if (active) theme.style(label, fg = ThemeColor.ACCENT, bold = true) else theme.style(label, fg = ThemeColor.TEXT)
        val badge = item.badge(state)
        val keyWidth = 4
        val badgeWidth = (width - 1 - 12 - keyWidth).coerceAtLeast(0)
        val badgeText = theme.style(TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(badge, badgeWidth, glyphs.ellipsis), badgeWidth), fg = ThemeColor.INFO)
        marker + labelStyled + badgeText + theme.style(item.key.padStart(keyWidth), fg = ThemeColor.DIM)
    }

    private fun sessionRows(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> {
        if (state.sessions.isEmpty()) return listOf(theme.style("-", fg = ThemeColor.DIM))
        val current = state.sessions.firstOrNull { it.id == state.activeSessionId }
        val ordered = listOfNotNull(current) + state.sessions.filter { it.id != state.activeSessionId }
        return ordered.take(SESSION_ROWS).map { task ->
            val isCurrent = task.id == state.activeSessionId
            val name = TextFit.truncateEnd(task.name.ifBlank { "untitled" }, width - 2, glyphs.ellipsis)
            if (isCurrent) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) + " " + theme.style(name, fg = ThemeColor.ACCENT, bold = true)
            else "  " + theme.style(name, fg = ThemeColor.DIM)
        }
    }

    private fun systemRows(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val provider = state.model?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: "-"
        val dot = if (state.coreConnected) theme.style(glyphs.dot, fg = ThemeColor.OK) else theme.style(glyphs.dot, fg = ThemeColor.ERR)
        fun row(label: String, value: String, key: String = ""): String {
            val left = theme.style(label.padEnd(8), fg = ThemeColor.DIM) + value
            val gap = (width - TuiRenderBuffer.visibleLength(left) - key.length).coerceAtLeast(1)
            return left + " ".repeat(gap) + theme.style(key, fg = ThemeColor.DIM)
        }
        return listOf(
            "$dot ${theme.style(TextFit.truncateEnd(provider, width - 2, glyphs.ellipsis), fg = ThemeColor.INFO)}",
            row("core", if (state.coreConnected) "in-process" else "offline"),
            row("rag", ragValue(state, width - 9, glyphs), "F4"),
            row("effort", state.reasoningEffort.name.lowercase(), "^T"),
            row("exec", state.executionMode.lowercase(), "^E"),
            row("egress", if (state.noEgressEnabled) theme.style("blocked", fg = ThemeColor.WARN) else "allowed", "^N")
        )
    }

    private fun ragValue(state: TuiState, width: Int, glyphs: Glyphs): String = when {
        state.ragIndexingProgress in 0.0..1.0 -> "indexing ${(state.ragIndexingProgress * 100).toInt()}%"
        else -> TextFit.truncateEnd(pl.jclab.refio.cli.tui.views.TuiRagView.headline(state), (width - 3).coerceAtLeast(1), glyphs.ellipsis)
    }

    private fun TuiState.isMain(tab: TuiTab) = screen == TuiScreen.MAIN && activeTab == tab

    internal fun stepsBadge(state: TuiState): String {
        val steps = state.activePlan ?: state.subtasks
        if (steps.isEmpty()) return ""
        val done = steps.count { it.status.uppercase() in DONE_STATUSES }
        return "$done/${steps.size}"
    }

    internal fun contextBadge(state: TuiState): String =
        if (state.contextMaxTokens <= 0) "-" else "${(state.contextUsedTokens.toLong() * 100 / state.contextMaxTokens)}%"

    /** Step statuses that count as finished; core writes SUCCESS, older rows may say COMPLETED. */
    val DONE_STATUSES = setOf("SUCCESS", "COMPLETED", "SKIPPED")
}
