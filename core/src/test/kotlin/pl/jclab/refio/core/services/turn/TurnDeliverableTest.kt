package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import pl.jclab.refio.core.db.TaskMode

class TurnDeliverableTest {

    private val substantialProse = "x".repeat(TurnDeliverable.PLAN_DELIVERABLE_MIN_CHARS)
    private val shortProse = "too short"

    @Test
    fun `write tool executed counts as deliverable regardless of mode`() {
        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 1, mode = TaskMode.AGENT, finalResponse = ""))
    }

    @Test
    fun `plan mode credits a substantial reply with no writes`() {
        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.PLAN, finalResponse = substantialProse))
    }

    @Test
    fun `plan mode does not credit a short reply`() {
        assertFalse(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.PLAN, finalResponse = shortProse))
    }

    @Test
    fun `main agent with prose and no writes is not a deliverable`() {
        // The parent AGENT is expected to actually change the workspace, so prose alone must not
        // be credited - otherwise a "here is what I would do" reply passes without any edit.
        assertFalse(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.AGENT, finalResponse = substantialProse, isSubagent = false))
    }

    @Test
    fun `subagent with substantial prose and no writes counts as a deliverable`() {
        // A read-only subagent (e.g. code-reviewer) delivers its answer AS prose; requiring a write
        // would fail every review/analysis delegation even though it did its job.
        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.AGENT, finalResponse = substantialProse, isSubagent = true))
    }

    @Test
    fun `subagent with only a short reply is not a deliverable`() {
        assertFalse(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.AGENT, finalResponse = shortProse, isSubagent = true))
    }
}
