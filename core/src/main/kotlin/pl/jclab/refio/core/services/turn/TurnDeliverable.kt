package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode

/**
 * Shared "did this turn already produce a deliverable?" predicate.
 *
 * Used at terminal stall / abort points to decide whether a non-clean finish should be a FAILURE
 * (the agent abandoned the request before producing anything) or a SUCCESS (the deliverable is
 * already on hand and only the sign-off / an optional self-verification step went wrong). This is
 * the single source of truth for that judgement so the guardian, the format hard-fail, and the
 * repetition abort all agree on deliverable-aware finalization.
 *
 *  - AGENT (main): a real FILE edit/create landed this turn → the file deliverable is on disk. The
 *    caller must pass the FILE-write count, NOT any write-mode call: run_terminal_command/run_code
 *    are mode=WRITE but leave no file, so a `mkdir`-and-stall must not read as a delivered turn.
 *  - PLAN: writes are structurally impossible; the deliverable IS the answer text, so a substantial
 *    reply (a real plan, not a bare "Let me produce a plan." intent stub) counts.
 *  - SUBAGENT: may be read-only (e.g. a code review) and deliver its whole answer AS prose, so a
 *    substantial reply counts even with zero writes - otherwise every review/analysis delegation
 *    would be judged as "delivered nothing".
 */
object TurnDeliverable {

    /** Minimum PLAN-mode reply length (chars) to count as a produced answer vs a bare intent stub. */
    const val PLAN_DELIVERABLE_MIN_CHARS = 100

    fun produced(
        fileWriteToolsExecutedInTurn: Int,
        mode: TaskMode,
        finalResponse: String,
        isSubagent: Boolean = false,
    ): Boolean {
        if (fileWriteToolsExecutedInTurn > 0) {
            return true
        }
        if ((mode == TaskMode.PLAN || isSubagent) && finalResponse.trim().length >= PLAN_DELIVERABLE_MIN_CHARS) {
            return true
        }
        return false
    }
}
