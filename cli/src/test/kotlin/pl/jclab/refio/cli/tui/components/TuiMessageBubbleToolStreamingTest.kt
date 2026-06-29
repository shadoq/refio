package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiMessageType
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI tool bubble must give the same "tokens are arriving" signal the plugin does: while a
 * code-editing tool streams its generated content, show a live character counter and keep the
 * growing body hidden (it would otherwise re-wrap noisily on every chunk). Once the tool finishes,
 * the result body is shown again and the counter is gone.
 */
class TuiMessageBubbleToolStreamingTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `streaming tool shows live char counter and hides the growing body`() {
        val message = TuiChatMessage(
            id = "t1",
            timestamp = 1000L,
            role = "tool",
            content = "abcdef",
            isStreaming = true,
            isToolStreaming = true,
            messageType = TuiMessageType.TOOL_CALL,
            toolName = "advance_code_editing",
        )

        val lines = TuiMessageBubble.renderToLines(terminal, message)

        assertTrue(lines.any { it.contains("6 chars") }, "expected a live character counter in: $lines")
        assertFalse(lines.any { it.contains("abcdef") }, "streamed body must stay hidden in: $lines")
    }

    @Test
    fun `finished tool shows the result body and no counter`() {
        val message = TuiChatMessage(
            id = "t1",
            timestamp = 1000L,
            role = "tool",
            content = "done: file written",
            isStreaming = false,
            isToolStreaming = false,
            messageType = TuiMessageType.TOOL_CALL,
            toolName = "advance_code_editing",
            metadata = mapOf("success" to true),
        )

        val lines = TuiMessageBubble.renderToLines(terminal, message)

        assertTrue(lines.any { it.contains("done: file written") }, "expected the result body in: $lines")
        assertFalse(lines.any { it.contains("chars") }, "a finished tool must not show a counter in: $lines")
    }
}
