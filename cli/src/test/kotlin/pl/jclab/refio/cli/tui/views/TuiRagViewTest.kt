package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiRagViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty rag view`() {
        val state = TuiState()
        TuiRagView.render(terminal, state, 20)
    }

    @Test
    fun `should render rag view with default content`() {
        val state = TuiState(activeTab = TuiTab.RAG)
        TuiRagView.render(terminal, state, 20)
    }
}
