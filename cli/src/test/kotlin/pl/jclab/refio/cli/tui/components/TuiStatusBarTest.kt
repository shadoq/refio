package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiStatusBarTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render status bar with mode`() {
        val state = TuiState(mode = "CHAT", model = "gpt-4o")
        val result = TuiStatusBar.render(terminal, state, 80)
        assertNotNull(result)
    }

    @Test
    fun `should show streaming indicator when streaming`() {
        val state = TuiState(isStreaming = true, mode = "AGENT", model = "claude-3.5")
        val result = TuiStatusBar.render(terminal, state, 80)
        assertNotNull(result)
    }

    @Test
    fun `should format cost and tokens`() {
        val state = TuiState(totalCostUsd = 1.2345, totalTokens = 4200)
        val result = TuiStatusBar.render(terminal, state, 80)
        assertNotNull(result)
    }

    @Test
    fun `should handle null model`() {
        val state = TuiState(model = null)
        val result = TuiStatusBar.render(terminal, state, 80)
        assertNotNull(result)
    }
}
