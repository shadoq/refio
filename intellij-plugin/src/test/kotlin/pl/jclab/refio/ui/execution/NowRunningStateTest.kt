package pl.jclab.refio.ui.execution

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
}
