package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("RagSearchTool")

/**
 * Tool: rag_search — semantic search over the project's RAG index.
 *
 * On-demand alternative to automatic RAG injection in the prompt context. The agent decides
 * when (and how broadly) to query, which keeps default turn context lean while still allowing
 * targeted lookups when the model knows what to look for.
 *
 * Parameters:
 * - query (string, required): natural-language search query
 * - top_k (int, optional, default 5, capped at MAX_TOP_K): number of fragments to return
 * - threshold (float, optional, default DEFAULT_THRESHOLD): minimum cosine similarity (0.0..1.0)
 * - content_type (string, optional): "PROJECT_CODE" | "DOCUMENTATION" — filter by index type
 *
 * Notes:
 * - Returns "No matches" instead of an error when nothing is above the threshold; this is
 *   normal feedback for the agent and should not be retried with the same args.
 * - The tool uses the embedding model/provider that the project was indexed with — the agent
 *   does not pick the model.
 */
class RagSearchTool(
    private val ragSearchService: RagSearchService,
    private val embeddingModel: String,
    private val projectRoot: Path
) : Tool {

    override val name: String = "rag_search"
    override val description: String =
        "Semantic search over the project's indexed code and documentation (RAG). " +
            "Use when you want to find relevant fragments by meaning rather than by exact text. " +
            "Prefer grep_search for exact identifiers and rag_search for concepts."
    override val mode: ToolMode = ToolMode.READ_ONLY
    override val category: ToolCategory = ToolCategory.DATA_PRODUCING
    override val selectionHint: String =
        "Semantic search over indexed code/docs. Use for concepts without good keywords; " +
        "prefer grep_search when you know an exact identifier."

    override fun validateParams(params: Map<String, Any>) {
        val query = params["query"] as? String
        if (query.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'query' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        try {
            val query = (params["query"] as? String)?.trim()
                ?: return ToolResult.error("Missing required parameter: 'query'")
            if (query.isBlank()) {
                return ToolResult.error("Parameter 'query' cannot be empty")
            }

            val topK = ((params["top_k"] as? Number)?.toInt() ?: DEFAULT_TOP_K)
                .coerceIn(1, MAX_TOP_K)
            val threshold = ((params["threshold"] as? Number)?.toFloat() ?: DEFAULT_THRESHOLD)
                .coerceIn(0.0f, 1.0f)
            val contentTypeRaw = (params["content_type"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val contentType = contentTypeRaw?.let { raw ->
                runCatching { RagContentType.valueOf(raw.uppercase()) }
                    .getOrElse {
                        return ToolResult.error(
                            "Invalid content_type: '$raw'. Allowed values: ${RagContentType.values().joinToString(", ")}"
                        )
                    }
            }

            logger.info {
                "rag_search: query='${query.take(80)}', top_k=$topK, threshold=$threshold, " +
                    "content_type=${contentType ?: "ANY"}"
            }

            val config = RagSearchConfig(
                similarityThreshold = threshold,
                topK = topK,
                contentType = contentType
            )

            val results = ragSearchService.search(
                projectRoot = projectRoot.toString(),
                query = query,
                model = embeddingModel,
                config = config
            )

            val duration = (System.currentTimeMillis() - startTime).toInt()
            val output = if (results.isEmpty()) {
                "No matches found for query: $query (threshold=$threshold, top_k=$topK). " +
                    "Try lowering threshold, broader phrasing, or grep_search for exact identifiers."
            } else {
                buildString {
                    appendLine("Found ${results.size} fragment(s) for: $query")
                    appendLine()
                    results.forEachIndexed { index, r ->
                        val location = buildString {
                            append(r.filePath)
                            if (r.startLine != null) {
                                append(":")
                                append(r.startLine)
                                if (r.endLine != null && r.endLine != r.startLine) {
                                    append("-")
                                    append(r.endLine)
                                }
                            }
                        }
                        val sim = "%.3f".format(r.similarity)
                        appendLine("--- [${index + 1}] $location (similarity=$sim, type=${r.contentType.name}) ---")
                        appendLine(r.content.trimEnd())
                        appendLine()
                    }
                }.trimEnd()
            }

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                metadata = mapOf(
                    "result_count" to results.size,
                    "query" to query,
                    "top_k" to topK,
                    "threshold" to threshold,
                    "content_type" to (contentType?.name ?: "ANY")
                )
            )
        } catch (e: Exception) {
            logger.warn(e) { "rag_search failed: ${e.message}" }
            return ToolResult.error("rag_search failed: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "Natural-language search query (concept, behaviour, intent — not exact identifier)."
                ),
                "top_k" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of fragments to return (1..$MAX_TOP_K).",
                    "default" to DEFAULT_TOP_K
                ),
                "threshold" to mapOf(
                    "type" to "number",
                    "description" to "Minimum cosine similarity (0.0..1.0). Higher = stricter.",
                    "default" to DEFAULT_THRESHOLD
                ),
                "content_type" to mapOf(
                    "type" to "string",
                    "description" to "Filter by index type. One of: PROJECT_CODE, DOCUMENTATION. Omit for all.",
                    "enum" to listOf("PROJECT_CODE", "DOCUMENTATION")
                )
            ),
            "required" to listOf("query")
        )
    }

    companion object {
        const val DEFAULT_TOP_K = 5
        const val MAX_TOP_K = 15
        const val DEFAULT_THRESHOLD = 0.65f
    }
}
