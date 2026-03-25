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

    fun renderToString(activeTab: TuiTab): String {
        return TuiTab.entries.mapIndexed { i, tab ->
            val label = " F${i + 1}:${tab.label} "
            if (tab == activeTab) TuiColors.tabActive(label) else TuiColors.tabInactive(label)
        }.joinToString(TuiColors.border("│"))
    }
}
