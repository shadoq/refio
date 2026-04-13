package pl.jclab.refio.core.tools.implementations

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

private val logger = dualLogger("FetchWebpageTool")

class FetchWebpageTool(
    private val llmClient: LLMClient,
    private val configService: ConfigService
) : Tool {
    override val name = "fetch_webpage"
    override val description = "Fetch a URL, convert HTML to Markdown, then extract information with AI using your prompt. " +
        "For raw HTTP/API access use http_request instead."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        val url = params["url"] as? String
        if (url.isNullOrBlank()) throw IllegalArgumentException("Parameter 'url' is required")
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw IllegalArgumentException("'url' must start with http:// or https://")
        val prompt = params["prompt"] as? String
        if (prompt.isNullOrBlank()) throw IllegalArgumentException("Parameter 'prompt' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val url = params["url"] as? String
            ?: return@withContext ToolResult.error("Missing 'url'")
        val prompt = params["prompt"] as? String
            ?: return@withContext ToolResult.error("Missing 'prompt'")
        val maxContentChars = ((params["max_content_chars"] as? Number)?.toInt() ?: 20_000)
            .coerceIn(1_000, 50_000)

        logger.info { "Fetching $url for AI processing" }
        val html = try {
            fetchHtml(url)
        } catch (e: Exception) {
            return@withContext ToolResult.error("Failed to fetch URL: ${e.message}")
        }

        val markdown = htmlToMarkdown(html, url, maxContentChars)

        val (model, provider) = try {
            configService.getWeakModel()
        } catch (e: Exception) {
            return@withContext ToolResult.error("No LLM model configured: ${e.message}")
        }

        val systemPrompt = "You are a precise web content extractor. " +
            "The user will provide Markdown content from a webpage and a request. " +
            "Answer the request using only the provided content. Be concise and accurate."

        val userMessage = "## Webpage: $url\n\n$markdown\n\n---\n\n## Request\n\n$prompt"

        val messages = listOf(
            LLMMessage(role = "user", content = userMessage)
        )

        logger.info { "Processing page with LLM (model=$model, provider=$provider, content=${markdown.length} chars)" }

        val llmResponse = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = 2048,
                stream = false,
                source = "FetchWebpageTool"
            )
        } catch (e: Exception) {
            return@withContext ToolResult.error("LLM processing failed: ${e.message}")
        }

        val answer = llmResponse.content

        ToolResult(
            success = true,
            output = answer,
            durationMs = (System.currentTimeMillis() - startTime).toInt(),
            metadata = mapOf(
                "url" to url,
                "content_chars" to markdown.length,
                "model" to model
            )
        )
    }

    private suspend fun fetchHtml(url: String): String {
        val client = HttpClient(CIO) {
            engine { requestTimeout = 20_000 }
            followRedirects = true
        }
        try {
            val response = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (compatible; Refio/1.0)")
                header("Accept", "text/html,application/xhtml+xml,*/*")
            }
            return response.bodyAsText()
        } finally {
            client.close()
        }
    }

    private fun htmlToMarkdown(html: String, baseUrl: String, maxChars: Int): String {
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("script, style, nav, footer, header, aside, .sidebar, .menu, .ads, .advertisement").remove()

        val mainContent = doc.select("main, article, .content, .post, #content, #main").firstOrNull()
            ?: doc.body()

        return buildString {
            appendLine("# ${doc.title()}")
            appendLine()
            convertElement(mainContent, this)
        }.take(maxChars).let {
            if (it.length == maxChars) "$it\n\n[... content truncated at $maxChars chars ...]"
            else it
        }
    }

    private fun convertElement(el: Element, sb: StringBuilder) {
        el.children().forEach { child ->
            when (child.tagName()) {
                "h1" -> sb.appendLine("# ${child.text()}\n")
                "h2" -> sb.appendLine("## ${child.text()}\n")
                "h3" -> sb.appendLine("### ${child.text()}\n")
                "h4", "h5", "h6" -> sb.appendLine("#### ${child.text()}\n")
                "p" -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) sb.appendLine("$text\n")
                }
                "ul", "ol" -> {
                    child.select("li").forEach { li ->
                        sb.appendLine("- ${li.text()}")
                    }
                    sb.appendLine()
                }
                "pre", "code" -> {
                    sb.appendLine("```\n${child.text()}\n```\n")
                }
                "a" -> {
                    val href = child.attr("abs:href")
                    val text = child.text()
                    if (text.isNotBlank() && href.isNotBlank()) sb.append("[$text]($href) ")
                }
                "br" -> sb.appendLine()
                else -> convertElement(child, sb)
            }
        }
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "url" to mapOf(
                "type" to "string",
                "description" to "URL to fetch"
            ),
            "prompt" to mapOf(
                "type" to "string",
                "description" to "What to extract or answer from the page. Be specific."
            ),
            "max_content_chars" to mapOf(
                "type" to "integer",
                "description" to "Max characters of page content to process (default: 20000, max: 50000)"
            )
        ),
        "required" to listOf("url", "prompt")
    )
}
