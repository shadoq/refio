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
    fun `default limit covers a whole single-file app in one response`() {
        // 256KB, raised from 128KB. A 35B-class model told to deliver a complete single-file app
        // streams the whole thing in ONE response instead of building it up through edit tools:
        // ornith:35b overshot the old ceiling by 6 characters after 11 minutes of generation and
        // lost all of it (e2e pixel-plumber-levels). The ceiling still exists to stop a runaway
        // decoder - it just must not be narrower than the output a healthy model actually emits.
        val limiter = OutputSizeLimiter()
        assertEquals(StreamGuardrail.Decision.Continue, limiter.onDelta("x", 200_000, "x", 0L))
        val decision = limiter.onDelta("x", 300_000, "x", 0L)
        assertTrue(decision is StreamGuardrail.Decision.Abort)
    }

    // ---- ceilingForContext: the ceiling scales with the model, it is not a constant ----

    @Test
    fun `a 64K-token window allows a 256KB response`() {
        // The case that motivated this: ornith:35b at a 64K window emitted 131078 chars of a
        // single-file game and was aborted 6 chars over the old fixed limit.
        assertEquals(262_144, OutputSizeLimiter.ceilingForContext(65_536))
    }

    @Test
    fun `a 128K-token window allows twice as much`() {
        // A bigger model must not inherit a smaller model's ceiling.
        assertEquals(524_288, OutputSizeLimiter.ceilingForContext(131_072))
    }

    @Test
    fun `a small window still gets the historical floor`() {
        // Deriving downwards would make small-window models WORSE than before the change; the
        // floor keeps the old fixed limit as a guaranteed minimum.
        assertEquals(OutputSizeLimiter.MIN_CEILING_CHARS, OutputSizeLimiter.ceilingForContext(4_096))
    }

    @Test
    fun `an implausibly large window is capped`() {
        // The guardrail must stay a guardrail: past the cap a single response is pathological
        // whatever the window claims. Also proves the multiplication cannot overflow Int.
        assertEquals(OutputSizeLimiter.MAX_CEILING_CHARS, OutputSizeLimiter.ceilingForContext(Int.MAX_VALUE))
    }
}
