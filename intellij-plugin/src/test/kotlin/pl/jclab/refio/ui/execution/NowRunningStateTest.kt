package pl.jclab.refio.ui.execution

import pl.jclab.refio.core.api.routers.LongTaskProgress
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.turn.TurnPhase
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * While the agent works, the user must be able to see what is running without scrolling. The
 * rules under test: the bar only exists during a live turn, a turn stalled on the user stops
 * animating (so a spinner never implies progress that is not happening), and the running tool
 * is named rather than described by an internal phase.
 */
class NowRunningStateTest {

    private fun snapshot(
        phase: TurnPhase,
        iteration: Int = 3,
        maxIterations: Int = 25,
        tool: String? = null
    ) = TurnStateSnapshot(
        phase = phase,
        iteration = iteration,
        maxIterations = maxIterations,
        activeToolName = tool
    )

    @Test
    fun `bar is hidden when no turn is running`() {
        assertFalse(NowRunningState.from(snapshot(TurnPhase.IDLE)).visible)
        assertFalse(NowRunningState.from(snapshot(TurnPhase.COMPLETED)).visible)
        assertFalse(NowRunningState.from(snapshot(TurnPhase.FAILED)).visible)
    }

    @Test
    fun `a running tool is named so the user knows what the agent touches`() {
        val state = NowRunningState.from(snapshot(TurnPhase.EXECUTING_TOOLS, tool = "advance_code_editing"))

        assertTrue(state.visible)
        assertTrue(state.busy)
        assertEquals("advance_code_editing", state.detailText)
        assertEquals("Step 3/25", state.stepText)
    }

    @Test
    fun `waiting on the user keeps the bar up but stops the spinner`() {
        val approval = NowRunningState.from(snapshot(TurnPhase.WAITING_FOR_PERMISSION))

        assertTrue(approval.visible)
        assertFalse(approval.busy, "a spinner here would imply the engine is working when it is blocked")
        assertEquals("waiting for approval", approval.detailText)
    }

    @Test
    fun `step text omits the limit when the engine reports none`() {
        val state = NowRunningState.from(snapshot(TurnPhase.CALLING_MODEL, iteration = 2, maxIterations = 0))

        assertEquals("Step 2", state.stepText)
    }

    @Test
    fun `elapsed time stays compact for long turns`() {
        assertEquals("0s", NowRunningState.formatElapsed(0))
        assertEquals("42s", NowRunningState.formatElapsed(42_000))
        assertEquals("1m 18s", NowRunningState.formatElapsed(78_000))
        assertEquals("2h 5m", NowRunningState.formatElapsed(7_500_000))
    }

    private fun round(status: LongTaskStatus = LongTaskStatus.RUNNING) = LongTaskProgress(
        taskId = "t1",
        status = status,
        round = 2,
        maxRounds = 12,
        activeMs = 47 * 60_000L,
        maxMinutes = 240,
        condition = "until: npm test",
        lastCheck = "round 1: failed (exit 1)",
        line = "round 2/12 · 47m of 4h · until: npm test · round 1: check failed (exit 1, 5 error lines)",
    )

    @Test
    fun `between rounds the bar stays up while the end condition is checked`() {
        val state = NowRunningState.from(snapshot(TurnPhase.COMPLETED), round())

        assertTrue(state.visible, "a check like npm test can run for minutes; a hidden bar reads as the agent being done")
        assertTrue(state.busy)
        assertEquals("", state.stepText)
        assertEquals("checking until: npm test", state.detailText)
        assertEquals("Round 2/12 · 47m of 4h · until: npm test · round 1: check failed (exit 1, 5 error lines)", state.roundText)
    }

    @Test
    fun `a running round is shown next to the step`() {
        val state = NowRunningState.from(snapshot(TurnPhase.CALLING_MODEL), round())

        assertEquals("Step 3/25", state.stepText)
        assertEquals("calling model", state.detailText)
        assertTrue(state.roundText.startsWith("Round 2/12"), state.roundText)
    }

    @Test
    fun `a long task that ended does not keep the bar up`() {
        assertFalse(NowRunningState.from(snapshot(TurnPhase.COMPLETED), round(LongTaskStatus.SUCCEEDED)).visible)
        assertEquals("", NowRunningState.from(snapshot(TurnPhase.CALLING_MODEL), round(LongTaskStatus.CANCELLED)).roundText)
    }
}
