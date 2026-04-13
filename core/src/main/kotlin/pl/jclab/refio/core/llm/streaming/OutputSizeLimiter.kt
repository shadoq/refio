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
 * Set conservatively: 128KB of text is ~32K tokens, which covers
 * legitimate large outputs such as single-file HTML apps, full file
 * regenerations, and detailed analysis reports.
 *
 * @param maxChars Abort threshold in characters. Default 131072 (~32K tokens).
 */
class OutputSizeLimiter(
    private val maxChars: Int = 131_072
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
}
