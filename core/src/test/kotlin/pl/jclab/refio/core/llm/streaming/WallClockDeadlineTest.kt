package pl.jclab.refio.core.llm.streaming

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallClockDeadlineTest {

    @Test
    fun `continues before deadline`() {
        var now = 1000L
        val deadline = WallClockDeadline(deadlineMs = 5000, clock = { now })
        val decision = deadline.onDelta("x", 1, "x", streamStartMs = 1000L)
        assertEquals(StreamGuardrail.Decision.Continue, decision)
    }

    @Test
    fun `continues just before deadline`() {
        val now = 5999L
        val deadline = WallClockDeadline(deadlineMs = 5000, clock = { now })
        // elapsed = 5999 - 1000 = 4999 < 5000 → still inside deadline, continue.
        val decision = deadline.onDelta("x", 1, "x", streamStartMs = 1000L)
        assertEquals(StreamGuardrail.Decision.Continue, decision)
    }

    @Test
    fun `aborts at exactly deadline`() {
        val now = 6000L
        val deadline = WallClockDeadline(deadlineMs = 5000, clock = { now })
        // elapsed = 6000 - 1000 = 5000 — the budget is consumed, abort.
        val decision = deadline.onDelta("x", 1, "x", streamStartMs = 1000L)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
        assertEquals("WALL_CLOCK_DEADLINE", decision.code)
    }

    @Test
    fun `aborts when elapsed exceeds deadline`() {
        val now = 6001L
        val deadline = WallClockDeadline(deadlineMs = 5000, clock = { now })
        // elapsed = 6001 - 1000 = 5001 > 5000 → abort
        val decision = deadline.onDelta("x", 1, "x", streamStartMs = 1000L)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
        assertEquals("WALL_CLOCK_DEADLINE", decision.code)
        assertTrue(
            decision.reason.contains("5001") && decision.reason.contains("5000"),
            "Reason should include both elapsed and deadline, got: ${decision.reason}"
        )
    }

    @Test
    fun `injected clock advances naturally across calls`() {
        var now = 0L
        val deadline = WallClockDeadline(deadlineMs = 1000, clock = { now })
        val start = 0L

        now = 500L
        assertEquals(StreamGuardrail.Decision.Continue, deadline.onDelta("a", 1, "a", start))

        now = 900L
        assertEquals(StreamGuardrail.Decision.Continue, deadline.onDelta("b", 2, "ab", start))

        now = 1500L
        val decision = deadline.onDelta("c", 3, "abc", start)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
    }
}
