package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.models.context.CodeFragmentDTO
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.EmbeddingCircuitBreaker
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("RagContextLoader")
// Hard cap on auto-injected RAG fragments. The actual count is taken from
// ConfigKeys.RAG_SEARCH_TOP_K (default 5) but we never exceed this even if a user sets a
// higher value, because auto-RAG bloats the prompt at turn start before the agent has
// any signal about which fragments are actually relevant.
private const val MAX_AUTO_RAG_FRAGMENTS = 8

class RagContextLoader(
    private val configService: ConfigService,
    ragSearchService: RagSearchService? = null,
    ragSearchModel: String? = null,
    ragSearchProvider: String? = null
) {
    @Volatile
    private var ragSearchServiceRef: RagSearchService? = ragSearchService

    @Volatile
    private var ragSearchModelRef: String? = ragSearchModel

    @Volatile
    private var ragSearchProviderRef: String? = ragSearchProvider

    fun updateRagSearchConfig(service: RagSearchService?, model: String?, provider: String?) {
        ragSearchServiceRef = service
        ragSearchModelRef = model
        ragSearchProviderRef = provider
    }

    /**
     * Determines if RAG should be skipped for simple/meta questions.
     * Based on ADR 0017: Refaktoryzacja Context Service.
     *
     * @param query User query
     * @return true if RAG should be skipped, false otherwise
     */
    internal fun shouldSkipRag(query: String?): Boolean {
        if (query.isNullOrBlank()) return true

        val queryLower = query.lowercase().trim()

        // System-injected harness phrases (retry / continue / nudge follow-ups). Embedding these
        // wastes 8-20s per iteration and never returns useful fragments because they carry no
        // task-specific signal.
        val systemPhrases = listOf(
            "continue from where you left off",
            "continue where you left off",
            "kontynuuj od miejsca",
            "kontynuuj zadanie"
        )
        if (systemPhrases.any { queryLower.contains(it) }) {
            logger.info { "[CONTEXT] Skipping RAG - system harness phrase: ${query.take(80)}" }
            return true
        }

        // Meta questions that don't need code context
        val metaPatterns = listOf(
            "co wiesz", "what do you know",
            "opisz projekt", "describe project", "describe the project",
            "jaki to projekt", "what project", "what is this project",
            "podsumuj", "summarize",
            "struktura", "structure", "project structure",
            "technologie", "technologies", "tech stack",
            "architektura", "architecture",
            "co to za projekt", "what kind of project"
        )

        if (metaPatterns.any { queryLower.contains(it) }) {
            logger.info { "[CONTEXT] Skipping RAG - meta question detected: ${query.take(100)}" }
            return true
        }

        // Short questions are usually meta (unless they mention code/file)
        if (query.length < 30 &&
            !queryLower.contains("kod") &&
            !queryLower.contains("code") &&
            !queryLower.contains("file") &&
            !queryLower.contains("plik") &&
            !queryLower.contains("function") &&
            !queryLower.contains("funkcja") &&
            !queryLower.contains("class") &&
            !queryLower.contains("klasa")
        ) {
            logger.info { "[CONTEXT] Skipping RAG - short meta question: ${query}" }
            return true
        }

        return false
    }

    suspend fun loadRagFragments(
        projectRoot: Path,
        query: String?,
    ): List<CodeFragmentDTO> {
        val searchService = ragSearchServiceRef ?: run {
            logger.info { "[CONTEXT] RAG search service not configured - skipping fragments" }
            return emptyList()
        }
        val model = ragSearchModelRef ?: run {
            logger.warn { "[CONTEXT] RAG search model is not configured - skipping fragments" }
            return emptyList()
        }
        if (ragSearchProviderRef.equals("ollama", ignoreCase = true)) {
            val endpoint = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
            val providerKey = "ollama:$endpoint"
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                val retryMs = EmbeddingCircuitBreaker.getCooldownRemaining(providerKey)
                logger.warn { "[CONTEXT] Skipping RAG fragments - Ollama unavailable (circuit OPEN, retry in ${retryMs}ms, endpoint=$endpoint)" }
                return emptyList()
            }
        }

        // Skip RAG for simple/meta questions (ADR 0017)
        if (shouldSkipRag(query)) {
            return emptyList()
        }

        val queryParts = listOfNotNull(query?.trim())
            .filter { it.isNotBlank() }
        if (queryParts.isEmpty()) {
            logger.info { "[CONTEXT] No RAG query data provided - skipping fragments" }
            return emptyList()
        }

        val combinedQuery = queryParts.joinToString("\n\n")
        val keywords = extractRagKeywords(queryParts)
        val hybridEnabled = configService.getTyped(ConfigKeys.RAG_SEARCH_HYBRID_ENABLED)

        return try {
            logger.info {
                "[CONTEXT] Running hybrid RAG search: query='${combinedQuery.take(120)}...', keywords=$keywords"
            }
            // topK comes from config (default 5), capped at MAX_AUTO_RAG_FRAGMENTS so an
            // unintended high config value cannot pollute the startup prompt with noise.
            val configuredTopK = configService.getTyped(ConfigKeys.RAG_SEARCH_TOP_K)
            val effectiveTopK = configuredTopK.coerceIn(1, MAX_AUTO_RAG_FRAGMENTS)
            val config = RagSearchConfig(
                similarityThreshold = configService.getTyped(ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD),
                topK = effectiveTopK,
                hybridSearch = hybridEnabled,
                keywords = keywords,
                semanticWeight = configService.getTyped(ConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT),
                includeContextChunks = configService.getTyped(ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)
            )
            val results = searchService.search(
                projectRoot = projectRoot.toString(),
                query = combinedQuery,
                model = model,
                config = config
            )

            val out = results.map { result ->
                CodeFragmentDTO(
                    filePath = result.filePath,
                    content = result.content,
                    startLine = result.startLine,
                    endLine = result.endLine,
                    similarity = result.similarity,
                    contentType = result.contentType.name
                )
            }

            logger.info { "Found the: ${out.size} fragments" }

            out
        } catch (e: Exception) {
            logger.warn(e) { "[CONTEXT] Hybrid RAG search failed (${e.message})" }
            emptyList()
        }
    }

    internal fun extractRagKeywords(parts: List<String>): List<String> {
        if (parts.isEmpty()) {
            return emptyList()
        }

        val tokens = parts
            .flatMap { it.split(Regex("[^A-Za-z0-9_/\\\\-]+")) }
            .map { it.trim().lowercase() }
            .filter { it.length >= 4 }
            .map { it.take(64) }

        // Prioritize unique keywords while preserving order
        val seen = mutableSetOf<String>()
        val keywords = mutableListOf<String>()
        for (token in tokens) {
            if (seen.add(token)) {
                keywords.add(token)
            }
            if (keywords.size >= 12) {
                break
            }
        }

        return keywords
    }
}
