package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.DiffUtils
import pl.jclab.refio.core.tools.FileLockManager
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isDirectory

private val logger = dualLogger("CodeEditingTool")

/**
 * Code Editing Tool - edits existing files using search-and-replace
 *
 * Parameters:
 * - path: Relative file path
 * - old_string: String to find and replace
 * - new_string: Replacement string
 * - replace_all: Replace all occurrences (default: false)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - File must exist
 * - old_string must be unique (unless replace_all=true)
 */
class CodeEditingTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "code_editing"
    override val description = "Search-and-replace edit in an existing file. FREE."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING
    override val selectionHint = "Small targeted edit in an existing file via exact string match (search/replace)."

    override fun validateParams(params: Map<String, Any>) {
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }
        if (params["old_string"] == null) {
            throw IllegalArgumentException("Parameter 'old_string' is required")
        }
        if (params["new_string"] == null) {
            throw IllegalArgumentException("Parameter 'new_string' is required")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val pathStr = params["path"] as? String
                ?: return ToolResult.error("Missing required parameter: 'path'")
            val oldString = params["old_string"] as? String
                ?: return ToolResult.error("Missing required parameter: 'old_string'")
            val newString = params["new_string"] as? String
                ?: return ToolResult.error("Missing required parameter: 'new_string'")
            val replaceAll = (params["replace_all"] as? Boolean) ?: false

            // Normalize path for security (bare filenames → "./file.txt", backslash → forward slash)
            val normalizedPathStr = normalizePath(pathStr)

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Editing file: relative='$pathStr', absolute='${path.toAbsolutePath()}', sandbox_root='${getSandboxRoot()}', oldString=${oldString.length} chars, newString=${newString.length} chars, replaceAll=$replaceAll" }

            return FileLockManager.withFileLock(path.toAbsolutePath().toString()) {
                // Re-validate path inside lock to close TOCTOU window (symlink swap between validate and I/O)
                sandbox.revalidateBeforeIO(path)

                // Check if file exists
                val fileExists = path.exists()
                val content: String

                if (!fileExists) {
                    // File doesn't exist - can only create if old_string is empty
                    if (oldString.isEmpty()) {
                        logger.info { "Creating new file: $pathStr with content from new_string" }

                        // Create parent directories if needed
                        path.parent?.let { parent ->
                            if (!Files.exists(parent)) {
                                Files.createDirectories(parent)
                                logger.info { "Created parent directories: ${path.parent}" }
                            }
                        }

                        // Create file with new_string content
                        Files.writeString(path, newString)
                        val duration = (System.currentTimeMillis() - startTime).toInt()
                        val newFileSize = path.fileSize()

                        logger.info { "Successfully created file: $pathStr (size: $newFileSize bytes, ${duration}ms)" }

                        val changeSummary = DiffUtils.buildChangeSummary(
                            originalContent = "",
                            newContent = newString,
                            filePath = pathStr,
                            replacements = 1,
                            created = true
                        )
                        val diff = changeSummary.unifiedDiff ?: ""

                        return@withFileLock ToolResult(
                            success = true,
                            output = buildString {
                                appendLine("File created successfully: $pathStr ($newFileSize bytes)")
                                appendLine("Diff:")
                                appendLine("```diff")
                                diff.lines().forEach { line -> appendLine(line) }
                                append("```")
                            },
                            bytesRead = 0,
                            bytesWritten = newString.toByteArray().size,
                            durationMs = duration,
                            filesChanged = listOf(pathStr),
                            changeSummary = changeSummary,
                            metadata = mapOf(
                                "path" to pathStr,  // Relative path to project root
                                "mode" to "create",
                                "file_size" to newFileSize,
                                "added_lines" to changeSummary.addedLines,
                                "removed_lines" to changeSummary.removedLines,
                                "diff" to diff
                            )
                        )
                    } else {
                        logger.warn { "File not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                        return@withFileLock ToolResult.error(
                            message = "File not found: $pathStr",
                            recovery = "To create a new file pass old_string=\"\" with the full new content, or use advance_code_editing with edit_description.",
                            nextActionHints = listOf(
                                "Verify the path with file_search(pattern=\"${path.fileName}\")",
                                "If the file should be created, retry with old_string=\"\"",
                                "For complex new files prefer advance_code_editing"
                            )
                        )
                    }
                }

                // Check if it's a regular file
                if (!path.isRegularFile()) {
                    logger.warn { "Not a regular file: $pathStr (is directory: ${path.isDirectory()})" }
                    return@withFileLock ToolResult.error("Not a regular file: $pathStr")
                }

                // Check file size
                val fileSize = path.fileSize()
                logger.info { "File size before edit: $fileSize bytes, absolute='${path.toAbsolutePath()}'" }
                if (fileSize > limits.maxFileSize) {
                    return@withFileLock ToolResult.error(
                        message = "File too large: $fileSize bytes (max ${limits.maxFileSize} bytes)",
                        recovery = "Use multi_line_editor for targeted edits, or split the change into smaller hunks."
                    )
                }

                // Read current content
                content = Files.readString(path)

                // Check if old_string exists
                if (!content.contains(oldString)) {
                    return@withFileLock ToolResult.error(
                        message = "String not found in file: '${oldString.take(80)}${if (oldString.length > 80) "…" else ""}' (${oldString.length} chars).",
                        recovery = "Read the file first to see the actual content, then retry with the exact substring (including whitespace).",
                        nextActionHints = listOf(
                            "read_file(path=\"$pathStr\") to inspect actual content",
                            "Try grep_search to locate the intended substring",
                            "Use multi_line_editor if the exact bytes are uncertain"
                        )
                    )
                }

                // Check uniqueness if not replaceAll
                if (!replaceAll) {
                    val occurrences = countOccurrences(content, oldString)
                    if (occurrences > 1) {
                        return@withFileLock ToolResult.error(
                            message = "String appears $occurrences times in file.",
                            recovery = "Either pass replace_all=true to apply to every occurrence, or extend old_string with surrounding context to make it unique.",
                            nextActionHints = listOf(
                                "Add more surrounding context to old_string",
                                "Pass replace_all=true if every occurrence should change"
                            )
                        )
                    }
                }

                // Perform replacement
                val newContent = if (replaceAll) {
                    content.replace(oldString, newString)
                } else {
                    content.replaceFirst(oldString, newString)
                }

                // Write updated content
                Files.writeString(path, newContent)
                val duration = (System.currentTimeMillis() - startTime).toInt()

                val replacements = countOccurrences(content, oldString)
                val newFileSize = path.fileSize()

                // Build proper unified diff (Myers, 3 lines context) via shared DiffUtils.
                // This replaces the previous naive "dump old then new" formatter.
                val changeSummary = DiffUtils.buildChangeSummary(
                    originalContent = content,
                    newContent = newContent,
                    filePath = pathStr,
                    replacements = replacements
                )
                val diff = changeSummary.unifiedDiff ?: ""

                logger.info { "Successfully edited file: $pathStr ($replacements replacements, ${duration}ms, size: $fileSize → $newFileSize bytes, +${changeSummary.addedLines}/-${changeSummary.removedLines})" }

                ToolResult(
                    success = true,
                    output = buildString {
                        appendLine("File edited successfully: $pathStr ($replacements replacements made)")
                        if (replaceAll && replacements > 1) {
                            appendLine("Preview shows one replacement pattern applied across all matches.")
                        }
                        appendLine("Diff:")
                        appendLine("```diff")
                        diff.lines().forEach { line -> appendLine(line) }
                        append("```")
                    },
                    bytesRead = content.toByteArray().size,
                    bytesWritten = newContent.toByteArray().size,
                    durationMs = duration,
                    filesChanged = listOf(pathStr),
                    changeSummary = changeSummary,
                    metadata = mapOf(
                        "path" to pathStr,  // Relative path to project root
                        "replacements" to replacements,
                        "old_length" to content.length,
                        "new_length" to newContent.length,
                        "added_lines" to changeSummary.addedLines,
                        "removed_lines" to changeSummary.removedLines,
                        "diff" to diff
                    )
                )
            }

        } catch (e: SecurityException) {
            logger.warn { "Security violation in code_editing: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to edit file" }
            return ToolResult.error("Failed to edit file: ${e.message}")
        }
    }

    private fun getSandboxRoot(): String {
        return sandbox.getProjectRoot().toString()
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
                "old_string" to mapOf(
                    "type" to "string",
                    "description" to "String to find and replace"
                ),
                "new_string" to mapOf(
                    "type" to "string",
                    "description" to "Replacement string"
                ),
                "replace_all" to mapOf(
                    "type" to "boolean",
                    "description" to "Replace all occurrences (default: false)",
                    "default" to false
                )
            ),
            "required" to listOf("path", "old_string", "new_string")
        )
    }
}
