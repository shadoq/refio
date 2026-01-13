package pl.jclab.refio.core.tools.implementations

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val logger = dualLogger("MultiLineEditorTool")

/**
 * Multi-Line Code Editor Tool - LLM-assisted line-based editing
 *
 * This tool uses an LLM to identify minimal line ranges that need to be changed,
 * then applies edits line-by-line without full file regeneration.
 *
 * Parameters:
 * - path: Relative file path
 * - edit_description: Natural language description of changes
 *
 * Advantages over code_editing:
 * - No exact string matching required
 * - Handles multiple edits in one call (3-10 locations)
 * - Better at preserving formatting
 *
 * Advantages over advance_code_editing:
 * - Much cheaper (~$0.02 vs ~$0.06)
 * - Faster (smaller LLM response)
 * - Less risk of unintended changes
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - File size limits enforced
 * - Line number validation (bounds checking)
 * - Overlap detection for edits
 *
 * Based on: docs/0041-multi-coding.md (RFC 0041)
 */
class MultiLineEditorTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val taskRepository: TaskRepository
) : Tool {

    override val name = "multi_line_editor"
    override val description =
        "**Preferred** LLM-assisted editing for targeted changes in existing files (2-10 locations). Cost-efficient (~$0.02), precise, preserves unchanged code. Use this for most editing tasks."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING

    private val gson = Gson()

    override fun validateParams(params: Map<String, Any>) {
        // Validate path
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }

        // Validate edit_description
        val editDescription = params["edit_description"] as? String
        if (editDescription.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'edit_description' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        val pathStr = params["path"] as String
        val editDescription = params["edit_description"] as String
        val taskId = params["taskId"] as? String

        return try {
            executeEdit(pathStr, editDescription, taskId, startTime)
        } catch (e: SecurityException) {
            logger.warn { "Security violation in multi_line_editor: ${e.message}" }
            ToolResult.error("Security error: ${e.message}")
        } catch (e: Exception) {
            logger.error(e) { "Failed to edit file: $pathStr" }
            ToolResult.error("Failed to edit file: ${e.message}")
        }
    }

    /**
     * Main edit execution logic
     */
    private suspend fun executeEdit(
        pathStr: String,
        editDescription: String,
        taskId: String?,
        startTime: Long
    ): ToolResult {
        // 1. Read file and validate
        val path = sandbox.resolve(pathStr)

        if (!path.exists()) {
            return ToolResult.error("File not found: $pathStr. Use advance_code_editing to create new files.")
        }

        if (!path.isRegularFile()) {
            return ToolResult.error("Not a regular file: $pathStr")
        }

        // Check file size
        val fileSize = path.fileSize()
        if (fileSize > limits.maxFileSize) {
            return ToolResult.error(
                "File too large: $fileSize bytes (max ${limits.maxFileSize} bytes). " +
                "Use advance_code_editing for large files or split into smaller edits."
            )
        }

        // Read content
        val originalContent = Files.readString(path)
        val lines = originalContent.lines()

        logger.info {
            "Multi-line edit: path=$pathStr, description='$editDescription', " +
            "file_size=$fileSize bytes, line_count=${lines.size}"
        }

        // 2. Prepare file with line numbers for LLM
        val numberedContent = lines.mapIndexed { index, line ->
            "${index + 1}: $line"
        }.joinToString("\n")

        // 3. Detect language
        val extension = path.fileName.toString().substringAfterLast('.', "")
        val language = detectLanguage(extension)

        // 4. Build prompts
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.MULTI_LINE_EDITING_SYSTEM
        )

        val userPrompt = promptsService.getSystemPrompt(
            type = PromptType.MULTI_LINE_EDITING_USER,
            variables = mapOf(
                "FILE_PATH" to pathStr,
                "LANGUAGE" to language,
                "EDIT_DESCRIPTION" to editDescription,
                "NUMBERED_CONTENT" to numberedContent
            )
        )

        // 5. Call LLM
        val messages = listOf(
            LLMMessage(role = "user", content = userPrompt)
        )

        val (model, provider) = configService.getModel(
            operation = ModelOperation.CODING,
            taskId = taskId
        )

        logger.info { "Using model for multi-line edit: $model ($provider), file has ${lines.size} lines" }

        val response = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = messages,
                systemPrompt = systemPrompt,
                temperature = 0.1, // Low temp for precision
                maxTokens = configService.getMaxOutputTokens(),
                stream = false, // No streaming for JSON response
                taskId = null,
                subtaskId = null,
                source = "MultiLineEditor"
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM request failed" }
            return ToolResult.error("LLM request failed: ${e.message}. Try rephrasing the edit description.")
        }

        val responseContent = response.content
        val usage = response.usage
        val cost = response.cost

        // 6. Parse JSON response
        val edits = try {
            parseEdits(responseContent, lines.size)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse LLM response as JSON" }
            return ToolResult.error(
                "Failed to parse LLM response. Response was: ${responseContent.take(200)}... " +
                "Try rephrasing the edit description or use advance_code_editing."
            )
        }

        if (edits.isEmpty()) {
            logger.warn { "LLM returned no edits for description: $editDescription" }
            return ToolResult.error(
                "LLM did not identify any changes to make. Try being more specific in the edit description."
            )
        }

        logger.info { "LLM identified ${edits.size} edits" }

        // 7. Validate edits
        val validationError = validateEdits(edits, lines.size)
        if (validationError != null) {
            return ToolResult.error(validationError)
        }

        // 8. Apply edits (from end to start to preserve line numbers)
        val newContent = applyEdits(lines, edits)

        // 9. Generate diff
        val diff = generateUnifiedDiff(originalContent, newContent, pathStr)
        val (addedLines, removedLines) = parseDiffStats(diff)

        // 10. Write file
        Files.writeString(path, newContent)
        val duration = (System.currentTimeMillis() - startTime).toInt()
        val newFileSize = path.fileSize()

        logger.info {
            "Multi-line edit completed: path=$pathStr, model=$model, " +
            "edits=${edits.size}, tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
            "cost_usd=$cost, duration=${duration}ms"
        }

        // 11. Update task metrics
        if (taskId != null) {
            taskRepository.incrementMetrics(
                id = taskId,
                tokensIn = usage.inputTokens,
                tokensOut = usage.outputTokens,
                costUsd = cost
            )
        }

        // 12. Return result
        return ToolResult(
            success = true,
            output = buildString {
                appendLine("File edited successfully: $pathStr")
                appendLine("Applied ${edits.size} edits to file")
                appendLine("Size: $fileSize bytes → $newFileSize bytes")
                appendLine("Model: $model, Tokens: ${usage.inputTokens}/${usage.outputTokens}, Cost: $${"%.4f".format(cost)}")
            },
            bytesRead = originalContent.toByteArray().size,
            bytesWritten = newContent.toByteArray().size,
            durationMs = duration,
            filesChanged = listOf(pathStr),
            metadata = mapOf(
                "path" to pathStr,
                "mode" to "multi_line_edit",
                "edits_count" to edits.size,
                "added_lines" to addedLines,
                "removed_lines" to removedLines,
                "edit_description" to editDescription,
                "model" to model,
                "provider" to provider,
                "tokens_in" to usage.inputTokens,
                "tokens_out" to usage.outputTokens,
                "cost_usd" to cost,
                "diff" to diff
            )
        )
    }

    /**
     * Parse JSON response from LLM into list of EditChange objects
     */
    private fun parseEdits(jsonResponse: String, totalLines: Int): List<EditChange> {
        try {
            // Extract JSON from response (might be wrapped in markdown)
            val jsonContent = extractJsonFromResponse(jsonResponse)

            val response = gson.fromJson(jsonContent, EditsResponse::class.java)
                ?: throw IllegalArgumentException("Failed to parse JSON response")

            // Sort by line_start ascending (as per spec)
            return response.changes.sortedBy { it.line_start }

        } catch (e: JsonSyntaxException) {
            logger.error(e) { "Invalid JSON syntax in LLM response" }
            throw IllegalArgumentException("Invalid JSON syntax: ${e.message}")
        }
    }

    /**
     * Extract JSON from response (handles markdown code blocks)
     */
    private fun extractJsonFromResponse(response: String): String {
        // Try to find JSON in markdown code block
        val jsonBlockPattern = Regex("```(?:json)?\\s*\\n(.+?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonBlockPattern.find(response)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // Try to find raw JSON (starts with {)
        val trimmed = response.trim()
        if (trimmed.startsWith("{")) {
            return trimmed
        }

        throw IllegalArgumentException("No JSON found in response")
    }

    /**
     * Validate edits before applying
     */
    private fun validateEdits(edits: List<EditChange>, totalLines: Int): String? {
        // Check line bounds
        for (edit in edits) {
            if (edit.line_start < 1) {
                return "Invalid edit: line_start ${edit.line_start} is less than 1"
            }
            if (edit.line_end > totalLines) {
                return "Invalid edit: line_end ${edit.line_end} exceeds file length ($totalLines lines)"
            }
            // Allow line_end < line_start for insertions (as per spec)
        }

        // Check for overlapping edits
        for (i in edits.indices) {
            for (j in i + 1 until edits.size) {
                val edit1 = edits[i]
                val edit2 = edits[j]

                // Check if ranges overlap
                val overlap = !(edit1.line_end < edit2.line_start || edit2.line_end < edit1.line_start)
                if (overlap) {
                    return "Overlapping edits detected: lines ${edit1.line_start}-${edit1.line_end} " +
                        "and ${edit2.line_start}-${edit2.line_end}. Please combine into single edit."
                }
            }
        }

        return null // Valid
    }

    /**
     * Apply edits to lines
     * Edits must be sorted by line_start descending to preserve line numbers
     */
    private fun applyEdits(lines: List<String>, edits: List<EditChange>): String {
        val mutableLines = lines.toMutableList()

        // Sort descending by line_start to preserve line numbers during edits
        val sortedEdits = edits.sortedByDescending { it.line_start }

        for (edit in sortedEdits) {
            val before = if (edit.line_start > 1) {
                mutableLines.subList(0, edit.line_start - 1)
            } else {
                emptyList()
            }

            val after = if (edit.line_end < mutableLines.size) {
                mutableLines.subList(edit.line_end, mutableLines.size)
            } else {
                emptyList()
            }

            val newLines = if (edit.new_content.isEmpty()) {
                // Deletion - no new lines
                emptyList()
            } else {
                // Split new content into lines
                edit.new_content.split("\n")
            }

            // Rebuild list
            mutableLines.clear()
            mutableLines.addAll(before)
            mutableLines.addAll(newLines)
            mutableLines.addAll(after)

            logger.debug {
                "Applied edit at lines ${edit.line_start}-${edit.line_end}: ${edit.description ?: "no description"}"
            }
        }

        return mutableLines.joinToString("\n")
    }

    /**
     * Generate unified diff between original and new content
     */
    private fun generateUnifiedDiff(
        originalContent: String,
        newContent: String,
        filePath: String
    ): String {
        val original = originalContent.lines()
        val new = newContent.lines()

        val diff = StringBuilder()
        diff.appendLine("--- a/$filePath")
        diff.appendLine("+++ b/$filePath")

        val maxLines = maxOf(original.size, new.size)
        for (i in 0 until maxLines) {
            val oldLine = original.getOrNull(i)
            val newLine = new.getOrNull(i)

            when {
                oldLine == newLine -> {
                    diff.appendLine("  $oldLine")
                }
                oldLine != null && newLine == null -> {
                    diff.appendLine("- $oldLine")
                }
                oldLine == null && newLine != null -> {
                    diff.appendLine("+ $newLine")
                }
                else -> {
                    diff.appendLine("- $oldLine")
                    diff.appendLine("+ $newLine")
                }
            }
        }

        return diff.toString()
    }

    /**
     * Parse diff stats to count added and removed lines
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
                    "description" to "Natural language description of the changes to make (e.g., 'Add null checks to getUserById and updateUser functions')"
                )
            ),
            "required" to listOf("path", "edit_description")
        )
    }
}

/**
 * Data class for a single edit change
 */
data class EditChange(
    val line_start: Int,
    val line_end: Int,
    val new_content: String,
    val description: String?
)

/**
 * Data class for LLM JSON response
 */
data class EditsResponse(
    val changes: List<EditChange>
)
