package pl.jclab.refio.core.session

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.TurnResult
import kotlin.test.assertEquals

/**
 * History shows how each task ended. A task the user stopped must read as cancelled, not as failed:
 * Stop between two rounds of a long task ends the loop without an exception, so the turn result is
 * all the IDE has to go by.
 */
class TaskStatusAfterTurnTest {

    private fun result(success: Boolean, incomplete: Boolean = false, stopReason: TurnStopReason = TurnStopReason.UNKNOWN) =
        TurnResult(success = success, response = "", iterations = 1, tokensIn = 0, tokensOut = 0, cost = 0.0, incomplete = incomplete, stopReason = stopReason)

    @Test
    fun `a turn or loop the user stopped is cancelled, not failed`() {
        assertEquals(TaskStatus.CANCELED, taskStatusAfter(result(success = false, stopReason = TurnStopReason.CANCELLED)))
    }

    @Test
    fun `a loop that ran out of rounds is incomplete and a delivered one succeeded`() {
        assertEquals(TaskStatus.INCOMPLETE, taskStatusAfter(result(success = false, incomplete = true, stopReason = TurnStopReason.COMPLETED)))
        assertEquals(TaskStatus.SUCCESS, taskStatusAfter(result(success = true, stopReason = TurnStopReason.COMPLETED)))
        assertEquals(TaskStatus.FAILED, taskStatusAfter(result(success = false, stopReason = TurnStopReason.EXCEPTION)))
    }
}
