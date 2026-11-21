package pl.jclab.refio.cli.tui.state

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.input.SlashActions
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `/model` and friends run what their key runs; a prompt template of the same name still wins. */
class SlashActionsTest {

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder()))

    private fun send(text: String, templates: List<String> = emptyList()) {
        every { viewModel.stateFlow } returns MutableStateFlow(TuiState(inputBuffer = text))
        every { viewModel.slashTemplateNames() } returns templates
        handler.dispatchAction(TuiAction.SendMessage, viewModel)
    }

    @Test
    fun `action command runs the action and sends no prompt`() {
        send("/model")
        verify { viewModel.showModelSelector() }
        send("/new")
        verify { viewModel.showNewSessionDialog() }
        send("/effort")
        verify { viewModel.cycleReasoningEffort() }
        send("/summarize")
        verify { viewModel.summarizeConversation() }
        verify(exactly = 0) { viewModel.sendMessage(any()) }
    }

    @Test
    fun `continue sends what Ctrl+L sends`() {
        send("/continue")
        verify { viewModel.continueConversation() }
    }

    @Test
    fun `plan switches straight to PLAN instead of stepping through modes`() {
        send("/plan")
        verify { viewModel.switchMode("PLAN") }
        verify(exactly = 0) { viewModel.cycleMode() }
    }

    @Test
    fun `user template with the same name wins over the action`() {
        send("/model", templates = listOf("model"))
        verify(exactly = 0) { viewModel.showModelSelector() }
        verify { viewModel.sendMessage("/model") }
    }

    @Test
    fun `a command with words after it is a prompt, not an action`() {
        assertNull(SlashActions.find("/model please", emptyList()))
    }

    @Test
    fun `filtering puts names starting with the letters before names containing them`() {
        val names = listOf("/continue", "/context", "/test", "/security-review", "/new")
        assertEquals(listOf("/continue", "/context"), SlashActions.filter(names, "/con"))
        assertEquals(listOf("/test", "/context"), SlashActions.filter(names, "/te"))
        assertEquals(listOf("/security-review"), SlashActions.filter(names, "/rev"))
    }
}
