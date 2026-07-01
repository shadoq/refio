package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode

/**
 * Shared "did this turn already produce a deliverable?" predicate.
 *
 * Used at terminal stall / abort points to decide whether a non-clean finish should be a FAILURE
 * (the agent abandoned the request before producing anything) or a SUCCESS (the deliverable is
 * already on hand and only the sign-off / an optional self-verification step went wrong). This is
 * the single source of truth for that judgement so the guardian, the format hard-fail, and the
 * repetition abort all agree (docs/0070 - "deliverable-aware finalization").
 *
 *  - AGENT/SUBAGENT: a write/edit executed this turn → the file deliverable is on disk.
 *  - PLAN: writes are structurally impossible; the deliverable IS the answer text, so a substantial
 *    reply (a real plan, not a bare "Let me produce a plan." intent stub) counts.
 */
object TurnDeliverable {

    /** Minimum PLAN-mode reply length (chars) to count as a produced answer vs a bare intent stub. */
    const val PLAN_DELIVERABLE_MIN_CHARS = 300

    fun produced(writeToolsExecutedInTurn: Int, mode: TaskMode, finalResponse: String): Boolean {
        if (writeToolsExecutedInTurn > 0) {
            return true
        }
        if (mode == TaskMode.PLAN && finalResponse.trim().length >= PLAN_DELIVERABLE_MIN_CHARS) {
            return true
        }
        return false
    }
}
