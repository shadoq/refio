package pl.jclab.refio.core.llm.streaming

/**
 * Hard ceiling on streamed output size. Protects against runaway generations
 * that slip past the repetition detector (e.g. a model that produces drifting-
 * but-never-terminating output) and against genuinely malformed responses.
 *
 * The limit is a blunt instrument — it does NOT try to be smart about whether
 * the content is useful. It exists so that we never sit on a stream for minutes
 * while it chews through context window budget, and so that downstream code is
 * never handed a 2MB "message" that came from a single LLM turn gone wrong.
 *
 * Set to cover the largest legitimate single response rather than the average one: 256KB of text
 * is ~64K tokens, which fits a complete single-file HTML app emitted in one shot. The previous
 * 128KB was narrower than that - a 35B-class model overshot it by 6 characters after 11 minutes of
 * generation and the whole response was discarded. Callers override it from
 * `limits.max_output_chars`.
 *
 * @param maxChars Abort threshold in characters. Default 262144 (~64K tokens).
 */
class OutputSizeLimiter(
    private val maxChars: Int = 262_144
) : StreamGuardrail {

    override val name: String = "size-limit"

    override fun onDelta(
        delta: String,
        accumulatedLength: Int,
        tail: String,
        streamStartMs: Long
    ): StreamGuardrail.Decision {
        if (accumulatedLength <= maxChars) return StreamGuardrail.Decision.Continue
        return StreamGuardrail.Decision.Abort(
            code = "OUTPUT_TOO_LARGE",
            reason = "Streamed output exceeded hard limit: $accumulatedLength > $maxChars chars"
        )
    }

    companion object {
        /**
         * Chars per token used to turn a token window into a character ceiling.
         *
         * Deliberately 4 and NOT [pl.jclab.refio.core.services.PromptTokenEstimator.CHARS_PER_TOKEN_BASE]
         * (3.5). The two serve opposite purposes and must round in opposite directions: the
         * estimator predicts tokens from text and must never UNDERstate them, so it assumes few
         * chars per token; this is a ceiling that must never cut off legitimate output, so it
         * assumes many. Indented HTML and code routinely exceed 4 chars/token. Do not "unify"
         * these two constants - the divergence is the point.
         */
        const val CHARS_PER_TOKEN_CEILING = 4

        /** Never derive a ceiling below this: it is the historical fixed limit. */
        const val MIN_CEILING_CHARS = 131_072

        /** Never derive a ceiling above this: past here a single "message" is pathological. */
        const val MAX_CEILING_CHARS = 8_388_608

        /**
         * Character ceiling for a model whose context window is [maxContextTokens] tokens.
         *
         * A response can never be longer than the window it is generated into, so the window is the
         * natural bound: a 64K-token model gets 256KB, a 128K-token one 512KB. A fixed limit cannot
         * do this - the old 131072 was ~2x too small for a 64K model asked to emit a whole
         * single-file app, which is how an 11-minute generation was discarded 6 characters over the
         * line.
         */
        fun ceilingForContext(maxContextTokens: Int): Int =
            (maxContextTokens.toLong() * CHARS_PER_TOKEN_CEILING)
                .coerceIn(MIN_CEILING_CHARS.toLong(), MAX_CEILING_CHARS.toLong())
                .toInt()
    }
}
