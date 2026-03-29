package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isDirectory

private val logger = dualLogger("ViewDiffTool")

/**
 * View Diff Tool - shows differences between two files or file versions
 *
 * Parameters:
 * - file1: First file path
 * - file2: Second file path (optional if content2 provided)
 * - content2: Alternative content to compare (instead of file2)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Read-only operation
 */
class ViewDiffTool(
    private val sandbox: PathSandbox
) : Tool {

    override val name = "view_diff"
    override val description = "View differences between files or content"
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        if (params["file1"] == null || (params["file1"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'file1' is required and cannot be empty")
        }
        val file2Str = params["file2"] as? String
        val content2 = params["content2"] as? String
        if (file2Str == null && content2 == null) {
            throw IllegalArgumentException("Either 'file2' or 'content2' parameter is required")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val file1Str = params["file1"] as? String
                ?: return ToolResult.error("Missing required parameter: 'file1'")

            val file2Str = params["file2"] as? String
            val content2 = params["content2"] as? String

            // Read first file
            val path1 = sandbox.resolve(file1Str)

            logger.info { "Computing diff: file1='$file1Str' (absolute='${path1.toAbsolutePath()}'), file2=${file2Str ?: "<content>"}" }

            if (!path1.exists()) {
                logger.warn { "File1 not found: $file1Str (resolved to ${path1.toAbsolutePath()})" }
                return ToolResult.error("File1 not found: $file1Str")
            }
            if (!path1.isRegularFile()) {
                logger.warn { "File1 is not a regular file: $file1Str (is directory: ${path1.isDirectory()})" }
                return ToolResult.error("File1 is not a regular file: $file1Str")
            }

            val file1Size = Files.size(path1)
            logger.debug { "File1 size: $file1Size bytes, absolute='${path1.toAbsolutePath()}'" }

            val content1 = Files.readString(path1)

            // Get second content
            val secondContent = if (content2 != null) {
                logger.debug { "Using content2 (${content2.length} chars) for comparison" }
                content2
            } else {
                val path2 = sandbox.resolve(file2Str!!)
                logger.debug { "File2: relative='$file2Str', absolute='${path2.toAbsolutePath()}'" }

                if (!path2.exists()) {
                    logger.warn { "File2 not found: $file2Str (resolved to ${path2.toAbsolutePath()})" }
                    return ToolResult.error("File2 not found: $file2Str")
                }
                if (!path2.isRegularFile()) {
                    logger.warn { "File2 is not a regular file: $file2Str (is directory: ${path2.isDirectory()})" }
                    return ToolResult.error("File2 is not a regular file: $file2Str")
                }

                val file2Size = Files.size(path2)
                logger.debug { "File2 size: $file2Size bytes, absolute='${path2.toAbsolutePath()}'" }

                Files.readString(path2)
            }

            // Compute diff
            val diff = computeSimpleDiff(content1.lines(), secondContent.lines())
            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "Diff computed: ${diff.added} added, ${diff.removed} removed, ${duration}ms" }

            return ToolResult(
                success = true,
                output = diff.formatted,
                durationMs = duration,
                metadata = mapOf(
                    "added_lines" to diff.added,
                    "removed_lines" to diff.removed,
                    "unchanged_lines" to diff.unchanged,
                    "file1" to file1Str,
                    "file2" to (file2Str ?: "<content>")
                )
            )

        } catch (e: SecurityException) {
            logger.warn { "Security violation in view_diff: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to compute diff" }
            return ToolResult.error("Failed to compute diff: ${e.message}")
        }
    }

    /**
     * Simple line-by-line diff implementation
     * This is a basic implementation - for production, consider using a proper diff library
     */
    private fun computeSimpleDiff(lines1: List<String>, lines2: List<String>): DiffResult {
        val result = mutableListOf<String>()
        var added = 0
        var removed = 0
        var unchanged = 0

        val maxLines = maxOf(lines1.size, lines2.size)

        for (i in 0 until maxLines) {
            val line1 = lines1.getOrNull(i)
            val line2 = lines2.getOrNull(i)

            when {
                line1 != null && line2 != null -> {
                    if (line1 == line2) {
                        result.add("  $line1")
                        unchanged++
                    } else {
                        result.add("- $line1")
                        result.add("+ $line2")
                        removed++
                        added++
                    }
                }
                line1 != null && line2 == null -> {
                    result.add("- $line1")
                    removed++
                }
                line1 == null && line2 != null -> {
                    result.add("+ $line2")
                    added++
                }
            }
        }

        return DiffResult(
            formatted = result.joinToString("\n"),
            added = added,
            removed = removed,
            unchanged = unchanged
        )
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file1" to mapOf(
                    "type" to "string",
                    "description" to "First file path"
                ),
                "file2" to mapOf(
                    "type" to "string",
                    "description" to "Second file path (optional if content2 provided)"
                ),
                "content2" to mapOf(
                    "type" to "string",
                    "description" to "Alternative content to compare against file1"
                )
            ),
            "required" to listOf("file1")
        )
    }

    private data class DiffResult(
        val formatted: String,
        val added: Int,
        val removed: Int,
        val unchanged: Int
    )
}
