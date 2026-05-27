package pl.jclab.refio.core.tools.implementations

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.security.UrlPolicy
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.utils.GsonInstance
import java.nio.file.Files
import java.util.Base64

private val logger = dualLogger("HttpRequestTool")

/**
 * HTTP Request Tool - performs HTTP requests to external APIs.
 *
 * Parameters:
 * - url: Target URL (required)
 * - method: HTTP method - GET, POST, PUT, DELETE (default: GET)
 * - body: Request body (optional, for POST/PUT). Accepts:
 *     * String — sent as-is.
 *     * Map / List / Number / Boolean — auto-serialized to JSON via Gson. This is
 *       the common LLM failure mode where the model passes the body as a JSON
 *       object instead of a JSON string. Without coercion, the parameter would
 *       be silently dropped (HTTP body would be empty) and the server would
 *       reject the request as "no data sent".
 * - body_file: Path to a file whose contents will be sent as the request body (optional, for POST/PUT).
 *   Use this instead of 'body' when the payload is large or binary (e.g. a JSON dataset, CSV upload,
 *   or binary file). The file is streamed directly without loading its content into LLM context.
 *   If both 'body' and 'body_file' are provided, 'body_file' takes precedence.
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
 *
 * Sessions / cookies:
 * - This tool is STATELESS. Each call uses a fresh HTTP client with no cookie jar.
 *   Cookies are NOT persisted between calls.
 * - For session-based authentication the agent must manage cookies manually:
 *   1. Inspect `Set-Cookie` headers in the response (returned in the output header
 *      summary and in metadata.response_headers).
 *   2. Pass them back on subsequent calls via the `headers` parameter, e.g.
 *      `headers: {"Cookie": "session=abc123; csrf=xyz"}`.
 *
 * Success semantics:
 * - success=true whenever an HTTP response is received, regardless of status code.
 *   The status code is exposed via ToolResult.exitCode and included in the output
 *   header summary so the agent can react to 4xx/5xx as domain data (e.g. retry
 *   with different payload, parse error body).
 * - success=false ONLY on infrastructure failures: network error, DNS failure,
 *   timeout, exception, invalid parameters. These are true tool failures.
 * - For save_to_file: the file is only written on 2xx/3xx. On 4xx/5xx the response
 *   body is returned inline (so the agent can inspect the error) and a NOTE is
 *   prepended indicating the save was skipped.
 */
