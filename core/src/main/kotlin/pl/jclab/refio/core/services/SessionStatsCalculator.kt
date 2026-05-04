package pl.jclab.refio.core.services

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session

/**
 * Aggregate stats computed from a session's message list.
 *
 * - durationMs: wall-clock from first to last message
 * - generationMs: sum of LLM latency + tool execution time across messages
 * - tokensIn / tokensOut / costUsd: prefer session-row totals (single source of truth from
 *   the task table — covers meta-LLM calls like auto-naming that don't create a chat
 *   message). Fall back to summing per-message metrics when no Session is provided.
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
    fun compute(messages: List<Message>, session: Session? = null): SessionStats {
        if (messages.isEmpty() && session == null) return SessionStats.EMPTY

        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var generationMs = 0L
        var msgTokensIn = 0
        var msgTokensOut = 0
        var msgCostUsd = 0.0

        messages.forEach { msg ->
            if (msg.createdAt < first) first = msg.createdAt
            if (msg.createdAt > last) last = msg.createdAt

            val metrics = msg.metrics
            if (metrics != null) {
                generationMs += metrics.latencyMs.toLong() + metrics.toolExecutionTimeMs.toLong()
                msgTokensIn += metrics.inputTokens
                msgTokensOut += metrics.outputTokens
                msgCostUsd += metrics.costUsd
            } else {
                msgTokensIn += msg.tokensIn ?: 0
                msgTokensOut += msg.tokensOut ?: 0
                msgCostUsd += msg.costUsd ?: 0.0
            }
        }

        // Prefer session-level totals so footer matches the header / history view.
        // Auto-naming and other meta-LLM calls inkrementują task.tokensIn/Out via
        // LLMClient centralization but never create a ChatMessage row, so the
        // per-message sum would otherwise undercount real cost.
        val tokensIn = session?.tokensIn?.takeIf { it > 0 } ?: msgTokensIn
        val tokensOut = session?.tokensOut?.takeIf { it > 0 } ?: msgTokensOut
        val costUsd = session?.costUsd?.takeIf { it > 0.0 } ?: msgCostUsd

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
