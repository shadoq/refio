package pl.jclab.refio.core.tools.implementations

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.utils.GsonInstance

private val logger = dualLogger("WebSearchTool")

class WebSearchTool(
    private val configService: ConfigService,
    private val networkPolicy: NetworkPolicy = NetworkPolicy(configService)
) : Tool {
    override val name = "web_search"
    override val description = "Search the web and return results with titles, URLs, and snippets. " +
        "Use when you need current information, documentation, release notes, or answers " +
        "that are not in the project codebase. " +
        "Requires 'tools.web_search.provider' and corresponding API key in config."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint = "Web search — current info, docs, release notes not in the codebase."

    override fun validateParams(params: Map<String, Any>) {
        val query = params["query"] as? String
        if (query.isNullOrBlank()) throw IllegalArgumentException("Parameter 'query' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val query = params["query"] as? String
            ?: return@withContext ToolResult.error("Missing required parameter: 'query'")
        val maxResults = ((params["max_results"] as? Number)?.toInt() ?: 10).coerceIn(1, 20)
        val taskId = params[ToolInternalParams.TASK_ID] as? String

        val provider = getConfig("tools.web_search.provider") ?: "duckduckgo"

        try {
            networkPolicy.assertEgressAllowed(name, "web_search:$provider", taskId)
        } catch (e: NoEgressViolationException) {
            return@withContext ToolResult.error(e.message ?: "no-egress mode blocks this call")
        }

        val results: List<SearchResult> = try {
            when (provider) {
                "brave" -> {
                    val apiKey = getConfig("tools.web_search.brave_api_key")
                        ?: return@withContext ToolResult.error(
                            "Brave Search API key not configured. " +
                                "Add 'tools.web_search.brave_api_key' to ~/.refio/config.yaml"
                        )
                    searchBrave(query, maxResults, apiKey)
                }
                "serpapi" -> {
                    val apiKey = getConfig("tools.web_search.serpapi_key")
                        ?: return@withContext ToolResult.error("SerpAPI key not configured.")
                    searchSerpApi(query, maxResults, apiKey)
                }
                "duckduckgo" -> searchDuckDuckGo(query, maxResults)
                else -> return@withContext ToolResult.error("Unknown search provider: $provider")
            }
        } catch (e: WebSearchProviderException) {
            logger.warn { "Web search provider error ($provider): ${e.message}" }
            return@withContext ToolResult.error(
                "Web search provider '$provider' failed: ${e.message}. " +
                    "Configure a different provider (brave/serpapi) in ~/.refio/config.yaml or retry later."
            )
        } catch (e: Exception) {
            logger.error(e) { "Web search failed: ${e.message}" }
            return@withContext ToolResult.error("Web search failed: ${e.message}")
        }

        if (results.isEmpty()) {
            // DuckDuckGo Instant Answer is not a general web search — explicit hint avoids the
            // agent retrying the same query (observed in session 2c7c570d: 2× identical queries).
            val hint = if (provider == "duckduckgo") {
                " (DuckDuckGo provider uses Instant Answer API which has limited coverage; " +
                    "configure 'brave' or 'serpapi' for general web search)"
            } else ""
            return@withContext ToolResult(
                success = true,
                output = "No results found for: $query$hint",
                durationMs = elapsed(startTime)
            )
        }

        val output = buildString {
            appendLine("Search results for: \"$query\"")
            appendLine("Provider: $provider | Found: ${results.size} results")
            appendLine()
            results.forEachIndexed { i, r ->
                appendLine("${i + 1}. ${r.title}")
                appendLine("   URL: ${r.url}")
                if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
                appendLine()
            }
        }

        ToolResult(
            success = true,
            output = output,
            durationMs = elapsed(startTime),
            metadata = mapOf(
                "query" to query,
                "provider" to provider,
                "result_count" to results.size,
                "results" to results.map { mapOf("title" to it.title, "url" to it.url, "snippet" to it.snippet) }
            )
        )
    }

    private suspend fun searchBrave(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val client = HttpClient(CIO) { engine { requestTimeout = 15_000 } }
        try {
            val response = client.get("https://api.search.brave.com/res/v1/web/search") {
                parameter("q", query)
                parameter("count", maxResults)
                header("Accept", "application/json")
                header("X-Subscription-Token", apiKey)
            }
            val responseText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw WebSearchProviderException("HTTP ${response.status.value}: ${responseText.take(200)}")
            }
            val body = GsonInstance.gson.fromJson(responseText, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val webResults = (body["web"] as? Map<*, *>)?.get("results") as? List<Map<*, *>>
                ?: emptyList()
            return webResults.map { r ->
                SearchResult(
                    title = r["title"] as? String ?: "",
                    url = r["url"] as? String ?: "",
                    snippet = r["description"] as? String ?: ""
                )
            }
        } finally {
            client.close()
        }
    }

    private suspend fun searchSerpApi(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val client = HttpClient(CIO) { engine { requestTimeout = 15_000 } }
        try {
            val response = client.get("https://serpapi.com/search") {
                parameter("q", query)
                parameter("num", maxResults)
                parameter("api_key", apiKey)
                parameter("engine", "google")
            }
            if (!response.status.isSuccess()) {
                throw WebSearchProviderException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val body = GsonInstance.gson.fromJson(response.bodyAsText(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val organicResults = body["organic_results"] as? List<Map<*, *>> ?: emptyList()
            return organicResults.take(maxResults).map { r ->
                SearchResult(
                    title = r["title"] as? String ?: "",
                    url = r["link"] as? String ?: "",
                    snippet = r["snippet"] as? String ?: ""
                )
            }
        } finally {
            client.close()
        }
    }

    private suspend fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val client = HttpClient(CIO) { engine { requestTimeout = 15_000 } }
        try {
            val response = client.get("https://api.duckduckgo.com/") {
                parameter("q", query)
                parameter("format", "json")
                parameter("no_html", "1")
                parameter("skip_disambig", "1")
            }
            if (!response.status.isSuccess()) {
                throw WebSearchProviderException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val body = GsonInstance.gson.fromJson(response.bodyAsText(), Map::class.java)
            val results = mutableListOf<SearchResult>()

            val abstractText = body["AbstractText"] as? String ?: ""
            val abstractUrl = body["AbstractURL"] as? String ?: ""
            val abstractTitle = body["Heading"] as? String ?: query
            if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(SearchResult(abstractTitle, abstractUrl, abstractText))
            }

            @Suppress("UNCHECKED_CAST")
            val related = body["RelatedTopics"] as? List<Map<*, *>> ?: emptyList()
            related.take(maxResults - results.size).forEach { topic ->
                val text = topic["Text"] as? String ?: return@forEach
                val url = (topic["FirstURL"] as? String) ?: return@forEach
                if (text.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(text.take(80), url, text))
                }
            }
            return results
        } finally {
            client.close()
        }
    }

    private fun getConfig(key: String): String? {
        return try {
            configService.get(key)
        } catch (_: Exception) {
            null
        }
    }

    private fun elapsed(start: Long) = (System.currentTimeMillis() - start).toInt()

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "Search query"
            ),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Max results to return (1-20, default: 10)"
            )
        ),
        "required" to listOf("query")
    )

    data class SearchResult(val title: String, val url: String, val snippet: String)

    private class WebSearchProviderException(message: String) : RuntimeException(message)
}
