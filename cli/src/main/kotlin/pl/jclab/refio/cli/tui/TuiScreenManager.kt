package pl.jclab.refio.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiLayoutRegions
import pl.jclab.refio.cli.tui.screens.TuiHelpScreen
import pl.jclab.refio.cli.tui.screens.TuiHistoryScreen
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Manages full-screen overlay screens (History, Settings).
 * Main screen rendering is now handled by TuiRenderer's split-pane compositor.
 */
object TuiScreenManager {
    fun renderOverlay(terminal: Terminal, state: TuiState, layout: TuiLayoutRegions) {
        when (state.screen) {
            TuiScreen.HISTORY -> TuiHistoryScreen.render(terminal, state, layout.contentHeight)
            TuiScreen.SETTINGS -> TuiSettingsScreen.render(terminal, state, layout.contentHeight)
            TuiScreen.HELP -> { /* handled by TuiRenderer */ }
            TuiScreen.MAIN -> { /* handled by TuiRenderer */ }
        }
    }
}
