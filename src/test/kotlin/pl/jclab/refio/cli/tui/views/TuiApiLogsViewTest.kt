package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiApiLogsViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty api logs`() {
        val state = TuiState()
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render api logs table`() {
        val state = TuiState(
            apiLogs = listOf(
                TuiApiLogEntry("10:23:45", "anthropic", "claude-3.5", 1200, 340, 0.003),
                TuiApiLogEntry("10:23:48", "anthropic", "claude-3.5", 890, 210, 0.002),
                TuiApiLogEntry("10:24:01", "openai", "gpt-4o", 2000, 500, 0.01)
            )
        )
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render summary statistics`() {
        val state = TuiState(
            apiLogs = listOf(
                TuiApiLogEntry("10:23:45", "anthropic", "claude-3.5", 1000, 200, 0.005),
                TuiApiLogEntry("10:23:50", "anthropic", "claude-3.5", 500, 100, 0.002)
            )
        )
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should handle many log entries`() {
        val state = TuiState(
            apiLogs = (1..50).map {
                TuiApiLogEntry("10:${String.format("%02d", it)}:00", "openai", "gpt-4", 100L * it, 50L * it, 0.001 * it)
            }
        )
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render single log entry`() {
        val state = TuiState(
            apiLogs = listOf(
                TuiApiLogEntry("12:00:00", "ollama", "llama3", 500, 200, 0.0)
            )
        )
        TuiApiLogsView.render(terminal, state, 20)
    }
}
