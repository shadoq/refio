package pl.jclab.refio.core.services

import org.htmlunit.BrowserVersion
import org.htmlunit.IncorrectnessListener
import org.htmlunit.SilentCssErrorHandler
import org.htmlunit.WebClient
import org.htmlunit.html.HtmlPage
import pl.jclab.refio.services.logging.dualLogger
import java.io.Closeable

/**
 * Lightweight HTML fetcher with optional JS rendering.
 * Shared wrapper so providers/indexers use the same code path.
 */
class DocumentFetcher(
    private val enableJs: Boolean = true,
    private val requestTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val jsWaitMs: Long = DEFAULT_JS_WAIT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT
) : Closeable {

    private val logger = dualLogger("DocumentFetcher")

    /**
     * Fetch HTML from URL. If JS is enabled, waits briefly for scripts.
     */
    fun fetch(url: String): FetchResult {
        // Always use JS (per requirement), but swallow JS errors and return best-effort HTML.
        val modes = listOf(true)
        var lastResult: FetchResult? = null

        for (jsEnabled in modes) {
            try {
                val result = fetchInternal(url, jsEnabled)
                if (result.html.isNotBlank()) {
                    return result
                }
                lastResult = result
            } catch (e: Exception) {
                logger.warn { "Document fetch failed (jsEnabled=$jsEnabled): ${e.message}" }
            }
        }

        // Best effort: return last (possibly empty) result instead of throwing
        return lastResult ?: FetchResult(
            url = url,
            statusCode = 0,
            contentType = null,
            html = ""
        )
    }

    private fun fetchInternal(url: String, jsEnabled: Boolean): FetchResult {
        return WebClient(BrowserVersion.CHROME).use { client ->
            configure(client, jsEnabled)

            val page = runCatching { client.getPage<HtmlPage>(url) }.getOrElse { ex ->
                logger.debug { "Failed to fetch (jsEnabled=$jsEnabled): ${ex.message}" }
                return FetchResult(
                    url = url,
                    statusCode = 0,
                    contentType = null,
                    html = ""
                )
            }

            if (jsEnabled) {
                client.waitForBackgroundJavaScript(jsWaitMs)
            }

            val html = page.asXml()
            FetchResult(
                url = page.url.toString(),
                statusCode = page.webResponse.statusCode,
                contentType = page.webResponse.contentType,
                html = html
            )
        }
    }

    private fun configure(client: WebClient, jsEnabled: Boolean) {
        val options = client.options
        options.isCssEnabled = false
        options.isJavaScriptEnabled = jsEnabled
        options.timeout = requestTimeoutMs
        options.isThrowExceptionOnFailingStatusCode = false
        options.isThrowExceptionOnScriptError = false
        options.isUseInsecureSSL = true
        options.isRedirectEnabled = true
        client.cache.clear()
        client.addRequestHeader("User-Agent", userAgent)
        client.cssErrorHandler = SilentCssErrorHandler()
        client.incorrectnessListener = IncorrectnessListener { _, _ -> }
        // Disable JS error logging entirely to avoid noise on modern bundles
        client.javaScriptErrorListener = null
    }

    override fun close() {
        // no-op (WebClient closed per call)
    }

    data class FetchResult(
        val url: String,
        val statusCode: Int,
        val contentType: String?,
        val html: String
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10000
        private const val DEFAULT_JS_WAIT_MS = 1500L
        private const val DEFAULT_USER_AGENT = "Refio Documentation Fetcher/1.0"
    }
}
