package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Why the turn loop stopped, as a value that can be counted.
 *
 * The loop has ~25 terminal exits. Five of them were named by [TurnFailureMarkerTracker]; the rest
 * lived only as an English sentence in the turn's response text, so "ran out of iterations", "hit
 * the cost ceiling" and "the model never produced a parsable answer" all reached a report as the
 * same shapeless failure. This is the machine-readable half of that sentence.
 *
 * Exported as `run.json.metrics.stopReason`. [TurnFailureMarkerTracker] stays untouched so existing
 * readers of `failureMarker` keep working.
 */
enum class TurnStopReason {
    /** The turn delivered what was asked and finalized cleanly. */
    COMPLETED,

    /** A completion guardian decided the request was not delivered and no re-entry would help. */
    GUARDIAN_INCOMPLETE,

    /** The loop used its whole iteration budget. */
    MAX_ITERATIONS,

    /** The session's running cost reached the configured ceiling. */
    COST_LIMIT,

    /** The turn ran longer than its wall-clock budget. */
    TIME_LIMIT,

    /** Tool calls failed often enough within the guardrail's window to stop the turn. */
    TOOL_ERROR_RATE,

    /** The same call, or byte-identical output, repeated past the abort threshold. */
    REPETITION_LOOP,

    /** A streak of writes that changed nothing - the model editing a file into its own content. */
    NOOP_WRITE_STALL,

    /** The model repeated the same prose across iterations instead of advancing. */
    TEXT_REPETITION,

    /** The model chanted one block of content instead of answering. */
    CONTENT_CHANTING,

    /** The model never produced a parsable answer and the recovery retries were exhausted. */
    FORMAT_UNRECOVERABLE,

    /**
     * The model answered in prose and the budget of format reminders ran out. Distinct from
     * [FORMAT_UNRECOVERABLE]: there the answer could not be parsed at all, here the loop chose to
     * stop asking. A comparison needs to tell "the model could not" from "we stopped waiting".
     */
    FORMAT_NUDGE_EXHAUSTED,

    /** A tool the policy blocks was called often enough to stop the turn. */
    BLOCKED_TOOL,

    /** Approval was refused call after call, or the user rejected a specific call. */
    DENIED_TOOL,

    /** The post-turn build/test kept failing after every repair round. */
    VERIFICATION_FAILED,

    /** The turn ended without success and without ever writing a file. */
    NO_FILE_WRITTEN,

    /** The user stopped the turn. */
    CANCELLED,

    /** An unhandled error ended the turn. */
    EXCEPTION,

    /**
     * The provider truncated the response against its own output-length limit. Not a format
     * breakdown: the model was answering correctly and was cut off mid-sentence.
     */
    OUTPUT_TRUNCATED,

    /** A streaming guardrail aborted the response while it was still being generated. */
    STREAM_GUARDRAIL,

    /**
     * The provider returned no content and no tool calls at all. Usually the prompt overran the
     * context window and was silently truncated, which is why it is worth its own value rather
     * than being filed under a format failure.
     */
    EMPTY_RESPONSE,

    /** No exit named itself. A rising count here means this enum has fallen behind the loop. */
    UNKNOWN;

    /**
     * True for the exits that mean the turn did its job. Used to keep the "nothing was written"
     * observation from overwriting a reason that already explains the stop.
     */
    val isClean: Boolean get() = this == COMPLETED || this == UNKNOWN
}

/**
 * Carries the stop reason from the turn loop to [SessionDebugExporter] (`run.json`), which builds
 * its snapshot from repositories keyed by `taskId` and never sees the turn result object.
 *
 * Last write wins, deliberately: a turn a completion guardian sends back into the loop stops twice,
 * and the reason that matters is the one it stopped on for good.
 */
object TurnStopReasonTracker {

    private val reasons = ConcurrentHashMap<String, TurnStopReason>()

    fun record(taskId: String, reason: TurnStopReason) {
        reasons[taskId] = reason
    }

    /** The reason recorded for [taskId]; [TurnStopReason.UNKNOWN] when the turn named none. */
    fun reasonFor(taskId: String): TurnStopReason = reasons[taskId] ?: TurnStopReason.UNKNOWN

    /** Test-only: forget all recorded reasons. */
    fun reset() {
        reasons.clear()
    }
}
