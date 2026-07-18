package pl.jclab.refio.core.api

/**
 * Callback-based streaming types for unified API.
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
    val cost: Double = 0.0,
    /**
     * Present only while the model is streaming a NATIVE tool call's arguments.
     * On such a chunk [delta] is typically empty — the model is emitting a structured tool call,
     * not text. Consumers use this to render a progressive "building <tool>(<args>)" indicator.
     */
    val toolCallProgress: ToolCallProgress? = null
)

/**
 * Snapshot of a native tool call being assembled during streaming.
 *
 * Emitted incrementally as the model streams a tool call's arguments. [accumulatedArguments] is the
 * full raw arguments JSON received so far for this call (often still a prefix mid-stream), so a
 * consumer can render the latest state without keeping its own per-index buffer.
 *
 * @param index Position in the parallel tool_calls array (identifies the call across chunks).
 * @param name Tool name once known (null on the very first chunks of some providers).
 * @param accumulatedArguments Raw arguments JSON accumulated so far for this call.
 */
data class ToolCallProgress(
    val index: Int,
    val name: String?,
    val accumulatedArguments: String
)
