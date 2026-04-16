package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.state.*
import pl.jclab.refio.cli.tui.views.TuiStepsView

class TuiPlanApprovalTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    private val planSteps = listOf(
        subtaskFixture(id = "s1", description = "Read project", status = "NEW", kind = "read_file"),
        subtaskFixture(id = "s2", description = "Edit code", status = "NEW", kind = "code_editing"),
    )

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(
                pendingPlanApproval = TuiPlanApproval(taskId = "task1", steps = planSteps)
            )
        }
    }

    @Test
    fun `y key should approve plan`() {
        handler.dispatchAction(TuiAction.TypeChar('y'), viewModel)
        verify { viewModel.approvePlan() }
    }

    @Test
    fun `n key should reject plan`() {
        handler.dispatchAction(TuiAction.TypeChar('n'), viewModel)
        verify { viewModel.rejectPlan() }
    }

    @Test
    fun `Enter should approve plan`() {
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
        verify { viewModel.approvePlan() }
    }

    @Test
    fun `Escape should reject plan`() {
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.rejectPlan() }
    }

    @Test
    fun `other keys should be ignored during plan approval`() {
        handler.dispatchAction(TuiAction.TypeChar('x'), viewModel)
        verify(exactly = 0) { viewModel.approvePlan() }
        verify(exactly = 0) { viewModel.rejectPlan() }
    }

    @Test
    fun `renderToBuffer should show plan approval overlay`() {
        val state = TuiState(
            pendingPlanApproval = TuiPlanApproval(taskId = "task1", steps = planSteps)
        )
        val buf = TuiStepsView.renderToBuffer(state, 80, 20)
        val lines = buf.getLines().joinToString("\n")
        // The title uses ANSI color codes, so check raw text content
        assertTrue(lines.contains("Plan Approval") || lines.contains("plan approval"),
            "Should show plan approval title, got: ${lines.take(200)}")
        assertTrue(lines.contains("2 steps"), "Should show step count")
    }
}
