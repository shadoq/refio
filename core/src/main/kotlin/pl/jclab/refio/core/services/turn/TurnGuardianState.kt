package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.llm.LLMResponse

/**
 * Explicit, auditable state for completion-guardian re-entries inside [AgentTurnLoop.executeTurnLoop].
 *
 * Previously three loose loop variables (`guardianReentryCount`, `candidateFinalResponse`,
 * `usedToolsSizeAtLastReentry`) scattered the re-entry decision across the turn loop, making it
 * impossible to log or replay. Gathering them here (docs/0058, Faza 2) is a pure consolidation —
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
) {
    /**
     * Stash the answer the user already saw before a re-entry drops it. Keeps only the FIRST one
     * ([preReentryResponse] already set → no-op) and only when it carried visible text worth saving.
     */
    fun captureIfFirst(response: LLMResponse, hasVisibleText: Boolean) {
        if (preReentryResponse == null && hasVisibleText) {
            preReentryResponse = response
        }
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
}
