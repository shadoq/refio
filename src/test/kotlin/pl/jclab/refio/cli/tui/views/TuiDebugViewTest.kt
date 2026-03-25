package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiDebugViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render default debug info`() {
        val state = TuiState()
        TuiDebugView.render(terminal, state, 20)
    }

    @Test
    fun `should render connected state`() {
        val state = TuiState(
            debugInfo = TuiDebugInfo(
                sessionId = "abc-123",
                mode = "AGENT",
                model = "claude-3.5-sonnet",
                status = "RUNNING",
                connected = true,
                dbPath = "/project/.refio/database.sqlite"
            )
        )
        TuiDebugView.render(terminal, state, 20)
    }

    @Test
    fun `should render disconnected state`() {
        val state = TuiState(
            debugInfo = TuiDebugInfo(connected = false)
        )
        TuiDebugView.render(terminal, state, 20)
    }

    @Test
    fun `should render with token and cost data`() {
        val state = TuiState(
            debugInfo = TuiDebugInfo(
                tokensIn = 12400,
                tokensOut = 3100,
                costUsd = 0.0234,
                messageCount = 14
            )
        )
        TuiDebugView.render(terminal, state, 20)
    }
}
