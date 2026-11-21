package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiViewModel

/** Clearing the RAG index throws away work that takes long (and may cost money) to rebuild, so it asks first. */
class RagClearKeysTest {

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder())).also { it.layoutMode = { LayoutMode.Wide } }

    private fun given(state: TuiState) {
        every { viewModel.stateFlow } returns MutableStateFlow(state)
    }

    @Test
    fun `c in the focused RAG panel asks instead of clearing at once`() {
        given(TuiState(activeTab = TuiTab.RAG, panelFocused = true))
        handler.dispatchAction(TuiAction.TypeChar('c'), viewModel)
        verify { viewModel.ragClearPressed() }
        verify(exactly = 0) { viewModel.ragClearIndex() }
    }

    @Test
    fun `any other key after the question keeps the index`() {
        given(TuiState(activeTab = TuiTab.RAG, panelFocused = true, ragClearArmed = true))
        handler.dispatchAction(TuiAction.ScrollDown, viewModel)
        verify { viewModel.disarmRagClear() }
    }
}
