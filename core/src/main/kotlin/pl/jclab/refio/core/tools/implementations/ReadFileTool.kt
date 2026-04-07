package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.DocumentReadService
import pl.jclab.refio.core.services.ImagePreparationService
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
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = dualLogger("ReadFileTool")

/**
 * Read File Tool - reads contents of a file with optional line range.
 *
 * Parameters:
 * - path: Relative file path within project (required)
 * - offset: 1-based line number to start reading from (optional, default: 1)
 * - limit: Maximum number of lines to read (optional, default: all)
 *
 * When offset/limit are used, the output includes a header with line range info
 * and the total line count, so the caller can paginate through the file.
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - File size limits enforced
 * - Only UTF-8 text files supported
 */
class ReadFileTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits,
    private val imagePreparationService: ImagePreparationService = ImagePreparationService(),
    private val documentReadService: DocumentReadService = DocumentReadService()
) : Tool {

    override val name = "read_file"
    override val description = "Read a text, image, or PDF file. Use offset/limit for large files."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        if (params["path"] == null || (params["path"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }
        val offset = toIntOrNull(params["offset"])
        if (offset != null && offset < 1) {
            throw IllegalArgumentException("Parameter 'offset' must be >= 1 (1-based line number)")
        }
        val limit = toIntOrNull(params["limit"])
        if (limit != null && limit < 1) {
            throw IllegalArgumentException("Parameter 'limit' must be >= 1")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            val pathStr = params["path"] as? String
                ?: return ToolResult.error("Missing required parameter: 'path'")

            val offset = toIntOrNull(params["offset"])
            val explicitLimit = toIntOrNull(params["limit"])
            val detail = ((params["detail"] as? String) ?: "normal").lowercase()
            // detail=summary trims to first 40 lines unless caller passed an explicit limit.
            // detail=full ignores summary truncation. detail=normal preserves caller offset/limit.
            val limit = when {
                explicitLimit != null -> explicitLimit
                detail == "summary" -> 40
                else -> null
            }
            val pageStart = toIntOrNull(params["page_start"])
            val pageEnd = toIntOrNull(params["page_end"])
            val requestedPages = if (pageStart != null || pageEnd != null) {
                val start = (pageStart ?: pageEnd ?: 1).coerceAtLeast(1)
                val end = (pageEnd ?: pageStart ?: start).coerceAtLeast(start)
                start..end
            } else {
                null
            }

            // Normalize path for security (bare filenames → "./file.txt", backslash → forward slash)
            val normalizedPathStr = normalizePath(pathStr)

            // Resolve and validate path
            val path = sandbox.resolve(normalizedPathStr)

            logger.info {
                "Reading file: relative='$pathStr', absolute='${path.toAbsolutePath()}', " +
                    "sandbox_root='${getSandboxRoot()}', offset=$offset, limit=$limit"
            }

            // Check if file exists
            if (!Files.exists(path)) {
                logger.warn { "File not found: $pathStr (resolved to ${path.toAbsolutePath()})" }
                return ToolResult.error(
                    message = "File not found: $pathStr",
                    recovery = "Verify the path. The file may have been renamed, moved, or never existed.",
                    nextActionHints = listOf(
                        "file_search(pattern=\"${path.fileName}\") to locate the file",
                        "read_directory(path=\"${path.parent?.let { sandbox.resolve(".").relativize(it).toString() } ?: "."}\") to inspect the parent",
                        "Use grep_search to find content if the path is uncertain"
                    )
                )
            }

            // Check if it's a regular file
            if (!path.isRegularFile()) {
                logger.warn { "Not a regular file: $pathStr (is directory: ${path.isDirectory()})" }
                return ToolResult.error(
                    message = "Not a regular file: $pathStr (is it a directory?)",
                    recovery = "Use read_directory to list a directory's contents.",
                    nextActionHints = listOf("read_directory(path=\"$pathStr\")")
                )
            }

            // Check file size
            val fileSize = path.fileSize()
            val fileExtension = path.fileName.toString().substringAfterLast('.', "")
            logger.debug { "File details: size=$fileSize bytes, extension='$fileExtension'" }
            if (fileSize > limits.maxFileSize) {
                return ToolResult.error(
                    "File too large: $fileSize bytes (max ${limits.maxFileSize} bytes). " +
                        "Use 'offset' and 'limit' parameters to read a specific line range."
                )
            }

            val mediaType = detectMediaType(path)
            if (mediaType != null && mediaType.startsWith("image/")) {
                val bytes = Files.readAllBytes(path)
                val prepared = imagePreparationService.prepare(bytes, mediaType)
                val output = "[Image: $pathStr (${prepared.originalSizeBytes} bytes, ${prepared.mediaType})]"
                return ToolResult(
                    success = true,
                    output = output,
                    bytesRead = prepared.preparedSizeBytes,
                    durationMs = (System.currentTimeMillis() - startTime).toInt(),
                    metadata = mapOf(
                        "type" to "image",
                        "path" to pathStr,
                        "media_type" to prepared.mediaType,
                        "base64" to prepared.base64Data,
                        "original_size" to prepared.originalSizeBytes,
                        "prepared_size" to prepared.preparedSizeBytes
                    )
                )
            }

            if (isPdf(path, mediaType)) {
                val documentResult = documentReadService.read(path, requestedPages)
                val duration = (System.currentTimeMillis() - startTime).toInt()
                return when (documentResult) {
                    is DocumentReadService.DocumentResult.InlineText -> ToolResult(
                        success = true,
                        output = documentResult.text,
                        bytesRead = documentResult.text.toByteArray().size,
                        durationMs = duration,
                        metadata = mapOf(
                            "type" to "document",
                            "path" to pathStr,
                            "format" to "pdf",
                            "page_count" to documentResult.pageCount,
                            "mode" to "inline"
                        )
                    )
                    is DocumentReadService.DocumentResult.PageRange -> ToolResult(
                        success = true,
                        output = "[PDF pages ${documentResult.range.first}-${documentResult.range.last} of ${documentResult.totalPages}]\n${documentResult.text}",
                        bytesRead = documentResult.text.toByteArray().size,
                        durationMs = duration,
                        metadata = mapOf(
                            "type" to "document",
                            "path" to pathStr,
                            "format" to "pdf",
                            "page_count" to documentResult.totalPages,
                            "page_start" to documentResult.range.first,
                            "page_end" to documentResult.range.last,
                            "mode" to "page_range"
                        )
                    )
                    is DocumentReadService.DocumentResult.Reference -> ToolResult(
                        success = true,
                        output = "PDF reference: $pathStr (${documentResult.pageCount} pages, ${documentResult.sizeBytes} bytes). " +
                            "Use page_start/page_end to read a specific range.",
                        bytesRead = 0,
                        durationMs = duration,
                        metadata = mapOf(
                            "type" to "document_reference",
                            "path" to pathStr,
                            "format" to "pdf",
                            "page_count" to documentResult.pageCount,
                            "size_bytes" to documentResult.sizeBytes,
                            "mode" to "reference"
                        )
                    )
                }
            }

            // Read file contents
            val allLines = Files.readAllLines(path)
            val totalLineCount = allLines.size

            val outputContent: String
            val readLineCount: Int
            val startLine: Int
            val endLine: Int

            if (offset != null || limit != null) {
                // Line-range reading (1-based offset)
                val startIdx = ((offset ?: 1) - 1).coerceIn(0, totalLineCount)
                val endIdx = if (limit != null) {
                    (startIdx + limit).coerceAtMost(totalLineCount)
                } else {
                    totalLineCount
                }

                val selectedLines = allLines.subList(startIdx, endIdx)
                readLineCount = selectedLines.size
                startLine = startIdx + 1
                endLine = startIdx + readLineCount

                val header = "[Lines $startLine-$endLine of $totalLineCount total]"
                val body = selectedLines.joinToString("\n")

                // Truncation warning: there are more lines AFTER what we just read.
                // This is critical so the model knows the file is incomplete and
                // explicitly knows how to read the rest. Without this, models tend
                // to silently assume they have the full file.
                val unreadAfter = totalLineCount - endLine
                val unreadBefore = startLine - 1
                outputContent = if (unreadAfter > 0 || unreadBefore > 0) {
                    val nextOffset = endLine + 1
                    val warning = buildString {
                        append("\n\n[!! PARTIAL READ — file '$pathStr' has $totalLineCount lines, ")
                        append("you read lines $startLine-$endLine ($readLineCount shown).")
                        if (unreadBefore > 0) append(" $unreadBefore line(s) BEFORE.")
                        if (unreadAfter > 0) {
                            append(" $unreadAfter line(s) AFTER — to continue reading call:")
                            append(" read_file(path=\"$pathStr\", offset=$nextOffset).")
                            append(" Critical constants/sections may be in the unread part.")
                        }
                        append(" !!]")
                    }
                    "$header\n$body$warning"
                } else {
                    "$header\n$body"
                }

                logger.info {
                    "Read file range: $pathStr lines $startLine-$endLine of $totalLineCount " +
                        "(unread before=$unreadBefore, after=$unreadAfter)"
                }
            } else {
                // Full file read
                outputContent = allLines.joinToString("\n")
                readLineCount = totalLineCount
                startLine = 1
                endLine = totalLineCount
            }

            val duration = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "Successfully read file: $pathStr ($readLineCount lines, ${duration}ms)" }

            val truncated = startLine > 1 || endLine < totalLineCount
            val metadata = mutableMapOf<String, Any>(
                "file_size" to fileSize,
                "total_lines" to totalLineCount,
                "lines_read" to readLineCount,
                "start_line" to startLine,
                "end_line" to endLine,
                "path" to pathStr,
                "truncated" to truncated
            )
            if (truncated && endLine < totalLineCount) {
                metadata["next_offset"] = endLine + 1
                metadata["unread_after"] = totalLineCount - endLine
            }
            if (truncated && startLine > 1) {
                metadata["unread_before"] = startLine - 1
            }

            return ToolResult(
                success = true,
                output = outputContent,
                bytesRead = outputContent.toByteArray().size,
                durationMs = duration,
                metadata = metadata
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
        return sandbox.getProjectRoot().toString()
    }

    /**
     * Safely convert a parameter value to Int.
     * Handles String, Int, Long, Double from JSON parsing.
     */
    private fun toIntOrNull(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative path to the file within the project"
                ),
                "offset" to mapOf(
                    "type" to "integer",
                    "description" to "Start line (1-based)."
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "Max lines to read from offset."
                ),
                "page_start" to mapOf(
                    "type" to "integer",
                    "description" to "For PDF files: first page to read (1-based)."
                ),
                "page_end" to mapOf(
                    "type" to "integer",
                    "description" to "For PDF files: last page to read (1-based, inclusive)."
                ),
                "detail" to mapOf(
                    "type" to "string",
                    "enum" to listOf("summary", "normal", "full"),
                    "description" to "Verbosity. 'summary' caps reading to the first 40 lines (good for scanning structure); 'normal' (default) honors offset/limit; 'full' = same as normal.",
                    "default" to "normal"
                )
            ),
            "required" to listOf("path")
        )
    }

    private fun detectMediaType(path: java.nio.file.Path): String? {
        return runCatching { Files.probeContentType(path) }.getOrNull()
    }

    private fun isPdf(path: java.nio.file.Path, mediaType: String?): Boolean {
        return path.fileName.toString().endsWith(".pdf", ignoreCase = true) || mediaType == "application/pdf"
    }
}
