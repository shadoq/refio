package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.tools.security.LimitExceededException
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.isRegularFile

private val logger = dualLogger("FileSearchTool")

/**
 * File Search Tool - finds files by name pattern
 *
 * Parameters:
 * - pattern: File name pattern (glob or regex)
 * - path: Starting directory (default: ".")
 * - max_depth: Maximum search depth (default: 10)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Search depth limits
 * - Result count limits
 */
class FileSearchTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "file_search"
    override val description = "Find files by name pattern within the project"
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        if (params["pattern"] == null || (params["pattern"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'pattern' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters (already validated)
            val pattern = params["pattern"] as String

            val pathStr = (params["path"] as? String) ?: "."
            val maxDepth = (params["max_depth"] as? Number)?.toInt() ?: 10

            // Validate max depth
            val effectiveMaxDepth = maxDepth.coerceAtMost(limits.maxSearchDepth)

            // Normalize path for security (backslash → forward slash)
            var normalizedPathStr = pathStr.replace('\\', '/')

            // Special case: if path looks like a bare filename, search current directory instead
            // (file_search expects directory, not file path)
            if (!normalizedPathStr.contains('/') && normalizedPathStr != "." && normalizedPathStr.isNotBlank()) {
                logger.info { "FileSearchTool: converted bare filename '$pathStr' to '.' (search current directory)" }
                normalizedPathStr = "."
            }

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Searching files: pattern='$pattern', relative='$pathStr', absolute='${path.toAbsolutePath()}', maxDepth=$effectiveMaxDepth" }

            // Check if directory exists
            if (!Files.exists(path)) {
                logger.warn { "Directory not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                return ToolResult.error("Directory not found: $pathStr")
            }

            if (!path.isDirectory()) {
                logger.warn { "Not a directory: $pathStr (is file: ${path.isRegularFile()})" }
                return ToolResult.error("Not a directory: $pathStr")
            }

            // Convert glob pattern to regex
            val regex = globToRegex(pattern)

            // Search files
            val results = mutableListOf<String>()
            Files.walk(path, effectiveMaxDepth).use { stream ->
                stream
                    .filter { filePath ->
                        // Exclude blacklisted directories from traversal
                        val relativePath = try {
                            path.relativize(filePath)
                        } catch (e: Exception) {
                            filePath
                        }

                        val shouldInclude = relativePath.none { segment ->
                            limits.shouldExcludeDirectory(segment.toString())
                        }
                        shouldInclude
                    }
                    .filter { !it.isDirectory() }
                    .filter { file ->
                        // Skip excluded file extensions
                        val fileName = file.fileName.toString()
                        !limits.shouldExcludeFile(fileName) && regex.matches(fileName)
                    }
                    .limit(limits.maxSearchResults.toLong() + 1)
                    .forEach { file ->
                        val relativePath = sandbox.resolve(".").relativize(file).toString()
                        results.add(relativePath)
                    }
            }

            // Check result limit
            if (results.size > limits.maxSearchResults) {
                logger.warn { "File search exceeded limit: ${results.size} > ${limits.maxSearchResults}" }
                throw LimitExceededException(
                    "Too many results: ${results.size} (max ${limits.maxSearchResults}). Refine your search pattern."
                )
            }

            // Format output
            val output = if (results.isEmpty()) {
                "No files found matching pattern: $pattern"
            } else {
                results.sorted().joinToString("\n")
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "File search completed: ${results.size} results, ${duration}ms" }

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                metadata = mapOf(
                    "result_count" to results.size,
                    "pattern" to pattern,
                    "search_path" to pathStr
                )
            )

        } catch (e: SecurityException) {
            logger.warn { "Security violation in file_search: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: LimitExceededException) {
            return ToolResult.error(e.message ?: "Limit exceeded")

        } catch (e: Exception) {
            logger.error(e) { "Failed to search files" }
            return ToolResult.error("Failed to search files: ${e.message}")
        }
    }

    /**
     * Convert glob pattern to regex
     * Simple implementation: * → .*, ? → ., ** → .*
     */
    private fun globToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .let { "^$it$" }

        return Regex(regexPattern, RegexOption.IGNORE_CASE)
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "pattern" to mapOf(
                    "type" to "string",
                    "description" to "File name pattern (glob syntax: *.kt, **/*.java)"
                ),
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Starting directory (default: current directory)",
                    "default" to "."
                ),
                "max_depth" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum search depth",
                    "default" to 10
                )
            ),
            "required" to listOf("pattern")
        )
    }
}
