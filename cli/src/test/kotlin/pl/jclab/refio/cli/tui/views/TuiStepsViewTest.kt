package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.subtaskFixture

class TuiStepsViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty steps`() {
        val state = TuiState()
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render subtasks with various statuses`() {
        val state = TuiState(
            subtasks = listOf(
                subtaskFixture(id = "1", description = "Analyze code", status = "COMPLETED"),
                subtaskFixture(id = "2", description = "Write tests", status = "RUNNING"),
                subtaskFixture(id = "3", description = "Deploy", status = "PENDING"),
                subtaskFixture(id = "4", description = "Broken step", status = "FAILED"),
            )
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render expanded subtask with details`() {
        val state = TuiState(
            subtasks = listOf(
                subtaskFixture(
                    id = "1", description = "Read files", status = "COMPLETED",
                    resultSummary = "Read 5 files, total 1200 lines",
                    tokensIn = 100, tokensOut = 50,
                ),
            ),
            selectedStepIndex = 0,
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render NEW status`() {
        val state = TuiState(
            subtasks = listOf(subtaskFixture(id = "1", description = "New task", status = "NEW"))
        )
        TuiStepsView.render(terminal, state, 20)
    }

    @Test
    fun `should render SKIPPED status`() {
        val state = TuiState(
            subtasks = listOf(subtaskFixture(id = "1", description = "Skipped task", status = "SKIPPED"))
        )
        TuiStepsView.render(terminal, state, 20)
    }
}
