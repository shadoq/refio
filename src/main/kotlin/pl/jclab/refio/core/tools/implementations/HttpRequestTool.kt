package pl.jclab.refio.core.tools.implementations

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.nio.file.Files

private val logger = dualLogger("HttpRequestTool")

/**
 * HTTP Request Tool - performs HTTP requests to external APIs.
 *
 * Parameters:
 * - url: Target URL (required)
 * - method: HTTP method - GET, POST, PUT, DELETE (default: GET)
 * - body: Request body (optional, for POST/PUT)
 * - headers: Additional headers as key-value map (optional)
 * - content_type: Content-Type header value (default: application/json)
 * - save_to_file: Path to save response body to disk (optional).
 *   When set, the full response is saved to the file and only a summary
 *   (file path, size, preview, and data statistics) is returned.
 *   Use this for large responses (CSV, JSON datasets) that should be
 *   processed by run_code instead of being loaded into LLM context.
 *
 * Limits:
 * - Response body max 5MB
 * - Timeout 60 seconds
 */
class HttpRequestTool(
    private val sandbox: PathSandbox? = null,
    private val maxResponseSize: Int = MAX_RESPONSE_SIZE,
    private val timeoutMs: Long = TIMEOUT_MS
) : Tool {

    override val name = "http_request"
    override val description = "Make HTTP requests to external APIs and web services. " +
        "Supports GET, POST, PUT, DELETE methods with custom headers and body. " +
        "Use for downloading data (CSV, JSON), calling REST APIs, and submitting results. " +
        "IMPORTANT: For large responses (CSV files, big JSON datasets), use the 'save_to_file' " +
        "parameter to save the response to disk. This returns only a summary with file path, " +
        "size, and preview — then use run_code to process the saved file. " +
        "This prevents large data from filling the context window."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        val url = params["url"] as? String
        if (url.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'url' is required and cannot be empty")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("Parameter 'url' must start with http:// or https://")
        }
        val method = (params["method"] as? String)?.uppercase() ?: "GET"
        if (method !in ALLOWED_METHODS) {
            throw IllegalArgumentException("Method must be one of: ${ALLOWED_METHODS.joinToString()}")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val url = params["url"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: 'url'")
            val method = (params["method"] as? String)?.uppercase() ?: "GET"
            val body = params["body"] as? String
            val rawContentType = params["content_type"] as? String
            // Validate and fallback to default if content_type is invalid
            val contentType = run {
                if (rawContentType == null) return@run "application/json"
                val parts = rawContentType.split("/", limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    logger.warn { "Invalid content_type '$rawContentType', falling back to application/json" }
                    return@run "application/json"
                }
                // Final check: try parsing to catch any edge cases
                try {
                    ContentType.parse(rawContentType)
                    rawContentType
                } catch (e: Exception) {
                    logger.warn { "Unparseable content_type '$rawContentType', falling back to application/json" }
                    "application/json"
                }
            }
            val saveToFile = params["save_to_file"] as? String

            @Suppress("UNCHECKED_CAST")
            val headers = params["headers"] as? Map<String, String> ?: emptyMap()

            logger.info { "HTTP $method $url (body=${body?.length ?: 0} chars, save_to_file=$saveToFile)" }

            val client = HttpClient(CIO) {
                engine {
                    requestTimeout = timeoutMs
                }
                expectSuccess = false
            }

            val result = withTimeoutOrNull(timeoutMs) {
                client.use { httpClient ->
                    val response = httpClient.request(url) {
                        this.method = HttpMethod.parse(method)
                        headers.forEach { (key, value) -> header(key, value) }
                        if (body != null && method in listOf("POST", "PUT", "PATCH")) {
                            contentType(ContentType.parse(contentType))
                            setBody(body)
                        }
                    }

                    val statusCode = response.status.value
                    val responseBody = response.bodyAsText()
                    val duration = (System.currentTimeMillis() - startTime).toInt()

                    val responseHeaders = response.headers.entries()
                        .associate { (key, values) -> key to values.joinToString(", ") }

                    logger.info {
                        "HTTP $method $url -> $statusCode (${responseBody.length} chars, ${duration}ms)"
                    }

                    // If save_to_file is specified, save to disk and return summary
                    if (saveToFile != null && statusCode in 200..399) {
                        val savedOutput = saveResponseToFile(saveToFile, responseBody, url)
                        ToolResult(
                            success = true,
                            output = savedOutput,
                            exitCode = statusCode,
                            durationMs = duration,
                            bytesRead = responseBody.toByteArray().size,
                            metadata = mapOf(
                                "url" to url,
                                "method" to method,
                                "status_code" to statusCode,
                                "response_length" to responseBody.length,
                                "saved_to_file" to saveToFile,
                                "response_headers" to responseHeaders
                            )
                        )
                    } else {
                        val truncatedBody = if (responseBody.length > maxResponseSize) {
                            responseBody.take(maxResponseSize) +
                                "\n\n... (response truncated to $maxResponseSize characters)"
                        } else {
                            responseBody
                        }

                        ToolResult(
                            success = statusCode in 200..399,
                            output = truncatedBody,
                            exitCode = statusCode,
                            durationMs = duration,
                            bytesRead = responseBody.toByteArray().size,
                            metadata = mapOf(
                                "url" to url,
                                "method" to method,
                                "status_code" to statusCode,
                                "response_length" to responseBody.length,
                                "truncated" to (responseBody.length > maxResponseSize),
                                "response_headers" to responseHeaders
                            )
                        )
                    }
                }
            }

            if (result == null) {
                logger.warn { "HTTP request timed out after ${timeoutMs}ms: $method $url" }
                return@withContext ToolResult.error(
                    "HTTP request timed out after ${timeoutMs / 1000} seconds"
                )
            }

            result

        } catch (e: Exception) {
            logger.error(e) { "HTTP request failed" }
            ToolResult.error("HTTP request failed: ${e.message}")
        }
    }

    /**
     * Save response body to a file and return a compact summary for the LLM context.
     *
     * The summary includes:
     * - File path where data was saved
     * - Total size in bytes and characters
     * - Content preview (first PREVIEW_CHARS characters)
     * - For CSV: column headers and row count
     * - For JSON: structure summary (array length or top-level keys)
     */
    private fun saveResponseToFile(filePath: String, responseBody: String, url: String): String {
        val resolvedPath = if (sandbox != null) {
            sandbox.resolve(filePath)
        } else {
            java.nio.file.Paths.get(filePath)
        }

        // Ensure parent directory exists
        resolvedPath.parent?.let { parentDir ->
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir)
            }
        }

        Files.writeString(resolvedPath, responseBody)

        logger.info {
            "Saved HTTP response to $resolvedPath (${responseBody.length} chars, ${responseBody.toByteArray().size} bytes)"
        }

        val summary = buildResponseSummary(responseBody, resolvedPath.toString(), url)
        return summary
    }

    /**
     * Build a compact summary of the saved response for the LLM context.
     */
    private fun buildResponseSummary(responseBody: String, filePath: String, url: String): String {
        val sizeBytes = responseBody.toByteArray().size
        val sizeChars = responseBody.length
        val preview = responseBody.take(PREVIEW_CHARS)

        val sb = StringBuilder()
        sb.appendLine("Response saved to file: $filePath")
        sb.appendLine("Source URL: $url")
        sb.appendLine("Size: $sizeBytes bytes ($sizeChars characters)")

        // Detect and analyze content type
        val trimmed = responseBody.trimStart()
        when {
            looksLikeCsv(trimmed) -> {
                val lines = responseBody.lines()
                val headerLine = lines.firstOrNull() ?: ""
                val dataLineCount = (lines.size - 1).coerceAtLeast(0)
                sb.appendLine("Format: CSV")
                sb.appendLine("Columns: $headerLine")
                sb.appendLine("Data rows: $dataLineCount")
            }
            trimmed.startsWith("[") -> {
                // JSON array - try to count elements
                val elementCount = countJsonArrayElements(trimmed)
                sb.appendLine("Format: JSON array")
                if (elementCount >= 0) sb.appendLine("Elements: $elementCount")
            }
            trimmed.startsWith("{") -> {
                sb.appendLine("Format: JSON object")
                // Extract top-level keys from first ~2000 chars
                val topKeys = extractJsonTopKeys(trimmed)
                if (topKeys.isNotEmpty()) {
                    sb.appendLine("Top-level keys: ${topKeys.joinToString(", ")}")
                }
            }
            else -> {
                val lineCount = responseBody.lines().size
                sb.appendLine("Format: text ($lineCount lines)")
            }
        }

        sb.appendLine()
        sb.appendLine("Preview (first $PREVIEW_CHARS chars):")
        sb.appendLine("---")
        sb.appendLine(preview)
        if (sizeChars > PREVIEW_CHARS) {
            sb.appendLine("---")
            sb.appendLine("... (${sizeChars - PREVIEW_CHARS} more characters in file)")
        }

        sb.appendLine()
        sb.appendLine("Use run_code tool to read and process this file from: $filePath")

        return sb.toString()
    }

    /**
     * Simple heuristic to detect CSV content.
     */
    private fun looksLikeCsv(content: String): Boolean {
        val firstLine = content.lineSequence().firstOrNull() ?: return false
        val secondLine = content.lineSequence().drop(1).firstOrNull() ?: return false
        // CSV typically has commas or semicolons in both header and first data line
        val headerCommas = firstLine.count { it == ',' }
        val dataCommas = secondLine.count { it == ',' }
        return headerCommas >= 2 && dataCommas >= 2 && headerCommas == dataCommas
    }

    /**
     * Count elements in a JSON array (simple heuristic).
     */
    private fun countJsonArrayElements(json: String): Int {
        if (!json.startsWith("[")) return -1
        return try {
            // Count top-level commas + 1 (rough estimate)
            var depth = 0
            var count = 0
            var inString = false
            var escaped = false
            for (ch in json) {
                if (escaped) { escaped = false; continue }
                if (ch == '\\') { escaped = true; continue }
                if (ch == '"') { inString = !inString; continue }
                if (inString) continue
                when (ch) {
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                    ',' -> if (depth == 1) count++
                }
            }
            if (count > 0) count + 1 else if (json.trim().let { it.length > 2 }) 1 else 0
        } catch (_: Exception) { -1 }
    }

    /**
     * Extract top-level keys from a JSON object (simple heuristic).
     */
    private fun extractJsonTopKeys(json: String): List<String> {
        val keys = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var keyStart = -1
        var expectingKey = true

        for ((i, ch) in json.withIndex()) {
            if (escaped) { escaped = false; continue }
            if (ch == '\\') { escaped = true; continue }
            if (ch == '"' && !escaped) {
                if (!inString && depth == 1 && expectingKey) {
                    keyStart = i + 1
                    inString = true
                } else if (inString && keyStart >= 0) {
                    keys.add(json.substring(keyStart, i))
                    keyStart = -1
                    inString = false
                    expectingKey = false
                } else {
                    inString = !inString
                }
                continue
            }
            if (inString) continue
            when (ch) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 1) expectingKey = false
                ',' -> if (depth == 1) expectingKey = true
            }
            if (keys.size >= 20) break // Limit to first 20 keys
        }
        return keys
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf(
                    "type" to "string",
                    "description" to "Target URL (must start with http:// or https://)"
                ),
                "method" to mapOf(
                    "type" to "string",
                    "description" to "HTTP method: GET, POST, PUT, DELETE (default: GET)",
                    "enum" to ALLOWED_METHODS
                ),
                "body" to mapOf(
                    "type" to "string",
                    "description" to "Request body (for POST/PUT). Send JSON as a string."
                ),
                "headers" to mapOf(
                    "type" to "object",
                    "description" to "Additional HTTP headers as key-value pairs",
                    "additionalProperties" to mapOf("type" to "string")
                ),
                "content_type" to mapOf(
                    "type" to "string",
                    "description" to "Content-Type header (default: application/json)"
                ),
                "save_to_file" to mapOf(
                    "type" to "string",
                    "description" to "Path to save response body to disk (relative to project root). " +
                        "RECOMMENDED for large responses (CSV, JSON datasets). " +
                        "When set, the full response is saved to the file and only a compact summary " +
                        "(file path, size, preview, data statistics) is returned instead of the full content. " +
                        "Use run_code to then process the saved file. " +
                        "Example: '.refio/downloads/data.csv'"
                )
            ),
            "required" to listOf("url")
        )
    }

    companion object {
        val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "DELETE", "PATCH")
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024 // 5MB
        const val TIMEOUT_MS = 60_000L // 60 seconds
        const val PREVIEW_CHARS = 500
    }
}
