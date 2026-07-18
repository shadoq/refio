package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage

/**
 * Encodes WHY the guardian re-entry state behaves the way it does (docs/0058, Faza 2).
 *
 * The "capture once, restore when re-entry added no work" policy is a deliberate trade-off
 * (AgentTurnLoop comment ~:462-470, observed sessions 54cf9c8c / 070ab0e5): a guardian re-entry
 * drops the terminal answer the user already saw; if the re-entry then produces no new tool work
 * the follow-up is usually a degraded re-phrasing, so we finalize the FIRST captured answer.
 * These tests pin that intent so a future change to the loop can't silently regress it.
 */
class TurnGuardianStateTest {

    private fun resp(content: String): LLMResponse =
        LLMResponse(
            content = content,
            usage = LLMUsage(0, 0, 0),
            model = "test-model",
            provider = "test",
            cost = 0.0,
        )

    @Test
    fun `restores the first answer when the re-entry added no tool work`() {
        // The user saw answer A; a guardian re-entered with usedTools.size == 2 at that moment.
        val state = TurnGuardianState()
        val answerA = resp("A — the complete answer the user already saw")
        state.captureIfFirst(answerA, hasVisibleText = true)
        state.onReentry(usedToolsSize = 2)

        // Re-entry produced a degraded re-phrasing B and called NO new tool (size still 2).
        val answerB = resp("B — degraded re-phrasing after the nudge")
        val effective = state.effectiveResponse(current = answerB, usedToolsSize = 2)

        // We keep A: finalizing B would replace the good answer with a worse one.
        assertSame(answerA, effective)
    }

    @Test
    fun `keeps the new answer when the re-entry called a tool`() {
        val state = TurnGuardianState()
        val answerA = resp("A — answer before re-entry")
        state.captureIfFirst(answerA, hasVisibleText = true)
        state.onReentry(usedToolsSize = 2)

        // Re-entry DID work: usedTools grew to 3. B incorporates that work and must win.
        val answerB = resp("B — answer that incorporates the new tool result")
        val effective = state.effectiveResponse(current = answerB, usedToolsSize = 3)

        assertSame(answerB, effective)
    }

    @Test
    fun `second capture does not overwrite the first — earliest answer wins`() {
        val state = TurnGuardianState()
        val answerA = resp("A — earliest, most complete")
        state.captureIfFirst(answerA, hasVisibleText = true)
        state.onReentry(usedToolsSize = 2)

        // A later re-entry tries to capture again; capture-once must ignore it.
        val answerB = resp("B — later, degraded")
        state.captureIfFirst(answerB, hasVisibleText = true)
        state.onReentry(usedToolsSize = 2)

        assertSame(answerA, state.preReentryResponse)
        assertEquals(2, state.reentryCount)
    }

    @Test
    fun `nothing to restore when no guardian re-entry happened`() {
        // A captured response with reentryCount == 0 must not be restorable: without a re-entry
        // there is no discarded answer to recover (guards the one-shot native fallback at :788).
        val state = TurnGuardianState()
        state.captureIfFirst(resp("captured but never re-entered"), hasVisibleText = true)

        assertEquals(0, state.reentryCount)
        assertNull(state.restorableResponse(usedToolsSize = 0))
    }

    @Test
    fun `does not capture an answer with no visible text`() {
        // Empty terminal text is never worth restoring — capture only meaningful answers.
        val state = TurnGuardianState()
        state.captureIfFirst(resp(""), hasVisibleText = false)

        assertNull(state.preReentryResponse)
    }

    // ---- replenishIfSustainedProgress: per-episode re-entry budget, not per-whole-turn ----

    @Test
    fun `replenishes the budget after sustained progress since the last re-entry`() {
        // Episode: a re-entry happened at usedTools.size == 4 (the 2ef4aabc shape: judge re-entered
        // early on a trivial pause). The agent then worked productively — many new tool calls.
        val state = TurnGuardianState()
        state.captureIfFirst(resp("early pause answer"), hasVisibleText = true)
        state.onReentry(usedToolsSize = 4)

        // 12 tools now (8 new since the snapshot) >= threshold → episode is recovered, budget reset.
        val reset = state.replenishIfSustainedProgress(usedToolsSize = 12, progressThreshold = 3)

        assertTrue(reset)
        assertEquals(0, state.reentryCount)         // fresh single re-entry available again
        assertNull(state.preReentryResponse)        // the early stash is stale after real progress
        assertEquals(0, state.usedToolsAtLastReentry)
    }

    @Test
    fun `does not replenish before the progress threshold is reached`() {
        // Only 2 new tool calls since the re-entry (4 -> 6) — below the threshold of 3. A model that
        // flaps between a bare intent announcement and one token tool call must NOT farm re-entries.
        val state = TurnGuardianState()
        state.onReentry(usedToolsSize = 4)

        val reset = state.replenishIfSustainedProgress(usedToolsSize = 6, progressThreshold = 3)

        assertFalse(reset)
        assertEquals(1, state.reentryCount)         // budget stays spent
        assertEquals(4, state.usedToolsAtLastReentry)
    }

    @Test
    fun `replenish is a no-op when no re-entry has happened yet`() {
        // No episode to recover — even a large tool count must not touch the (already full) budget.
        val state = TurnGuardianState()

        val reset = state.replenishIfSustainedProgress(usedToolsSize = 50, progressThreshold = 3)

        assertFalse(reset)
        assertEquals(0, state.reentryCount)
    }

    @Test
    fun `replenish never steals a restorable answer`() {
        // restorableResponse fires only when NO new tools ran since the snapshot; replenish requires
        // >= threshold new calls. The two conditions are mutually exclusive — a reset can never drop
        // an answer that was about to be restored. Pin that: at the snapshot size, nothing resets and
        // the stash is still restorable.
        val state = TurnGuardianState()
        val answerA = resp("A — the answer the user already saw")
        state.captureIfFirst(answerA, hasVisibleText = true)
        state.onReentry(usedToolsSize = 5)

        // No new tools (size still 5): not sustained progress, so no reset...
        assertFalse(state.replenishIfSustainedProgress(usedToolsSize = 5, progressThreshold = 3))
        // ...and the stashed answer remains restorable.
        assertSame(answerA, state.restorableResponse(usedToolsSize = 5))
    }
}
