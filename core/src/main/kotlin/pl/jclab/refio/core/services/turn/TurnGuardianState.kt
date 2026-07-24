package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.llm.LLMResponse

/**
 * Explicit, auditable state for completion-guardian re-entries inside [AgentTurnLoop.executeTurnLoop].
 *
 * Previously three loose loop variables (`guardianReentryCount`, `candidateFinalResponse`,
 * `usedToolsSizeAtLastReentry`) scattered the re-entry decision across the turn loop, making it
 * impossible to log or replay. Gathering them here is a pure consolidation -
 * no behaviour change; `toString()` now yields a single replayable snapshot.
 *
 * **Capture-once policy (deliberate trade-off, not a bug).** A guardian re-entry drops the terminal
 * answer the user already saw (the loop `continue`s before persisting it). If the re-entry then
 * produces NO new tool work, the follow-up response is usually a degraded re-phrasing — finalizing
 * it would replace the good answer with a worse one and lose the original entirely. So we stash the
 * FIRST discarded answer and restore it at finalize when the re-entry added nothing. Captured once
 * so the earliest, most-complete terminal answer wins (observed 2026-05, sessions 54cf9c8c /
 * 070ab0e5). When the re-entry DID call a tool (usedTools grew), its later response incorporates the
 * new work and is the right one to keep.
 */
data class TurnGuardianState(
    /** beforeFinish guardian re-entry counter (capped by GuardianRegistry.maxReentries). */
    var reentryCount: Int = 0,
    /** The first terminal text response a guardian re-entry discarded; null until first capture. */
    var preReentryResponse: LLMResponse? = null,
    /** Snapshot of usedTools.size at the moment of the most recent guardian re-entry. */
    var usedToolsAtLastReentry: Int = 0,
    /**
     * True once the captured pre-re-entry answer has been persisted to the transcript as its own
     * ASSISTANT row (so the report the user saw while streaming survives the re-entry instead of
     * vanishing). Guards against persisting it a second time at finalize. See [captureAlreadyFinalized].
     */
    var capturePersisted: Boolean = false,
) {
    /**
     * Stash the answer the user already saw before a re-entry drops it. Keeps only the FIRST one
     * ([preReentryResponse] already set → no-op) and only when it carried visible text worth saving.
     *
     * @return true when THIS call stored a new capture (so the caller can persist it once); false
     *   when there was already a capture or the answer had no visible text.
     */
    fun captureIfFirst(response: LLMResponse, hasVisibleText: Boolean): Boolean {
        if (preReentryResponse == null && hasVisibleText) {
            preReentryResponse = response
            return true
        }
        return false
    }

    /** Record that a guardian re-entered: bump the counter and snapshot the tool-usage size. */
    fun onReentry(usedToolsSize: Int) {
        reentryCount++
        usedToolsAtLastReentry = usedToolsSize
    }

    /**
     * The stashed pre-re-entry answer IFF it should be restored: a re-entry happened and added no
     * new tool work since its snapshot. `null` otherwise (no re-entry, or the re-entry did work).
     */
    fun restorableResponse(usedToolsSize: Int): LLMResponse? =
        preReentryResponse?.takeIf { reentryCount > 0 && usedToolsSize <= usedToolsAtLastReentry }

    /** The response to finalize: the restored pre-re-entry answer if applicable, else [current]. */
    fun effectiveResponse(current: LLMResponse, usedToolsSize: Int): LLMResponse =
        restorableResponse(usedToolsSize) ?: current

    /**
     * True when the captured answer was already persisted at re-entry AND it is the one finalize
     * would restore (re-entry added no new tool work). In that case finalize must NOT persist it a
     * second time — the row already exists in the transcript, chronologically before the nudge.
     *
     * When the re-entry DID add tool work ([restorableResponse] is null) this returns false so the
     * new terminal response is still persisted; the already-persisted capture then stands as the
     * earlier report the user saw, followed by the response that incorporates the extra work.
     */
    fun captureAlreadyFinalized(usedToolsSize: Int): Boolean =
        capturePersisted && restorableResponse(usedToolsSize) != null

    /**
     * Replenish the bounded re-entry budget once the agent has made sustained progress since the
     * last re-entry, so the budget is "per stall episode" rather than "once per whole turn".
     *
     * WHY: the single bounded re-entry used to be spent for the entire turn. A budget consumed
     * early on a trivial pause left a GENUINE near-completion stall many iterations later with no
     * safety net (observed: session 2ef4aabc — the judge re-entered on iteration 5 for a
     * "checking the structure..." pause, then the agent worked productively for 13 iterations and
     * stalled one tool call short of delivering; the budget was gone → turn finalized INCOMPLETE).
     * Once [progressThreshold] or more new tool calls have run since the snapshot, that earlier
     * episode is considered recovered: clear the counter, the now-stale stashed answer, and the
     * snapshot so the next terminal stall gets a fresh single re-entry.
     *
     * This never steals a restorable answer: [restorableResponse] only fires when NO new tools ran
     * since the snapshot (`usedToolsSize <= usedToolsAtLastReentry`), the exact opposite of the
     * `>= progressThreshold` new calls required here.
     *
     * @return true when an episode was reset (for logging), false otherwise.
     */
    fun replenishIfSustainedProgress(usedToolsSize: Int, progressThreshold: Int): Boolean {
        if (reentryCount == 0) return false
        if (usedToolsSize - usedToolsAtLastReentry < progressThreshold) return false
        reentryCount = 0
        preReentryResponse = null
        usedToolsAtLastReentry = 0
        // A new stall episode may capture and persist a fresh report of its own.
        capturePersisted = false
        return true
    }

    companion object {
        /**
         * New tool calls required since the last guardian re-entry before the re-entry budget is
         * replenished. Small enough that an agent that genuinely recovered (the 2ef4aabc case ran
         * 12+ tools after its re-entry) regains a safety net, large enough that a model flapping
         * between a bare intent announcement and one token tool call cannot farm infinite
         * re-entries (it would need [DEFAULT_PROGRESS_RESET_THRESHOLD] real calls between each).
         */
        const val DEFAULT_PROGRESS_RESET_THRESHOLD = 3
    }
}
