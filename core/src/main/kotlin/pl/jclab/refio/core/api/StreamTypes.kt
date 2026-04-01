package pl.jclab.refio.core.api

/**
 * Callback-based streaming types for unified API (RFC 0032).
 *
 * Design Philosophy:
 * - Streaming is a PRESENTATION mechanism, not an API change
 * - Methods always return the same type (ChatResponse, PlanningResponse, etc.)
 * - Optional callback for UI updates during generation
 * - No Flow in public API - callbacks are simpler and more intuitive
 */

/**
 * Callback invoked with each chunk from LLM.
 * Used to update UI during streaming.
 */
typealias StreamCallback = (StreamChunk) -> Unit

/**
 * Chunk of data from streaming LLM response.
 *
 * @param delta New content (incremental)
 * @param accumulated Full content so far
 * @param isComplete True if this is the final chunk
 * @param source Origin of the chunk: "chat", "planning", "step_planner", "summarizer"
 * @param usage Token usage (only present in final chunk)
 * @param cost Cost in USD (only present in final chunk)
 */
data class StreamChunk(
    val delta: String,
    val accumulated: String,
    val isComplete: Boolean,
    val source: String? = null,
    val usage: pl.jclab.refio.core.llm.LLMUsage? = null,
    val cost: Double = 0.0
)
