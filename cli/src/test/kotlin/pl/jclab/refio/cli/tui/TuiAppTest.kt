package pl.jclab.refio.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderer
import pl.jclab.refio.cli.tui.state.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TuiAppTest {

    @Test
    fun `TuiScreenManager should render history overlay`() {
        val terminal = Terminal(terminalInterface = TerminalRecorder())
        val state = TuiState(screen = TuiScreen.HISTORY)
        val layout = pl.jclab.refio.cli.tui.rendering.TuiLayoutRegions.fromTerminal(80, 24)
        TuiScreenManager.renderOverlay(terminal, state, layout)
    }

    @Test
    fun `TuiScreenManager should render settings overlay`() {
        val terminal = Terminal(terminalInterface = TerminalRecorder())
        val state = TuiState(screen = TuiScreen.SETTINGS)
        val layout = pl.jclab.refio.cli.tui.rendering.TuiLayoutRegions.fromTerminal(80, 24)
        TuiScreenManager.renderOverlay(terminal, state, layout)
    }

    @Test
    fun `TuiTabBar should render all tabs`() {
        val terminal = Terminal(terminalInterface = TerminalRecorder())
        for (tab in TuiTab.entries) {
            TuiTabBar.render(terminal, tab)
        }
    }

    private fun createTestJlineTerminal(): org.jline.terminal.Terminal =
        org.jline.terminal.TerminalBuilder.builder()
            .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            .type("dumb")
            .build()

    @Test
    fun `TuiRenderer should handle full state render`() {
        val terminal = Terminal(terminalInterface = TerminalRecorder())
        val renderer = TuiRenderer(terminal, createTestJlineTerminal())
        val state = TuiState(
            mode = "AGENT",
            model = "gpt-4o",
            messages = listOf(
                TuiChatMessage("1", 1000L, "user", "Hello"),
                TuiChatMessage("2", 2000L, "assistant", "Hi!")
            ),
            totalCostUsd = 0.05,
            totalTokens = 1500
        )
        renderer.render(state)
    }

    @Test
    fun `TuiRenderer should handle split-pane mode`() {
        val terminal = Terminal(terminalInterface = TerminalRecorder())
        val renderer = TuiRenderer(terminal, createTestJlineTerminal())
        val state = TuiState(
            activeTab = TuiTab.STEPS,
            mode = "PLAN",
            messages = listOf(
                TuiChatMessage("1", 1000L, "user", "Hello"),
                TuiChatMessage("2", 2000L, "assistant", "Plan created")
            ),
            steps = listOf(
                TuiStep("s1", "Analyze code", "RUNNING"),
                TuiStep("s2", "Write tests", "PENDING")
            )
        )
        renderer.render(state)
    }
}
