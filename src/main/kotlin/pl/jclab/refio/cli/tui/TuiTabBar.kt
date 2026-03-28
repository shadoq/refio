package pl.jclab.refio.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.state.TuiTab

/**
 * Tab bar component — renders 7 tabs with F-key labels.
 * Active tab is highlighted, inactive tabs are dimmed.
 */
object TuiTabBar {
    fun render(terminal: Terminal, activeTab: TuiTab) {
        terminal.println(renderToString(activeTab))
    }

    fun renderToString(activeTab: TuiTab, panelFocused: Boolean = false): String {
        val sep = TuiColors.border("\u2502")
        val helpLabel = TuiColors.tabInactive(" F1:Help ")
        val tabs = TuiTab.entries.filter { it != TuiTab.CHAT }.map { tab ->
            val fKeyNum = tab.fKey ?: (tab.ordinal + 1)
            val label = " F${fKeyNum}:${tab.label} "
            if (tab == activeTab) {
                if (panelFocused) {
                    TuiColors.tabFocused(label)
                } else {
                    TuiColors.tabActive(label)
                }
            } else {
                TuiColors.tabInactive(label)
            }
        }
        val focusHint = if (activeTab != TuiTab.CHAT) {
            "  " + TuiColors.muted("[Tab] to toggle focus")
        } else ""
        return helpLabel + sep + tabs.joinToString(sep) + focusHint
    }
}
