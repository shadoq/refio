package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiContextViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty context`() {
        val state = TuiState()
        TuiContextView.render(terminal, state, 20)
    }

    @Test
    fun `should render context sections with progress bars`() {
        val state = TuiState(
            contextSections = listOf(
                TuiContextSection("Project Instructions", "project", 500, 2000),
                TuiContextSection("User Message", "user", 200, 1000),
                TuiContextSection("RAG Results", "rag", 1500, 3000),
                TuiContextSection("Conversation History", "conversation", 800, 2000),
                TuiContextSection("Tool Descriptions", "tools", 300, 1000)
            )
        )
        TuiContextView.render(terminal, state, 20)
    }

    @Test
    fun `should handle section at max capacity`() {
        val state = TuiState(
            contextSections = listOf(
                TuiContextSection("Full Section", "project", 3000, 3000)
            )
        )
        TuiContextView.render(terminal, state, 20)
    }

    @Test
    fun `should handle section with zero max`() {
        val state = TuiState(
            contextSections = listOf(
                TuiContextSection("Empty Section", "user", 0, 0)
            )
        )
        TuiContextView.render(terminal, state, 20)
    }

    @Test
    fun `should handle unknown category`() {
        val state = TuiState(
            contextSections = listOf(
                TuiContextSection("Unknown", "other", 100, 500)
            )
        )
        TuiContextView.render(terminal, state, 20)
    }
}
