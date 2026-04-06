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
    /** read_file on a code file - need to preserve class structures, function signatures */
    CODE_ANALYSIS,

    /** grep_search, file_search - need to preserve match context */
    SEARCH_RESULT,

    /**
     * read_file on a data file (.json, .csv, .txt, .log, .md). Code-style summarization
     * is destructive here ("no class definitions found" is useless prose). Instead we
     * keep structure (item count, sample first/last) and never paraphrase numbers.
     */
    DATA_FILE,

    /**
     * run_code / run_terminal_command — deterministic program output. Preserve every
     * number, identifier, and error message verbatim; never collapse lists; keep
     * head + tail when too long. The summarizer must NOT rephrase.
     */
    RAW_OUTPUT,

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
     * @param toolArgs Optional tool arguments (used to refine context type — e.g.
     *                 read_file on `.json` → DATA_FILE, on `.kt` → CODE_ANALYSIS)
     * @return Concise summary of the tool result
     */
    suspend fun summarizeToolResult(
        toolName: String,
        rawOutput: String,
        taskId: String,
        toolArgs: Map<String, Any?>? = null
    ): ToolResultSummary {
        val contextType = getContextTypeForTool(toolName, toolArgs)
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

        // Context-aware max tokens for better detail preservation.
        // Higher limits reduce finishReason=length failures with weaker/larger models
        // that tend to be verbose (e.g. qwen3.5, glm-5).
        val maxTokens = when (contextType) {
            SummaryContextType.CODE_ANALYSIS -> 4096   // Keep more details for code
            SummaryContextType.DATA_FILE -> 3072       // Preserve structure + samples
            SummaryContextType.RAW_OUTPUT -> 4096      // Preserve numbers/IDs/errors verbatim
            SummaryContextType.SEARCH_RESULT -> 2048   // Medium for search
            SummaryContextType.GENERAL -> 1536         // Standard for others
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

            SummaryContextType.DATA_FILE -> """
This is a DATA file (JSON/CSV/TXT/log/markdown), NOT source code.
DO NOT mention "no class definitions", "no functions", "no imports" — that is wrong context.

Instead, describe:
- What type of data the file holds (array of strings, table of records, log lines, prose, etc.)
- The total number of items / rows / lines if visible
- The structure of one item (field names for JSON, columns for CSV, log format, etc.)
- A literal sample: first 1–3 items AND last 1–3 items, copied verbatim
- Any obvious pattern (sorted? all numeric? all matching a regex?)
- Any error markers, status fields, or anomalies

Never paraphrase numbers or identifiers — copy them exactly.
            """.trimIndent()

            SummaryContextType.RAW_OUTPUT -> """
This is the raw stdout of an executed program (run_code / run_terminal_command).
Treat it as deterministic data the model needs to read literally — DO NOT rephrase.

Preserve verbatim:
- Every number (counts, IDs, totals, percentages, sizes)
- Every error message and stack-trace line, exactly as printed
- Every file path, URL, exit code, and HTTP status
- The first 5 and last 5 non-empty output lines, copied as-is

If the output is a list (e.g. lines like "0001: ..."), keep AT LEAST the first 10 items
and the total count. Never collapse a list to "many items" or "various IDs".

If the output contains an explicit error / API response (e.g. "HTTP Error 400",
"code: -970", "Traceback", "SyntaxError"), reproduce that block IN FULL.
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

            SummaryContextType.DATA_FILE -> """
You are a DATA-file summarizer. The file you are looking at is structured data
(JSON, CSV, log, markdown, plain text), NOT source code.

Strict rules:
- NEVER write "no class definitions", "no interfaces", "no functions",
  "no imports", or any code-architecture commentary. That is wrong context.
- NEVER paraphrase numeric values or identifiers — copy them character-for-character.
- ALWAYS include a literal sample of the first and last entries.
- ALWAYS include a count (rows / items / lines) when visible.
- Treat the content as data the user will need to retrieve later.
            """.trimIndent()

            SummaryContextType.RAW_OUTPUT -> """
You are a RAW-output summarizer for program stdout (Python, JavaScript, shell).
The downstream model needs to read this output LITERALLY to make a decision —
your job is to compress safely, not to interpret.

Strict rules:
- NEVER rewrite, rephrase, or "improve" the wording of any line.
- NEVER replace specific numbers with words like "several", "many", "some".
- ALWAYS copy errors, exceptions, stack traces, and HTTP error bodies VERBATIM.
- ALWAYS keep the first ~10 and last ~5 non-empty output lines unchanged.
- If the output contains a list of identifiers, preserve identifiers exactly
  and report the total count.
- Use a markdown code block (```) to wrap any preserved literal output.
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
     * Determine context type based on tool name + arguments.
     *
     * Key distinctions:
     * - read_file on a code file → CODE_ANALYSIS (preserve classes/functions)
     * - read_file on a data file (.json/.csv/.txt/.log/.md) → DATA_FILE
     *   (preserve structure, never rephrase numbers/IDs)
     * - run_code / run_terminal_command → RAW_OUTPUT (preserve every number,
     *   ID, error verbatim — these are deterministic program outputs that the
     *   model needs to read literally)
     */
    private fun getContextTypeForTool(
        toolName: String,
        toolArgs: Map<String, Any?>? = null
    ): SummaryContextType {
        return when (toolName) {
            "read_file" -> {
                val path = (toolArgs?.get("path") as? String).orEmpty()
                if (isDataFilePath(path)) SummaryContextType.DATA_FILE
                else SummaryContextType.CODE_ANALYSIS
            }
            "grep_search", "file_search" -> SummaryContextType.SEARCH_RESULT
            "run_code", "run_terminal_command" -> SummaryContextType.RAW_OUTPUT
            else -> SummaryContextType.GENERAL
        }
    }

    /**
     * Heuristic: paths whose extension indicates structured/data content rather
     * than source code. Code-style summarization is destructive for these.
     */
    private fun isDataFilePath(path: String): Boolean {
        if (path.isBlank()) return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in DATA_FILE_EXTENSIONS
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
        /**
         * File extensions treated as DATA_FILE rather than CODE_ANALYSIS.
         * These are structured/text files where code-style summarization
         * ("no classes found, no imports detected") is actively misleading.
         */
        val DATA_FILE_EXTENSIONS = setOf(
            "json", "csv", "tsv", "txt", "log", "md", "markdown",
            "yaml", "yml", "xml", "html", "htm", "ini", "conf", "cfg",
            "properties", "toml", "env", "sql"
        )

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
