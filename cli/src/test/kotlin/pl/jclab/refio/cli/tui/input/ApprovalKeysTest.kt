package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.state.TuiPendingApproval
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiToolApprovalRequest
import pl.jclab.refio.cli.tui.state.TuiViewModel
import pl.jclab.refio.core.services.turn.ProposedChange

/** The approval frame's keys: by letter, by ↑↓ and Enter, and never while the user is typing a reply. */
class ApprovalKeysTest {

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder())).also { it.layoutMode = { LayoutMode.Wide } }
    private val write = TuiToolApprovalRequest(
        "r1", "advance_code_editing", "Edit file", mapOf("path" to "src/A.kt"),
        ProposedChange("src/A.kt", "a\n", "b\n", "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-a\n+b\n"),
    )

    private fun given(state: TuiState) {
        every { viewModel.stateFlow } returns MutableStateFlow(state)
    }

    @Test
    fun `t behaves like a and allows the tool for the session`() {
        given(TuiState(pendingToolApproval = write))
        handler.dispatchAction(TuiAction.TypeChar('t'), viewModel)
        handler.dispatchAction(TuiAction.TypeChar('a'), viewModel)
        verify(exactly = 2) { viewModel.trustToolExecution("r1", "advance_code_editing") }
    }

    @Test
    fun `n rejects and leaves the prompt ready for what to change`() {
        given(TuiState(pendingToolApproval = write))
        handler.dispatchAction(TuiAction.TypeChar('n'), viewModel)
        verify { viewModel.rejectWithChangeRequest("r1") }
    }

    @Test
    fun `d opens the full diff and does not decide anything`() {
        given(TuiState(pendingToolApproval = write))
        handler.dispatchAction(TuiAction.TypeChar('d'), viewModel)
        verify { viewModel.openApprovalDiff() }
        verify(exactly = 0) { viewModel.approveToolExecution(any()) }
        verify(exactly = 0) { viewModel.rejectToolExecution(any()) }
    }

    @Test
    fun `enter runs the highlighted choice`() {
        given(TuiState(pendingToolApproval = write, approvalChoice = 1))
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
        verify { viewModel.trustToolExecution("r1", "advance_code_editing") }
    }

    @Test
    fun `arrows move the highlight instead of scrolling`() {
        given(TuiState(pendingToolApproval = write))
        handler.dispatchAction(TuiAction.ScrollDown, viewModel)
        verify { viewModel.approvalChoiceDown(4) }
        verify(exactly = 0) { viewModel.chatScrollDown() }
    }

    @Test
    fun `approval keys act only while approval is pending and the prompt is empty`() {
        given(TuiState(pendingToolApproval = write, inputBuffer = "you should"))
        handler.dispatchAction(TuiAction.TypeChar('y'), viewModel)
        verify(exactly = 0) { viewModel.approveToolExecution(any()) }

        given(TuiState())
        handler.dispatchAction(TuiAction.TypeChar('y'), viewModel)
        verify(exactly = 0) { viewModel.approveToolExecution(any()) }
        verify { viewModel.insertAtCursor('y') }
    }

    @Test
    fun `a focused panel keeps its own keys while approval waits`() {
        given(TuiState(pendingToolApproval = write, activeTab = TuiTab.STEPS, panelFocused = true))
        handler.dispatchAction(TuiAction.TypeChar('a'), viewModel)
        verify(exactly = 0) { viewModel.trustToolExecution(any(), any()) }
    }

    @Test
    fun `agent approval uses the same keys`() {
        given(TuiState(pendingApprovals = listOf(TuiPendingApproval("p1", "ag1", "coder", "write files", "medium"))))
        handler.dispatchAction(TuiAction.TypeChar('t'), viewModel)
        verify { viewModel.trustAgent("ag1") }
        verify { viewModel.approve("p1") }
    }

    @Test
    fun `esc never rejects, because unbound keys like Delete reach the app as Esc`() {
        given(TuiState(pendingToolApproval = write))
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify(exactly = 0) { viewModel.rejectWithChangeRequest(any()) }
        verify(exactly = 0) { viewModel.rejectToolExecution(any()) }
    }

    @Test
    fun `letters pasted faster than the screen refreshes go to the prompt, not to the approval`() {
        given(TuiState(pendingToolApproval = write))
        every { viewModel.liveInputBuffer() } returns "h"
        handler.dispatchAction(TuiAction.TypeChar('a'), viewModel)
        verify(exactly = 0) { viewModel.trustToolExecution(any(), any()) }
    }

    @Test
    fun `enter in the open model selector does not approve the tool`() {
        given(TuiState(pendingToolApproval = write, modelSelectorVisible = true))
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
        verify(exactly = 0) { viewModel.approveToolExecution(any()) }
        verify(exactly = 0) { viewModel.trustToolExecution(any(), any()) }
    }

    @Test
    fun `esc in the diff viewer closes it and does not reject`() {
        given(TuiState(pendingToolApproval = write, fileViewerVisible = true))
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.closeFileViewer() }
        verify(exactly = 0) { viewModel.rejectWithChangeRequest(any()) }
    }
}
