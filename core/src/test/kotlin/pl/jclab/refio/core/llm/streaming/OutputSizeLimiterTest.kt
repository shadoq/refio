package pl.jclab.refio.core.llm.streaming

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputSizeLimiterTest {

    @Test
    fun `continues while under limit`() {
        val limiter = OutputSizeLimiter(maxChars = 1000)
        val decision = limiter.onDelta("x".repeat(500), 500, "x".repeat(500), 0L)
        assertEquals(StreamGuardrail.Decision.Continue, decision)
    }

    @Test
    fun `continues exactly at limit`() {
        val limiter = OutputSizeLimiter(maxChars = 1000)
        val decision = limiter.onDelta("x", 1000, "x", 0L)
        assertEquals(StreamGuardrail.Decision.Continue, decision)
    }

    @Test
    fun `aborts when accumulated length exceeds limit`() {
        val limiter = OutputSizeLimiter(maxChars = 1000)
        val decision = limiter.onDelta("x", 1001, "x", 0L)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
        assertEquals("OUTPUT_TOO_LARGE", decision.code)
        assertTrue(
            decision.reason.contains("1001") && decision.reason.contains("1000"),
            "Reason should include both accumulated and limit, got: ${decision.reason}"
        )
    }

    @Test
    fun `default limit is sensible`() {
        // Sanity check on the default — 32KB is the documented value.
        val limiter = OutputSizeLimiter()
        // 20K chars should still be fine
        assertEquals(StreamGuardrail.Decision.Continue, limiter.onDelta("x", 20_000, "x", 0L))
        // 40K chars should trip
        val decision = limiter.onDelta("x", 40_000, "x", 0L)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
    }
}
