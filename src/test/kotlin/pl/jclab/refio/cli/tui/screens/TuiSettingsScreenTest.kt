package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiSettingsScreenTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render default settings tab (General)`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 0)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should render all 11 settings tabs`() {
        for (i in 0..10) {
            val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = i)
            TuiSettingsScreen.render(terminal, state, 30)
        }
    }

    @Test
    fun `should render Providers tab`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 1)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should render Theme tab with color preview`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 10)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should render MCP tab`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 5)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should render Tools tab`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 7)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should render Advanced tab`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 9)
        TuiSettingsScreen.render(terminal, state, 30)
    }

    @Test
    fun `should handle out-of-range settings tab`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 99)
        TuiSettingsScreen.render(terminal, state, 30)
    }
}
