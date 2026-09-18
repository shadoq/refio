package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * How much steering the turn needed: nudges sent, guardian re-entries, the worst repetition streak,
 * the tool-error rate the guardrail itself measured, and writes that changed nothing.
 *
 * Every one of these numbers was already being computed inside the loop and then dropped - the
 * repetition and error stats existed only to build the text of an abort message, and the nudge
 * counters were local variables. Collected here they answer the one question no external agent can:
 * how much of a finished run is the model's doing and how much is the harness pushing it along.
 */
data class GuardrailStats(
    val consolidationNudges: Int = 0,
    val regenerationNudges: Int = 0,
    val subagentInvokeNudges: Int = 0,
    val formatRetryNudges: Int = 0,
    val guardianReentries: Int = 0,
    /** Longest run of identical tool calls the repetition tracker saw. */
    val maxRepeatedCall: Int = 0,
    /** Tool-error rate in the guardrail's own window, as the guardrail measured it. */
    val toolErrorRate: Double = 0.0,
    val noopWrites: Int = 0,
) {
    companion object {
        val NONE = GuardrailStats()
    }
}

/**
 * Per-task guardrail statistics on their way to `run.json.metrics.guardrails`. Thread-safe
 * process-global singleton keyed by `taskId`, mirroring [TurnVerificationTracker].
 *
 * Last write wins: the loop records once at the end of the turn, with the totals it accumulated.
 */
object TurnGuardrailStatsTracker {

    private val stats = ConcurrentHashMap<String, GuardrailStats>()

    fun record(taskId: String, summary: GuardrailStats) {
        stats[taskId] = summary
    }

    /** The guardrail stats for [taskId]; [GuardrailStats.NONE] when none recorded. */
    fun statsFor(taskId: String): GuardrailStats = stats[taskId] ?: GuardrailStats.NONE

    /** Test-only: forget all recorded stats. */
    fun reset() {
        stats.clear()
    }
}
