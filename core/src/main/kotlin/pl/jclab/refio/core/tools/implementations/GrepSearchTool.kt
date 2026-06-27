package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.tools.security.LimitExceededException
import pl.jclab.refio.core.security.RegexSafetyValidator
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = dualLogger("GrepSearchTool")

/**
 * Grep Search Tool - searches for text patterns in files
 *
 * Parameters:
 * - pattern: Search pattern (regex)
 * - path: Starting directory (default: ".")
 * - file_pattern: File name filter (default: "*")
 * - case_sensitive: Case sensitive search (default: false)
 * - max_results: Maximum results (default: 100)
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Result count limits
 * - File size limits (won't search huge files)
 */
class GrepSearchTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : Tool {

    override val name = "grep_search"
    override val description = "Search file contents by regex pattern."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "Search file contents by exact string or regex. Use for identifiers and known literals; " +
        "prefer rag_search for conceptual queries without good keywords."

    override fun validateParams(params: Map<String, Any>) {
        val pattern = params["pattern"] as? String
        if (pattern.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'pattern' is required and cannot be empty")
        }
        RegexSafetyValidator.validate(pattern)
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val pattern = params["pattern"] as? String
                ?: return ToolResult.error("Missing required parameter: 'pattern'")
            RegexSafetyValidator.validate(pattern)

            val pathStr = (params["path"] as? String) ?: "."
            val filePattern = (params["file_pattern"] as? String) ?: "*"
            val caseSensitive = (params["case_sensitive"] as? Boolean) ?: false
            val maxResults = ((params["max_results"] as? Number)?.toInt() ?: 100)
                .coerceAtMost(limits.maxGrepResults)
            val detail = ((params["detail"] as? String) ?: "normal").lowercase()

            // Normalize path for security (backslash → forward slash)
            val normalizedPathStr = pathStr.replace('\\', '/')

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info { "Grep search: pattern='$pattern', relative='$pathStr', absolute='${path.toAbsolutePath()}', filePattern='$filePattern', caseSensitive=$caseSensitive, maxResults=$maxResults" }

            if (!Files.exists(path)) {
                logger.warn { "Path not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                return ToolResult.error("Path not found: $pathStr")
            }

            // Create regex for content search
            val contentRegex = if (caseSensitive) {
                Regex(pattern)
            } else {
                Regex(pattern, RegexOption.IGNORE_CASE)
            }

            val results = mutableListOf<GrepResult>()
            var filesSearched = 0
            var filesSkipped = 0

            // Single-file mode: skip the walk, agents often pass a concrete file path here.
            // The "Not a directory" rejection cost a whole turn for a trivial intent mismatch.
            if (path.isRegularFile()) {
                val fileName = path.fileName.toString()
                if (limits.shouldExcludeFile(fileName)) {
                    return ToolResult.error("File extension excluded by safety limits: $fileName")
                }
                searchInFile(path, contentRegex, results, maxResults)?.let { filesSkipped += it }
                filesSearched = 1
            } else if (path.isDirectory()) {
                // file_pattern is a NAME filter, but models often pass a path-anchored
                // glob ("core/.../services/*.kt"). globToRegex anchors with ^...$ and we
                // match it against the bare file name, so any '/' silently yields 0 hits.
                // The directory is already scoped by `path`, so reduce a path-like pattern
                // to its last segment ("*.kt") instead of failing the whole search.
                val effectiveFilePattern = if (filePattern.contains('/')) {
                    val basename = filePattern.substringAfterLast('/').ifBlank { "*" }
                    logger.info { "Grep file_pattern '$filePattern' contains '/'; matching by name segment '$basename' (dir already scoped by path)" }
                    basename
                } else {
                    filePattern
                }
                val fileRegex = globToRegex(effectiveFilePattern)
                var limitReached = false

                Files.walk(path, limits.maxSearchDepth).use { stream ->
                    val iterator = stream
                        .filter { filePath ->
                            val relativePath = try {
                                path.relativize(filePath)
                            } catch (e: Exception) {
                                filePath
                            }
                            relativePath.none { segment ->
                                limits.shouldExcludeDirectory(segment.toString())
                            }
                        }
                        .filter { it.isRegularFile() }
                        .filter { fileRegex.matches(it.fileName.toString()) }
                        .iterator()

                    while (iterator.hasNext() && !limitReached) {
                        val file = iterator.next()
                        val fileName = file.fileName.toString()

                        if (limits.shouldExcludeFile(fileName)) {
                            logger.debug { "Skipping excluded file: $fileName (blacklisted extension)" }
                            continue
                        }

                        filesSearched++
                        val skipped = searchInFile(file, contentRegex, results, maxResults)
                        if (skipped != null) filesSkipped += skipped
                        if (results.size >= maxResults) limitReached = true
                    }
                }
            } else {
                logger.warn { "Unsupported path type: $pathStr (not file or directory)" }
                return ToolResult.error("Path is neither a file nor a directory: $pathStr")
            }

            // Check result limit
            if (results.size >= maxResults) {
                logger.warn { "Grep search hit limit: ${results.size} >= $maxResults" }
            }

            // Rank declaration hits above plain usages (docs/0060 Faza 2). Stable sort, so within
            // a tier the original file-walk order is preserved. Only collected results are ranked —
            // a maxResults truncation during the walk can still drop a later declaration.
            val ranked = if (results.size > 1) {
                results.sortedByDescending { isDeclaration(it.line) }
            } else {
                results
            }

            // Format output (verbosity controlled by `detail`)
            val output = when {
                ranked.isEmpty() -> "No matches found for pattern: $pattern"
                detail == "summary" -> formatResultsSummary(ranked)
                else -> formatResults(ranked)
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "Grep search completed: ${results.size} matches in $filesSearched files (skipped $filesSkipped large files), ${duration}ms, detail=$detail" }

            // Empty-result hints (lesson 03E04: tools should suggest next steps).
            val hints: List<String>? = if (results.isEmpty()) {
                buildList {
                    add("Try a less specific pattern or remove anchors")
                    if (caseSensitive) add("Retry with case_sensitive=false")
                    if (filePattern != "*") add("Broaden file_pattern (currently '$filePattern')")
                    if (pathStr != ".") add("Search a parent directory or use path=\".\"")
                    add("Use file_search to confirm files of the expected type exist")
                }
            } else null

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                nextActionHints = hints,
                metadata = mapOf(
                    "match_count" to results.size,
                    "files_searched" to filesSearched,
                    "pattern" to pattern,
                    "search_path" to pathStr,
                    "detail" to detail
                )
            )

        } catch (e: SecurityException) {
            logger.warn { "Security violation in grep_search: ${e.message}" }
            return ToolResult.error("Security error: ${e.message}")

        } catch (e: Exception) {
            logger.error(e) { "Failed to grep search" }
            return ToolResult.error("Failed to grep search: ${e.message}")
        }
    }

    // Returns 1 if file was skipped due to size, null otherwise. Caller tracks `filesSkipped`.
    private fun searchInFile(
        file: java.nio.file.Path,
        contentRegex: Regex,
        results: MutableList<GrepResult>,
        maxResults: Int
    ): Int? {
        val fileSize = Files.size(file)
        if (fileSize > limits.maxFileSize) {
            logger.debug { "Skipping large file: ${file.toAbsolutePath()}, size=$fileSize bytes (max ${limits.maxFileSize})" }
            return 1
        }
        try {
            val content = Files.readString(file)
            val lines = content.lines()
            val relativePath = sandbox.resolve(".").relativize(file).toString()
            for ((index, line) in lines.withIndex()) {
                if (contentRegex.containsMatchIn(line)) {
                    results.add(GrepResult(file = relativePath, lineNumber = index + 1, line = line.trim()))
                    if (results.size >= maxResults) return null
                }
            }
        } catch (e: Exception) {
            logger.debug { "Failed to read file: ${file.fileName} - ${e.message}" }
        }
        return null
    }

    private fun globToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .let { "^$it$" }

        return Regex(regexPattern, RegexOption.IGNORE_CASE)
    }

    private fun formatResults(results: List<GrepResult>): String {
        return results.joinToString("\n") { result ->
            "${result.file}:${result.lineNumber}: ${result.line}"
        }
    }

    /**
     * Compact one-line-per-file summary used when detail="summary".
     * Trades match content for context budget — agent gets file paths + counts only.
     */
    private fun formatResultsSummary(results: List<GrepResult>): String {
        val byFile = results.groupingBy { it.file }.eachCount()
        return buildString {
            appendLine("Matches in ${byFile.size} file(s) (${results.size} total):")
            byFile.entries
                .sortedByDescending { it.value }
                .forEach { (file, count) -> appendLine("  $file: $count match(es)") }
        }.trimEnd()
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "pattern" to mapOf(
                    "type" to "string",
                    "description" to "Search pattern (regex syntax)"
                ),
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Starting directory (default: current directory)",
                    "default" to "."
                ),
                "file_pattern" to mapOf(
                    "type" to "string",
                    "description" to "File NAME filter (glob, e.g. \"*.kt\") — NOT a path. Scope the directory with `path`, not here. A path-anchored value like \"src/**/*.kt\" is reduced to its last segment (\"*.kt\").",
                    "default" to "*"
                ),
                "case_sensitive" to mapOf(
                    "type" to "boolean",
                    "description" to "Case sensitive search",
                    "default" to false
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of results",
                    "default" to 100
                ),
                "detail" to mapOf(
                    "type" to "string",
                    "enum" to listOf("summary", "normal", "full"),
                    "description" to "Verbosity. 'summary' = file:count aggregation only (cheapest); 'normal' (default) = full file:line:content lines; 'full' = same as normal.",
                    "default" to "normal"
                )
            ),
            "required" to listOf("pattern")
        )
    }

    private fun isDeclaration(line: String): Boolean = DECLARATION_REGEX.containsMatchIn(line)

    private data class GrepResult(
        val file: String,
        val lineNumber: Int,
        val line: String
    )

    companion object {
        // A line looks like a *declaration* (a type/function being defined) rather than a usage.
        // Such lines rank above plain usages so an agent grepping for a symbol sees its definition
        // first (docs/0060 Faza 2). `val`/`var` are deliberately EXCLUDED: they overwhelmingly mark
        // local variable declarations — i.e. *usages* of the searched type (`val x = Foo()`) — and
        // would pollute the ranking. Spans Kotlin/Java, Python (`def`), Go/Rust (`func`/`fn`), TS.
        private val DECLARATION_REGEX =
            Regex("\\b(class|interface|object|enum|fun|def|func|fn|type|struct|trait)\\b")
    }
}
