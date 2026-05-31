package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.DiffUtils
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.FileTool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.fileSize

private val logger = dualLogger("CreateNewFileTool")

/**
 * Create New File Tool - creates a new file with content
 *
 * Parameters:
 * - path: Relative file path
 * - content: File content to write
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Creates parent directories if needed
 * - Returns warning (success=true) if file already exists (use code_editing instead)
 */
class CreateNewFileTool(
    sandbox: PathSandbox,
    private val limits: FileLimits
) : FileTool(sandbox) {

    override val name = "create_new_file"
    override val description = "Create a NEW SMALL file (config, stub, short snippet) " +
        "where the agent already has the full content ready. " +
        "Strongly prefer `advance_code_editing` for code files > ~50 lines, HTML pages, full classes, " +
        "or any content generated from scratch — that tool uses a dedicated LLM call so your agent " +
        "response stays small and avoids streaming timeouts. Stuffing hundreds of lines into `content` " +
        "here inflates the agent response, wastes tokens, and risks truncation. " +
        "HARD FAILS if file already exists. Pre-check path in a PRIOR turn (file_search/read_directory). " +
        "On 'File already exists' error: switch to read_file + code_editing."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING
    override val selectionHint =
        "ONLY for small new files (≤50 lines): configs, stubs, short snippets where you already have the exact final content. " +
        "For HTML pages, full classes, scripts, games, or anything >50 lines: STOP — use `advance_code_editing` instead. " +
        "Stuffing 200–900 lines into the `content` parameter blows your output-token budget (10K+ wasted tokens), " +
        "risks streaming truncation, and bloats every subsequent turn's conversation history. " +
        "`advance_code_editing` delegates generation to the editing model so your agent response stays small."

    override fun validateParams(params: Map<String, Any>) {
        validatePathParam(params)
        if (params["content"] == null) {
            throw IllegalArgumentException("Parameter 'content' is required")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            val pathStr = validatePathParam(params)
            val content = params["content"] as? String
                ?: return ToolResult.error("Missing required parameter: 'content'")

            if (content.length > limits.maxFileSize) {
                return ToolResult.error(
                    "Content too large: ${content.length} bytes (max ${limits.maxFileSize} bytes)"
                )
            }

            val path = resolveSandboxPath(pathStr)

            logger.info { "Creating file: relative='$pathStr', absolute='${path.toAbsolutePath()}', contentSize=${content.length} chars, lineCount=${content.lines().size}" }

            return withLockedFile(path) {

                // Check if file already exists.
                // We return success=false (a real failure) rather than a soft warning, because
                // create_new_file MUST NOT silently no-op: prior behaviour caused models to think
                // creation succeeded and continue with stale assumptions about file content.
                if (path.exists()) {
                    logger.warn { "File already exists: $pathStr (resolved to ${path.toAbsolutePath()})" }
                    return@withLockedFile ToolResult.error(
                        message = "File already exists: $pathStr. create_new_file refuses to overwrite.",
                        // The prior recovery hedged with "decide whether it already satisfies the
                        // request (it might — skip the write and report it)". On a "save/write the
                        // report to <path>" task that clause backfired: models saw the file existed,
                        // declared success, and either printed the content into chat or ended the turn
                        // WITHOUT ever writing the file (observed manual-test sessions 06320e8a /
                        // 9467f4a9). Make the recovery decisive: if the task is to write content,
                        // OVERWRITE it this turn; only skip after confirming the existing content
                        // already matches.
                        recovery = "DO NOT retry create_new_file for this path — it will fail again. " +
                            "The file ALREADY EXISTS with older content. If your task is to WRITE/SAVE " +
                            "content to $pathStr, you MUST overwrite it in THIS SAME turn: call " +
                            "advance_code_editing(path=$pathStr) to replace the whole file with your " +
                            "intended content, or code_editing / multi_line_editor for a partial edit. " +
                            "Printing the content into chat does NOT write the file and does NOT satisfy " +
                            "the request. Only skip the write if you have first CONFIRMED via " +
                            "read_file($pathStr) that the existing content already matches what was asked.",
                        nextActionHints = listOf(
                            "advance_code_editing path=$pathStr — overwrite the whole file with the intended content (use for 'save/write the report' tasks)",
                            "code_editing — for small targeted edits to the existing file",
                            "multi_line_editor — for semantic edits where exact strings are unknown",
                            "read_file path=$pathStr — only to CONFIRM existing content before deciding to skip"
                        )
                    )
                }

                // Check if parent is a directory
                val parent = path.parent
                if (parent != null && parent.exists() && !parent.isDirectory()) {
                    return@withLockedFile ToolResult.error("Parent path exists but is not a directory: ${parent.fileName}")
                }

                // Create parent directories if needed
                if (parent != null && !parent.exists()) {
                    Files.createDirectories(parent)
                    logger.info { "Created parent directories: ${parent.fileName}" }
                }

                // Write file
                Files.writeString(path, content)
                val duration = (System.currentTimeMillis() - startTime).toInt()
                val createdFileSize = path.fileSize()

                val changeSummary = DiffUtils.buildChangeSummary(
                    originalContent = "",
                    newContent = content,
                    filePath = pathStr,
                    created = true
                )

                logger.info { "Successfully created file: $pathStr (${content.length} chars, ${duration}ms, size: $createdFileSize bytes, absolute='${path.toAbsolutePath()}')" }

                ToolResult(
                    success = true,
                    output = "File created successfully: $pathStr (${changeSummary.addedLines} lines)",
                    bytesWritten = content.toByteArray().size,
                    durationMs = duration,
                    filesChanged = listOf(pathStr),
                    changeSummary = changeSummary,
                    metadata = mapOf(
                        "path" to pathStr,
                        "line_count" to content.lines().size,
                        "char_count" to content.length,
                        "added_lines" to changeSummary.addedLines
                    )
                )
            }

        } catch (e: SecurityException) {
            logger.warn { "Security violation in create_new_file: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to create file" }
            return ToolResult.error("Failed to create file: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative path for the new file."
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "File content."
                )
            ),
            "required" to listOf("path", "content")
        )
    }
}
