package pl.jclab.refio.core.services.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Global aggregated statistics for tool usage across all sessions.
 *
 * Fed by [pl.jclab.refio.core.services.AgentTurnLoop] whenever a tool finishes executing.
 * Consumed by the Debug panel (Tool Usage Analytics section) to help identify flaky tools
 * and investigate where the agent spends its time.
 *
 * Thread-safe singleton. Reset via [reset] (user-initiated only).
 */
object ToolUsageStats {

    data class Stats(
        val count: AtomicLong = AtomicLong(0),
        val successCount: AtomicLong = AtomicLong(0),
        val totalDurationMs: AtomicLong = AtomicLong(0),
        val maxDurationMs: AtomicLong = AtomicLong(0),
        // Last error message stored for a quick glance at the Debug panel
        @Volatile var lastErrorMessage: String? = null
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    /**
     * Record a completed tool invocation.
     * Called from AgentTurnLoop after each ToolCalled event is emitted.
     */
    fun record(toolName: String, durationMs: Long, success: Boolean, errorMessage: String? = null) {
        val s = stats.getOrPut(toolName) { Stats() }
        s.count.incrementAndGet()
        if (success) s.successCount.incrementAndGet()
        s.totalDurationMs.addAndGet(durationMs.coerceAtLeast(0))
        s.maxDurationMs.updateAndGet { maxOf(it, durationMs) }
        if (!success && errorMessage != null) {
            s.lastErrorMessage = errorMessage.take(200)
        }
    }

    /**
     * Snapshot of current stats ordered by invocation count (descending).
     */
    fun snapshot(): List<Row> = stats.entries
        .map { (name, s) ->
            val count = s.count.get()
            val success = s.successCount.get()
            val total = s.totalDurationMs.get()
            Row(
                toolName = name,
                count = count,
                successRate = if (count > 0) success.toDouble() / count else 0.0,
                avgDurationMs = if (count > 0) total / count else 0L,
                maxDurationMs = s.maxDurationMs.get(),
                lastError = s.lastErrorMessage
            )
        }
        .sortedByDescending { it.count }

    fun reset() {
        stats.clear()
    }

    data class Row(
        val toolName: String,
        val count: Long,
        val successRate: Double,
        val avgDurationMs: Long,
        val maxDurationMs: Long,
        val lastError: String?
    )
}
