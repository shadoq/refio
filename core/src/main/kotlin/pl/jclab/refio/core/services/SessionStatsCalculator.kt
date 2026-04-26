package pl.jclab.refio.core.services

import pl.jclab.refio.api.models.Message

/**
 * Aggregate stats computed from a session's message list.
 *
 * - durationMs: wall-clock from first to last message
 * - generationMs: sum of LLM latency + tool execution time across messages
 * - tokensIn / tokensOut / costUsd: sums from message metrics
 */
data class SessionStats(
    val durationMs: Long,
    val generationMs: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double
) {
    val isEmpty: Boolean get() =
        durationMs == 0L && generationMs == 0L && tokensIn == 0 && tokensOut == 0 && costUsd == 0.0

    companion object {
        val EMPTY = SessionStats(0L, 0L, 0, 0, 0.0)
    }
}

object SessionStatsCalculator {
    fun compute(messages: List<Message>): SessionStats {
        if (messages.isEmpty()) return SessionStats.EMPTY

        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var generationMs = 0L
        var tokensIn = 0
        var tokensOut = 0
        var costUsd = 0.0

        messages.forEach { msg ->
            if (msg.createdAt < first) first = msg.createdAt
            if (msg.createdAt > last) last = msg.createdAt

            val metrics = msg.metrics
            if (metrics != null) {
                generationMs += metrics.latencyMs.toLong() + metrics.toolExecutionTimeMs.toLong()
                tokensIn += metrics.inputTokens
                tokensOut += metrics.outputTokens
                costUsd += metrics.costUsd
            } else {
                tokensIn += msg.tokensIn ?: 0
                tokensOut += msg.tokensOut ?: 0
                costUsd += msg.costUsd ?: 0.0
            }
        }

        val duration = if (first == Long.MAX_VALUE) 0L else (last - first).coerceAtLeast(0L)
        return SessionStats(
            durationMs = duration,
            generationMs = generationMs,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            costUsd = costUsd
        )
    }
}
