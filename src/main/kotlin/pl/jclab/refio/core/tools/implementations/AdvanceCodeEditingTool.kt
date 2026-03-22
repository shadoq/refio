package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val logger = dualLogger("AdvanceCodeEditingTool")

/**
 * Advanced Code Editing Tool - LLM-assisted file editing with intelligent intent processing
 *
 * This tool support:
 * 1. LLM-ASSISTED MODE (new): Uses edit_description parameter
 *    - Agent provides intent: "Add error handling for null values"
 *    - LLM reads file, generates complete modified version
 *    - Tool creates diff, snapshot, and writes file
 *
 * Parameters (LLM-assisted mode):
 * - path: Relative file path
 * - edit_description: Natural language description of what to change
 *   Example: "Add error handling for null values in parseUser function"
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Snapshot created before modification
 * - Unified diff generation for review
 * - File size limits enforced
 *
 * Based on: docs/0017-advance-code.md (ADR 0017)
 */
class AdvanceCodeEditingTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val taskRepository: TaskRepository
) : Tool {

    override val name = "advance_code_editing"
    override val description =
        "LLM-assisted full file regeneration. **Use this tool for:** creating new code files (html, js, ts, php, java, python, kotlin, css, etc.) OR major refactoring of existing files. Expensive (~3x cost of multi_line_editor) - use only when necessary. For simple text files (md, txt, json), use create_new_file instead. Automatically creates parent directories if needed."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING

    override fun validateParams(params: Map<String, Any>) {
        // Validate path
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }

        // Check mode: either edit_description OR (old_string + new_string)
        val editDescription = params["edit_description"] as? String

        if (editDescription.isNullOrBlank()) {
            throw IllegalArgumentException("Either 'edit_description' must be provided")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return execute(params, stream = false, onChunk = null)
    }

    /**
     * Execute with optional streaming support (RFC 0032).
     *
     * @param params Tool parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Tool result
     */
    suspend fun execute(
        params: Map<String, Any>,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ToolResult {
        return executeWithListener(params, stream, onChunk, null, null)
    }

    /**
     * Execute with ExecutionEventListener for direct UI streaming integration.
     *
     * This method is called by ToolExecutor when listener is available,
     * providing direct streaming of generated code to ChatView.
     *
     * @param params Tool parameters
     * @param subtask Current subtask (for listener context)
     * @param listener ExecutionEventListener for streaming to UI
     * @return Tool result
     */
    suspend fun executeWithListener(
        params: Map<String, Any>,
        subtask: Subtask?,
        listener: ExecutionEventListener?
    ): ToolResult {
        val pathStr = params["path"] as? String ?: "unknown"
        logger.info { "[ACE_STREAM] executeWithListener called: path=$pathStr, hasListener=${listener != null}, hasSubtask=${subtask != null}" }

        // Create callback that forwards to listener
        val onChunk: StreamCallback? = if (listener != null && subtask != null) { chunk ->
            logger.debug { "[ACE_STREAM] onChunk invoked: accumulated=${chunk.accumulated.length} chars, isComplete=${chunk.isComplete}" }
            listener.onToolCodeGenerationStream(
                step = subtask,
                toolName = name,
                filePath = pathStr,
                streamContent = chunk.accumulated,
                isComplete = chunk.isComplete
            )
        } else {
            logger.warn { "[ACE_STREAM] No callback created - listener=${listener != null}, subtask=${subtask != null}" }
            null
        }

        logger.info { "[ACE_STREAM] Calling internal executeWithListener with stream=${listener != null}" }
        return executeWithListener(params, listener != null, onChunk, subtask, listener)
    }

    /**
     * Internal execute with all options.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun executeWithListener(
        params: Map<String, Any>,
        stream: Boolean,
        onChunk: StreamCallback?,
        _subtask: Subtask?,
        _listener: ExecutionEventListener?
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        val pathStr = params["path"] as? String
            ?: return ToolResult.error("Missing required parameter: 'path'")
        val editDescription = params["edit_description"] as? String
        val taskId = params["taskId"] as? String

        return try {
            if (editDescription != null) {
                executeLLMAssistedEdit(pathStr, editDescription, taskId, startTime, stream, onChunk)
            } else {
                ToolResult.error("Either 'edit_description' or 'old_string' must be provided")
            }
        } catch (e: SecurityException) {
            logger.warn { "Security violation in advance_code_editing: ${e.message}" }
            ToolResult.error("Security error: ${e.message}")
        } catch (e: Exception) {
            logger.error(e) { "Failed to edit file: $pathStr" }
            ToolResult.error("Failed to edit file: ${e.message}")
        }
    }

    /**
     * LLM-assisted edit: Intent → Full file regeneration → Diff → Snapshot → Write
     * (RFC 0032: unified streaming support)
     */
    private suspend fun executeLLMAssistedEdit(
        pathStr: String,
        editDescription: String,
        taskId: String?,
        startTime: Long,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ToolResult {
        // 1. Read file or prepare for creation
        val normalizedPathStr = normalizePath(pathStr)
        val path = sandbox.resolve(normalizedPathStr)
        val fileExists = path.exists()

        if (limits.shouldExcludeFile(path.fileName.toString())) {
            return ToolResult.error("File extension not allowed: ${path.fileName}")
        }

        val originalContent: String
        val fileSize: Long

        if (!fileExists) {
            // File doesn't exist - will create it with LLM-generated content
            logger.info { "LLM-assisted create: path=$pathStr, description='$editDescription' (file will be created)" }

            // Create parent directories if needed
            path.parent?.let { parent ->
                if (!Files.exists(parent)) {
                    Files.createDirectories(parent)
                    logger.info { "Created parent directories: ${path.parent}" }
                }
            }

            originalContent = ""
            fileSize = 0
        } else {
            // File exists - will edit it
            if (!path.isRegularFile()) {
                return ToolResult.error("Not a regular file: $pathStr")
            }

            // Check file size
            fileSize = path.fileSize()
            if (fileSize > limits.maxFileSize) {
                return ToolResult.error(
                    "File too large for LLM-assisted editing: $fileSize bytes (max ${limits.maxFileSize} bytes). " +
                            "Use simple search-replace mode or split into smaller edits."
                )
            }

            originalContent = Files.readString(path)
            logger.info { "LLM-assisted edit: path=$pathStr, description='$editDescription', size=$fileSize bytes" }
        }

        // 2. Get file extension for language detection
        val extension = path.fileName.toString().substringAfterLast('.', "")
        val language = detectLanguage(extension)

        // 3. Build LLM prompts using PromptsService with automatic variable substitution
        // CODE_EDITING_SYSTEM: Instructions for LLM behavior → sent as LLM role "system"
        // CODE_EDITING_USER: Data template with variables → sent as LLM role "user" after substitution
        val systemPrompt = promptsService.getSystemPrompt(
            type = pl.jclab.refio.core.db.PromptType.CODE_EDITING_SYSTEM
        )

        val userPrompt = promptsService.getSystemPrompt(
            type = pl.jclab.refio.core.db.PromptType.CODE_EDITING_USER,
            variables = mapOf(
                "FILE_PATH" to pathStr,
                "LANGUAGE" to language,
                "ORIGINAL_CONTENT" to originalContent,
                "EDIT_DESCRIPTION" to editDescription
            )
        )

        // 4. Call LLM with paired prompts
        // - systemPrompt → sent via systemPrompt parameter (instructions: rules, format, constraints)
        // - userPrompt → sent as user message (data: file content, edit request)
        val messages = listOf(
            LLMMessage(role = "user", content = userPrompt)
        )

        // Get model from config (uses CODING operation type)
        val (model, provider) = configService.getModel(
            operation = ModelOperation.CODING,
            taskId = taskId
        )
        logger.info { "Using agent model for edit (${originalContent.lines().size} lines): $model ($provider), stream=$stream, hasOnChunk=${onChunk != null}" }

        // RFC 0032: Use unified complete() with stream flag
        var didStream = false
        val streamingCallback: StreamCallback? = if (stream && onChunk != null) { chunk ->
            didStream = true
            onChunk(chunk)
        } else {
            null
        }

        val response = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = messages,
                systemPrompt = systemPrompt,
                temperature = 0.2, // Low temperature for deterministic output
                maxTokens = configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE) * 2, // From limits settings
                stream = stream,
                onChunk = streamingCallback,
                taskId = null,  // Tool-level call, no task context
                subtaskId = null,  // Tool-level call, no subtask context
                source = "AdvCodeEditor"  // Request source for tracking
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM request failed" }
            return ToolResult.error("LLM request failed: ${e.message}. Try again or use simple search-replace mode.")
        }

        val responseContent = response.content
        val usage = response.usage
        val cost = response.cost

        if (stream && onChunk != null && !didStream) {
            onChunk(
                StreamChunk(
                    delta = responseContent,
                    accumulated = responseContent,
                    isComplete = true,
                    source = "AdvCodeEditor",
                    usage = usage,
                    cost = cost
                )
            )
        }

        // 5. Extract code from response
        val newContent = extractCodeBlock(responseContent, language)
            ?: return ToolResult.error(
                "LLM did not return valid code block. Try rephrasing the edit description or use simple search-replace mode."
            )

        // 6. Generate diff
        val diff = generateUnifiedDiff(
            originalContent = originalContent,
            newContent = newContent,
            filePath = pathStr
        )

        // 7. Optional: Syntax validation
        val validationError = validateSyntax(newContent, language)
        if (validationError != null) {
            logger.warn { "Syntax validation warning: $validationError" }
            // Continue anyway - user can rollback
        }

        // 8. Snapshot creation
        // Note: Snapshots are created by AgentExecutor before tool execution
        // Tool itself doesn't create snapshots - this is handled at the workflow level

        // 9. Write file
        Files.writeString(path, newContent)
        val duration = (System.currentTimeMillis() - startTime).toInt()
        val newFileSize = path.fileSize()

        logger.info {
            "LLM-assisted edit completed: path=$pathStr, model=$model, " +
                    "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                    "cost_usd=$cost, diff_lines=${diff.lines().size}, duration=${duration}ms"
        }

        // Update task metrics if taskId is provided
        if (taskId != null) {
            taskRepository.incrementMetrics(
                id = taskId,
                tokensIn = usage.inputTokens,
                tokensOut = usage.outputTokens,
                costUsd = cost
            )
        }

        // 10. Parse diff stats for UI display
        val (addedLines, removedLines) = parseDiffStats(diff)

        // 11. Return result (changes displayed as badge in UI)
        return ToolResult(
            success = true,
            output = buildString {
                if (fileExists) {
                    appendLine("File edited successfully: $pathStr")
                } else {
                    appendLine("File created successfully: $pathStr")
                }
                if (fileExists) {
                    appendLine("Size: $fileSize bytes → $newFileSize bytes")
                } else {
                    appendLine("Size: $newFileSize bytes")
                }
                appendLine("Model: $model, Tokens: ${usage.inputTokens}/${usage.outputTokens}, Cost: $${"%.4f".format(cost)}")
                // Wrap diff in markdown code block for proper UI rendering
                appendLine("Diff:")
                appendLine("```diff")
                diff.lines().forEach { line -> appendLine(line) }
                append("```")
            },
            bytesRead = originalContent.toByteArray().size,
            bytesWritten = newContent.toByteArray().size,
            durationMs = duration,
            filesChanged = listOf(pathStr),
            metadata = mapOf(
                "path" to pathStr,  // Relative path to project root
                "mode" to "llm_assisted",
                "diff_lines" to diff.lines().size,
                "added_lines" to addedLines,
                "removed_lines" to removedLines,
                "edit_description" to editDescription,
                "model" to model,
                "provider" to provider,
                "tokens_in" to usage.inputTokens,
                "tokens_out" to usage.outputTokens,
                "cost_usd" to cost
            )
        )
    }

    /**
     * Parse diff stats to count added and removed lines.
     * Returns Pair(addedLines, removedLines).
     */
    private fun parseDiffStats(diff: String): Pair<Int, Int> {
        var addedLines = 0
        var removedLines = 0

        diff.lines().forEach { line ->
            when {
                line.startsWith("+ ") -> addedLines++
                line.startsWith("- ") -> removedLines++
            }
        }

        return Pair(addedLines, removedLines)
    }

    /**
     * Extract code block from LLM response
     * Supports markdown code fences: ```language\ncode\n```
     */
    private fun extractCodeBlock(response: String, expectedLanguage: String): String? {
        // Pattern 1: ```language\ncode\n```
        val pattern1 = Regex("```$expectedLanguage\\s*\\n(.+?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val match1 = pattern1.find(response)
        if (match1 != null) {
            return match1.groupValues[1].trim()
        }

        // Pattern 2: ```\ncode\n``` (no language specified)
        val pattern2 = Regex("```\\s*\\n(.+?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val match2 = pattern2.find(response)
        if (match2 != null) {
            return match2.groupValues[1].trim()
        }

        // Pattern 3: Entire response is code (no fences)
        // Only if response doesn't contain explanatory text
        if (!response.contains("Here") && !response.contains("I've") && response.lines().size > 3) {
            return response.trim()
        }

        return null
    }

    /**
     * Generate unified diff between two strings using Myers diff algorithm
     * Groups changes into hunks with proper @@ headers for better readability
     */
    private fun generateUnifiedDiff(
        originalContent: String,
        newContent: String,
        filePath: String
    ): String {
        val contextLines = 3
        val original = originalContent.lines()
        val updated = newContent.lines()
        val diffEntries = buildDiffEntries(original, updated)
        val hunks = buildDiffHunks(diffEntries, contextLines)

        val diff = StringBuilder()
        diff.appendLine("--- a/$filePath")
        diff.appendLine("+++ b/$filePath")

        for (hunk in hunks) {
            diff.appendLine("@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@")
            for (entry in hunk.lines) {
                when (entry.type) {
                    DiffEntryType.CONTEXT -> diff.appendLine("  ${entry.content}")
                    DiffEntryType.DELETE -> diff.appendLine("- ${entry.content}")
                    DiffEntryType.INSERT -> diff.appendLine("+ ${entry.content}")
                }
            }
        }

        return diff.toString()
    }

    private data class DiffEntry(
        val type: DiffEntryType,
        val content: String,
        val oldLine: Int?,
        val newLine: Int?
    )

    private enum class DiffEntryType {
        CONTEXT,
        DELETE,
        INSERT
    }

    private data class DiffHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<DiffEntry>
    )

    private fun buildDiffEntries(original: List<String>, updated: List<String>): List<DiffEntry> {
        val m = original.size
        val n = updated.size
        val lcs = Array(m + 1) { IntArray(n + 1) }

        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                lcs[i][j] = if (original[i] == updated[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val result = mutableListOf<DiffEntry>()
        var i = 0
        var j = 0

        while (i < m && j < n) {
            when {
                original[i] == updated[j] -> {
                    result.add(
                        DiffEntry(
                            type = DiffEntryType.CONTEXT,
                            content = original[i],
                            oldLine = i,
                            newLine = j
                        )
                    )
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    result.add(
                        DiffEntry(
                            type = DiffEntryType.DELETE,
                            content = original[i],
                            oldLine = i,
                            newLine = null
                        )
                    )
                    i++
                }
                else -> {
                    result.add(
                        DiffEntry(
                            type = DiffEntryType.INSERT,
                            content = updated[j],
                            oldLine = null,
                            newLine = j
                        )
                    )
                    j++
                }
            }
        }

        while (i < m) {
            result.add(
                DiffEntry(
                    type = DiffEntryType.DELETE,
                    content = original[i],
                    oldLine = i,
                    newLine = null
                )
            )
            i++
        }

        while (j < n) {
            result.add(
                DiffEntry(
                    type = DiffEntryType.INSERT,
                    content = updated[j],
                    oldLine = null,
                    newLine = j
                )
            )
            j++
        }

        return result
    }

    private fun buildDiffHunks(entries: List<DiffEntry>, contextLines: Int): List<DiffHunk> {
        if (entries.isEmpty()) return emptyList()

        val changedIndices = entries.indices.filter { entries[it].type != DiffEntryType.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()

        val mergedRanges = mutableListOf<IntRange>()
        for (index in changedIndices) {
            val rangeStart = maxOf(0, index - contextLines)
            val rangeEnd = minOf(entries.lastIndex, index + contextLines)

            if (mergedRanges.isEmpty()) {
                mergedRanges.add(rangeStart..rangeEnd)
                continue
            }

            val previous = mergedRanges.last()
            if (rangeStart <= previous.last + 1) {
                mergedRanges[mergedRanges.lastIndex] = previous.first..maxOf(previous.last, rangeEnd)
            } else {
                mergedRanges.add(rangeStart..rangeEnd)
            }
        }

        return mergedRanges.map { range ->
            val hunkEntries = entries.subList(range.first, range.last + 1)
            val oldStart = (hunkEntries.firstNotNullOfOrNull { it.oldLine }
                ?: hunkEntries.firstNotNullOfOrNull { it.newLine }
                ?: 0) + 1
            val newStart = (hunkEntries.firstNotNullOfOrNull { it.newLine }
                ?: hunkEntries.firstNotNullOfOrNull { it.oldLine }
                ?: 0) + 1

            DiffHunk(
                oldStart = oldStart,
                oldCount = hunkEntries.count { it.oldLine != null },
                newStart = newStart,
                newCount = hunkEntries.count { it.newLine != null },
                lines = hunkEntries.toList()
            )
        }
    }

    /**
     * Detect programming language from file extension
     */
    private fun detectLanguage(extension: String): String {
        return when (extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "cs" -> "csharp"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "md" -> "markdown"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            else -> "plaintext"
        }
    }

    /**
     * Optional: Validate syntax (language-specific)
     * For MVP: skip or basic checks only
     */
    @Suppress("UNUSED_PARAMETER")
    private fun validateSyntax(content: String, _language: String): String? {
        // Future: language-specific parsers
        // For MVP: just check if file is not empty and has basic structure
        if (content.trim().isEmpty()) {
            return "Generated content is empty"
        }
        return null
    }

    private fun countOccurrences(text: String, substring: String): Int {
        if (substring.isEmpty()) return 0

        var count = 0
        var index = 0

        while (text.indexOf(substring, index).also { index = it } != -1) {
            count++
            index += substring.length
        }

        return count
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative path to the file to edit"
                ),
                "edit_description" to mapOf(
                    "type" to "string",
                    "description" to "Natural language description of the edit to perform (e.g., 'Add error handling for null values') " +
                            "or code file to create (e.g., 'Create a utility class for string manipulation', 'Create a React component for user profile'). " +
                            "**Preferred for creating new code files** (html, js, ts, php, java, python, kotlin, css, etc.). " +
                            "Use this parameter for LLM-assisted editing/creation. File and parent directories will be created if they don't exist."
                ),
            ),
            "required" to listOf("path"),
            "oneOf" to listOf(
                mapOf("required" to listOf("edit_description")),
                mapOf("required" to listOf("old_string", "new_string"))
            )
        )
    }

}
