package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A denial from the approval policy gets its own counter instead of the tool-error rate.
 *
 * The two are different failures. A tool error means the model cannot drive its tools; a denial
 * means the environment refused a command the model was right to want - measured on `build-rest-api`,
 * where `lsof -ti :8792` fell outside the run's regex, counted as the eighth of ten tool errors and
 * pushed the turn over the 80% abort threshold. Removing that one entry leaves 70%, under the bar.
 *
 * Persistence is still a signal: the model is told a blocked call will stay blocked, so asking again
 * and again is a loop and ends the turn - under its own name, not under "too many tool errors".
 */
class ConsecutiveDeniedToolTrackerTest {

    @Test
    fun `a single denial does not end the turn`() {
        val tracker = TurnGuardrails.ConsecutiveDeniedToolTracker()

        assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(true))
    }

    @Test
    fun `three denials in a row end the turn`() {
        val tracker = TurnGuardrails.ConsecutiveDeniedToolTracker()

        tracker.record(true)
        tracker.record(true)
        val third = tracker.record(true)

        val abort = assertIs<TurnGuardrails.LoopStatus.ABORT>(third)
        assertIs<String>(abort.reason)
        check(abort.reason.contains("blocked")) { "the reason must say what happened: ${abort.reason}" }
        check(abort.incomplete) { "nothing was delivered, so the turn is incomplete" }
    }

    // A model that hits the gate, changes approach and gets work done is not looping.
    @Test
    fun `a permitted call in between clears the streak`() {
        val tracker = TurnGuardrails.ConsecutiveDeniedToolTracker()

        tracker.record(true)
        tracker.record(true)
        tracker.record(false)
        tracker.record(true)
        val next = tracker.record(true)

        assertIs<TurnGuardrails.LoopStatus.OK>(next)
    }
}
