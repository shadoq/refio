package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiHistoryScreenTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render history screen`() {
        val state = TuiState(screen = TuiScreen.HISTORY)
        TuiHistoryScreen.render(terminal, state, 20)
    }

    @Test
    fun `should show command hints`() {
        val state = TuiState(screen = TuiScreen.HISTORY)
        TuiHistoryScreen.render(terminal, state, 30)
    }
}
