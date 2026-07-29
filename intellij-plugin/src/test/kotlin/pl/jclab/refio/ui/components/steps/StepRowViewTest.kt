package pl.jclab.refio.ui.components.steps

import pl.jclab.refio.core.api.SubtaskResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan header is what tells a user whether a run succeeded and where its time went. The rules
 * under test: only finished work contributes to the total time, a step still awaiting approval is
 * actionable while a finished one is not, and failures are counted separately from successes.
 */
class StepRowViewTest {

    private fun subtask(
        id: String = "s1",
        status: String = "SUCCESS",
        startedAt: Long? = 1_000L,
        completedAt: Long? = 3_000L,
        errorMessage: String? = null
    ) = SubtaskResponse(
        id = id,
        taskId = "t1",
        orderIndex = 0,
        kind = "code_editing",
        status = status,
        approvalStatus = "NONE",
        requiresApproval = false,
        approvedByUser = false,
        description = "Apply discount rule",
        paramsJson = null,
        stepPlanJson = null,
        summary = null,
        result = null,
        startedAt = startedAt,
        finishedAt = completedAt,
        errorCode = null,
        errorMessage = errorMessage,
        tokensIn = 0,
        tokensOut = 0,
        costUsd = 0.0,
        latencyMs = 0,
        model = null,
        provider = null,
        createdAt = 0L,
        updatedAt = 0L,
        completedAt = completedAt
    )

    @Test
    fun `a step that never ran contributes no time to the plan total`() {
        assertEquals(0L, StepRowView.durationOf(subtask(startedAt = null, completedAt = null)))
        assertEquals(0L, StepRowView.durationOf(subtask(startedAt = 1_000L, completedAt = null)))
        assertEquals(2_000L, StepRowView.durationOf(subtask()))
    }

    @Test
    fun `only a step awaiting approval offers approve, only unstarted steps offer skip`() {
        val awaiting = StepRowView.from(subtask(status = "PENDING_APPROVAL"), 1)
        assertTrue(awaiting.canApprove)
        assertTrue(awaiting.canSkip)

        val done = StepRowView.from(subtask(status = "SUCCESS"), 1)
        assertFalse(done.canApprove)
        assertFalse(done.canSkip)

        val planned = StepRowView.from(subtask(status = "PLANNED"), 1)
        assertFalse(planned.canApprove)
        assertTrue(planned.canSkip)
    }

    @Test
    fun `a canceled step is not reported as a failure`() {
        assertEquals(StepRowView.State.SKIPPED, StepRowView.stateOf("CANCELED"))
        assertEquals(StepRowView.State.FAILED, StepRowView.stateOf("FAILED"))
        assertEquals(StepRowView.State.PENDING, StepRowView.stateOf("PENDING_APPROVAL"))
    }

    @Test
    fun `summary counts outcomes separately and sums the elapsed time`() {
        val steps = listOf(
            StepRowView.from(subtask(id = "a", status = "SUCCESS", startedAt = 0, completedAt = 1_000), 1),
            StepRowView.from(subtask(id = "b", status = "FAILED", startedAt = 0, completedAt = 500, errorMessage = "boom"), 2),
            StepRowView.from(subtask(id = "c", status = "PLANNED", startedAt = null, completedAt = null), 3)
        )

        val summary = PlanSummaryModel.from(steps)

        assertEquals(3, summary.total)
        assertEquals(1, summary.ok)
        assertEquals(1, summary.failed)
        assertEquals(1_500L, summary.totalDurationMs)
        assertTrue(summary.hasFailures)
    }

    @Test
    fun `a row falls back to the tool name when the plan gave no description`() {
        val row = StepRowView.from(subtask().copy(description = ""), 1)
        assertEquals("code_editing", row.title)
    }
}
