package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.state.ToolCallRow
import pl.jclab.refio.cli.tui.state.ToolRowStatus
import pl.jclab.refio.cli.tui.state.TranscriptUnit
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel

/** Keys act on the selected chat item only while something is selected; otherwise they type into the prompt. */
class ChatSelectionKeysTest {

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder())).also { it.layoutMode = { LayoutMode.Wide } }
    private val row = ToolCallRow("c1", "read_file", "a.kt", "line 1", ToolRowStatus.DONE, 300, null, 0, null)

    private fun given(state: TuiState, unit: TranscriptUnit?) {
        every { viewModel.stateFlow } returns MutableStateFlow(state)
        every { viewModel.selectedTranscriptUnit() } returns unit
    }

    @Test
    fun `space types a space in the prompt but expands the selected tool row`() {
        given(TuiState(), null)
        handler.dispatchAction(TuiAction.TypeChar(' '), viewModel)
        verify { viewModel.insertAtCursor(' ') }

        given(TuiState(selectedMessageIndex = 1), TranscriptUnit.Tool(row))
        handler.dispatchAction(TuiAction.TypeChar(' '), viewModel)
        verify { viewModel.toggleToolRowExpanded("c1") }
    }

    @Test
    fun `esc leaves the selection and returns to the prompt`() {
        given(TuiState(selectedMessageIndex = 0), TranscriptUnit.Tool(row))
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.clearMessageSelection() }
    }

    @Test
    fun `o opens the whole output of the selected tool row`() {
        given(TuiState(selectedMessageIndex = 0), TranscriptUnit.Tool(row))
        handler.dispatchAction(TuiAction.TypeChar('o'), viewModel)
        verify { viewModel.openToolOutput(row) }
    }

    @Test
    fun `v shows the whole selected prompt`() {
        val prompt = TuiChatMessage(id = "u", timestamp = 0, role = "user", content = "a\nb\nc")
        given(TuiState(selectedMessageIndex = 0), TranscriptUnit.Message(prompt))
        handler.dispatchAction(TuiAction.TypeChar('v'), viewModel)
        verify { viewModel.viewFullMessage(prompt) }
    }

    @Test
    fun `notice keys act only while the notice is last and the prompt is empty`() {
        val notice = TuiChatMessage(id = "a", timestamp = 0, role = "assistant", content = "x", metadata = mapOf("stop_reason" to "OUTPUT_TRUNCATED"))
        given(TuiState(messages = listOf(notice)), null)
        handler.dispatchAction(TuiAction.TypeChar('c'), viewModel)
        verify(exactly = 1) { viewModel.continueConversation() }

        given(TuiState(messages = listOf(notice), inputBuffer = "can", cursorPosition = 3), null)
        handler.dispatchAction(TuiAction.TypeChar('c'), viewModel)
        verify(exactly = 1) { viewModel.continueConversation() }
        verify { viewModel.insertAtCursor('c') }
    }
}
