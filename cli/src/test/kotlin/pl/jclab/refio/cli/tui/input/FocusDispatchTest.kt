package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.state.TranscriptUnit
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiViewModel

/** Keys must reach the pane the user sees as focused, never one the layout has hidden. */
class FocusDispatchTest {

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder()))

    private fun given(state: TuiState, mode: LayoutMode) {
        every { viewModel.stateFlow } returns MutableStateFlow(state)
        handler.layoutMode = { mode }
    }

    @Test
    fun `tab never focuses a pane hidden by the narrow layout`() {
        given(TuiState(activeTab = TuiTab.CHAT), LayoutMode.Narrow)
        handler.dispatchAction(TuiAction.TogglePanelFocus, viewModel)
        verify(exactly = 0) { viewModel.togglePanelFocus() }
    }

    @Test
    fun `tab moves focus to a panel shown beside the prompt`() {
        given(TuiState(activeTab = TuiTab.STEPS), LayoutMode.Medium)
        handler.dispatchAction(TuiAction.TogglePanelFocus, viewModel)
        verify(exactly = 1) { viewModel.togglePanelFocus() }
    }

    @Test
    fun `tab without an open panel has nothing to focus`() {
        given(TuiState(activeTab = TuiTab.CHAT), LayoutMode.Wide)
        handler.dispatchAction(TuiAction.TogglePanelFocus, viewModel)
        verify(exactly = 0) { viewModel.togglePanelFocus() }
    }

    @Test
    fun `letters go to the prompt while it has focus`() {
        given(TuiState(activeTab = TuiTab.CHAT, inputBuffer = "ab", cursorPosition = 2), LayoutMode.Wide)
        handler.dispatchAction(TuiAction.TypeChar('c'), viewModel)
        verify { viewModel.insertAtCursor('c') }
    }

    @Test
    fun `esc closes the panel beside the chat, and the right column with it`() {
        given(TuiState(activeTab = TuiTab.STEPS, panelFocused = true), LayoutMode.Wide)
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.setActiveTab(TuiTab.CHAT) }
    }

    @Test
    fun `esc with a message selected in the chat clears the selection and keeps the panel open`() {
        given(TuiState(activeTab = TuiTab.STEPS, selectedMessageIndex = 0), LayoutMode.Wide)
        every { viewModel.selectedTranscriptUnit() } returns TranscriptUnit.Message(TuiChatMessage(id = "u", timestamp = 0, role = "user", content = "hi"))
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.clearMessageSelection() }
        verify(exactly = 0) { viewModel.setActiveTab(any()) }
    }

    @Test
    fun `esc closes a panel that fills the narrow screen`() {
        given(TuiState(activeTab = TuiTab.LOGS), LayoutMode.Narrow)
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.setActiveTab(TuiTab.CHAT) }
    }
}
