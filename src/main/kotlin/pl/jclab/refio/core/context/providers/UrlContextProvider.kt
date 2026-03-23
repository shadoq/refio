package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private val logger = dualLogger("UrlContextProvider")

/**
 * Provider for web content from URLs.
 *
 * Usage: @url:https://example.com
 * Fetches and includes web page content.
 *
 * Note: Uses Ktor HttpClient for HTTP requests.
 */
class UrlContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "url",
        displayTitle = "url",
        description = "Fetch content from web URLs",
        type = ProviderType.QUERY,
        icon = "🌐"
    )

    private val client = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        // Add timeouts to prevent hanging
        engine {
            requestTimeout = 30000  // 30 seconds
            endpoint {
                connectTimeout = 5000  // 5 seconds
                socketTimeout = 25000  // 25 seconds
            }
        }
    }

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> = withContext(Dispatchers.IO) {
        val urlString = query.trim()
        if (urlString.isEmpty()) {
            logger.warn { "Empty URL provided" }
            return@withContext emptyList()
        }

        logger.debug { "URL fetch request: $urlString" }

        // Validate URL
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            logger.error(e) { "Invalid URL: $urlString" }
            return@withContext listOf(
                ContextItem(
                    description = "Invalid URL",
                    content = "[Error: Invalid URL format: $urlString]",
                    name = "URL Error",
                    uri = ContextUri(type = "url", value = urlString)
                )
            )
        }

        try {
            logger.info { "Fetching URL: $urlString" }

            val response = client.get(urlString)

            if (!response.status.isSuccess()) {
                logger.warn { "HTTP error: ${response.status.value} ${response.status.description}" }
                return@withContext listOf(
                    ContextItem(
                        description = "HTTP Error ${response.status.value}",
                        content = buildString {
                            appendLine("Failed to fetch URL: $urlString")
                            appendLine("Status: ${response.status.value} ${response.status.description}")
                            appendLine()
                            appendLine("Please check:")
                            appendLine("- URL is accessible")
                            appendLine("- No authentication required")
                            appendLine("- No CORS restrictions")
                        },
                        name = "URL Error",
                        uri = ContextUri(type = "url", value = urlString)
                    )
                )
            }

            val content = response.bodyAsText()
            val contentType = response.headers[HttpHeaders.ContentType] ?: "unknown"
            val contentLength = content.length

            logger.info { "Fetched $contentLength bytes from $urlString (type: $contentType)" }

            // Limit content size to prevent huge contexts
            val maxContentLength = 100_000  // 100 KB
            val truncatedContent = if (content.length > maxContentLength) {
                logger.warn { "Content truncated from ${content.length} to $maxContentLength bytes" }
                content.take(maxContentLength) + "\n\n[Content truncated - original size: ${content.length} bytes]"
            } else {
                content
            }

            return@withContext listOf(
                ContextItem(
                    description = "URL: ${url.host}",
                    content = buildString {
                        appendLine("URL: $urlString")
                        appendLine("Status: ${response.status.value}")
                        appendLine("Content-Type: $contentType")
                        appendLine("Content-Length: $contentLength bytes")
                        appendLine()
                        appendLine("Content:")
                        appendLine("---")
                        appendLine(truncatedContent)
                    },
                    name = url.host,
                    uri = ContextUri(type = "url", value = urlString)
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch URL: $urlString" }
            return@withContext listOf(
                ContextItem(
                    description = "Error fetching URL",
                    content = buildString {
                        appendLine("Failed to fetch $urlString")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine()
                        appendLine("Possible causes:")
                        appendLine("- Network connectivity issues")
                        appendLine("- Invalid URL")
                        appendLine("- Server timeout")
                        appendLine("- SSL/TLS certificate errors")
                    },
                    name = "Error",
                    uri = ContextUri(type = "error", value = urlString)
                )
            )
        }
    }
}
