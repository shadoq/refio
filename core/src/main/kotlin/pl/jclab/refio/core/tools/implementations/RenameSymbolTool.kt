package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.refactor.StructuralRefactorer

private val logger = dualLogger("RenameSymbolTool")

/**
 * Rename Symbol Tool - renames a symbol across the whole project in one call.
 *
 * Parameters:
 * - file: Relative path of a file containing the symbol (anchor for semantic engines)
 * - line: 1-based line number of the symbol in that file
 * - old_name: Current symbol name
 * - new_name: New symbol name
 *
 * The actual engine is injected: IDE refactoring engine inside the IntelliJ plugin,
 * word-boundary text replace in CLI/headless. The description states the active guarantee.
 */
class RenameSymbolTool(
    private val refactorer: StructuralRefactorer
) : Tool {

    override val name = "rename_symbol"
    override val description =
        "Rename a symbol project-wide in one call (engine: ${refactorer.engineDescription}). " +
        "Prefer this over manual per-file edits for renames."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING
    override val selectionHint =
        "Project-wide symbol rename in one call. Prefer over grep + per-file code_editing for renames."

    override fun validateParams(params: Map<String, Any>) {
        if ((params["file"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'file' is required and cannot be empty")
        }
        if ((params["old_name"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'old_name' is required and cannot be empty")
        }
        if ((params["new_name"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'new_name' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        try {
            val file = params["file"] as? String
                ?: return ToolResult.error("Missing required parameter: 'file'")
            val line = (params["line"] as? Number)?.toInt() ?: 1
            val oldName = params["old_name"] as? String
                ?: return ToolResult.error("Missing required parameter: 'old_name'")
            val newName = params["new_name"] as? String
                ?: return ToolResult.error("Missing required parameter: 'new_name'")

            if (oldName == newName) {
                return ToolResult.error(
                    message = "old_name and new_name are identical: '$oldName'",
                    recovery = "Pass a new_name that differs from old_name."
                )
            }

            logger.info { "Renaming symbol '$oldName' -> '$newName' (anchor $file:$line)" }
            val result = refactorer.renameSymbol(file, line, oldName, newName)
            val duration = (System.currentTimeMillis() - startTime).toInt()

            if (result.filesChanged.isEmpty()) {
                return ToolResult.error(
                    message = "Symbol '$oldName' not found in any project file.",
                    recovery = "Verify the symbol name with find_usages or grep_search, then retry.",
                    nextActionHints = listOf(
                        "find_usages(symbol_name=\"$oldName\") to check the symbol exists",
                        "Check for a typo in old_name"
                    )
                )
            }

            return ToolResult(
                success = true,
                output = buildString {
                    appendLine("Renamed '$oldName' -> '$newName': ${result.replacements} occurrence(s) in ${result.filesChanged.size} file(s).")
                    result.filesChanged.forEach { appendLine("  $it") }
                }.trimEnd(),
                durationMs = duration,
                filesChanged = result.filesChanged,
                metadata = mapOf(
                    "old_name" to oldName,
                    "new_name" to newName,
                    "replacements" to result.replacements,
                    "files_changed" to result.filesChanged.size
                )
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(
                message = "Invalid parameters: ${e.message}",
                recovery = "Symbol names must be plain identifiers (letters, digits, underscore)."
            )
        } catch (e: SecurityException) {
            logger.warn { "Security violation in rename_symbol: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")
        } catch (e: Exception) {
            logger.error(e) { "Failed to rename symbol" }
            return ToolResult.error("Failed to rename symbol: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file" to mapOf(
                    "type" to "string",
                    "description" to "Relative path of a file containing the symbol (declaration preferred)"
                ),
                "line" to mapOf(
                    "type" to "integer",
                    "description" to "1-based line number of the symbol in that file",
                    "default" to 1
                ),
                "old_name" to mapOf(
                    "type" to "string",
                    "description" to "Current symbol name (plain identifier)"
                ),
                "new_name" to mapOf(
                    "type" to "string",
                    "description" to "New symbol name (plain identifier)"
                )
            ),
            "required" to listOf("file", "line", "old_name", "new_name")
        )
    }
}
