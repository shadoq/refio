package pl.jclab.refio.core.services

/**
 * Tool result data for summarization.
 *
 * Internal data structure for tool results with summarization information.
 * Used by AgentTurnLoop to pass summarized tool results to the database layer.
 *
 * @property toolCallId ID of the tool call this result is for
 * @property content Summarized content (or RAW if not summarized)
 * @property isSummarized Whether content is a summary of the original output
 * @property rawOutput Full original RAW output (for UI or last tool in context)
 * @property metadata Optional JSON metadata from tool result (e.g., file path, line count)
 */
data class ToolResultData(
    val toolCallId: String,
    val content: String,          // Summary (or RAW if not summarized)
    val isSummarized: Boolean,    // Whether content is a summary
    val rawOutput: String? = null, // Full RAW output (optional, for UI)
    val metadata: String? = null  // JSON metadata from tool result (e.g., path, line_count)
)

/**
 * Tool result summary from ToolResultSummarizer.
 *
 * Result of summarizing a tool execution output.
 *
 * @property summary The summarized content
 * @property wasSummarized Whether summarization was performed (false for short outputs)
 * @property tokensIn Input tokens used for summarization
 * @property tokensOut Output tokens generated
 * @property cost Cost of summarization
 */
data class ToolResultSummary(
    val summary: String,
    val wasSummarized: Boolean,
    val tokensIn: Int,
    val tokensOut: Int,
    val cost: Double
)
