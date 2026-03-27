package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.state.*

class TuiStepsViewInteractiveTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    private val sampleSubtasks = listOf(
        TuiSubtask(id = "s1", name = "Read files", status = "COMPLETED", tokensIn = 100, tokensOut = 50, costUsd = 0.001),
        TuiSubtask(id = "s2", name = "Edit code", status = "PENDING"),
        TuiSubtask(id = "s3", name = "Verify", status = "NEW")
    )

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(
                activeTab = TuiTab.STEPS,
                subtasks = sampleSubtasks,
                selectedStepIndex = 1
            )
        }
    }

    @Test
    fun `approve key should call approveSubtask for selected step`() {
        handler.dispatchAction(TuiAction.TypeChar('a'), viewModel)
        verify { viewModel.approveSubtask("s2") }
    }

    @Test
    fun `skip key should call skipSubtask for selected step`() {
        handler.dispatchAction(TuiAction.TypeChar('s'), viewModel)
        verify { viewModel.skipSubtask("s2") }
    }

    @Test
    fun `delete key should call deleteSubtask for selected step`() {
        handler.dispatchAction(TuiAction.TypeChar('d'), viewModel)
        verify { viewModel.deleteSubtask("s2") }
    }

    @Test
    fun `move up key should call moveStepUp`() {
        handler.dispatchAction(TuiAction.TypeChar('u'), viewModel)
        verify { viewModel.moveStepUp(1) }
    }

    @Test
    fun `move down key should call moveStepDown`() {
        handler.dispatchAction(TuiAction.TypeChar('j'), viewModel)
        verify { viewModel.moveStepDown(1) }
    }

    @Test
    fun `pause key should call togglePause`() {
        handler.dispatchAction(TuiAction.TypeChar('p'), viewModel)
        verify { viewModel.togglePause() }
    }

    @Test
    fun `cancel all key should call cancelAllPending`() {
        handler.dispatchAction(TuiAction.TypeChar('C'), viewModel)
        verify { viewModel.cancelAllPending() }
    }

    @Test
    fun `scroll up should call selectStepUp`() {
        handler.dispatchAction(TuiAction.ScrollUp, viewModel)
        verify { viewModel.selectStepUp() }
    }

    @Test
    fun `scroll down should call selectStepDown`() {
        handler.dispatchAction(TuiAction.ScrollDown, viewModel)
        verify { viewModel.selectStepDown() }
    }

    @Test
    fun `renderToBuffer should show subtasks with selection`() {
        val state = TuiState(subtasks = sampleSubtasks, selectedStepIndex = 1)
        val buf = TuiStepsView.renderToBuffer(state, 80, 20)
        val lines = buf.getLines().joinToString("\n")
        assertTrue(lines.contains("Read files"), "Should show first subtask")
        assertTrue(lines.contains("Edit code"), "Should show second subtask")
        assertTrue(lines.contains("Verify"), "Should show third subtask")
    }

    @Test
    fun `renderToBuffer should show metrics for completed steps`() {
        val state = TuiState(subtasks = sampleSubtasks)
        val buf = TuiStepsView.renderToBuffer(state, 80, 20)
        val lines = buf.getLines().joinToString("\n")
        assertTrue(lines.contains("Tokens:"), "Should show token metrics for completed step")
    }

    @Test
    fun `renderToBuffer should show toolbar hint`() {
        val state = TuiState(subtasks = sampleSubtasks)
        val buf = TuiStepsView.renderToBuffer(state, 80, 20)
        val lines = buf.getLines().joinToString("\n")
        assertTrue(lines.contains("[a]pprove"), "Should show toolbar hint")
    }
}
