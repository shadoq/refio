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

class TuiSettingsScreenInteractiveTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState(screen = TuiScreen.SETTINGS, settingsTab = 0)
        }
    }

    @Test
    fun `number keys should switch settings tabs`() {
        handler.dispatchAction(TuiAction.TypeChar('2'), viewModel)
        verify { viewModel.setSettingsTab(1) }
    }

    @Test
    fun `0 key should switch to tab 10`() {
        handler.dispatchAction(TuiAction.TypeChar('0'), viewModel)
        verify { viewModel.setSettingsTab(9) }
    }

    @Test
    fun `first R only arms the reset so a stray keypress cannot wipe all settings`() {
        every { viewModel.isSettingsResetArmed() } returns false
        handler.dispatchAction(TuiAction.TypeChar('R'), viewModel)
        verify { viewModel.armSettingsReset() }
        verify(exactly = 0) { viewModel.resetAllSettings() }
    }

    @Test
    fun `second R confirms and performs the reset`() {
        every { viewModel.isSettingsResetArmed() } returns true
        handler.dispatchAction(TuiAction.TypeChar('R'), viewModel)
        verify { viewModel.resetAllSettings() }
        verify { viewModel.addSystemMessage(match { it.contains("reset") }) }
    }

    @Test
    fun `lowercase r works the same as uppercase because the footer advertises lowercase`() {
        every { viewModel.isSettingsResetArmed() } returns false
        handler.dispatchAction(TuiAction.TypeChar('r'), viewModel)
        verify { viewModel.armSettingsReset() }
    }

    @Test
    fun `escape cancels an armed reset instead of leaving the screen`() {
        every { viewModel.isSettingsResetArmed() } returns true
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.disarmSettingsReset() }
        verify(exactly = 0) { viewModel.setScreen(TuiScreen.MAIN) }
    }

    @Test
    fun `unhandled characters are ignored and never leak into the chat input buffer`() {
        handler.dispatchAction(TuiAction.TypeChar('x'), viewModel)
        verify(exactly = 0) { viewModel.insertAtCursor(any()) }
    }

    @Test
    fun `escape should go back to main`() {
        handler.dispatchAction(TuiAction.BackToMain, viewModel)
        verify { viewModel.setScreen(TuiScreen.MAIN) }
    }

    @Test
    fun `renderToLines should show settings tabs`() {
        val state = TuiState(screen = TuiScreen.SETTINGS, settingsTab = 0)
        TuiSettingsScreen.setViewModel(viewModel)
        every { viewModel.getConfigSection(any()) } returns emptyMap()
        val lines = TuiSettingsScreen.renderToLines(state, 100, 20)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("Settings"), "Should show Settings header")
        assertTrue(output.contains("General"), "Should show General tab")
    }

    @Test
    fun `renderToLines should show interactive hints`() {
        val state = TuiState(screen = TuiScreen.SETTINGS)
        TuiSettingsScreen.setViewModel(viewModel)
        every { viewModel.getConfigSection(any()) } returns emptyMap()
        val lines = TuiSettingsScreen.renderToLines(state, 100, 20)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("[r]eset") || output.contains("Reset"),
            "Should show Reset hint, got: ${output.takeLast(200)}")
    }
}
