package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.jclab.refio.core.logging.LogSink
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LogSink implementation for TUI.
 * Captures log messages from DualLogger and pushes them into the TUI state
 * for display in the Logs tab.
 *
 * When a [scope] is provided, entries are buffered and flushed to [logsState]
 * at most once per [flushIntervalMs]. This keeps a chatty core (e.g. during
 * streaming, where every log line would otherwise trigger a re-render) from
 * flooding the render pipeline. Without a scope, entries are emitted
 * immediately (synchronous fallback, used by tests).
 *
 * Thread-safe: the pending buffer is guarded by its own lock and state
 * updates go through MutableStateFlow.update which is atomic.
 */
class TuiLogSink(
    private val logsState: MutableStateFlow<List<TuiLogEntry>>,
    private val maxEntries: Int = 500,
    private val scope: CoroutineScope? = null,
    private val flushIntervalMs: Long = 250
) : LogSink {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val pending = ArrayDeque<TuiLogEntry>()
    private val flushScheduled = AtomicBoolean(false)

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
        val flushScope = scope
        if (flushScope == null) {
            emitBatch(listOf(entry))
            return
        }
        synchronized(pending) {
            pending.addLast(entry)
            // Cap the buffer too: if nobody flushes (scope busy), don't grow unbounded.
            while (pending.size > maxEntries) {
                pending.removeFirst()
            }
        }
        if (flushScheduled.compareAndSet(false, true)) {
            flushScope.launch {
                delay(flushIntervalMs)
                flushScheduled.set(false)
                flushPending()
            }
        }
    }

    /** Drain the pending buffer into the state flow as a single update. */
    fun flushPending() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        emitBatch(batch)
    }

    private fun emitBatch(batch: List<TuiLogEntry>) {
        logsState.update { entries ->
            val updated = entries + batch
            if (updated.size > maxEntries) updated.takeLast(maxEntries) else updated
        }
    }
}
