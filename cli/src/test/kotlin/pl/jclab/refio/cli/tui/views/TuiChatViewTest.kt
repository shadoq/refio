package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiChatViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val theme = Theme(ColorDepth.NONE)

    private fun lines(state: TuiState, width: Int = 90, height: Int = 40, compact: Boolean = false): List<String> =
        TuiChatView.renderMessages(terminal, state, width, height, theme, Glyphs.UNICODE, compact).getLines().map(TuiRenderBuffer::stripAnsi)

    private fun toolMessage(callId: String, name: String, args: String, result: String?, success: Boolean? = true, streamed: String? = null): TuiChatMessage {
        val call = TranscriptBuilder.toolCallMessage("temp-$callId", callId, name, args, 1_000, running = result == null)
        return when {
            streamed != null -> call.copy(content = streamed, isStreaming = true, isToolStreaming = true)
            result == null -> call
            else -> call.copy(content = result, isStreaming = false, metadata = call.metadata + (TranscriptBuilder.META_SUCCESS to success))
        }
    }

    @Test
    fun `tool calls of one turn share one TOOLS frame with one row each`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(id = "u", timestamp = 0, role = "user", content = "look"),
                toolMessage("c1", "read_file", """{"path":"src/App.kt"}""", "lines 1-40 of 296"),
                toolMessage("c2", "grep_search", """{"query":"TODO"}""", "3 matches"),
            )
        )
        val out = lines(state)
        assertEquals(1, out.count { it.contains("TOOLS") }, out.joinToString("\n"))
        assertEquals(1, out.count { it.contains("read_file") && it.contains("src/App.kt") && it.contains("lines 1-40 of 296") })
        assertEquals(1, out.count { it.contains("grep_search") })
        assertFalse(out.any { it.contains("[tool]") }, "no duplicate result block")
    }

    @Test
    fun `streaming tool shows a live character count and hides the growing body`() {
        val state = TuiState(messages = listOf(toolMessage("c1", "advance_code_editing", """{"path":"a.kt"}""", null, streamed = "abcdef")))
        val out = lines(state)
        assertTrue(out.any { it.contains("6 chars") }, out.joinToString("\n"))
        assertFalse(out.any { it.contains("abcdef") })
    }

    @Test
    fun `expanded tool row shows at most five output lines under the gutter`() {
        val output = (1..8).joinToString("\n") { "line $it" }
        val state = TuiState(messages = listOf(toolMessage("c1", "read_file", """{"path":"a.kt"}""", output)), expandedToolRows = setOf("c1"))
        val out = lines(state)
        assertTrue(out.any { it.contains("⎿ line 1") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("line 5") })
        assertFalse(out.any { it.contains("line 6") })
        assertTrue(out.any { it.contains("3 more lines") && it.contains("o open") })
    }

    @Test
    fun `finished tool group folds to one line in the narrow layout`() {
        val state = TuiState(messages = listOf(toolMessage("c1", "read_file", """{"path":"a.kt"}""", "ok"), toolMessage("c2", "list_dir", "{}", "2 dirs")))
        val out = lines(state, compact = true)
        assertTrue(out.any { it.contains("▸ 2 tools") }, out.joinToString("\n"))
        assertFalse(out.any { it.contains("TOOLS") })
    }

    @Test
    fun `long prompt is clipped to two lines with a way to see it all`() {
        val prompt = (1..6).joinToString("\n") { "requirement $it" }
        val out = lines(TuiState(messages = listOf(TuiChatMessage(id = "u", timestamp = 0, role = "user", content = prompt))))
        assertTrue(out.any { it.startsWith("YOU") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("requirement 2") })
        assertFalse(out.any { it.contains("requirement 3") })
        assertTrue(out.any { it.contains("4 more lines") && it.contains("v view all") })
    }

    @Test
    fun `truncated answer shows the output limit notice instead of the generic advice`() {
        val truncated = TuiChatMessage(
            id = "a", timestamp = 0, role = "assistant",
            content = "The agent's response was truncated due to output length limits.\n\n**Solutions:**\n1. Ask for a smaller implementation",
            metadata = mapOf("stop_reason" to "OUTPUT_TRUNCATED")
        )
        val out = lines(TuiState(messages = listOf(truncated), model = "openai/gpt-5", maxOutputTokens = 16384))
        assertTrue(out.any { it.contains("OUTPUT LIMIT") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("[c] continue") && it.contains("[l] raise limit to 32768") })
        assertFalse(out.any { it.contains("Solutions") })
    }

    @Test
    fun `raising the output limit is not offered when the context window was the cap`() {
        val truncated = TuiChatMessage(id = "a", timestamp = 0, role = "assistant", content = "x", metadata = mapOf("stop_reason" to "OUTPUT_TRUNCATED"))
        val state = TuiState(messages = listOf(truncated), model = "ollama/qwen3:8b", contextMaxTokens = 32768, contextUsedTokens = 30000, maxOutputTokens = 16384)
        val out = lines(state)
        assertTrue(out.any { it.contains("context window") }, out.joinToString("\n"))
        assertFalse(out.any { it.contains("[l]") })
    }

    @Test
    fun `selection scrolled out of view is brought back on screen`() {
        assertEquals(0 until 10, TuiChatView.visibleWindow(total = 100, height = 10, scrollOffset = 0, selectedLine = 0))
        assertEquals(90 until 100, TuiChatView.visibleWindow(total = 100, height = 10, scrollOffset = 0, selectedLine = -1))
    }

    @Test
    fun `empty session shows the model and how to start`() {
        val out = lines(TuiState(model = "ollama/qwen3:8b"))
        assertTrue(out.first().contains("Welcome to Refio"))
        assertTrue(out.any { it.contains("ollama/qwen3:8b") })
    }
}
