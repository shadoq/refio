package pl.jclab.refio.core.llm.streaming

/**
 * Provider-agnostic guard that inspects a streaming LLM response in real time
 * and decides whether the stream should continue or be aborted.
 *
 * Guardrails are request-scoped — instantiate a fresh one per LLM call; they are
 * NOT thread-safe and carry accumulating state (counters, timestamps, hashes).
 *
 * Integration point: `LLMClient.complete()` wraps the user's `onStreamChunk`
 * callback and invokes `onDelta(...)` after each new content delta. If any
 * guardrail returns [Decision.Abort], the callback throws a [StreamAbortedException]
 * which propagates through the adapter (as a [kotlinx.coroutines.CancellationException])
 * and unwinds the stream.
 *
 * To add a new guardrail:
 * 1. Implement this interface in `core/llm/streaming/`.
 * 2. Register it in [StreamGuardrails.defaults] (or a task-specific factory).
 * 3. Unit-test it with deterministic input (no real LLM calls).
 */
interface StreamGuardrail {
    /**
     * Called after each non-empty content delta has been appended to the
     * accumulated content buffer.
     *
     * Implementations must be cheap — this runs on the hot streaming path.
     * Expensive checks (regex, hashing) should be gated by a chunk counter
     * so they only run every N deltas.
     *
     * @param delta The newly-received content fragment (never empty).
     * @param accumulatedLength Total length of the accumulated content so far.
     * @param tail Last ~N chars of the accumulated content (N is guardrail-defined,
     *             typically 400-2000). Using a pre-computed tail keeps the hot path
     *             fast — don't materialize the full buffer unless you must.
     * @param streamStartMs `System.currentTimeMillis()` recorded when the stream began.
     */
    fun onDelta(
        delta: String,
        accumulatedLength: Int,
        tail: String,
        streamStartMs: Long
    ): Decision

    /**
     * Result of a single [onDelta] check.
     */
    sealed class Decision {
        /** Continue streaming — nothing suspicious so far. */
        object Continue : Decision()

        /**
         * Abort the stream immediately.
         *
         * @param code Short machine-readable identifier — used in logs and metrics.
         * @param reason Human-readable diagnostic — included in the thrown exception.
         */
        data class Abort(val code: String, val reason: String) : Decision()
    }

    /**
     * Short name for logs and diagnostics (e.g. `"repetition"`, `"size-limit"`).
     */
    val name: String
}
