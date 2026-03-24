package pl.jclab.refio.core.tools.implementations

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
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "create_new_file"
    override val description = "Create a new file with simple text content. **Use this tool for:** configuration files (json, yaml, xml), documentation (md, txt), data files (csv), and other non-code files. **For code files** (html, js, ts, php, java, python, kotlin, etc.), prefer advance_code_editing which uses LLM for better code generation."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_MODIFYING

    override fun validateParams(params: Map<String, Any>) {
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }
        if (params["content"] == null) {
            throw IllegalArgumentException("Parameter 'content' is required")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val pathStr = params["path"] as? String
                ?: return ToolResult.error("Missing required parameter: 'path'")
            val content = params["content"] as? String
                ?: return ToolResult.error("Missing required parameter: 'content'")

            // Check content size
            if (content.length > limits.maxFileSize) {
                return ToolResult.error(
                    "Content too large: ${content.length} bytes (max ${limits.maxFileSize} bytes)"
                )
            }

            // Normalize path for security (bare filenames → "./file.txt", backslash → forward slash)
            val normalizedPathStr = normalizePath(pathStr)

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Creating file: relative='$pathStr', absolute='${path.toAbsolutePath()}', contentSize=${content.length} chars, lineCount=${content.lines().size}" }

            return FileLockManager.withFileLock(path.toAbsolutePath().toString()) {
                // Check if file already exists
                if (path.exists()) {
                    logger.warn { "File already exists: $pathStr (resolved to ${path.toAbsolutePath()})" }
                    val duration = (System.currentTimeMillis() - startTime).toInt()
                    return@withFileLock ToolResult(
                        success = true,
                        output = "⚠️ Warning: File already exists: $pathStr (skipped, use code_editing to modify existing files)",
                        bytesWritten = 0,
                        durationMs = duration,
                        filesChanged = emptyList(),
                        metadata = mapOf(
                            "path" to pathStr,
                            "warning" to "file_already_exists"
                        )
                    )
                }

                // Check if parent is a directory
                val parent = path.parent
                if (parent != null && parent.exists() && !parent.isDirectory()) {
                    return@withFileLock ToolResult.error("Parent path exists but is not a directory: ${parent.fileName}")
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

                logger.info { "Successfully created file: $pathStr (${content.length} chars, ${duration}ms, size: $createdFileSize bytes, absolute='${path.toAbsolutePath()}')" }

                ToolResult(
                    success = true,
                    output = "File created successfully: $pathStr",
                    bytesWritten = content.toByteArray().size,
                    durationMs = duration,
                    filesChanged = listOf(pathStr),
                    metadata = mapOf(
                        "path" to pathStr,
                        "line_count" to content.lines().size,
                        "char_count" to content.length
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
                    "description" to "Relative path for the new file (prefer this tool for .md, .txt, .json, .yaml, .xml, .csv files)"
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "Content to write to the file. For code files (html, js, ts, php, java, python, etc.), use advance_code_editing instead for better LLM-generated code quality."
                )
            ),
            "required" to listOf("path", "content")
        )
    }
}
