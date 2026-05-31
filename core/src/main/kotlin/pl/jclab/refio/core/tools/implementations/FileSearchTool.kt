package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.tools.security.LimitExceededException
import pl.jclab.refio.core.logging.dualLogger
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
 * - offset: Number of matches to skip (default: 0)
 * - limit: Maximum number of results to return (default: maxSearchResults)
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
    override val description = "Find files by name pattern (glob syntax)."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint = "Find files by name/path (glob). Also used as pre-check before create_new_file."

    override fun validateParams(params: Map<String, Any>) {
        if (params["pattern"] == null || (params["pattern"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'pattern' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val pattern = params["pattern"] as? String
                ?: return ToolResult.error("Missing required parameter: 'pattern'")

            val pathStr = (params["path"] as? String) ?: "."
            val requestedDepth = (params["max_depth"] as? Number)?.toInt()
            val offset = (params["offset"] as? Number)?.toInt() ?: 0
            val rawLimit = (params["limit"] as? Number)?.toInt() ?: limits.maxSearchResults
            val detail = ((params["detail"] as? String) ?: "normal").lowercase()
            // detail=summary trims output to a small head + count, regardless of `limit`.
            val limit = if (detail == "summary") rawLimit.coerceAtMost(20) else rawLimit

            if (offset < 0) {
                return ToolResult.error("Parameter 'offset' must be >= 0")
            }

            if (limit <= 0) {
                return ToolResult.error("Parameter 'limit' must be >= 1")
            }

            // Recursion-depth resolution.
            //
            // A bare-name glob ("*.kt", "Foo.kt", "*") or any `**` pattern means "find by
            // name ANYWHERE" — it must search the whole tree. Honouring a small caller
            // `max_depth` here silently returns nothing on deep package trees (observed
            // 2026-05: the business-analyst subagent ran `*.kt` with max_depth=1/2 against
            // core/src/main/kotlin — whose first .kt is ~8 levels deep — got 0 hits, and
            // looped). For those patterns we ignore a caller depth smaller than the
            // configured maximum and search to limits.maxSearchDepth.
            //
            // Patterns that anchor to a path segment (contain "/" but not "**", e.g.
            // "subdir/*.txt") keep honouring max_depth, since depth is positional there.
            val recursiveByName = !pattern.contains('/') || pattern.contains("**")
            val effectiveMaxDepth = if (recursiveByName) {
                limits.maxSearchDepth
            } else {
                (requestedDepth ?: limits.maxSearchDepth).coerceAtMost(limits.maxSearchDepth)
            }

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

            logger.info {
                "Searching files: pattern='$pattern', relative='$pathStr', absolute='${path.toAbsolutePath()}', " +
                    "maxDepth=$effectiveMaxDepth, offset=$offset, limit=$limit"
            }

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
            val patternUsesPath = pattern.contains("/") || pattern.contains("\\") || pattern.contains("**")

            // Search files
            val results = mutableListOf<String>()
            var matchedCount = 0
            var hasMore = false
            Files.walk(path, effectiveMaxDepth).use { stream ->
                val iterator = stream
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
                        if (limits.shouldExcludeFile(fileName)) {
                            return@filter false
                        }

                        val relativePath = try {
                            path.relativize(file)
                        } catch (e: Exception) {
                            file
                        }
                        val normalizedPath = relativePath.toString().replace('\\', '/')
                        val candidate = if (patternUsesPath) normalizedPath else fileName
                        regex.matches(candidate)
                    }
                    .iterator()

                while (iterator.hasNext()) {
                    val file = iterator.next()
                    matchedCount += 1
                    if (matchedCount <= offset) {
                        continue
                    }
                    if (results.size < limit) {
                        val relativePath = sandbox.resolve(".").relativize(file).toString()
                        results.add(relativePath)
                        continue
                    }
                    hasMore = true
                    break
                }
            }

            // Format output (verbosity controlled by `detail`)
            val output = when {
                results.isEmpty() && matchedCount == 0 -> "No files found matching pattern: $pattern"
                results.isEmpty() -> "No files found in requested range for pattern: $pattern"
                detail == "summary" -> buildString {
                    appendLine("Found $matchedCount file(s) matching '$pattern' (showing first ${results.size}):")
                    results.sorted().forEach { appendLine("  $it") }
                }.trimEnd()
                else -> results.sorted().joinToString("\n")
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "File search completed: ${results.size} results, ${duration}ms, detail=$detail" }

            // Empty-result hints (lesson 03E04: tools should suggest next steps).
            val hints: List<String>? = if (results.isEmpty() && matchedCount == 0) {
                buildList {
                    add("Try a broader glob (e.g. **/${pattern.removePrefix("**/")})")
                    add("Drop file-extension constraints to confirm the file exists at all")
                    if (pathStr != ".") add("Search from project root with path=\".\"")
                    add("Use grep_search to look for known content from the file")
                }
            } else null

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                nextActionHints = hints,
                metadata = mapOf(
                    "result_count" to results.size,
                    "matched_count" to matchedCount,
                    "pattern" to pattern,
                    "search_path" to pathStr,
                    "offset" to offset,
                    "limit" to limit,
                    "has_more" to hasMore,
                    "detail" to detail
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
        val normalized = pattern.replace('\\', '/')
        val sb = StringBuilder("^")
        var i = 0

        while (i < normalized.length) {
            val ch = normalized[i]
            when (ch) {
                '*' -> {
                    val isDoubleStar = i + 1 < normalized.length && normalized[i + 1] == '*'
                    if (isDoubleStar) {
                        val hasSlash = i + 2 < normalized.length && normalized[i + 2] == '/'
                        if (hasSlash) {
                            sb.append("(?:.*/)?")
                            i += 3
                            continue
                        }
                        sb.append(".*")
                        i += 2
                        continue
                    }
                    sb.append("[^/]*")
                }
                '?' -> sb.append(".")
                '/', '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append("\\").append(ch)
                else -> sb.append(ch)
            }
            i += 1
        }

        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
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
                    "description" to "Maximum search depth. Ignored for by-name globs (\"*.kt\") " +
                        "and `**` patterns — those always search the whole tree. Only honoured " +
                        "for path-anchored patterns like \"subdir/*.txt\".",
                    "default" to 15
                ),
                "offset" to mapOf(
                    "type" to "integer",
                    "description" to "Number of matches to skip",
                    "default" to 0
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of results to return",
                    "default" to limits.maxSearchResults
                ),
                "detail" to mapOf(
                    "type" to "string",
                    "enum" to listOf("summary", "normal", "full"),
                    "description" to "Verbosity. 'summary' = first 20 paths + total count; 'normal' (default) / 'full' = full sorted listing.",
                    "default" to "normal"
                )
            ),
            "required" to listOf("pattern")
        )
    }
}
