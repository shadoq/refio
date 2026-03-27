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

class TuiRagViewInteractiveTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(activeTab = TuiTab.RAG)
        }
    }

    @Test
    fun `r key should trigger ragReindex`() {
        handler.dispatchAction(TuiAction.TypeChar('r'), viewModel)
        verify { viewModel.ragReindex() }
    }

    @Test
    fun `e key should trigger ragGenerateEmbeddings`() {
        handler.dispatchAction(TuiAction.TypeChar('e'), viewModel)
        verify { viewModel.ragGenerateEmbeddings() }
    }

    @Test
    fun `renderToBuffer should show progress bar when indexing`() {
        val state = TuiState(ragIndexingProgress = 0.45, ragIndexingStatus = "Processing files...")
        val buf = TuiRagView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("45%"), "Should show progress percentage")
        assertTrue(output.contains("Processing files"), "Should show status message")
    }

    @Test
    fun `renderToBuffer should not show progress when not indexing`() {
        val state = TuiState(ragIndexingProgress = -1.0)
        val buf = TuiRagView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertFalse(output.contains("Progress"), "Should not show progress section")
    }

    @Test
    fun `renderToBuffer should show toolbar hints`() {
        val state = TuiState()
        val buf = TuiRagView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("[r] Reindex"), "Should show reindex hint")
        assertTrue(output.contains("[e] Embeddings"), "Should show embeddings hint")
    }
}
