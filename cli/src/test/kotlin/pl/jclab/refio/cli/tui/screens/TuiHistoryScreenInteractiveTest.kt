package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.state.*

class TuiHistoryScreenInteractiveTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    private val sessions = listOf(
        taskResponseFixture(id = "id1", name = "Session 1", mode = "CHAT", tokensIn = 100, tokensOut = 50, costUsd = 0.01, createdAt = 1000L, updatedAt = 2000L),
        taskResponseFixture(id = "id2", name = "Session 2", mode = "AGENT", status = "RUNNING", tokensIn = 200, tokensOut = 100, costUsd = 0.02, createdAt = 1100L, updatedAt = 2100L, pinned = true),
        taskResponseFixture(id = "id3", name = "Session 3", mode = "PLAN", tokensIn = 50, tokensOut = 25, costUsd = 0.005, createdAt = 900L, updatedAt = 1900L),
    )

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(
                screen = TuiScreen.HISTORY,
                sessions = sessions,
                selectedHistoryIndex = 0,
                activeSessionId = "id1"
            )
        }
    }

    @Test
    fun `scroll down should navigate to next session`() {
        handler.dispatchAction(TuiAction.ScrollDown, viewModel)
        verify { viewModel.selectHistoryDown() }
    }

    @Test
    fun `scroll up should navigate to previous session`() {
        handler.dispatchAction(TuiAction.ScrollUp, viewModel)
        verify { viewModel.selectHistoryUp() }
    }

    @Test
    fun `enter should load selected session`() {
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
        verify { viewModel.loadSelectedSession() }
    }

    @Test
    fun `p key should toggle pin`() {
        handler.dispatchAction(TuiAction.TypeChar('p'), viewModel)
        verify { viewModel.togglePinSession() }
    }

    @Test
    fun `d key should delete session`() {
        handler.dispatchAction(TuiAction.TypeChar('d'), viewModel)
        verify { viewModel.deleteSelectedSession() }
    }

    @Test
    fun `c key should filter to CHAT`() {
        handler.dispatchAction(TuiAction.TypeChar('c'), viewModel)
        verify { viewModel.setHistoryFilter("CHAT") }
    }

    @Test
    fun `a key should filter to AGENT`() {
        handler.dispatchAction(TuiAction.TypeChar('a'), viewModel)
        verify { viewModel.setHistoryFilter("AGENT") }
    }

    @Test
    fun `escape should go back to main`() {
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.setScreen(TuiScreen.MAIN) }
    }

    @Test
    fun `renderToLines should show sessions`() {
        val state = TuiState(sessions = sessions, selectedHistoryIndex = 1, activeSessionId = "id1")
        val lines = TuiHistoryScreen.renderToLines(state, 100, 20)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("Session 1"), "Should show session 1")
        assertTrue(output.contains("Session 2"), "Should show session 2")
    }

    @Test
    fun `renderToLines should show filter label`() {
        val state = TuiState(sessions = sessions, historyFilter = "CHAT")
        val lines = TuiHistoryScreen.renderToLines(state, 100, 20)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("CHAT"), "Should show filter label")
    }

    @Test
    fun `renderToLines should show navigation hints`() {
        val state = TuiState(sessions = sessions)
        val lines = TuiHistoryScreen.renderToLines(state, 100, 20)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("Navigate"), "Should show navigation hints")
        assertTrue(output.contains("Load"), "Should show load hint")
    }
}
