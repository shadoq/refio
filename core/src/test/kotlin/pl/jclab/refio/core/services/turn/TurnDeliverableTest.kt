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

    @Test
    fun `a reply that only announces the work still to come is not a plan`() {
        // Long enough to clear the char floor, yet it delivers nothing: crediting it would report
        // SUCCESS and hand the user an announcement where the plan should be.
        val stub = "Let me now analyze all these files carefully, check every caller involved, " +
            "and then produce the plan you asked for."

        assertFalse(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.PLAN, finalResponse = stub))
        assertFalse(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.AGENT, finalResponse = stub, isSubagent = true))
    }

    @Test
    fun `a structured plan is credited even though it starts with an announcement`() {
        // The steps ARE the deliverable; how the model introduces them is irrelevant.
        val plan = """
            I will refactor the parser in three steps:
            1. Extract the tokenizer into its own class.
            2. Move the error recovery out of the recursive descent.
            3. Cover the nested-quote case with a regression test.
        """.trimIndent()

        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.PLAN, finalResponse = plan))
    }

    @Test
    fun `a prose finding from a review subagent is credited`() {
        // No structure, no writes - and still the whole point of the delegation.
        val review = "The bug is in ConfigResolver.set: an app-scoped write drops the project row " +
            "even when it only records session state, so project configuration disappears a few seconds after startup."

        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.AGENT, finalResponse = review, isSubagent = true))
    }

    @Test
    fun `a long single paragraph is credited even if it opens like an announcement`() {
        // Past a point the text carries substance whatever it opens with; staying strict there would
        // throw away finished work, which is the more expensive mistake of the two.
        val longAnswer = "Let me walk through the failure: " + "the loop never terminates because the guard is inverted. ".repeat(8)

        assertTrue(TurnDeliverable.produced(fileWriteToolsExecutedInTurn = 0, mode = TaskMode.PLAN, finalResponse = longAnswer))
    }
}
