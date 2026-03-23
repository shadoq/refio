package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.tools.security.FileTooLargeException
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isDirectory

private val logger = dualLogger("ReadFileTool")

/**
 * Read File Tool - reads contents of a file
 *
 * Parameters:
 * - path: Relative file path within project
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - File size limits enforced
 * - Only UTF-8 text files supported
 */
class ReadFileTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "read_file"
    override val description = "Read contents of a text file within the project"
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val pathStr = params["path"] as? String
                ?: return ToolResult.error("Missing required parameter: 'path'")

            // Normalize path for security (bare filenames → "./file.txt", backslash → forward slash)
            val normalizedPathStr = normalizePath(pathStr)

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Reading file: relative='$pathStr', absolute='${path.toAbsolutePath()}', sandbox_root='${getSandboxRoot()}'" }

            // Check if file exists
            if (!Files.exists(path)) {
                logger.warn { "File not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                return ToolResult.error("File not found: $pathStr")
            }

            // Check if it's a regular file
            if (!path.isRegularFile()) {
                logger.warn { "Not a regular file: $pathStr (is directory: ${path.isDirectory()})" }
                return ToolResult.error("Not a regular file: $pathStr (is it a directory?)")
            }

            // Check file size
            val fileSize = path.fileSize()
            val fileExtension = path.fileName.toString().substringAfterLast('.', "")
            logger.debug { "File details: size=$fileSize bytes, extension='$fileExtension', absolute='${path.toAbsolutePath()}'" }
            if (fileSize > limits.maxFileSize) {
                return ToolResult.error(
                    "File too large: $fileSize bytes (max ${limits.maxFileSize} bytes)"
                )
            }

            // Read file contents
            val content = Files.readString(path)
            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "Successfully read file: $pathStr (${content.length} chars, ${duration}ms)" }

            return ToolResult(
                success = true,
                output = content,
                bytesRead = content.toByteArray().size,
                durationMs = duration,
                metadata = mapOf(
                    "file_size" to fileSize,
                    "line_count" to content.lines().size,
                    "path" to pathStr
                )
            )

        } catch (e: SecurityException) {
            logger.warn { "Security violation in read_file: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: FileTooLargeException) {
            return ToolResult.error(e.message ?: "File too large")

        } catch (e: java.nio.file.NoSuchFileException) {
            return ToolResult.error("File not found: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to read file" }
            return ToolResult.error("Failed to read file: ${e.message}")
        }
    }

    private fun getSandboxRoot(): String {
        // Use reflection to access private projectRoot field from PathSandbox
        return try {
            val field = sandbox.javaClass.getDeclaredField("projectRoot")
            field.isAccessible = true
            field.get(sandbox).toString()
        } catch (e: Exception) {
            "<unable to access>"
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative path to the file within the project"
                )
            ),
            "required" to listOf("path")
        )
    }
}
