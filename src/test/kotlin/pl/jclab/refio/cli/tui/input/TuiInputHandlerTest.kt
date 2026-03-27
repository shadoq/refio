package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiViewModel

class TuiInputHandlerTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState()
        }
    }

    @Test
    fun `dispatchAction SwitchTab should call setActiveTab`() {
        handler.dispatchAction(TuiAction.SwitchTab(TuiTab.STEPS), viewModel)
        verify { viewModel.setActiveTab(TuiTab.STEPS) }
    }

    @Test
    fun `dispatchAction SwitchScreen should call setScreen`() {
        handler.dispatchAction(TuiAction.SwitchScreen(TuiScreen.SETTINGS), viewModel)
        verify { viewModel.setScreen(TuiScreen.SETTINGS) }
    }

    @Test
    fun `dispatchAction BackToMain should set MAIN screen`() {
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.setScreen(TuiScreen.MAIN) }
    }

    @Test
    fun `dispatchAction TypeChar should insert at cursor`() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(inputBuffer = "hel")
        }
        handler.dispatchAction(TuiAction.TypeChar('l'), viewModel)
        verify { viewModel.insertAtCursor('l') }
    }

    @Test
    fun `dispatchAction Backspace should delete at cursor`() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(inputBuffer = "hello")
        }
        handler.dispatchAction(TuiAction.Backspace, viewModel)
        verify { viewModel.deleteAtCursor() }
    }

    @Test
    fun `dispatchAction Backspace on empty buffer should still call deleteAtCursor`() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(inputBuffer = "")
        }
        handler.dispatchAction(TuiAction.Backspace, viewModel)
        verify { viewModel.deleteAtCursor() }
    }

    @Test
    fun `dispatchAction CycleMode should call cycleMode`() {
        handler.dispatchAction(TuiAction.CycleMode, viewModel)
        verify { viewModel.cycleMode() }
    }

    @Test
    fun `dispatchAction Quit should call shutdown and stop`() {
        handler.dispatchAction(TuiAction.Quit, viewModel)
        verify { viewModel.shutdown() }
    }

    @Test
    fun `dispatchAction ScrollLeft should move cursor left`() {
        handler.dispatchAction(TuiAction.ScrollLeft, viewModel)
        verify { viewModel.moveCursorLeft() }
    }

    @Test
    fun `dispatchAction ScrollRight should move cursor right`() {
        handler.dispatchAction(TuiAction.ScrollRight, viewModel)
        verify { viewModel.moveCursorRight() }
    }

    @Test
    fun `dispatchAction ScrollUp on chat tab should scroll chat`() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(activeTab = TuiTab.CHAT, screen = TuiScreen.MAIN)
        }
        handler.dispatchAction(TuiAction.ScrollUp, viewModel)
        verify { viewModel.chatScrollUp() }
    }
}
