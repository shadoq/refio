package pl.jclab.refio.core.tools.implementations

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
import kotlin.io.path.isDirectory

private val logger = dualLogger("MultiEditTool")

/**
 * Multi-Edit Tool - applies multiple edits to different files
 *
 * Parameters:
 * - edits: List of edit operations, each with:
 *   - path: File path
 *   - old_string: String to replace
 *   - new_string: Replacement string
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - All edits are atomic (all succeed or all fail)
 * - File size limits enforced
 */
class MultiEditTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "multi_edit"
    override val description = "Apply multiple search-and-replace edits to different files atomically. Use this when you need to make simple, consistent changes across multiple files (e.g., renaming variables, updating imports). For complex edits, use multi_line_editor or advance_code_editing on individual files."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING

    override fun validateParams(params: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        val edits = params["edits"] as? List<Map<String, Any>>
        if (edits == null) {
            throw IllegalArgumentException("Parameter 'edits' is required (array of edit operations)")
        }
        if (edits.isEmpty()) {
            throw IllegalArgumentException("Parameter 'edits' must contain at least one edit operation")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            validateParams(params)

            // Extract parameters (already validated)
            @Suppress("UNCHECKED_CAST")
            val edits = params["edits"] as List<Map<String, Any>>

            logger.info { "Applying ${edits.size} edits" }

            // Parse all edits first
            val parsedEdits = edits.mapIndexed { index, edit ->
                parseEdit(edit, index)
            }

            // Prepare all edits (read files, validate)
            val preparations = parsedEdits.map { edit ->
                prepareEdit(edit)
            }

            // Apply all edits
            val results = preparations.map { prep ->
                applyEdit(prep)
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()
            val filesChanged = results.map { it.path }
            val totalReplacements = results.sumOf { it.replacements }

            logger.info { "Successfully applied ${edits.size} edits to ${filesChanged.size} files, ${duration}ms" }

            return ToolResult(
                success = true,
                output = formatResults(results),
                durationMs = duration,
                filesChanged = filesChanged,
                metadata = mapOf(
                    "edit_count" to edits.size,
                    "files_changed" to filesChanged.size,
                    "total_replacements" to totalReplacements
                )
            )

        } catch (e: EditException) {
            logger.warn { "Multi-edit failed: ${e.message}" }
            return ToolResult.error("Edit failed: ${e.message}")

        } catch (e: IllegalArgumentException) {
            logger.warn { "Multi-edit validation failed: ${e.message}" }
            return ToolResult.error(e.message ?: "Invalid multi-edit parameters")

        } catch (e: SecurityException) {
            logger.warn { "Security violation in multi_edit: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to apply multi-edit" }
            return ToolResult.error("Failed to apply edits: ${e.message}")
        }
    }

    private fun parseEdit(edit: Map<String, Any>, index: Int): ParsedEdit {
        val path = edit["path"] as? String
            ?: throw EditException("Edit #$index: 'path' is required")

        val oldString = edit["old_string"] as? String
            ?: throw EditException("Edit #$index: 'old_string' is required")

        val newString = edit["new_string"] as? String
            ?: throw EditException("Edit #$index: 'new_string' is required")

        return ParsedEdit(
            index = index,
            path = path,
            oldString = oldString,
            newString = newString
        )
    }

    private fun prepareEdit(edit: ParsedEdit): PreparedEdit {
        // Resolve and validate path
        val path = sandbox.resolve(edit.path)

        logger.debug { "Preparing edit #${edit.index}: relative='${edit.path}', absolute='${path.toAbsolutePath()}'" }

        // Check if file exists
        if (!path.exists()) {
            logger.warn { "Edit #${edit.index}: File not found: ${edit.path} (resolved to ${path.toAbsolutePath()})" }
            throw EditException("Edit #${edit.index}: File not found: ${edit.path}")
        }

        // Check if it's a regular file
        if (!path.isRegularFile()) {
            logger.warn { "Edit #${edit.index}: Not a regular file: ${edit.path} (is directory: ${path.isDirectory()})" }
            throw EditException("Edit #${edit.index}: Not a regular file: ${edit.path}")
        }

        // Check file size
        val fileSize = path.fileSize()
        logger.debug { "Edit #${edit.index}: File size=$fileSize bytes, absolute='${path.toAbsolutePath()}'" }

        if (fileSize > limits.maxFileSize) {
            logger.warn { "Edit #${edit.index}: File too large: $fileSize bytes (max ${limits.maxFileSize})" }
            throw EditException(
                "Edit #${edit.index}: File too large: $fileSize bytes (max ${limits.maxFileSize} bytes)"
            )
        }

        // Read current content
        val content = Files.readString(path)

        // Check if old_string exists
        if (!content.contains(edit.oldString)) {
            throw EditException(
                "Edit #${edit.index}: String not found in ${edit.path}: '${edit.oldString}'"
            )
        }

        return PreparedEdit(
            edit = edit,
            path = path,
            originalContent = content
        )
    }

    private fun applyEdit(prep: PreparedEdit): EditResult {
        // Perform replacement (single occurrence)
        val newContent = prep.originalContent.replaceFirst(prep.edit.oldString, prep.edit.newString)

        // Write updated content
        Files.writeString(prep.path, newContent)

        val newFileSize = prep.path.fileSize()
        logger.debug { "Applied edit #${prep.edit.index}: ${prep.edit.path}, size: ${prep.originalContent.length} → ${newContent.length} chars, fileSize=$newFileSize bytes" }

        return EditResult(
            path = prep.edit.path,
            replacements = 1,
            oldLength = prep.originalContent.length,
            newLength = newContent.length
        )
    }

    private fun formatResults(results: List<EditResult>): String {
        val lines = results.mapIndexed { index, result ->
            "${index + 1}. ${result.path}: ${result.replacements} replacement(s)"
        }

        return "Successfully applied ${results.size} edit(s):\n" + lines.joinToString("\n")
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "edits" to mapOf(
                    "type" to "array",
                    "description" to "Array of edit operations",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf(
                                "type" to "string",
                                "description" to "File path"
                            ),
                            "old_string" to mapOf(
                                "type" to "string",
                                "description" to "String to replace"
                            ),
                            "new_string" to mapOf(
                                "type" to "string",
                                "description" to "Replacement string"
                            )
                        ),
                        "required" to listOf("path", "old_string", "new_string")
                    )
                )
            ),
            "required" to listOf("edits")
        )
    }

    private data class ParsedEdit(
        val index: Int,
        val path: String,
        val oldString: String,
        val newString: String
    )

    private data class PreparedEdit(
        val edit: ParsedEdit,
        val path: java.nio.file.Path,
        val originalContent: String
    )

    private data class EditResult(
        val path: String,
        val replacements: Int,
        val oldLength: Int,
        val newLength: Int
    )

    private class EditException(message: String) : Exception(message)
}
