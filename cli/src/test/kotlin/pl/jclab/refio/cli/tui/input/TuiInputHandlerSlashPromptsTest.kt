package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel

class TuiInputHandlerSlashPromptsTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val handler = TuiInputHandler(terminal)
    private val viewModel = mockk<TuiViewModel>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { viewModel.stateFlow } returns mockk {
            every { value } returns TuiState()
        }
        every { viewModel.getSlashPrompts() } returns SlashPrompt.BUILTINS
    }

    @Test
    fun `handleCommand should pass slash prompt explain to sendMessage`() {
        // Slash prompts (prompt templates) are expanded inline in sendMessage(),
        // not intercepted by handleCommand(). handleCommand always returns false.
        val result = handler.handleCommand("/explain some code here", viewModel)
        assert(!result) { "Slash prompts should not be intercepted by handleCommand" }
        verify(exactly = 0) { viewModel.sendMessage(any()) }
    }

    @Test
    fun `handleCommand should pass slash prompt fix to sendMessage`() {
        val result = handler.handleCommand("/fix this bug", viewModel)
        assert(!result) { "Slash prompts should not be intercepted by handleCommand" }
        verify(exactly = 0) { viewModel.sendMessage(any()) }
    }

    @Test
    fun `handleCommand should not handle unknown slash prompt`() {
        val result = handler.handleCommand("/nonexistent-prompt", viewModel)
        assert(!result) { "Should not handle unknown prompt" }
        verify(exactly = 0) { viewModel.sendMessage(any()) }
    }

    @Test
    fun `handleCommand always returns false - system commands removed`() {
        // All system commands have been removed — operations are accessed via GUI
        assert(!handler.handleCommand("/quit", viewModel))
        assert(!handler.handleCommand("/help", viewModel))
        assert(!handler.handleCommand("/clear", viewModel))
        assert(!handler.handleCommand("/resend", viewModel))
        assert(!handler.handleCommand("/export /tmp/chat.md", viewModel))
    }

    @Test
    fun `handleCommand should return false for non-command input`() {
        val result = handler.handleCommand("hello world", viewModel)
        assert(!result) { "Regular input should not be handled as command" }
    }
}
