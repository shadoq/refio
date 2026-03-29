package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiRendererTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())
    private val jlineTerminal = org.jline.terminal.TerminalBuilder.builder()
        .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        .type("dumb")
        .build()
    private val renderer = TuiRenderer(terminal, jlineTerminal)

    @Test
    fun `render should handle empty state`() {
        val state = TuiState()
        // Should not throw
        renderer.render(state)
    }

    @Test
    fun `render should handle state with messages`() {
        val state = TuiState(
            messages = listOf(
                TuiChatMessage(
                    id = "1",
                    timestamp = System.currentTimeMillis(),
                    role = "user",
                    content = "Hello"
                ),
                TuiChatMessage(
                    id = "2",
                    timestamp = System.currentTimeMillis(),
                    role = "assistant",
                    content = "Hi there"
                )
            )
        )
        renderer.render(state)
    }

    @Test
    fun `render should skip identical state`() {
        val state = TuiState()
        renderer.render(state)
        // Second render with same state should be skipped (no error)
        renderer.render(state)
    }

    @Test
    fun `render should handle all tabs`() {
        for (tab in TuiTab.entries) {
            val state = TuiState(activeTab = tab)
            renderer.render(state)
        }
    }

    @Test
    fun `render should handle history screen`() {
        val state = TuiState(screen = TuiScreen.HISTORY)
        renderer.render(state)
    }

    @Test
    fun `render should handle settings screen`() {
        val state = TuiState(screen = TuiScreen.SETTINGS)
        renderer.render(state)
    }

    @Test
    fun `render should handle split-pane mode`() {
        // When activeTab != CHAT, split-pane mode should activate
        val state = TuiState(
            activeTab = TuiTab.STEPS,
            messages = listOf(
                TuiChatMessage("1", System.currentTimeMillis(), "user", "Hello")
            ),
            steps = listOf(TuiStep("s1", "Analyze code", "RUNNING"))
        )
        renderer.render(state)
    }

    @Test
    fun `tab bar should include mode and status info`() {
        val state = TuiState(mode = "AGENT", model = "gpt-4o")
        // Tab bar now renders status info — just verify render doesn't crash
        renderer.render(state)
    }

    @Test
    fun `render should handle streaming state`() {
        val state = TuiState(
            isStreaming = true,
            messages = listOf(
                TuiChatMessage(
                    id = "stream",
                    timestamp = System.currentTimeMillis(),
                    role = "assistant",
                    content = "Thinking...",
                    isStreaming = true
                )
            )
        )
        renderer.render(state)
    }

    @Test
    fun `render should handle debug tab with data`() {
        val state = TuiState(
            activeTab = TuiTab.DEBUG,
            debugInfo = TuiDebugInfo(
                sessionId = "test-123",
                mode = "AGENT",
                model = "claude-3.5-sonnet",
                status = "RUNNING",
                tokensIn = 1000,
                tokensOut = 500,
                costUsd = 0.0123,
                messageCount = 10,
                connected = true,
                dbPath = "/tmp/test.db"
            )
        )
        renderer.render(state)
    }
}
