package pl.jclab.refio.core.llm.streaming

/**
 * Detects "stuck in a loop" streaming LLM output where a weak model generates
 * the same block of text over and over again until the stream times out.
 *
 * Observed failure mode (qwen3.5:35b + AiDevs domatowo task, 2026-04-11):
 * the model produced the same ~500-char Python-code block 40+ times back-to-back,
 * burning 522 seconds of wall-clock time before Ktor's HTTP timeout killed it.
 * Nothing in the logs showed the content — the only trace was the timeout error.
 * This guardrail catches that pattern within a second or two of it starting.
 *
 * ## Algorithm
 *
 * After every [checkEveryNDeltas] content deltas, scan the tail of the accumulated
 * content for a **periodic suffix**: some period `k` such that the last
 * `k * repeatThreshold` characters consist of `repeatThreshold` identical back-to-back
 * copies of the same `k`-char block. The scan tries every integer period from
 * [minPeriod] to [maxPeriod] — this catches repetitions whose block boundaries do
 * NOT align to round numbers, which is the common case (a streamed Python block
 * is rarely a multiple of 50/100/200 chars).
 *
 * The "back-to-back at the tail" shape is specific enough to avoid false positives
 * on normal output. Natural language and code rarely contain four exact-byte copies
 * of the same block immediately after each other.
 *
 * ## Cost
 *
 * Gated by [checkEveryNDeltas] — the scan only runs every N deltas, so the hot path
 * does nothing except increment a counter on most calls. When the scan runs, the
 * cost is O(maxPeriod × repeatThreshold) byte compares — for defaults that is
 * ≈ 1500 × 4 ≈ 6K compares per period candidate, roughly 1M operations per full
 * scan. Amortized over 20 deltas that is ≈ 50K ops per delta — well under a ms.
 * The scan short-circuits the moment a periodic suffix is found, so common cases
 * are cheaper than the worst case.
 *
 * @param checkEveryNDeltas How often to run the actual period scan. Default 20 —
 *                          means every 20th non-empty delta runs the heuristic.
 * @param repeatThreshold Number of consecutive back-to-back block repetitions
 *                        required to fire the abort. Default 4 — three repetitions
 *                        can happen in legitimate lists/code; four almost never.
 * @param minPeriod Smallest period length tested. Default 20 — periods shorter than
 *                  this tend to be legitimate ASCII patterns (separators, indent).
 * @param maxPeriod Largest period length tested. Default 1500 — covers large
 *                  hallucinated code blocks while keeping scan cost bounded.
 */
