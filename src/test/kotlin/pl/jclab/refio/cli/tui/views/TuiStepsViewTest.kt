package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiStepsViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty steps`() {
        val state = TuiState()
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render steps with various statuses`() {
        val state = TuiState(
            steps = listOf(
                TuiStep(id = "1", name = "Analyze code", status = "COMPLETED"),
                TuiStep(id = "2", name = "Write tests", status = "RUNNING"),
                TuiStep(id = "3", name = "Deploy", status = "PENDING"),
                TuiStep(id = "4", name = "Broken step", status = "FAILED")
            )
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render expanded step with details`() {
        val state = TuiState(
            steps = listOf(
                TuiStep(
                    id = "1", name = "Read files", status = "COMPLETED",
                    details = "Read 5 files, total 1200 lines", expanded = true
                )
            )
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should not show details for collapsed step`() {
        val state = TuiState(
            steps = listOf(
                TuiStep(
                    id = "1", name = "Read files", status = "COMPLETED",
                    details = "Some details", expanded = false
                )
            )
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render NEW status`() {
        val state = TuiState(
            steps = listOf(TuiStep(id = "1", name = "New task", status = "NEW"))
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render OK status`() {
        val state = TuiState(
            steps = listOf(TuiStep(id = "1", name = "Done task", status = "OK"))
        )
        TuiStepsView.render(terminal, state, 20)
    }
}
