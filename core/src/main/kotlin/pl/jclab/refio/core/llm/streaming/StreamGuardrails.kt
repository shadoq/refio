package pl.jclab.refio.core.llm.streaming

/**
 * Composition of zero or more [StreamGuardrail]s that are checked in order
 * on each streaming delta. The first guardrail to return [StreamGuardrail.Decision.Abort]
 * wins — remaining guardrails are not consulted.
 *
 * Pass an instance into the streaming pipeline (see `LLMClient.complete()`)
 * to inspect streamed content as it arrives and abort the request if any
 * guardrail fires.
 *
 * ## Tail handling
 *
 * [StreamGuardrails] owns the accumulated content buffer and the tail slice
 * passed to each guardrail. This way every guardrail sees the same tail and
 * the string slicing only happens once per delta, not once per guardrail.
 *
 * The tail size is `max(tailHint, largestBlockNeededByAnyGuardrail)`; for the
 * default set that comes out to 4096 chars — more than enough for repetition
 * detection, cheap enough to not matter on the hot path.
 *
 * ## Not thread-safe
 *
 * Instantiate a fresh [StreamGuardrails] per LLM request. Each instance holds
 * request-scoped state (accumulated content, per-guardrail counters, stream
 * start timestamp). Sharing one across concurrent requests WILL corrupt that state.
 */
class StreamGuardrails(
    private val guardrails: List<StreamGuardrail>,
    private val tailSize: Int = 4096,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val accumulated = StringBuilder()
    private val streamStartMs: Long = clock()

    /**
     * Feed the next streaming delta to all guardrails. Returns the first
     * [StreamGuardrail.Decision.Abort] produced by any guardrail, or
     * [StreamGuardrail.Decision.Continue] if all guardrails passed.
     *
     * Empty deltas are a no-op — [onDelta] returns immediately without
     * touching state or invoking guardrails.
     */
    fun check(delta: String): StreamGuardrail.Decision {
        if (delta.isEmpty()) return StreamGuardrail.Decision.Continue
        accumulated.append(delta)

        val tail = if (accumulated.length <= tailSize) {
            accumulated.toString()
        } else {
            accumulated.substring(accumulated.length - tailSize)
        }
        val accLen = accumulated.length

        for (g in guardrails) {
            val decision = g.onDelta(delta, accLen, tail, streamStartMs)
            if (decision is StreamGuardrail.Decision.Abort) {
                return decision
            }
        }
        return StreamGuardrail.Decision.Continue
    }

    /**
     * Accumulated content captured from the stream so far. Used by the caller
     * when building a [StreamAbortedException] so debug logs / error reports
     * can include what was actually generated before the abort.
     */
    fun accumulatedContent(): String = accumulated.toString()

    /** Timestamp when the first delta was received (stream start). */
    fun streamStartMillis(): Long = streamStartMs

    /** Names of the active guardrails, for log messages. */
    fun activeGuardrailNames(): List<String> = guardrails.map { it.name }

    companion object {
        /**
         * Default guardrail set used by `LLMClient.complete()`.
         *
         * Current composition:
         * - [RepetitionDetector] with conservative defaults (fires on 4×
         *   consecutive block repetitions, any block size 50–800 chars).
         * - [OutputSizeLimiter] at 32 KB — prevents runaway continuations.
         * - [WallClockDeadline] at 180 s — independent of Ktor's request
         *   timeout, so a stuck stream unwinds cleanly without waiting for
         *   the full provider timeout.
         *
         * All thresholds are deliberately loose — the goal is "catch obvious
         * pathology without false positives on healthy long-running streams".
         */
        fun defaults(wallClockDeadlineMs: Long = 180_000): StreamGuardrails {
            return StreamGuardrails(
                guardrails = listOf(
                    RepetitionDetector(),
                    OutputSizeLimiter(),
                    WallClockDeadline(deadlineMs = wallClockDeadlineMs)
                )
            )
        }

        /** Disabled/no-op guardrails — used in tests and for streams where we want zero guarding. */
        fun none(): StreamGuardrails = StreamGuardrails(guardrails = emptyList())
    }
}
