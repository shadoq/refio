package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pl.jclab.refio.core.logging.LogSink
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * LogSink implementation for TUI.
 * Captures log messages from DualLogger and pushes them into the TUI state
 * for display in the Logs tab.
 *
 * Thread-safe: all updates go through MutableStateFlow.update which is atomic.
 */
class TuiLogSink(
    private val logsState: MutableStateFlow<List<TuiLogEntry>>,
    private val maxEntries: Int = 500
) : LogSink {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun debug(component: String, message: String) {
        addEntry("DEBUG", "[$component] $message")
    }

    override fun info(component: String, message: String) {
        addEntry("INFO", "[$component] $message")
    }

    override fun warn(component: String, message: String) {
        addEntry("WARN", "[$component] $message")
    }

    override fun error(component: String, message: String, throwable: Throwable?) {
        val msg = if (throwable != null) {
            "[$component] $message: ${throwable.message}"
        } else {
            "[$component] $message"
        }
        addEntry("ERROR", msg)
    }

    private fun addEntry(level: String, message: String) {
        val timestamp = LocalTime.now().format(timeFormatter)
        val entry = TuiLogEntry(timestamp = timestamp, level = level, message = message)
        logsState.update { entries ->
            val updated = entries + entry
            if (updated.size > maxEntries) updated.takeLast(maxEntries) else updated
        }
    }
}
