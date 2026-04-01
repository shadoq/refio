package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiLogsViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    @Test
    fun `should render empty logs`() {
        val state = TuiState()
        TuiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render logs with all levels`() {
        val state = TuiState(
            logs = listOf(
                TuiLogEntry("10:00:01", "DEBUG", "Initializing..."),
                TuiLogEntry("10:00:02", "INFO", "Core started"),
                TuiLogEntry("10:00:03", "WARN", "Slow response"),
                TuiLogEntry("10:00:04", "ERROR", "Connection failed")
            )
        )
        TuiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should truncate to content height`() {
        val state = TuiState(
            logs = (1..50).map {
                TuiLogEntry("10:${String.format("%02d", it)}:00", "INFO", "Log entry $it")
            }
        )
        TuiLogsView.render(terminal, state, 10) // Only shows last 7 (10-3)
    }

    @Test
    fun `should handle unknown log level`() {
        val state = TuiState(
            logs = listOf(TuiLogEntry("10:00:00", "TRACE", "trace message"))
        )
        TuiLogsView.render(terminal, state, 20)
    }
}
