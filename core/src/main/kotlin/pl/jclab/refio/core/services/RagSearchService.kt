package pl.jclab.refio.core.services

import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.rag.BM25Scorer
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.logging.dualLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt

private val logger = dualLogger("RagSearchService")

/**
 * Service for semantic search using RAG.
 *
 * Features:
 * - Cosine similarity search
 * - Content type filtering (PROJECT_CODE vs DOCUMENTATION)
 * - Result ranking and deduplication
 * - Configurable similarity threshold
 * - Project isolation (projectRoot)
 *
 * Usage:
 * ```
 * val service = RagSearchService(ragRepository, embeddingProvider)
 * val results = service.search(
 *     projectRoot = "/path/to/project",
 *     query = "authentication logic",
 *     topK = 5
 * )
 * results.forEach { result ->
 *     println("${result.filePath}:${result.startLine} (similarity: ${result.similarity})")
 *     println(result.content)
 * }
 * ```
 */
class RagSearchService(
    private val ragRepository: RagRepository,
    private val embeddingProvider: EmbeddingProvider,
    private val defaultConfig: RagSearchConfig = RagSearchConfig(),
    private val bm25Scorer: BM25Scorer? = null
) {
    companion object {
        private const val MIN_SIMILARITY_THRESHOLD = 0.3f
        private const val SEARCH_BATCH_SIZE = 500

        /**
         * How many candidates per requested result are kept for the ranking stage. Everything
         * after the similarity scan (chunk body loading, redundancy dedup, context chunks) is
         * paid per candidate, and dedup is quadratic in full chunk text, so the pool has to be
         * bounded: a query with a low threshold can otherwise match the whole index. 8x leaves
         * dedup a wide pool to collapse overlapping regions from and still refill topK.
         */
        private const val CANDIDATE_POOL_MULTIPLIER = 8

        /** Upper bound on the pool regardless of topK; never trims below topK itself. */
        private const val CANDIDATE_POOL_CAP = 500

        /** Context chunks are drawn from a deliberately wider similarity band than results. */
        private const val CONTEXT_THRESHOLD_FACTOR = 0.8f

        private fun candidatePoolSize(topK: Int): Int {
            val scaled = topK.toLong() * CANDIDATE_POOL_MULTIPLIER
            return scaled
                .coerceAtMost(CANDIDATE_POOL_CAP.toLong())
                .coerceAtLeast(topK.toLong())
                .toInt()
        }
    }

    /**
     * Search for relevant chunks using semantic similarity.
     *
     * @param projectRoot Project root directory (isolates search per project)
     * @param query Search query
     * @param model Embedding model (must match indexed embeddings)
     * @param config Search configuration
     * @return List of search results sorted by similarity (descending)
     * @throws kotlinx.coroutines.TimeoutCancellationException if search exceeds timeout
     */
    suspend fun search(
        projectRoot: String,
        query: String,
        model: String = "ollama/nomic-embed-text",
        config: RagSearchConfig = defaultConfig
    ): List<RagSearchResult> = withTimeout(config.timeoutMs) {
        val startTime = System.currentTimeMillis()
        logger.info {
            "Searching RAG: project=$projectRoot, query='${query.take(50)}...', " +
                "topK=${config.topK}, hybrid=${config.hybridSearch}"
        }

        if (query.isBlank()) {
            logger.warn { "Empty query provided" }
            return@withTimeout emptyList()
        }

        try {
            if (config.hybridSearch) {
                return@withTimeout hybridSearchInternal(
                    projectRoot = projectRoot,
                    query = query,
                    keywords = config.keywords,
                    model = model,
                    config = config
                )
            }

            return@withTimeout semanticSearchInternal(
                projectRoot = projectRoot,
                query = query,
                model = model,
                config = config
            )
        } finally {
            GlobalMetrics.recordOperationTime("rag_search", System.currentTimeMillis() - startTime)
        }
    }

    /**
     * Search for relevant chunks using semantic similarity.
     *
     * @param projectRoot Project root directory (isolates search per project)
     * @param query Search query
     * @param model Embedding model (must match indexed embeddings)
     * @param topK Number of results to return
     * @param contentType Filter by content type (null = all types)
     * @param similarityThreshold Minimum similarity score (0.0 to 1.0)
     * @param timeoutMs Timeout in milliseconds (default 30s)
     * @return List of search results sorted by similarity (descending)
     * @throws kotlinx.coroutines.TimeoutCancellationException if search exceeds timeout
     */
    suspend fun search(
        projectRoot: String,
        query: String,
        model: String = "ollama/nomic-embed-text",
        topK: Int = 5,
        contentType: RagContentType? = null,
        similarityThreshold: Float = RagSearchConfig.DEFAULT_SIMILARITY_THRESHOLD,
        timeoutMs: Long = 30_000L
    ): List<RagSearchResult> = search(
        projectRoot = projectRoot,
        query = query,
        model = model,
        config = RagSearchConfig(
            similarityThreshold = similarityThreshold,
            topK = topK,
            contentType = contentType,
            timeoutMs = timeoutMs
        )
    )

    private suspend fun semanticSearchInternal(
        projectRoot: String,
        query: String,
        model: String,
        config: RagSearchConfig
    ): List<RagSearchResult> {
        logger.info { "Semantic RAG search: project=$projectRoot, query='${query.take(50)}...'" }

        // 1. Generate query embedding
        val queryVector = try {
            embeddingProvider.generateEmbedding(query, model)
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate query embedding" }
            throw Exception("Failed to generate query embedding: ${e.message}", e)
        }

        val totalEmbeddings = ragRepository.countEmbeddings(projectRoot, model, config.contentType)
        logger.debug { "Found $totalEmbeddings embeddings to search" }

        if (totalEmbeddings == 0) {
            logger.warn { "No embeddings found for project=$projectRoot, model=$model" }
            return emptyList()
        }

        // Only the best candidates survive the scan. The similarity pass is cheap (vectors are
        // already in memory), everything downstream is not, so the pool is capped here instead
        // of letting every chunk above the threshold through.
        val poolSize = candidatePoolSize(config.topK)
        val poolThreshold = if (config.includeContextChunks) {
            contextThreshold(config)
        } else {
            config.similarityThreshold
        }
        val candidateHeap = PriorityQueue<Pair<Float, Int>>(poolSize + 1, compareBy { it.first })

        // Query vector norm is constant for the whole search - compute it once
        // instead of once per compared chunk.
        val queryNorm = vectorNorm(queryVector)

        var lastSeenId = 0
        var scanned = 0
        while (scanned < totalEmbeddings) {
            val batch = ragRepository.getEmbeddingsBatch(
                projectRoot = projectRoot,
                model = model,
                contentType = config.contentType,
                afterId = lastSeenId,
                limit = SEARCH_BATCH_SIZE
            )
            if (batch.isEmpty()) {
                break
            }

            batch.forEach { embedding ->
                try {
                    val chunkVector = deserializeVector(embedding.vector)
                    val similarity = cosineSimilarity(queryVector, chunkVector, queryNorm)

                    if (similarity >= poolThreshold) {
                        if (candidateHeap.size < poolSize) {
                            candidateHeap.offer(similarity to embedding.chunkId)
                        } else if (similarity > (candidateHeap.peek()?.first ?: Float.NEGATIVE_INFINITY)) {
                            candidateHeap.poll()
                            candidateHeap.offer(similarity to embedding.chunkId)
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process embedding ${embedding.id}" }
                }
            }

            scanned += batch.size
            lastSeenId = batch.last().id
        }

        val similarityByChunkId = candidateHeap.associate { (similarity, chunkId) -> chunkId to similarity }

        val chunkIdsAboveThreshold = similarityByChunkId
            .filterValues { it >= config.similarityThreshold }
            .keys
            .toList()

        logger.debug {
            "Candidates kept: ${similarityByChunkId.size} (pool $poolSize), " +
                "above threshold: ${chunkIdsAboveThreshold.size}/$totalEmbeddings"
        }

        if (chunkIdsAboveThreshold.isEmpty()) {
            logger.info { "No embeddings above threshold ${config.similarityThreshold}" }
            return emptyList()
        }

        val chunksMap = ragRepository.getChunksBatch(chunkIdsAboveThreshold).associateBy { it.id }
        val fileIds = chunksMap.values.map { it.fileId }.distinct()
        val filesMap = ragRepository.getFilesBatch(fileIds).associateBy { it.id }

        logger.debug { "Prefetched ${chunksMap.size} chunks and ${filesMap.size} files" }

        val results = chunkIdsAboveThreshold.mapNotNull { chunkId ->
            try {
                val chunk = chunksMap[chunkId]
                if (chunk == null) {
                    logger.warn { "Chunk $chunkId not found for semantic search result" }
                    return@mapNotNull null
                }

                val file = filesMap[chunk.fileId]
                if (file == null) {
                    logger.warn { "File ${chunk.fileId} not found for chunk ${chunk.id}" }
                    return@mapNotNull null
                }

                val similarity = similarityByChunkId[chunkId] ?: return@mapNotNull null

                RagSearchResult(
                    chunkId = chunk.id,
                    fileId = file.id,
                    filePath = file.filePath,
                    content = chunk.content,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    similarity = similarity,
                    contentType = file.contentType
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to build result for chunk $chunkId" }
                null
            }
        }

        val finalResults = if (config.includeContextChunks) {
            addContextChunks(
                results = results,
                similarityByChunkId = similarityByChunkId,
                chunksMap,
                filesMap = filesMap,
                config = config
            )
        } else {
            results
        }

        // Log similarity distribution for debugging (use the candidate pool, already ranked)
        val topSimilarities = candidateHeap.sortedByDescending { it.first }.take(5)
        logger.debug { "Top ${topSimilarities.size} similarities: ${topSimilarities.map { String.format("%.3f", it.first) }}" }
        logger.debug {
            "Threshold: ${config.similarityThreshold}, " +
                "Results above threshold: ${results.size}/$totalEmbeddings"
        }

        val topResults = dedupeRedundantRegions(finalResults)
            .take(config.topK)

        logger.info {
            "Search completed: ${topResults.size} results " +
                "(from ${results.size} above threshold ${config.similarityThreshold})"
        }

        return topResults
    }

    /**
     * Collapse results that are redundant with a higher-similarity one. The chunker can emit
     * overlapping chunks of the same file region (full-file ⊃ class ⊃ method) whose embeddings
     * are near-identical, so a single region can occupy the whole top-K as copies — starving a
     * weak model of distinct signal and driving it into re-search loops (observed 2026-05,
     * session 1fc544f9: 5 identical fragments of one service file returned for every query).
     *
     * Walking highest-similarity first, a result is dropped when an already-kept result from the
     * same file either has identical text or fully contains its line range (the kept one already
     * carries that content). Distinct regions and adjacent context chunks survive untouched.
     */
    private fun dedupeRedundantRegions(results: List<RagSearchResult>): List<RagSearchResult> {
        val kept = mutableListOf<RagSearchResult>()
        for (candidate in results.sortedByDescending { it.similarity }) {
            val redundant = kept.any { k -> isRedundantWith(kept = k, candidate = candidate) }
            if (!redundant) kept.add(candidate)
        }
        return kept
    }

    private fun isRedundantWith(kept: RagSearchResult, candidate: RagSearchResult): Boolean {
        if (kept.content == candidate.content) return true
        if (kept.filePath != candidate.filePath) return false
        val keptStart = kept.startLine ?: return false
        val keptEnd = kept.endLine ?: return false
        val candStart = candidate.startLine ?: return false
        val candEnd = candidate.endLine ?: return false
        // kept fully contains candidate's range → candidate adds nothing new.
        return keptStart <= candStart && keptEnd >= candEnd
    }

    /**
     * Hybrid search combining semantic search with keyword matching.
     *
     * Results are re-ranked based on:
     * - Semantic similarity (from embeddings)
     * - Keyword presence (exact match bonus)
     * - Recency (newer files ranked higher)
     *
     * @param projectRoot Project root directory
     * @param query Search query
     * @param keywords Keywords to boost (optional)
     * @param model Embedding model
     * @param topK Number of results
     * @return List of search results with hybrid scoring
     */
    suspend fun hybridSearch(
        projectRoot: String,
        query: String,
        keywords: List<String> = emptyList(),
        model: String = "ollama/nomic-embed-text",
        topK: Int = 5
    ): List<RagSearchResult> {
        logger.info {
            "Hybrid search: project=$projectRoot, query='${query.take(50)}...', keywords=$keywords"
        }

        val config = RagSearchConfig(
            similarityThreshold = MIN_SIMILARITY_THRESHOLD,
            topK = topK,
            hybridSearch = true,
            keywords = keywords
        )

        return withTimeout(config.timeoutMs) {
            hybridSearchInternal(
                projectRoot = projectRoot,
                query = query,
                keywords = keywords,
                model = model,
                config = config
            )
        }
    }

    private suspend fun hybridSearchInternal(
        projectRoot: String,
        query: String,
        keywords: List<String>,
        model: String,
        config: RagSearchConfig
    ): List<RagSearchResult> {
        // 1. Perform semantic search (get more results for re-ranking)
        val semanticResults = semanticSearchInternal(
            projectRoot = projectRoot,
            query = query,
            model = model,
            config = config.copy(topK = config.topK * 2, hybridSearch = false)
        )

        // 2. Re-rank with BM25 or keyword boosting
        if (bm25Scorer != null) {
            logger.debug { "Using BM25 scorer for hybrid re-ranking" }
            val bm25Scores = try {
                bm25Scorer.score(query, projectRoot)
            } catch (e: Exception) {
                logger.error(e) { "BM25 scoring failed, falling back to keyword scoring" }
                null
            }

            if (bm25Scores != null) {
                val rerankedResults = semanticResults.map { result ->
                    val bm25Score = bm25Scores[result.chunkId] ?: 0f
                    val hybridScore = result.similarity * config.semanticWeight +
                        (bm25Score * (1.0f - config.semanticWeight))
                    result.copy(similarity = hybridScore)
                }

                return rerankedResults
                    .sortedByDescending { it.similarity }
                    .take(config.topK)
            }
        }

        // Fallback: keyword-based re-ranking
        if (keywords.isEmpty()) {
            return semanticResults.take(config.topK)
        }

        val rerankedResults = semanticResults.map { result ->
            val keywordScore = calculateKeywordScore(result.content, keywords)
            val hybridScore = result.similarity * config.semanticWeight +
                (keywordScore * (1.0f - config.semanticWeight))

            result.copy(similarity = hybridScore)
        }

        // 3. Sort by hybrid score and take top K
        return rerankedResults
            .sortedByDescending { it.similarity }
            .take(config.topK)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun addContextChunks(
        results: List<RagSearchResult>,
        similarityByChunkId: Map<Int, Float>,
        _chunksMap: Map<Int, pl.jclab.refio.core.db.IndexChunk>,
        filesMap: Map<Int, pl.jclab.refio.core.db.IndexFile>,
        config: RagSearchConfig
    ): List<RagSearchResult> {
        if (results.isEmpty()) return results

        val topFileIds = results.take(3).map { it.fileId }.toSet()
        if (topFileIds.isEmpty()) return results

        val existingChunkIds = results.map { it.chunkId }.toSet()
        // similarityByChunkId is already the bounded candidate pool, so this stays bounded too.
        val candidateChunkIds = similarityByChunkId
            .filterValues { it >= contextThreshold(config) }
            .keys
            .filterNot { existingChunkIds.contains(it) }

        if (candidateChunkIds.isEmpty()) return results

        val contextChunks = ragRepository.getChunksBatch(candidateChunkIds)
            .filter { topFileIds.contains(it.fileId) }

        if (contextChunks.isEmpty()) return results

        val contextResults = contextChunks.mapNotNull { chunk ->
            val similarity = similarityByChunkId[chunk.id] ?: return@mapNotNull null
            val file = filesMap[chunk.fileId] ?: return@mapNotNull null

            RagSearchResult(
                chunkId = chunk.id,
                fileId = file.id,
                filePath = file.filePath,
                content = chunk.content,
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                similarity = similarity,
                contentType = file.contentType
            )
        }

        return (results + contextResults).distinctBy { it.chunkId }
    }

    private fun contextThreshold(config: RagSearchConfig): Float =
        (config.similarityThreshold * CONTEXT_THRESHOLD_FACTOR).coerceAtLeast(0.0f)

    /**
     * Calculate keyword match score (0.0 to 1.0)
     */
    private fun calculateKeywordScore(content: String, keywords: List<String>): Float {
        if (keywords.isEmpty()) return 0f

        val contentLower = content.lowercase()
        val matchCount = keywords.count { keyword ->
            contentLower.contains(keyword.lowercase())
        }

        return matchCount.toFloat() / keywords.size
    }

    /**
     * Deserialize byte array to float array (little-endian, float32)
     */
    private fun deserializeVector(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(bytes.size / 4)
        for (i in vector.indices) {
            vector[i] = buffer.getFloat()
        }
        return vector
    }

    /**
     * Calculate cosine similarity between two vectors.
     *
     * Returns value between -1.0 and 1.0:
     * - 1.0 = identical vectors
     * - 0.0 = orthogonal (no similarity)
     * - -1.0 = opposite vectors
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray, precomputedNormA: Float): Float {
        require(a.size == b.size) { "Vectors must have same dimensions (a=${a.size}, b=${b.size})" }

        var dotProduct = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normB += b[i] * b[i]
        }

        // Handle zero vectors
        if (precomputedNormA == 0f || normB == 0f) {
            return 0f
        }

        return dotProduct / (precomputedNormA * sqrt(normB))
    }

    /** Euclidean (L2) norm of a vector. */
    private fun vectorNorm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) {
            sum += x * x
        }
        return sqrt(sum)
    }
}

/**
 * RAG search result with similarity score
 */
data class RagSearchResult(
    val chunkId: Int,
    val fileId: Int,
    val filePath: String,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val similarity: Float,
    val contentType: RagContentType
)
