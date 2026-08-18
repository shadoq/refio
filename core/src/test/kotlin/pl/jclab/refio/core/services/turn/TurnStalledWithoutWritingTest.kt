package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fact that used to go unrecorded: a top-level AGENT turn that ended without writing anything.
 *
 * Every such run reached the reports as a bare INCOMPLETE, which the harness buckets together with
 * repetition loops. Measured across a 24-model sweep, three models ended this way on the same
 * scenario - each announcing the edit it was about to make and then stopping - and reading those
 * runs as loops sent the analysis after a mechanism that did not exist.
 */
class TurnStalledWithoutWritingTest {

    @Test
    fun `an AGENT turn that failed without writing a file is marked`() {
        assertTrue(
            TurnDeliverable.stalledWithoutWriting(
                success = false, mode = TaskMode.AGENT, depth = 0, fileWriteToolsExecutedInTurn = 0,
            )
        )
    }

    @Test
    fun `a turn that wrote something is not this failure`() {
        assertFalse(
            TurnDeliverable.stalledWithoutWriting(
                success = false, mode = TaskMode.AGENT, depth = 0, fileWriteToolsExecutedInTurn = 1,
            )
        )
    }

    @Test
    fun `a successful turn is never marked`() {
        assertFalse(
            TurnDeliverable.stalledWithoutWriting(
                success = true, mode = TaskMode.AGENT, depth = 0, fileWriteToolsExecutedInTurn = 0,
            )
        )
    }

    // PLAN cannot write by construction, so writing nothing says nothing about the turn.
    @Test
    fun `a PLAN turn is never marked for not writing`() {
        assertFalse(
            TurnDeliverable.stalledWithoutWriting(
                success = false, mode = TaskMode.PLAN, depth = 0, fileWriteToolsExecutedInTurn = 0,
            )
        )
    }

    // A read-only subagent (a review, an analysis) delivers its whole answer as prose.
    @Test
    fun `a nested turn is never marked for not writing`() {
        assertFalse(
            TurnDeliverable.stalledWithoutWriting(
                success = false, mode = TaskMode.AGENT, depth = 1, fileWriteToolsExecutedInTurn = 0,
            )
        )
    }
}
