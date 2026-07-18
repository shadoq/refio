package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Records the specific guardrail that aborted a task's turn, so a failing run can be classified by
 * *why* it stopped rather than only by the coarse `session.status`.
 *
 * The e2e harness otherwise buckets every INCOMPLETE turn as "loop" and every FAILED
 * turn as "agent-fail", which hides the difference between a byte-identical repetition loop and a
 * stalled no-op writer - a distinction the stabilization gate's failure-mode breakdown (and the
 * self-improve diagnosis) needs. The turn loop calls [record] at the abort site; the marker survives
 * to [pl.jclab.refio.core.debug.SessionDebugExporter], which reads it into
 * `run.json.metrics.failureMarker` for `classify_failure_mode` to map.
 *
 * First marker wins: the first guardrail to fire is the cause; any later abort is a consequence.
 * Thread-safe process-global singleton keyed by `taskId`, mirroring [ContextOverflowTracker].
 * Reset via [reset] (tests only).
 */
object TurnFailureMarkerTracker {

    /** A byte-identical output loop was detected and the turn was stopped. */
    const val LOOP_ABORTED = "LOOP_ABORTED"

    /** A streak of writes that changed nothing (no-op writes) stalled the turn with no deliverable. */
    const val NOOP_WRITE_STALL = "NOOP_WRITE_STALL"

    /**
     * The deterministic post-turn verification (project build/test) kept failing after all repair
     * rounds were exhausted; the turn ended without a verified deliverable.
     */
    const val VERIFICATION_FAILED = "VERIFICATION_FAILED"

    private val markers = ConcurrentHashMap<String, String>()

    /** Record the guardrail [marker] that aborted [taskId]'s turn. First marker for a task wins. */
    fun record(taskId: String, marker: String) {
        markers.putIfAbsent(taskId, marker)
    }

    /** The failure marker recorded for [taskId], or null if the turn did not hit a marked abort. */
    fun markerFor(taskId: String): String? = markers[taskId]

    /** Test-only: forget all recorded markers. */
    fun reset() {
        markers.clear()
    }
}
