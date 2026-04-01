package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.context.CompressionLevel
import pl.jclab.refio.core.services.context.ToolResultCompression
import pl.jclab.refio.core.services.context.ToolResultCompressionConfig
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ToolResultSummarizer")

/**
 * Context type for summarization - determines detail level.
 */
enum class SummaryContextType {
    /** read_file - need to preserve class structures, function signatures */
    CODE_ANALYSIS,

    /** grep_search, file_search - need to preserve match context */
    SEARCH_RESULT,

    /** Other tools - standard summarization */
    GENERAL
}

/**
 * Service for summarizing tool execution results to reduce context size.
 *
 * Uses WEAK model (cheaper, faster) to generate concise summaries
 * of tool outputs. Summary is stored in chat history instead of full output.
 *
 * Key principles:
 * - Short outputs (< 500 chars) are not summarized
 * - WEAK model is used for summarization (minimize cost)
 * - Last tool result in context uses RAW output for precision
 * - Older tool results use summaries to save context
 * - Context-aware summarization for better detail preservation
 *
 * Reference: Similar to StepSummarizer but for individual tool results.
 */
class ToolResultSummarizer(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val taskRepository: pl.jclab.refio.core.db.repositories.TaskRepository
) {
    /**
     * Summarize a single tool result.
     *
     * @param toolName Name of the tool that was executed
     * @param rawOutput Raw output from the tool
     * @param taskId Task ID for metrics tracking
     * @return Concise summary of the tool result
     */
    suspend fun summarizeToolResult(
        toolName: String,
        rawOutput: String,
        taskId: String
    ): ToolResultSummary {
        // Determine context type based on tool name
        val contextType = getContextTypeForTool(toolName)

        return summarizeToolResultWithContext(toolName, rawOutput, taskId, contextType)
    }

    /**
     * Summarize a single tool result with explicit context type.
     *
     * @param toolName Name of the tool that was executed
     * @param rawOutput Raw output from the tool
     * @param taskId Task ID for metrics tracking
     * @param contextType Type of content being summarized
     * @return Concise summary of the tool result
     */
    suspend fun summarizeToolResultWithContext(
        toolName: String,
        rawOutput: String,
        taskId: String,
        contextType: SummaryContextType
    ): ToolResultSummary {
        // Check if summarization is enabled
        val summaryEnabled = configService.getTyped(ConfigKeys.TOOL_SUMMARY_ENABLED)
        if (!summaryEnabled) {
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        val minLength = configService.getTyped(ConfigKeys.TOOL_SUMMARY_MIN_LENGTH)

        if (rawOutput.length <= minLength) {
            // Too short to summarize, return as-is
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        logger.info { "[SUMMARIZER] Summarizing result for tool: $toolName (output length: ${rawOutput.length}, context: $contextType)" }

        val userPrompt = buildSummarizerPrompt(toolName, rawOutput, contextType)

        // Use WEAK model for summarization (cheaper, faster)
        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = taskId
        )

        // Context-aware max tokens for better detail preservation
        val maxTokens = when (contextType) {
            SummaryContextType.CODE_ANALYSIS -> 1000   // Keep more details for code
            SummaryContextType.SEARCH_RESULT -> 800    // Medium for search
            SummaryContextType.GENERAL -> 600          // Standard for others
        }

        // Explicitly pass thinking=false to ensure all output goes to content.
        // Models like qwen3.5 may generate thinking tokens even without think=true,
        // which wastes tokens on reasoning instead of producing summary content.
        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(LLMMessage(role = "user", content = userPrompt)),
            systemPrompt = buildSystemPrompt(toolName, contextType),
            maxTokens = maxTokens,
            temperature = 0.3,
            thinking = false,
            source = "ToolResultSummarizer",
            taskId = taskId,
            subtaskId = null
        )

        val summary = response.content.trim().ifBlank {
            logger.warn {
                "[SUMMARIZER_EMPTY] Empty summary from $provider/$model for tool=$toolName, " +
                    "finishReason=${response.finishReason}. Using deterministic compression."
            }
            compressToolResult(rawOutput, null, CompressionLevel.SUMMARY)
        }

        logger.info {
            "[SUMMARIZER] Summary generated: ${rawOutput.length} -> ${summary.length} chars, " +
            "tokens: ${response.usage.inputTokens}/${response.usage.outputTokens}, cost: $${response.cost}"
        }

        // Update task metrics
        taskRepository.incrementMetrics(
            id = taskId,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            costUsd = response.cost
        )

        return ToolResultSummary(
            summary = summary,
            wasSummarized = true,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            cost = response.cost
        )
    }

    /**
     * Build prompt for tool result summarization.
     */
    private fun buildSummarizerPrompt(toolName: String, rawOutput: String, contextType: SummaryContextType): String {
        // Truncate very long outputs
        val truncatedOutput = if (rawOutput.length > 16394) {
            rawOutput.take(16394) + "\n... (truncated raw ... ${rawOutput.length - 16394} more chars)"
        } else {
            rawOutput
        }

        val instructions = when (contextType) {
            SummaryContextType.CODE_ANALYSIS -> """
Focus on:
- Class/interface names and their relationships
- Function signatures and key parameters
- Important imports and dependencies
- Overall structure and architecture
- Omit implementation details unless critical
            """.trimIndent()

            SummaryContextType.SEARCH_RESULT -> """
Focus on:
- Number of matches found
- File paths where matches occurred
- Brief context of what was matched
- Line numbers if provided
- Group by file for clarity
            """.trimIndent()

            SummaryContextType.GENERAL -> """
Focus on:
- Key findings and results
- Important data points
- Errors or warnings if present
- Keep it concise (2-3 sentences)
            """.trimIndent()
        }

        return """
Tool: $toolName

Output to summarize:
```
$truncatedOutput
```

$instructions
""".trimIndent()
    }

    /**
     * Build system prompt for summarization based on context type.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildSystemPrompt(_toolName: String, contextType: SummaryContextType): String {
        return when (contextType) {
            SummaryContextType.CODE_ANALYSIS -> """
You are a code analysis summarizer. Create detailed summaries of code files.

Guidelines:
- Preserve class/interface structures and inheritance
- Keep function signatures with parameter types
- Mention key properties and their types
- Note important patterns or design decisions
- Use markdown formatting for clarity
- Max 3-5 sentences for files, more for complex structures
            """.trimIndent()

            SummaryContextType.SEARCH_RESULT -> """
You are a search result summarizer. Create concise summaries of search matches.

Guidelines:
- Group results by file
- Show match counts per file
- Include brief context for matches
- Preserve line numbers
- Use markdown formatting
            """.trimIndent()

            SummaryContextType.GENERAL -> """
You are a tool result summarizer. Create a concise summary of tool execution results.

Guidelines:
- Keep key findings (file paths, match counts, class names, function signatures)
- Truncate verbose content (long file contents, repetitive output)
- Preserve error messages exactly
- Max 2-3 sentences
- Use markdown formatting for clarity
            """.trimIndent()
        }
    }

    /**
     * Determine context type based on tool name.
     */
    private fun getContextTypeForTool(toolName: String): SummaryContextType {
        return when (toolName) {
            "read_file" -> SummaryContextType.CODE_ANALYSIS
            "grep_search", "file_search" -> SummaryContextType.SEARCH_RESULT
            else -> SummaryContextType.GENERAL
        }
    }

    fun compressToolResult(rawOutput: String, summary: String?, level: CompressionLevel): String {
        val summaryMax = configService.getTyped(ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH)
        val compressionConfig = ToolResultCompressionConfig(
            detailedMaxChars = (summaryMax * 3).coerceAtLeast(600),
            summaryMaxChars = summaryMax
        )
        return ToolResultCompression.compress(rawOutput, summary, level, compressionConfig)
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """
You are a tool result summarizer. Create a concise summary of tool execution results.

Guidelines:
- Keep key findings (file paths, match counts, class names, function signatures)
- Truncate verbose content (long file contents, repetitive output)
- Preserve error messages exactly
- Max 2-3 sentences
- Use markdown formatting for clarity

Examples:
- read_file: "Read Service.kt (450 lines). Contains 3 classes: Service, Validator, Client."
- grep_search: "Found 5 matches for 'Token' in 3 files: AuthService.kt (2), Validator.kt (2), Token.kt (1)"
- file_search: "Found 8 .kt files matching pattern '*Service.kt' in src/main/kotlin/"
"""
    }
}
