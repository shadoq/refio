package pl.jclab.refio.core.debug

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TurnIterationTracker] - the loop's own iteration count on its way to
 * `run.json.metrics.iterations`. The headline efficiency number used to be the subtask row count
 * (`toolCallCount`) under a name that read like iterations, so these assertions guard the
 * distinction rather than the plumbing.
 */
class TurnIterationTrackerTest {

    @AfterTest
    fun tearDown() = TurnIterationTracker.reset()

    @Test
    fun `records the iterations a turn took and the ceiling it ran under`() {
        TurnIterationTracker.record("t-basic", IterationSummary(used = 7, limit = 200))

        assertEquals(IterationSummary(used = 7, limit = 200), TurnIterationTracker.summaryFor("t-basic"))
    }

    @Test
    fun `an unknown task reads as zero rather than failing the export`() {
        assertEquals(IterationSummary.NONE, TurnIterationTracker.summaryFor("t-never-ran"))
    }

    @Test
    fun `two tasks running side by side do not read each other's counts`() {
        TurnIterationTracker.record("t-a", IterationSummary(used = 3, limit = 40))
        TurnIterationTracker.record("t-b", IterationSummary(used = 19, limit = 200))

        assertEquals(3, TurnIterationTracker.summaryFor("t-a").used)
        assertEquals(19, TurnIterationTracker.summaryFor("t-b").used)
    }

    @Test
    fun `the last write wins so a re-entered turn reports its final count`() {
        TurnIterationTracker.record("t-reentry", IterationSummary(used = 4, limit = 200))
        TurnIterationTracker.record("t-reentry", IterationSummary(used = 9, limit = 200))

        assertEquals(9, TurnIterationTracker.summaryFor("t-reentry").used)
    }

    @Test
    fun `a turn that ran out of iterations is visible as such`() {
        TurnIterationTracker.record("t-exhausted", IterationSummary(used = 40, limit = 40))
        TurnIterationTracker.record("t-roomy", IterationSummary(used = 6, limit = 40))

        assertTrue(TurnIterationTracker.summaryFor("t-exhausted").exhausted)
        assertFalse(TurnIterationTracker.summaryFor("t-roomy").exhausted)
    }
}
