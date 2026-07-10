package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Outcome of the deterministic post-turn verification step (project build/test run by the loop
 * code, not the model). Carried in the turn result and exported to `run.json` as
 * `metrics.verification = {ran, attempts, result}` so the e2e harness can assert on it.
 */
data class VerificationSummary(
    /** True when a verification command actually executed at least once this turn. */
    val ran: Boolean,
    /** Number of verification command executions (initial run + re-runs after repair rounds). */
    val attempts: Int,
    /** "PASSED" or "FAILED" when [ran] is true; null when verification never ran. */
    val result: String?,
) {
    companion object {
        val NOT_RUN = VerificationSummary(ran = false, attempts = 0, result = null)
        const val RESULT_PASSED = "PASSED"
        const val RESULT_FAILED = "FAILED"
    }
}

/**
 * Records the per-task verification summary so it survives from the turn loop to
 * [SessionDebugExporter] (`run.json`). Thread-safe process-global singleton keyed by `taskId`,
 * mirroring [TurnFailureMarkerTracker]. Last write wins: the summary of the final verification
 * state of the turn is the one that matters.
 */
object TurnVerificationTracker {

    private val summaries = ConcurrentHashMap<String, VerificationSummary>()

    fun record(taskId: String, summary: VerificationSummary) {
        summaries[taskId] = summary
    }

    /** The verification summary for [taskId]; [VerificationSummary.NOT_RUN] when none recorded. */
    fun summaryFor(taskId: String): VerificationSummary = summaries[taskId] ?: VerificationSummary.NOT_RUN

    /** Test-only: forget all recorded summaries. */
    fun reset() {
        summaries.clear()
    }
}
