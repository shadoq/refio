package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel

class TuiModelSelectorTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    private val modelCandidates = listOf(
        "ollama/qwen2.5-coder:7b",
        "ollama/llama3.2:3b",
        "anthropic/claude-sonnet"
    )

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(
                modelSelectorVisible = true,
                modelSelectorCandidates = modelCandidates,
                modelSelectorIndex = 0
            )
        }
    }

    @Test
    fun `scroll down should call modelSelectorNext`() {
        handler.dispatchAction(TuiAction.ScrollDown, viewModel)
        verify { viewModel.modelSelectorNext() }
    }

    @Test
    fun `scroll up should call modelSelectorPrev`() {
        handler.dispatchAction(TuiAction.ScrollUp, viewModel)
        verify { viewModel.modelSelectorPrev() }
    }

    @Test
    fun `enter should call modelSelectorAccept`() {
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
        verify { viewModel.modelSelectorAccept() }
    }

    @Test
    fun `escape should call dismissModelSelector`() {
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.dismissModelSelector() }
    }

    @Test
    fun `SelectModel action should trigger showModelSelector`() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState()
        }
        handler.dispatchAction(TuiAction.SelectModel, viewModel)
        verify { viewModel.showModelSelector() }
    }
}
