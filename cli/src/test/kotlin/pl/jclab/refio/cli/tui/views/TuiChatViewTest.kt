package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiChatViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty chat`() {
        val state = TuiState()
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render user message`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(id = "1", timestamp = 1000L, role = "user", content = "Hello")
            )
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render assistant message`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(id = "1", timestamp = 1000L, role = "assistant", content = "Hi there!")
            )
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render assistant message with markdown`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(
                    id = "1", timestamp = 1000L, role = "assistant",
                    content = "Here is code:\n```kotlin\nfun main() {}\n```"
                )
            )
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render streaming message`() {
        val state = TuiState(
            isStreaming = true,
            messages = listOf(
                TuiChatMessage(
                    id = "stream", timestamp = 1000L, role = "assistant",
                    content = "Thinking...", isStreaming = true
                )
            )
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render agent event messages`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(
                    id = "1", timestamp = 1000L, role = "agent_event",
                    content = "Agent started", agentName = "CodeReview",
                    agentColorIndex = 0, messageType = TuiMessageType.AGENT_STARTED
                )
            )
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should render multiple messages`() {
        val state = TuiState(
            messages = (1..10).map {
                TuiChatMessage(
                    id = "$it", timestamp = it * 1000L,
                    role = if (it % 2 == 0) "assistant" else "user",
                    content = "Message $it"
                )
            }
        )
        TuiChatView.render(terminal, state, 20)
    }

    @Test
    fun `should handle small content height`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(id = "1", timestamp = 1000L, role = "user", content = "Hello")
            )
        )
        TuiChatView.render(terminal, state, 5) // Very small
    }
}
