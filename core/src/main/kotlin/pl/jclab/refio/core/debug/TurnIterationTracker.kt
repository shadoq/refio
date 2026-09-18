package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * How many times the turn loop went round, and the ceiling it was allowed.
 *
 * Kept apart from the subtask row count on purpose: a turn can open several subtask rows in one
 * iteration (a parallel read batch) or none at all (a text-only iteration), so the two numbers
 * answer different questions. This one answers "how much did the loop have to work".
 */
data class IterationSummary(
    /** Loop iterations actually taken. */
    val used: Int,
    /** The ceiling the loop was configured with, so a run that hit it is visible as such. */
    val limit: Int,
) {
    /** True when the turn used up its whole budget - the shape of a run that gave up, not finished. */
    val exhausted: Boolean get() = limit > 0 && used >= limit

    companion object {
        val NONE = IterationSummary(used = 0, limit = 0)
    }
}

/**
 * Records the per-task iteration summary so it survives from the turn loop to
 * [SessionDebugExporter] (`run.json`). Thread-safe process-global singleton keyed by `taskId`,
 * mirroring [TurnVerificationTracker].
 *
 * Last write wins: a turn re-entered by a completion guardian reports the count it finally reached,
 * not the count at the first exit.
 */
object TurnIterationTracker {

    private val summaries = ConcurrentHashMap<String, IterationSummary>()

    fun record(taskId: String, summary: IterationSummary) {
        summaries[taskId] = summary
    }

    /** The iteration summary for [taskId]; [IterationSummary.NONE] when none recorded. */
    fun summaryFor(taskId: String): IterationSummary = summaries[taskId] ?: IterationSummary.NONE

    /** Test-only: forget all recorded summaries. */
    fun reset() {
        summaries.clear()
    }
}
