package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.tools.security.LimitExceededException
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.*

private val logger = dualLogger("ReadDirectoryTool")

/**
 * Read Directory Tool - lists files and directories
 *
 * Parameters:
 * - path: Relative directory path (default: ".")
 * - recursive: Whether to list recursively (default: false)
 * - max_depth: Maximum recursion depth (default: 3)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - File count limits enforced
 * - Depth limits for recursive listing
 */
class ReadDirectoryTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "read_directory"
    override val description = "List files and directories within the project"
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        // Path is optional - defaults to "."
        if (params["path"] != null && (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' cannot be empty (use \".\" for current directory or omit parameter)")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters
            val pathStr = (params["path"] as? String) ?: "."
            val recursive = (params["recursive"] as? Boolean) ?: false
            val maxDepth = (params["max_depth"] as? Number)?.toInt() ?: 3

            // Validate max depth
            val effectiveMaxDepth = maxDepth.coerceAtMost(limits.maxSearchDepth)

            // Normalize path for security (bare filenames → "./file.txt", backslash → forward slash)
            val normalizedPathStr = normalizePath(pathStr)

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Listing directory: relative='$pathStr', absolute='${path.toAbsolutePath()}', recursive=$recursive, maxDepth=$effectiveMaxDepth" }

            // Check if directory exists
            if (!Files.exists(path)) {
                logger.warn { "Directory not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                return ToolResult.error("Directory not found: $pathStr")
            }

            // Check if it's a directory
            if (!path.isDirectory()) {
                logger.warn { "Not a directory: $pathStr (is file: ${path.isRegularFile()})" }
                return ToolResult.error("Not a directory: $pathStr")
            }

            // List files
            val filesList = if (recursive) {
                listRecursive(path, effectiveMaxDepth)
            } else {
                listSingleLevel(path)
            }

            // Check file count limit
            if (filesList.size > limits.maxFilesInDirectory) {
                logger.warn { "Directory listing exceeded limit: ${filesList.size} > ${limits.maxFilesInDirectory}" }
                throw LimitExceededException(
                    "Too many files: ${filesList.size} (max ${limits.maxFilesInDirectory})"
                )
            }

            // Format output
            val output = formatFileList(filesList)
            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "Successfully listed directory: $pathStr (${filesList.size} entries, ${duration}ms)" }

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                metadata = mapOf(
                    "file_count" to filesList.size,
                    "directory_count" to filesList.count { it.isDirectory },
                    "path" to pathStr,
                    "recursive" to recursive
                )
            )

        } catch (e: SecurityException) {
            logger.warn { "Security violation in read_directory: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: LimitExceededException) {
            return ToolResult.error(e.message ?: "Limit exceeded")

        } catch (e: Exception) {
            logger.error(e) { "Failed to list directory" }
            return ToolResult.error("Failed to list directory: ${e.message}")
        }
    }

    private fun listSingleLevel(path: java.nio.file.Path): List<FileEntry> {
        return Files.list(path).use { stream ->
            stream.map { file ->
                FileEntry(
                    name = file.name,
                    relativePath = sandbox.resolve(".").relativize(file).toString(),
                    isDirectory = file.isDirectory(),
                    size = if (file.isRegularFile()) file.fileSize() else 0L,
                    lastModified = file.getLastModifiedTime().toMillis()
                )
            }.toList()
        }
    }

    private fun listRecursive(path: java.nio.file.Path, maxDepth: Int): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()

        Files.walk(path, maxDepth).use { stream ->
            stream.forEach { file ->
                // Skip the root directory itself
                if (file != path) {
                    entries.add(
                        FileEntry(
                            name = file.name,
                            relativePath = sandbox.resolve(".").relativize(file).toString(),
                            isDirectory = file.isDirectory(),
                            size = if (file.isRegularFile()) file.fileSize() else 0L,
                            lastModified = file.getLastModifiedTime().toMillis(),
                            depth = path.relativize(file).nameCount
                        )
                    )
                }
            }
        }

        return entries.sortedWith(compareBy({ it.depth }, { it.relativePath }))
    }

    private fun formatFileList(files: List<FileEntry>): String {
        if (files.isEmpty()) {
            return "(empty directory)"
        }

        val lines = files.map { file ->
            val type = if (file.isDirectory) "DIR " else "FILE"
            val size = if (file.isDirectory) "     " else formatSize(file.size)
            val indent = "  ".repeat(file.depth)
            "$indent$type  $size  ${file.relativePath}"
        }

        return lines.joinToString("\n")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative directory path (default: current directory)",
                    "default" to "."
                ),
                "recursive" to mapOf(
                    "type" to "boolean",
                    "description" to "List files recursively",
                    "default" to false
                ),
                "max_depth" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum recursion depth",
                    "default" to 3
                )
            ),
            "required" to emptyList<String>()
        )
    }

    private data class FileEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val depth: Int = 0
    )
}
