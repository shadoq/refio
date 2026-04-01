package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.*

class TuiApiLogsViewTest {

    private val terminal = Terminal(terminalInterface = TerminalRecorder())

    private fun logEntry(
        time: String = "10:23:45",
        provider: String = "anthropic",
        model: String = "claude-3.5",
        tokIn: Long = 1200,
        tokOut: Long = 340,
        cost: Double = 0.003
    ) = TuiApiLogEntry(
        timestamp = time, provider = provider, model = model,
        tokensIn = tokIn, tokensOut = tokOut, costUsd = cost
    )

    @Test
    fun `should render empty api logs`() {
        val state = TuiState()
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render api logs table`() {
        val state = TuiState(
            apiLogs = listOf(
                logEntry("10:23:45", "anthropic", "claude-3.5", 1200, 340, 0.003),
                logEntry("10:23:48", "anthropic", "claude-3.5", 890, 210, 0.002),
                logEntry("10:24:01", "openai", "gpt-4o", 2000, 500, 0.01)
            )
        )
        TuiApiLogsView.render(terminal, state, 20)
    }

    @Test
    fun `should render detail view`() {
        val state = TuiState(
            apiLogs = listOf(
                TuiApiLogEntry(
                    id = "log-1", timestamp = "10:23:45", provider = "anthropic",
                    model = "claude-3.5-sonnet", tokensIn = 1200, tokensOut = 340,
                    costUsd = 0.003, latencyMs = 1234, httpStatus = 200,
                    source = "chat", endpoint = "https://api.anthropic.com/v1/messages",
                    requestPayload = "{\"model\": \"claude-3.5-sonnet\", \"messages\": []}",
                    responsePayload = "{\"content\": [{\"text\": \"Hello\"}]}"
                )
            ),
            selectedApiLogIndex = 0,
            apiLogDetailVisible = true
        )
        TuiApiLogsView.render(terminal, state, 30)
    }

    @Test
    fun `should render error log detail`() {
        val state = TuiState(
            apiLogs = listOf(
                TuiApiLogEntry(
                    timestamp = "10:23:45", provider = "openai", model = "gpt-4",
                    tokensIn = 0, tokensOut = 0, costUsd = 0.0, latencyMs = 5000,
                    httpStatus = 429, errorType = "RateLimitError",
                    errorMessage = "Too many requests"
                )
            ),
            selectedApiLogIndex = 0,
            apiLogDetailVisible = true
        )
        TuiApiLogsView.render(terminal, state, 30)
    }

    @Test
    fun `should handle many log entries`() {
        val state = TuiState(
            apiLogs = (1..50).map {
                logEntry("10:${String.format("%02d", it)}:00", "openai", "gpt-4", 100L * it, 50L * it, 0.001 * it)
            }
        )
        TuiApiLogsView.render(terminal, state, 20)
    }
}