class RepetitionDetector(
    private val checkEveryNDeltas: Int = 20,
    private val repeatThreshold: Int = 4,
    private val minPeriod: Int = 20,
    private val maxPeriod: Int = 1500
) : StreamGuardrail {

    override val name: String = "repetition"

    private var deltaCount: Int = 0
    private var lastCheckedLength: Int = -1

    override fun onDelta(
        delta: String,
        accumulatedLength: Int,
        tail: String,
        streamStartMs: Long
    ): StreamGuardrail.Decision {
        deltaCount++
        if (deltaCount % checkEveryNDeltas != 0) return StreamGuardrail.Decision.Continue

        // Skip if no new content has been added since the last check. This shouldn't
        // happen in practice (callers only invoke us for non-empty deltas) but guards
        // against degenerate flows where the tail is identical call-over-call.
        if (accumulatedLength == lastCheckedLength) return StreamGuardrail.Decision.Continue
        lastCheckedLength = accumulatedLength

        // Minimum tail to even try the smallest period.
        val minNeeded = minPeriod * repeatThreshold
        if (tail.length < minNeeded) return StreamGuardrail.Decision.Continue

        val effectiveMaxPeriod = minOf(maxPeriod, tail.length / repeatThreshold)
        if (effectiveMaxPeriod < minPeriod) return StreamGuardrail.Decision.Continue

        // Scan candidate periods from smallest to largest. We want the SMALLEST
        // period that exhibits a periodic suffix, because larger periods have a
        // higher chance of being accidental (e.g. a list of similar items).
        for (k in minPeriod..effectiveMaxPeriod) {
            if (isPeriodicSuffix(tail, k, repeatThreshold)) {
                // A periodic suffix dominated by whitespace + a single fill character is
                // structural / diagrammatic data, not a model generation loop:
                //   - ASCII rules / separators / box-drawing lines (────, ====, ....)
                //   - tile-map / level-data arrays of mostly-empty rows
                //     ("....................", "....................", …)
                //   - heavily-indented nested structures
                // These are legitimately repetitive and were tripping a threshold-4 abort
                // (observed: qwen3.5:122b generating a C64 game's LEVELS array — guardrail
                // killed a valid stream). A genuine degeneration loop repeats high-entropy
                // content (code, prose) with no single dominant fill character, so it stays
                // below [STRUCTURAL_FILL_FRACTION] and still aborts.
                if (isLowInformationRun(tail, k * repeatThreshold)) continue

                val preview = tail.substring(tail.length - k)
                    .replace("\n", "\\n")
                    .take(120)
                return StreamGuardrail.Decision.Abort(
                    code = "REPETITION_LOOP",
                    reason = "Detected $repeatThreshold back-to-back repetitions of a " +
                        "${k}-char block at end of stream (accumulated=${accumulatedLength} chars). " +
                        "Block preview: \"$preview\""
                )
            }
        }

        return StreamGuardrail.Decision.Continue
    }

    /**
     * Returns true if the last `k * copies` chars of [s] consist of [copies]
     * back-to-back copies of the same `k`-char block.
     *
     * Uses [String.regionMatches] so no substrings are allocated.
     */
    private fun isPeriodicSuffix(s: String, k: Int, copies: Int): Boolean {
        val needed = k * copies
        if (s.length < needed) return false
        // The tail block against which all preceding copies are compared.
        val blockStart = s.length - k
        // Check copies 2..copies (first copy is the block itself, trivially equal).
        for (i in 2..copies) {
            val copyStart = s.length - i * k
            if (!s.regionMatches(copyStart, s, blockStart, k)) return false
        }
        return true
    }

    /**
     * Returns true if the last [len] chars of [s] are "low information" — i.e. dominated by
     * whitespace plus a single repeated fill character. This covers horizontal rules /
     * separators (100% one char, the original case), tile-map / level-data rows (mostly dots
     * + indentation), and heavily-indented structures. Such content is legitimately repetitive
     * and must NOT be mistaken for a generation loop.
     *
     * Genuine degeneration loops repeat high-entropy content (code, prose) where no single
     * non-whitespace character dominates, so they stay below [STRUCTURAL_FILL_FRACTION] and
     * still abort. Subsumes the previous strict single-character check (a 100% single-char run
     * scores 1.0). The narrow multi-char-loop guard (e.g. "ababab…", ~0.5) still fires.
     */
    private fun isLowInformationRun(s: String, len: Int): Boolean {
        if (len <= 0 || len > s.length) return false
        val start = s.length - len
        var whitespace = 0
        val counts = HashMap<Char, Int>()
        for (idx in start until s.length) {
            val c = s[idx]
            if (c.isWhitespace()) whitespace++ else counts[c] = (counts[c] ?: 0) + 1
        }
        val topNonWhitespace = counts.values.maxOrNull() ?: 0
        return (whitespace + topNonWhitespace).toDouble() / len >= STRUCTURAL_FILL_FRACTION
    }

    /** Reset accumulated state. Used by tests; not normally needed in production. */
    fun reset() {
        deltaCount = 0
        lastCheckedLength = -1
    }

    companion object {
        /**
         * A repeated region whose (whitespace + single most-common non-whitespace char) share
         * is at least this fraction is treated as structural/diagrammatic data and exempted
         * from the abort. Tuned so tile-map level rows (~0.74-0.96 dots+indent) and ASCII rules
         * (1.0) are exempt, while a tight multi-char loop like "ababab…" (~0.5) still aborts.
         */
        const val STRUCTURAL_FILL_FRACTION = 0.66
    }
}
