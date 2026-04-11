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
 * Set conservatively: 32KB of text is ~8000 tokens, which is larger than
 * any legitimate single agent turn response should be.
 *
 * @param maxChars Abort threshold in characters. Default 32768 (~8K tokens).
 */
class OutputSizeLimiter(
    private val maxChars: Int = 32_768
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
