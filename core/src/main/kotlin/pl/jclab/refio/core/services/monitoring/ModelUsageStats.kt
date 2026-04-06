package pl.jclab.refio.core.services.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Global aggregated statistics for LLM model usage across all sessions.
 *
 * Fed by [pl.jclab.refio.core.services.AgentTurnLoop] after each successful LLM call.
 * Consumed by the Debug panel (Model Usage Analytics section) to help the user compare
 * cost/latency between providers and models and spot regressions in new model versions.
 *
 * Thread-safe singleton. Reset via [reset] (user-initiated only).
 */
object ModelUsageStats {

    data class Stats(
        val calls: AtomicLong = AtomicLong(0),
        val tokensIn: AtomicLong = AtomicLong(0),
        val tokensOut: AtomicLong = AtomicLong(0),
        // Store cost as tenths of a microdollar (×10_000_000) to avoid floating-point drift
        val costMicroTenths: AtomicLong = AtomicLong(0),
        val totalDurationMs: AtomicLong = AtomicLong(0),
        val maxDurationMs: AtomicLong = AtomicLong(0)
    )

    private val stats = ConcurrentHashMap<Key, Stats>()

    private data class Key(val provider: String, val model: String)

    /**
     * Record a completed LLM call. Called from AgentTurnLoop after each LLM response.
     */
    fun record(
        provider: String?,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
        durationMs: Long
    ) {
        val key = Key(provider ?: "unknown", model)
        val s = stats.getOrPut(key) { Stats() }
        s.calls.incrementAndGet()
        s.tokensIn.addAndGet(tokensIn.toLong())
        s.tokensOut.addAndGet(tokensOut.toLong())
        s.costMicroTenths.addAndGet((costUsd * 10_000_000).toLong())
        s.totalDurationMs.addAndGet(durationMs.coerceAtLeast(0))
        s.maxDurationMs.updateAndGet { maxOf(it, durationMs) }
    }

    /**
     * Snapshot of current stats ordered by cost (descending) — the expensive
     * models are what the user usually wants to see first.
     */
    fun snapshot(): List<Row> = stats.entries
        .map { (key, s) ->
            val calls = s.calls.get()
            val totalMs = s.totalDurationMs.get()
            Row(
                provider = key.provider,
                model = key.model,
                calls = calls,
                tokensIn = s.tokensIn.get(),
                tokensOut = s.tokensOut.get(),
                costUsd = s.costMicroTenths.get() / 10_000_000.0,
                avgDurationMs = if (calls > 0) totalMs / calls else 0L,
                maxDurationMs = s.maxDurationMs.get()
            )
        }
        .sortedByDescending { it.costUsd }

    fun reset() {
        stats.clear()
    }

    data class Row(
        val provider: String,
        val model: String,
        val calls: Long,
        val tokensIn: Long,
        val tokensOut: Long,
        val costUsd: Double,
        val avgDurationMs: Long,
        val maxDurationMs: Long
    )
}
