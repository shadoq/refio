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
     * run_code / run_terminal_command / http_request — deterministic program or
     * protocol output. Preserve every number, identifier, status code, and error
     * message verbatim; never collapse lists; keep head + tail when too long.
     * The summarizer must NOT rephrase.
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

        val configMinLength = configService.getTyped(ConfigKeys.TOOL_SUMMARY_MIN_LENGTH)
        // Hard floor: never summarize below this regardless of config. The summarizer
        // LLM call costs more (latency + WEAK-model tokens) than the saved bytes for
        // outputs of a few hundred chars. Applied to ALL context types.
        val effectiveMinLength = maxOf(configMinLength, GLOBAL_MIN_SKIP_THRESHOLD)

        if (rawOutput.length <= effectiveMinLength) {
            logger.info {
                "[SUMMARIZER_SKIP] Tool $toolName output (${rawOutput.length} chars) below " +
                    "effective min ($effectiveMinLength), keeping raw."
            }
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        // Skip eager summarization for read_file outputs under 512KB.
        // These are deferred to RECENT_WORK budget-driven compression which
        // can make better decisions about how much to keep based on available
        // context window. See design spec: 2026-04-12-agent-execution-reliability.
        if (toolName == "read_file" && rawOutput.length < 524_288) {
            logger.info {
                "[SUMMARIZER_SKIP] read_file output (${rawOutput.length} chars) below " +
                    "lazy-compression threshold (512KB), deferring to RECENT_WORK budget."
            }
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        // Higher skip threshold for RAW_OUTPUT (run_code, run_terminal_command, http_request).
        // These tool outputs typically contain literal data the model needs to read
        // verbatim (IDs, counts, error bodies, HTTP status codes, response JSON). Below
        // 4KB the cost-benefit is clearly negative — observed production traces show
        // 506-char outputs triggering 80s+ WEAK calls for 8 chars of "compression"
        // while paraphrasing critical IDs.
        if (contextType == SummaryContextType.RAW_OUTPUT && rawOutput.length < RAW_OUTPUT_SKIP_THRESHOLD) {
            logger.info {
                "[SUMMARIZER_SKIP] Tool $toolName output (${rawOutput.length} chars) below " +
                    "RAW_OUTPUT_SKIP_THRESHOLD ($RAW_OUTPUT_SKIP_THRESHOLD), keeping raw."
            }
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        // Higher skip threshold for DATA_FILE (read_file on .md, .json, .csv, etc.).
        // The WEAK model summarizer is destructive for structured data under 4KB:
        // it paraphrases numbers, drops samples, and produces "no class definitions"
        // commentary. Observed in documentation-engineer sessions where 19 extra LLM
        // calls were made for small doc files, each taking 20-35s on Ollama.
        if (contextType == SummaryContextType.DATA_FILE && rawOutput.length < DATA_FILE_SKIP_THRESHOLD) {
            logger.info {
                "[SUMMARIZER_SKIP] Tool $toolName output (${rawOutput.length} chars) below " +
                    "DATA_FILE_SKIP_THRESHOLD ($DATA_FILE_SKIP_THRESHOLD), keeping raw."
            }
            return ToolResultSummary(
                summary = rawOutput,
                wasSummarized = false,
                tokensIn = 0,
                tokensOut = 0,
                cost = 0.0
            )
        }

        // Above the skip threshold, RAW_OUTPUT (shell / script stdout) is summarized
        // DETERMINISTICALLY — head + tail with the TAIL placed FIRST. The LLM summarizer
        // is bypassed entirely for this context type because:
        //   1) Trailing data is critical (exit codes, API verify responses, error blocks,
        //      stack traces) — paraphrasing it via WEAK model loses information that
        //      gates the agent's next decision.
        //   2) Downstream conversation builders may further compact tool result content
        //      to a fixed prefix; placing the tail first guarantees critical trailing
        //      data survives any prefix-based truncation.
        //   3) Skipping the WEAK call saves ~5–25s and ~$0.005 per shell invocation.
        if (contextType == SummaryContextType.RAW_OUTPUT) {
            val deterministic = buildRawOutputTailFirstSummary(toolName, rawOutput)
            logger.info {
                "[SUMMARIZER_DETERMINISTIC] RAW_OUTPUT for tool $toolName: " +
                    "${rawOutput.length} -> ${deterministic.length} chars (head+tail, no LLM call)"
            }
            return ToolResultSummary(
                summary = deterministic,
                wasSummarized = true,
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
            SummaryContextType.CODE_ANALYSIS -> 8192   // Keep more details for code
            SummaryContextType.DATA_FILE -> 4096       // Preserve structure + samples
            SummaryContextType.RAW_OUTPUT -> 8192      // Preserve numbers/IDs/errors verbatim
            SummaryContextType.SEARCH_RESULT -> 4096   // Medium for search
            SummaryContextType.GENERAL -> 4096         // Standard for others
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

        // Task metrics auto-incremented inside LLMClient.complete() via taskId
        // passed in the call above. No manual increment here.

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
        // For oversized outputs we used to take only the head and drop everything after
        // 16 KB. That made the "preserve last N lines" instruction in the RAW_OUTPUT
        // prompt impossible to follow — the tail simply never reached the summarizer.
        // Concrete failure mode: an 80 KB run_terminal_command output where the last
        // ~2 KB contained the /verify API response with the flag was silently dropped,
        // and downstream the agent went into a re-read loop because the result it
        // needed was nowhere to be seen.
        //
        // Keep a head + tail slice instead. Tail is preserved verbatim so trailing
        // exit codes, HTTP responses, error blocks, and stack traces always survive.
        val truncatedOutput = if (rawOutput.length <= SUMMARIZER_INPUT_BUDGET) {
            rawOutput
        } else {
            val sliceSize = (SUMMARIZER_INPUT_BUDGET - 200) / 2
            val omitted = rawOutput.length - 2 * sliceSize
            val head = rawOutput.take(sliceSize)
            val tail = rawOutput.takeLast(sliceSize)
            "$head\n... [middle truncated: $omitted chars omitted; end of output preserved below] ...\n$tail"
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
     * - run_code / run_terminal_command / http_request → RAW_OUTPUT (preserve every
     *   number, ID, status code, error verbatim — these are deterministic program or
     *   protocol outputs that the model needs to read literally)
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
            "run_code", "run_terminal_command", "http_request" -> SummaryContextType.RAW_OUTPUT
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

    /**
     * Build a deterministic head + tail summary for shell / script stdout. Tail is
     * placed FIRST so that any prefix-based downstream truncation preserves the
     * critical trailing content (exit codes, API verify responses, error blocks,
     * stack traces). Below the (head + tail) size threshold the whole output is
     * emitted verbatim with no head/tail split.
     *
     * Format:
     *
     * ```
     * [run_terminal_command output, total=120018 chars]
     * [TAIL — last 1500 chars verbatim, contains exit code / API response / errors]
     * ```
     * <verbatim tail>
     * ```
     *
     * [HEAD — first 600 chars verbatim]
     * ```
     * <verbatim head>
     * ```
     * [middle truncated: 117918 chars omitted between head and tail.
     *  To retrieve the full raw output, call memory(action="get_subtask_output", subtask_id=...)]
     * ```
     */
    private fun buildRawOutputTailFirstSummary(toolName: String, output: String): String {
        val total = output.length
        if (total <= RAW_OUTPUT_HEAD_BYTES + RAW_OUTPUT_TAIL_BYTES) {
            return buildString {
                append("[$toolName output, total=$total chars — full verbatim]\n")
                append("```\n")
                append(output.trimEnd())
                append("\n```")
            }
        }
        val tail = output.takeLast(RAW_OUTPUT_TAIL_BYTES)
        val head = output.take(RAW_OUTPUT_HEAD_BYTES)
        val omitted = total - RAW_OUTPUT_HEAD_BYTES - RAW_OUTPUT_TAIL_BYTES
        // The middle-cut marker is the single most important affordance for debugging
        // tasks where the diagnostic data lives in the MIDDLE of the output (state
        // exploration traces, full JSON dumps from API discovery endpoints, per-step
        // logs). A polite "you can call memory(...)" is routinely ignored — the agent
        // sees the tail with an error and just patches the code again. The marker
        // below is intentionally loud and directive: it tells the agent that retrying
        // the same command without first reading the omitted middle is forbidden.
        return buildString {
            append("[$toolName output, total=$total chars — head+tail summary, middle wycięto]\n")
            append("[TAIL — last $RAW_OUTPUT_TAIL_BYTES chars verbatim, contains exit code / API response / errors]\n")
            append("```\n")
            append(tail.trimEnd())
            append("\n```\n\n")
            append("[HEAD — first $RAW_OUTPUT_HEAD_BYTES chars verbatim]\n")
            append("```\n")
            append(head.trimEnd())
            append("\n```\n")
            append("\n[!! MIDDLE TRUNCATED — $omitted chars hidden between HEAD and TAIL !!]\n")
            append("The hidden middle of this output frequently contains the data that\n")
            append("explains *why* the visible TAIL says what it says — full API JSON\n")
            append("responses, per-step traces, intermediate values, discovery results,\n")
            append("and tool listings that did not fit in the first $RAW_OUTPUT_HEAD_BYTES bytes.\n")
            append("\n")
            append("BEFORE YOU TAKE YOUR NEXT ACTION:\n")
            append("- If you are about to RETRY the same command or PATCH the same file\n")
            append("  based only on the TAIL above, STOP. The TAIL alone is rarely enough\n")
            append("  to diagnose anything more complex than a syntax error.\n")
            append("- Call memory(action=\"get_subtask_output\", subtask_id=\"<id of THIS\n")
            append("  subtask — the one that just produced this output>\", offset=0,\n")
            append("  limit=64000) to recover the full middle. Use a larger offset to\n")
            append("  page through if the output is bigger than 64KB.\n")
            append("- Only after you have actually read the middle should you decide what\n")
            append("  to change. Do NOT guess from the tail.\n")
            append("- If you have already retrieved the middle once and the answer was\n")
            append("  not there, then a different command is probably needed — do NOT\n")
            append("  re-run the same command expecting a different output.\n")
        }
    }

    companion object {
        /**
         * Hard floor for ALL tool outputs regardless of context type or user config.
         * Below this size the summarizer LLM call (~hundreds of ms to multiple seconds
         * even with WEAK model, plus its own input tokens) costs more than the bytes
         * it could possibly save. Acts as a lower bound on TOOL_SUMMARY_MIN_LENGTH —
         * raising the config above this is fine, lowering it below has no effect.
         */
        const val GLOBAL_MIN_SKIP_THRESHOLD = 2048

        /**
         * Below this size, RAW_OUTPUT (run_code / run_terminal_command / http_request)
         * is NEVER summarized. The summarizer LLM call costs more than the saved
         * tokens — see TurnToolExecutor traces where 506-char outputs triggered 80s+
         * calls for 8 chars of "compression". http_request responses (JSON bodies,
         * HTTP status codes, error payloads) have the same characteristics and are
         * routed through the same path. Not configurable on purpose.
         */
        const val RAW_OUTPUT_SKIP_THRESHOLD = 8192

        /**
         * Below this size, DATA_FILE (read_file on .md/.json/.csv etc.) is NEVER
         * summarized. The WEAK model summarizer is destructive for small structured
         * data files — it paraphrases numbers, drops samples, and wastes 20-35s per
         * call on local models. Matches RAW_OUTPUT_SKIP_THRESHOLD.
         */
        const val DATA_FILE_SKIP_THRESHOLD = 8192

        /** Trailing bytes of stdout copied verbatim into the head of a deterministic RAW_OUTPUT summary. */
        const val RAW_OUTPUT_TAIL_BYTES = 4096

        /** Leading bytes of stdout copied verbatim after the tail block in a deterministic RAW_OUTPUT summary. */
        const val RAW_OUTPUT_HEAD_BYTES = 4096

        /**
         * Total characters of raw output sent to the summarizer LLM. When the output
         * exceeds this budget we keep half from the head and half from the tail (see
         * buildSummarizerPrompt). The tail-preserving slice exists so trailing data
         * — exit codes, API response bodies, stack traces — always survives the
         * compression step regardless of what the WEAK summarizer model decides.
         */
        const val SUMMARIZER_INPUT_BUDGET = 16394

        /**
         * File extensions treated as DATA_FILE rather than CODE_ANALYSIS.
         * These are structured/text files where code-style summarization
         * ("no classes found, no imports detected") is actively misleading.
         *
         * NOTE: html/htm are intentionally NOT in this set. HTML pages are
         * frequently read by agents to extract IDs, form fields, or table
         * data, and the WEAK summarizer collapses them to "this is a login
         * form" or similar paraphrases that destroy the structural content
         * the agent actually needs. HTML flows through structure-aware
         * compression in ToolResultCompression instead.
         */
        val DATA_FILE_EXTENSIONS = setOf(
            "json", "csv", "tsv", "txt", "log", "md", "markdown",
            "yaml", "yml", "xml", "ini", "conf", "cfg",
            "properties", "toml", "env", "sql"
        )
    }
}
