package pl.jclab.refio.core.llm.streaming

import kotlinx.coroutines.CancellationException

/**
 * Thrown when a [StreamGuardrail] decides to abort an in-flight LLM stream.
 *
 * Extends [CancellationException] so that well-behaved coroutine code re-throws
 * it instead of treating it as a generic failure. Adapters MUST let it propagate
 * out of their per-chunk try/catch blocks — see `catch (e: CancellationException) { throw e }`
 * pattern used across all adapters in `core/llm/adapters/`.
 *
 * Carries the partial content already accumulated at the moment of abort so that
 * callers can log or persist it for debugging.
 *
 * @param code Machine-readable abort reason (e.g. `REPETITION_LOOP`, `OUTPUT_TOO_LARGE`, `WALL_CLOCK_DEADLINE`)
 * @param reason Human-readable explanation — goes into logs and error messages
 * @param partialContent Everything streamed BEFORE the abort fired. Can be very large; don't put in context.
 */
class StreamAbortedException(
    val code: String,
    val reason: String,
    val partialContent: String
) : CancellationException("Stream aborted by guardrail [$code]: $reason")