class HttpRequestTool(
    private val sandbox: PathSandbox? = null,
    private val maxResponseSize: Int = MAX_RESPONSE_SIZE,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val urlPolicy: UrlPolicy = UrlPolicy(),
    private val networkPolicy: NetworkPolicy? = null
) : Tool {

    override val name = "http_request"
    override val description = "Make HTTP requests (GET/POST/PUT/DELETE). Stateless — no cookie jar. " +
        "Body accepts JSON string or raw object (auto-serialized). Use save_to_file for large responses."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint = "HTTP requests (GET/POST/PUT/DELETE). Use save_to_file for large responses."

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
            val taskId = params[ToolInternalParams.TASK_ID] as? String
            try {
                networkPolicy?.assertEgressAllowed(name, url, taskId)
            } catch (e: NoEgressViolationException) {
                return@withContext ToolResult.error(e.message ?: "no-egress mode blocks this call")
            }
            urlPolicy.validate(url)
            val method = (params["method"] as? String)?.uppercase() ?: "GET"
            val bodyFile = params["body_file"] as? String
            val body = if (bodyFile != null) null else coerceBody(params["body"])
            val rawContentType = params["content_type"] as? String
            // No fallback: invalid content_type is a caller bug, not something to paper over.
            val contentType = if (rawContentType == null) {
                "application/json"
            } else {
                val parts = rawContentType.split("/", limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    return@withContext ToolResult.error(
                        "Invalid content_type '$rawContentType' (expected 'type/subtype')"
                    )
                }
                try {
                    ContentType.parse(rawContentType)
                    rawContentType
                } catch (e: Exception) {
                    return@withContext ToolResult.error(
                        "Unparseable content_type '$rawContentType': ${e.message}"
                    )
                }
            }
            val saveToFile = params["save_to_file"] as? String

            @Suppress("UNCHECKED_CAST")
            val headers = params["headers"] as? Map<String, String> ?: emptyMap()

            // Resolve body_file path and read bytes if provided
            val bodyFileBytes: ByteArray? = if (bodyFile != null && method in listOf("POST", "PUT", "PATCH")) {
                val resolvedBodyFile = if (sandbox != null) sandbox.resolve(bodyFile) else java.nio.file.Paths.get(bodyFile)
                if (!Files.exists(resolvedBodyFile)) {
                    return@withContext ToolResult.error("body_file not found: $resolvedBodyFile")
                }
                Files.readAllBytes(resolvedBodyFile)
            } else null

            logger.info {
                "HTTP $method $url (body=${body?.length ?: 0} chars, body_file=${bodyFileBytes?.size?.let { "$it bytes" } ?: bodyFile}, save_to_file=$saveToFile)"
            }

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
                        if (method in listOf("POST", "PUT", "PATCH")) {
                            when {
                                bodyFileBytes != null -> {
                                    contentType(ContentType.parse(contentType))
                                    setBody(bodyFileBytes)
                                }
                                body != null -> {
                                    contentType(ContentType.parse(contentType))
                                    setBody(body)
                                }
                            }
                        }
                    }

                    val statusCode = response.status.value
                    val duration = (System.currentTimeMillis() - startTime).toInt()

                    val responseHeaders = response.headers.entries()
                        .associate { (key, values) -> key to values.joinToString(", ") }

                    val responseContentType = responseHeaders["content-type"]
                        ?: responseHeaders["Content-Type"] ?: ""
                    val isBinary = isBinaryContentType(responseContentType)

                    logger.info {
                        "HTTP $method $url -> $statusCode (binary=$isBinary, ${duration}ms)"
                    }

                    // Build header summary for LLM context (only useful headers)
                    val headerSummary = buildHeaderSummary(statusCode, responseHeaders)

                    if (isBinary) {
                        val bytes = response.readBytes()
                        if (saveToFile != null && statusCode in 200..399) {
                            val savedOutput = saveBinaryResponseToFile(saveToFile, bytes, url, responseContentType)
                            ToolResult(
                                success = true,
                                output = headerSummary + savedOutput,
                                exitCode = statusCode,
                                durationMs = duration,
                                bytesRead = bytes.size,
                                metadata = mapOf(
                                    "url" to url,
                                    "method" to method,
                                    "status_code" to statusCode,
                                    "response_length" to bytes.size,
                                    "saved_to_file" to saveToFile,
                                    "binary" to true,
                                    "response_headers" to responseHeaders
                                )
                            )
                        } else {
                            // Binary without save_to_file (or save skipped on non-2xx): return base64 (capped at 1MB)
                            val cap = minOf(bytes.size, MAX_BINARY_INLINE_BYTES)
                            val b64 = Base64.getEncoder().encodeToString(bytes.take(cap).toByteArray())
                            val truncated = bytes.size > MAX_BINARY_INLINE_BYTES
                            val saveSkippedNote = if (saveToFile != null) {
                                "NOTE: save_to_file was skipped because HTTP status $statusCode is not 2xx/3xx. Response body is returned inline for inspection."
                            } else null
                            val output = buildString {
                                appendLine("Binary response (${bytes.size} bytes, content-type: $responseContentType)")
                                if (truncated) appendLine("WARNING: truncated to $MAX_BINARY_INLINE_BYTES bytes")
                                if (saveSkippedNote != null) appendLine(saveSkippedNote)
                                if (saveToFile == null) appendLine("Use 'save_to_file' parameter to save binary content to disk.")
                                appendLine()
                                appendLine("Base64:")
                                append(b64)
                            }
                            // Tool succeeds whenever we received an HTTP response. Status code is
                            // returned in exitCode + output for the agent to react on. Only network
                            // failures / timeouts / exceptions map to success=false.
                            ToolResult(
                                success = true,
                                output = headerSummary + output,
                                exitCode = statusCode,
                                durationMs = duration,
                                bytesRead = bytes.size,
                                metadata = mapOf(
                                    "url" to url,
                                    "method" to method,
                                    "status_code" to statusCode,
                                    "response_length" to bytes.size,
                                    "binary" to true,
                                    "truncated" to truncated,
                                    "response_headers" to responseHeaders
                                )
                            )
                        }
                    } else {
                        val responseBody = response.bodyAsText()
                        // If save_to_file is specified, save to disk and return summary
                        if (saveToFile != null && statusCode in 200..399) {
                            val savedOutput = saveResponseToFile(saveToFile, responseBody, url)
                            ToolResult(
                                success = true,
                                output = headerSummary + savedOutput,
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
                            val saveSkippedPrefix = if (saveToFile != null) {
                                "NOTE: save_to_file was skipped because HTTP status $statusCode is not 2xx/3xx. Response body is returned inline for inspection.\n\n"
                            } else ""

                            // Tool succeeds whenever we received an HTTP response. Status code is
                            // returned in exitCode + output for the agent to react on. Only network
                            // failures / timeouts / exceptions map to success=false.
                            ToolResult(
                                success = true,
                                output = headerSummary + saveSkippedPrefix + truncatedBody,
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
     * Coerce a raw `body` parameter into a String suitable for the HTTP request.
     *
     * The LLM may pass `body` as either:
     *  - a String (already-serialized JSON, form data, or plain text), OR
     *  - a Map / List / primitive (a JSON object that the model embedded directly
     *    in its tool call instead of escaping into a string).
     *
     * Without this coercion the second case silently dropped to `null` (because
     * `params["body"] as? String` returns null on a Map), so the HTTP request
     * went out with an empty body and the server reported "no data sent". This
     * was the actual root cause of multiple stuck-agent reports.
     *
     * Strategy:
     *  - null → null (no body)
     *  - String → return as-is (don't double-encode)
     *  - Map/List/Array → serialize via Gson as JSON
     *  - Number/Boolean → toString (matches JSON literal form)
     *  - Anything else → try Gson, fall back to toString
     */
    internal fun coerceBody(raw: Any?): String? {
        return when (raw) {
            null -> null
            is String -> raw
            is Map<*, *>, is List<*>, is Array<*> -> {
                try {
                    GsonInstance.gson.toJson(raw)
                } catch (e: Exception) {
                    logger.warn { "Failed to serialize body of type ${raw::class.simpleName} to JSON via Gson: ${e.message}. Falling back to toString()." }
                    raw.toString()
                }
            }
            is Number, is Boolean -> raw.toString()
            else -> {
                try {
                    GsonInstance.gson.toJson(raw)
                } catch (e: Exception) {
                    logger.warn { "Failed to coerce body of type ${raw::class.simpleName}: ${e.message}. Falling back to toString()." }
                    raw.toString()
                }
            }
        }
    }

    private fun isBinaryContentType(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return BINARY_CONTENT_TYPE_PREFIXES.any { lower.startsWith(it) }
    }

    private fun saveBinaryResponseToFile(filePath: String, bytes: ByteArray, url: String, contentType: String): String {
        val resolvedPath = if (sandbox != null) {
            sandbox.resolve(filePath)
        } else {
            java.nio.file.Paths.get(filePath)
        }

        resolvedPath.parent?.let { parentDir ->
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir)
            }
        }

        Files.write(resolvedPath, bytes)

        logger.info { "Saved binary HTTP response to $resolvedPath (${bytes.size} bytes, $contentType)" }

        return buildString {
            appendLine("Binary response saved to file: $resolvedPath")
            appendLine("Source URL: $url")
            appendLine("Size: ${bytes.size} bytes")
            appendLine("Content-Type: $contentType")
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
                    "description" to "Request body for POST/PUT as a string. For JSON payloads, pass a JSON-serialized string (e.g. \"{\\\"key\\\":\\\"value\\\"}\"). Mutually exclusive with body_file."
                ),
                "body_file" to mapOf(
                    "type" to "string",
                    "description" to "Path to a file to send as request body (for POST/PUT). Preferred over 'body' for large or binary payloads — streams the file directly without loading it into LLM context. If both body and body_file are given, body_file takes precedence."
                ),
                "headers" to mapOf(
                    "type" to "object",
                    "description" to "HTTP headers as key-value pairs. For session auth pass cookies here, e.g. {\"Cookie\": \"session=abc; csrf=xyz\"} — copy values from the Set-Cookie header of a previous response.",
                    "additionalProperties" to mapOf("type" to "string")
                ),
                "content_type" to mapOf(
                    "type" to "string",
                    "description" to "Content-Type (default: application/json)."
                ),
                "save_to_file" to mapOf(
                    "type" to "string",
                    "description" to "Save response to this path instead of returning full content."
                )
            ),
            "required" to listOf("url")
        )
    }

    /**
     * Build a compact header summary for LLM context.
     * Only includes status line and headers useful for API interaction
     * (rate limits, retry, location, auth errors, content info).
     */
    private fun buildHeaderSummary(statusCode: Int, headers: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("HTTP $statusCode")

        val importantHeaders = headers.filter { (key, _) ->
            val lower = key.lowercase()
            IMPORTANT_HEADER_PREFIXES.any { lower.startsWith(it) }
        }

        if (importantHeaders.isNotEmpty()) {
            for ((key, value) in importantHeaders) {
                sb.appendLine("$key: $value")
            }
        }

        sb.appendLine()
        return sb.toString()
    }

    companion object {
        val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "DELETE", "PATCH")
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024 // 5MB
        const val TIMEOUT_MS = 60_000L // 60 seconds
        const val PREVIEW_CHARS = 500
        const val MAX_BINARY_INLINE_BYTES = 1 * 1024 * 1024 // 1MB base64 inline cap

        private val BINARY_CONTENT_TYPE_PREFIXES = listOf(
            "image/",
            "audio/",
            "video/",
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "application/gzip",
        )

        /** Header prefixes relevant for LLM decision-making (lowercase). */
        private val IMPORTANT_HEADER_PREFIXES = listOf(
            "retry-after",
            "x-ratelimit",
            "x-rate-limit",
            "ratelimit",
            "location",
            "www-authenticate",
            "content-type",
            "x-request-id",
            "set-cookie",
        )
    }
}
