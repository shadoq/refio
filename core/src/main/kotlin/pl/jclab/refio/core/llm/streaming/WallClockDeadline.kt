package pl.jclab.refio.core.llm.streaming

/**
 * Independent wall-clock deadline for streaming LLM responses.
 *
 * The Ktor HTTP client has its own request timeout, but in practice that
 * timeout is set generously (often 300–520 s) because non-streaming calls
 * to slow local models can legitimately take a long time. For streaming
 * calls the semantics are different: a healthy stream delivers chunks
 * continuously, and "it's been 180 seconds since the stream started" is
 * nearly always a sign of something wrong even if chunks are still trickling.
 *
 * This guardrail fires INSIDE the stream loop (before the Ktor timeout
 * would), so the abort path is clean: we log partial content, throw a
 * [StreamAbortedException] with `code = WALL_CLOCK_DEADLINE`, and unwind
 * the adapter via `CancellationException` propagation. The alternative
 * (waiting for Ktor's `HttpRequestTimeoutException`) is noisy and blocks
 * the caller for the full provider timeout.
 *
 * @param deadlineMs Maximum permitted stream duration in milliseconds,
 *                   measured from the timestamp passed into [onDelta] as
 *                   `streamStartMs`. Default 180_000 (3 minutes).
 * @param clock Source of "now" in milliseconds. Injected for testability.
 */
class WallClockDeadline(
    private val deadlineMs: Long = 180_000,
    private val clock: () -> Long = System::currentTimeMillis
) : StreamGuardrail {

    override val name: String = "wall-clock-deadline"

    override fun onDelta(
        delta: String,
        accumulatedLength: Int,
        tail: String,
        streamStartMs: Long
    ): StreamGuardrail.Decision {
        val elapsed = clock() - streamStartMs
        if (elapsed < deadlineMs) return StreamGuardrail.Decision.Continue
        return StreamGuardrail.Decision.Abort(
            code = "WALL_CLOCK_DEADLINE",
            reason = "Stream exceeded wall-clock deadline: ${elapsed}ms > ${deadlineMs}ms " +
                "(accumulated=${accumulatedLength} chars)"
        )
    }
}
