package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.DiffUtils
import java.nio.file.Files
import java.nio.file.Path

private val logger = dualLogger("ProposedChangeBuilder")

/**
 * The concrete file change an editing tool is about to make, attached to a tool
 * approval request so the user can review a diff BEFORE the write happens.
 *
 * For small files both full contents travel with the request; for large files
 * (over [ProposedChangeBuilder.FULL_CONTENT_MAX_LINES] lines) only the unified
 * diff is carried and [oldContent]/[newContent] are null.
 */
data class ProposedChange(
    val filePath: String,
    val oldContent: String?,
    val newContent: String?,
    val unifiedDiff: String,
    val diffTruncated: Boolean = false
)

/**
 * Builds a [ProposedChange] from a tool call's arguments, before the tool executes.
 *
 * Only tools whose arguments fully determine the new file content are supported:
 * - create_new_file: content is in the arguments
 * - code_editing: old_string/new_string replacement simulated on current content
 * - advance_code_editing with old_string/new_string arguments (the LLM-assisted
 *   edit_description variant generates content only during execution, so no
 *   preview is possible for it)
 * - multi_edit: sequential old_string/new_string replacements, single-file batches
 *
 * multi_line_editor and multi-file multi_edit batches return null (no preview).
 * This is a best-effort informational payload: any failure returns null and the
 * approval flow proceeds without a diff.
 */
class ProposedChangeBuilder(private val projectRoot: Path) {

    companion object {
        /** Above this line count (old or new content) only the diff is carried. */
        const val FULL_CONTENT_MAX_LINES = 200

        /** Hard cap on diff lines carried in the approval request. */
        const val MAX_DIFF_LINES = 400

        const val TRUNCATION_MARKER = "... (diff truncated)"
    }

    fun build(toolName: String, arguments: Map<String, Any>): ProposedChange? {
        return try {
            when (toolName.lowercase()) {
                "create_new_file" -> buildForCreate(arguments)
                "code_editing", "advance_code_editing" -> buildForReplacement(arguments)
                "multi_edit" -> buildForMultiEdit(arguments)
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "[PROPOSED_CHANGE] Skipping preview for $toolName: ${e.message}" }
            null
        }
    }

    private fun buildForCreate(arguments: Map<String, Any>): ProposedChange? {
        val path = arguments["path"] as? String ?: return null
        val content = arguments["content"] as? String ?: return null
        if (!isInsideRoot(path)) {
            return null
        }
        val oldContent = readFileOrNull(path) ?: ""
        return assemble(path, oldContent, content)
    }

    private fun buildForReplacement(arguments: Map<String, Any>): ProposedChange? {
        val path = arguments["path"] as? String ?: return null
        val oldString = arguments["old_string"] as? String ?: return null
        val newString = arguments["new_string"] as? String ?: return null
        if (!isInsideRoot(path)) {
            return null
        }
        val oldContent = readFileOrNull(path)
            ?: if (oldString.isEmpty()) "" else return null
        val newContent = if (oldString.isEmpty()) {
            newString
        } else {
            if (!oldContent.contains(oldString)) {
                return null
            }
            if (arguments["replace_all"] == true) {
                oldContent.replace(oldString, newString)
            } else {
                oldContent.replaceFirst(oldString, newString)
            }
        }
        return assemble(path, oldContent, newContent)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildForMultiEdit(arguments: Map<String, Any>): ProposedChange? {
        val edits = arguments["edits"] as? List<Map<String, Any>> ?: return null
        if (edits.isEmpty()) {
            return null
        }
        val paths = edits.mapNotNull { it["path"] as? String }.distinct()
        // Multi-file batches have no single before/after pair to preview.
        if (paths.size != 1) {
            return null
        }
        val path = paths.first()
        val oldContent = readFileOrNull(path) ?: return null
        var newContent = oldContent
        for (edit in edits) {
            val oldString = edit["old_string"] as? String ?: return null
            val newString = edit["new_string"] as? String ?: return null
            if (!newContent.contains(oldString)) {
                return null
            }
            newContent = newContent.replaceFirst(oldString, newString)
        }
        return assemble(path, oldContent, newContent)
    }

    private fun assemble(path: String, oldContent: String, newContent: String): ProposedChange {
        val fullDiff = DiffUtils.generateUnifiedDiff(oldContent, newContent, path)
        val diffLines = fullDiff.lines()
        val truncated = diffLines.size > MAX_DIFF_LINES
        val diff = if (truncated) {
            (diffLines.take(MAX_DIFF_LINES) + TRUNCATION_MARKER).joinToString("\n")
        } else {
            fullDiff
        }
        val large = oldContent.lines().size > FULL_CONTENT_MAX_LINES ||
            newContent.lines().size > FULL_CONTENT_MAX_LINES
        return ProposedChange(
            filePath = path,
            oldContent = if (large) null else oldContent,
            newContent = if (large) null else newContent,
            unifiedDiff = diff,
            diffTruncated = truncated
        )
    }

    private fun isInsideRoot(relativePath: String): Boolean {
        val resolved = projectRoot.resolve(relativePath).normalize()
        return resolved.startsWith(projectRoot.normalize())
    }

    /** Read-only, sandbox-bounded lookup of the current file content. */
    private fun readFileOrNull(relativePath: String): String? {
        if (!isInsideRoot(relativePath)) {
            return null
        }
        val resolved = projectRoot.resolve(relativePath).normalize()
        if (!Files.isRegularFile(resolved)) {
            return null
        }
        return Files.readString(resolved)
    }
}
