package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.DiffUtils
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ChangeSummary
import pl.jclab.refio.core.tools.base.FileTool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.logging.dualLogger
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
 *   - replace_all: Replace every occurrence (default: false)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - old_string must be unique in the file (unless replace_all=true)
 * - All edits are validated up front; if any edit fails, no file is written (atomic for
 *   logical failures - a JVM crash mid-write is still not transactional)
 * - File size limits enforced
 */
class MultiEditTool(
    sandbox: PathSandbox,
    private val limits: FileLimits
) : FileTool(sandbox) {

    override val name = "multi_edit"
    override val description = "Atomic search-and-replace across multiple files. FREE."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING
    override val selectionHint = "Atomic search/replace across multiple files or multiple sites in one file."

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

            // Validate and compute every edit in memory before touching disk: if a later edit
            // fails, no earlier file is left half-written (atomic for logical failures). Edits to
            // the same file apply cumulatively - each sees the prior edit's pending content
            // instead of stale disk state.
            val pendingContent = LinkedHashMap<java.nio.file.Path, String>()
            val prepared = parsedEdits.map { edit ->
                val resolvedPath = resolveSandboxPath(edit.path)
                withLockedFile(resolvedPath) {
                    val prep = prepareEdit(edit, pendingContent[resolvedPath])
                    val newContent = if (edit.replaceAll) {
                        prep.originalContent.replace(prep.oldMatch, prep.newMatch)
                    } else {
                        prep.originalContent.replaceFirst(prep.oldMatch, prep.newMatch)
                    }
                    pendingContent[resolvedPath] = newContent
                    prep to newContent
                }
            }

            // Every edit validated and computed - commit the writes.
            val results = prepared.map { (prep, newContent) ->
                withLockedFile(prep.path) {
                    commitEdit(prep, newContent)
                }
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()
            val filesChanged = results.map { it.path }
            val totalReplacements = results.sumOf { it.replacements }
            val totalAdded = results.sumOf { it.changeSummary.addedLines }
            val totalRemoved = results.sumOf { it.changeSummary.removedLines }

            // Aggregate change summary across all edits — agent gets a single rolled-up diff
            // it can scan, plus per-file diffs in metadata.
            val aggregatedSummary = ChangeSummary(
                addedLines = totalAdded,
                removedLines = totalRemoved,
                unifiedDiff = results.mapNotNull { it.changeSummary.unifiedDiff }.joinToString("\n"),
                replacements = totalReplacements
            )

            logger.info { "Successfully applied ${edits.size} edits to ${filesChanged.size} files, ${duration}ms (+$totalAdded/-$totalRemoved)" }

            val allNoop = results.isNotEmpty() && results.all { it.changeSummary.noop }
            return ToolResult(
                success = true,
                output = if (allNoop) {
                    buildString {
                        appendLine("⚠ No changes applied: all ${results.size} edit(s) matched but produced identical content.")
                        appendLine("Per-file: ${results.joinToString(", ") { "${it.path} (${it.replacements} match)" }}")
                        appendLine("Next step: verify new_string differs from old_string, or re-read the files to confirm whether the desired state is already present.")
                    }
                } else {
                    formatResults(results)
                },
                durationMs = duration,
                filesChanged = filesChanged,
                changeSummary = aggregatedSummary,
                metadata = mapOf(
                    "edit_count" to edits.size,
                    "files_changed" to filesChanged.size,
                    "total_replacements" to totalReplacements,
                    "added_lines" to totalAdded,
                    "removed_lines" to totalRemoved,
                    "noop" to allNoop,
                    "diffs" to results.associate { it.path to (it.changeSummary.unifiedDiff ?: "") }
                )
            )

        } catch (e: EditException) {
            logger.warn { "Multi-edit failed: ${e.message}" }
            return ToolResult.error(
                message = "Edit failed: ${e.message}",
                recovery = e.recovery,
                nextActionHints = e.nextActionHints
            )

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
            newString = newString,
            replaceAll = edit["replace_all"] as? Boolean ?: false
        )
    }

    private fun prepareEdit(edit: ParsedEdit, baseContent: String? = null): PreparedEdit {
        // Resolve and validate path
        val path = sandbox.resolve(edit.path)

        logger.debug { "Preparing edit #${edit.index}: relative='${edit.path}', absolute='${path.toAbsolutePath()}'" }

        // Read current content. A non-null baseContent means a prior edit in this batch already
        // produced content for this file (and validated its existence/size), so chain from that
        // pending content instead of re-reading stale disk state.
        val content: String = if (baseContent != null) {
            baseContent
        } else {
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

            Files.readString(path)
        }

        // Reconcile line endings: read_file feeds the model LF, but this file may be CRLF on disk
        // (Windows checkout). Match/replace in the file's own EOL so the edit lands and the file
        // keeps its line endings (no whole-file churn).
        val oldMatch = pl.jclab.refio.core.tools.LineEndings.toFileEol(edit.oldString, content)
        val newMatch = pl.jclab.refio.core.tools.LineEndings.toFileEol(edit.newString, content)

        // Check if old_string exists
        if (!content.contains(oldMatch)) {
            throw EditException(
                "Edit #${edit.index}: String not found in ${edit.path}: '${edit.oldString}'"
            )
        }

        // Check uniqueness if not replaceAll: an ambiguous old_string would silently land on the
        // first match, which is almost never the site the agent meant.
        val occurrences = countOccurrences(content, oldMatch)
        if (!edit.replaceAll && occurrences > 1) {
            throw EditException(
                message = "Edit #${edit.index}: String appears $occurrences times in file ${edit.path}.",
                recovery = "Either pass replace_all=true on this edit to apply to every occurrence, or extend old_string with surrounding context to make it unique.",
                nextActionHints = listOf(
                    "Add more surrounding context to old_string",
                    "Pass replace_all=true on this edit if every occurrence should change"
                )
            )
        }

        return PreparedEdit(
            edit = edit,
            path = path,
            originalContent = content,
            oldMatch = oldMatch,
            newMatch = newMatch,
            replacements = if (edit.replaceAll) occurrences else 1
        )
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

    private fun commitEdit(prep: PreparedEdit, newContent: String): EditResult {
        // newContent was computed during the validation pass, which released the file lock before
        // this one was taken. Another writer (a second agent in the same run) could have landed in
        // between, and persisting the pre-computed content would silently drop its change. Fail
        // loud instead. This narrows the window to the gap between this read and the write below;
        // it does not remove it.
        val onDisk = try {
            Files.readString(prep.path)
        } catch (e: Exception) {
            throw EditException(
                message = "Edit #${prep.edit.index}: ${prep.edit.path} became unreadable before the write: ${e.message}",
                recovery = "Re-read the file and reapply the edit."
            )
        }
        if (onDisk != prep.originalContent) {
            throw EditException(
                message = "Edit #${prep.edit.index}: ${prep.edit.path} changed on disk after the edit was validated.",
                recovery = "Nothing was written for this file. Re-read it and reapply the edit against the current content.",
                nextActionHints = listOf(
                    "read_file(path=\"${prep.edit.path}\") to see the current content",
                    "Reapply the edit with an old_string taken from the fresh content"
                )
            )
        }

        // Build the change summary before writing: a failure there then leaves the file untouched.
        val changeSummary = DiffUtils.buildChangeSummary(
            originalContent = prep.originalContent,
            newContent = newContent,
            filePath = prep.edit.path,
            replacements = prep.replacements
        )

        Files.writeString(prep.path, newContent)
        val newFileSize = prep.path.fileSize()
        logger.debug { "Applied edit #${prep.edit.index}: ${prep.edit.path}, size: ${prep.originalContent.length} → ${newContent.length} chars, fileSize=$newFileSize bytes, +${changeSummary.addedLines}/-${changeSummary.removedLines}" }

        return EditResult(
            path = prep.edit.path,
            replacements = prep.replacements,
            oldLength = prep.originalContent.length,
            newLength = newContent.length,
            changeSummary = changeSummary
        )
    }

    private fun formatResults(results: List<EditResult>): String {
        val lines = results.mapIndexed { index, result ->
            "${index + 1}. ${result.path}: ${result.replacements} replacement(s) (+${result.changeSummary.addedLines}/-${result.changeSummary.removedLines})"
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
                            ),
                            "replace_all" to mapOf(
                                "type" to "boolean",
                                "description" to "Replace all occurrences (default: false). When false, old_string must appear exactly once in the file",
                                "default" to false
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
        val newString: String,
        val replaceAll: Boolean
    )

    private data class PreparedEdit(
        val edit: ParsedEdit,
        val path: java.nio.file.Path,
        val originalContent: String,
        // edit.oldString / edit.newString re-expressed in the file's line-ending convention.
        val oldMatch: String,
        val newMatch: String,
        val replacements: Int
    )

    private data class EditResult(
        val path: String,
        val replacements: Int,
        val oldLength: Int,
        val newLength: Int,
        val changeSummary: ChangeSummary
    )

    private class EditException(
        message: String,
        val recovery: String? = null,
        val nextActionHints: List<String>? = null
    ) : Exception(message)
}
