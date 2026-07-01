package pl.jclab.refio.core.debug

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [TurnFailureMarkerTracker] - the per-task record of *which* guardrail aborted a turn,
 * read into `run.json.metrics.failureMarker` so the e2e classifier can tell a repetition loop from a
 * no-op-write stall instead of lumping both as "loop".
 */
class TurnFailureMarkerTrackerTest {

    @AfterTest
    fun tearDown() = TurnFailureMarkerTracker.reset()

    @Test
    fun `records and reads a marker per task`() {
        TurnFailureMarkerTracker.record("t-record", TurnFailureMarkerTracker.LOOP_ABORTED)
        assertEquals("LOOP_ABORTED", TurnFailureMarkerTracker.markerFor("t-record"))
    }

    @Test
    fun `the first marker for a task wins (the first guardrail is the cause)`() {
        TurnFailureMarkerTracker.record("t-first", TurnFailureMarkerTracker.NOOP_WRITE_STALL)
        TurnFailureMarkerTracker.record("t-first", TurnFailureMarkerTracker.LOOP_ABORTED)
        assertEquals("NOOP_WRITE_STALL", TurnFailureMarkerTracker.markerFor("t-first"))
    }

    @Test
    fun `an untracked task has no marker`() {
        assertNull(TurnFailureMarkerTracker.markerFor("t-never-seen"))
    }

    @Test
    fun `reset clears recorded markers`() {
        TurnFailureMarkerTracker.record("t-reset", TurnFailureMarkerTracker.LOOP_ABORTED)
        TurnFailureMarkerTracker.reset()
        assertNull(TurnFailureMarkerTracker.markerFor("t-reset"))
    }
}
