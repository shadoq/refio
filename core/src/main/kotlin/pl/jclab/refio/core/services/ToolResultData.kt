package pl.jclab.refio.core.services

/**
 * Tool result data for summarization.
 *
 * Internal data structure for tool results with summarization information.
 * Used by AgentTurnLoop to pass summarized tool results to the database layer.
 *
 * @property toolCallId ID of the tool call this result is for (LLM-side identifier)
 * @property subtaskId Id of the persisted Subtask row that produced this result. This is the
 *   canonical identifier shared by RECENT_WORK, WORKING_MEMORY, and the MESSAGES tool header so
 *   the model sees one stable id per tool execution. Nullable for the rare paths that build
 *   ToolResultData without a backing subtask row (legacy/synthetic results).
 * @property content Summarized content (or RAW if not summarized)
 * @property isSummarized Whether content is a summary of the original output
 * @property rawOutput Full original RAW output (for UI or last tool in context). May include
 *   appended in-band hints/nudges that the model should see — do NOT use it for loop detection.
 * @property loopSignature The tool's own output BEFORE any appended hints/nudges, used solely as
 *   the byte-identical hash input for [pl.jclab.refio.core.services.turn.TurnGuardrails.TurnRepetitionTracker].
 *   Kept separate from [content]/[rawOutput] because the progressive "[⚠ possible loop]" nudge embeds
 *   a varying subtask UUID at the tail; hashing that would make every repeated read look "different"
 *   and silently defeat the repeated-call hard-abort. Null when there is nothing to track.
 * @property metadata Optional JSON metadata from tool result (e.g., file path, line count)
 */
data class ToolResultData(
    val toolCallId: String,
    val subtaskId: String? = null,
    val content: String,          // Summary (or RAW if not summarized)
    val isSummarized: Boolean,    // Whether content is a summary
    val rawOutput: String? = null, // Full RAW output (optional, for UI)
    val loopSignature: String? = null, // Raw output sans hints/nudges, for loop-detection hashing only
    val metadata: String? = null, // JSON metadata from tool result (e.g., path, line_count)
    // True when a WRITE tool reported changeSummary.noop — the LLM-generated content was identical
    // to the existing file, so zero bytes changed. Lifted into a structured flag (the same way
    // subTokens* are pulled from tool metadata below) so the turn loop can treat a futile edit as
    // "no progress" without re-parsing the metadata JSON. See TurnGuardrails.TurnRepetitionTracker.
    val noop: Boolean = false,
    // Sub-LLM usage from tools that internally call an LLM (advance_code_editing,
    // multi_line_editor). Persisted on the TOOL ChatMessage so SessionStatsBar
    // and per-message stats include these tokens — they are real LLM cost.
    val subTokensIn: Int? = null,
    val subTokensOut: Int? = null,
    val subCost: Double? = null
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
