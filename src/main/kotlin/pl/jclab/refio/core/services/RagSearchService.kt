package pl.jclab.refio.core.services

import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.services.logging.dualLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val defaultConfig: RagSearchConfig = RagSearchConfig()
) {
    companion object {
        private const val MIN_SIMILARITY_THRESHOLD = 0.3f
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
        logger.info {
            "Searching RAG: project=$projectRoot, query='${query.take(50)}...', " +
                "topK=${config.topK}, hybrid=${config.hybridSearch}"
        }

        if (query.isBlank()) {
            logger.warn { "Empty query provided" }
            return@withTimeout emptyList()
        }

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

        // 2. Get all embeddings for project
        val embeddings = ragRepository.getEmbeddings(projectRoot, model, config.contentType)

        logger.debug { "Found ${embeddings.size} embeddings to search" }

        if (embeddings.isEmpty()) {
            logger.warn { "No embeddings found for project=$projectRoot, model=$model" }
            return emptyList()
        }

        // 3. Calculate similarities and collect embeddings above threshold
        val allSimilarities = mutableListOf<Pair<Int, Float>>()  // (embeddingId, similarity)
        val embeddingsAboveThreshold = mutableListOf<Pair<pl.jclab.refio.core.db.Embedding, Float>>()
        val similarityByChunkId = mutableMapOf<Int, Float>()

        embeddings.forEach { embedding ->
            try {
                val chunkVector = deserializeVector(embedding.vector)
                val similarity = cosineSimilarity(queryVector, chunkVector)

                // Track all similarities for debugging
                allSimilarities.add(embedding.id to similarity)
                similarityByChunkId[embedding.chunkId] = similarity

                // Collect embeddings above threshold
                if (similarity >= config.similarityThreshold) {
                    embeddingsAboveThreshold.add(embedding to similarity)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to process embedding ${embedding.id}" }
            }
        }

        logger.debug { "Embeddings above threshold: ${embeddingsAboveThreshold.size}/${embeddings.size}" }

        if (embeddingsAboveThreshold.isEmpty()) {
            logger.info { "No embeddings above threshold ${config.similarityThreshold}" }
            return emptyList()
        }

        // 4. Prefetch chunks and files in batch (OPTIMIZATION: 2 queries instead of N+2)
        val chunkIds = embeddingsAboveThreshold.map { it.first.chunkId }.distinct()
        val chunksMap = ragRepository.getChunksBatch(chunkIds).associateBy { it.id }

        val fileIds = chunksMap.values.map { it.fileId }.distinct()
        val filesMap = ragRepository.getFilesBatch(fileIds).associateBy { it.id }

        logger.debug { "Prefetched ${chunksMap.size} chunks and ${filesMap.size} files" }

        // 5. Build results using prefetched data
        val results = embeddingsAboveThreshold.mapNotNull { (embedding, similarity) ->
            try {
                val chunk = chunksMap[embedding.chunkId]
                if (chunk == null) {
                    logger.warn { "Chunk ${embedding.chunkId} not found for embedding ${embedding.id}" }
                    return@mapNotNull null
                }

                val file = filesMap[chunk.fileId]
                if (file == null) {
                    logger.warn { "File ${chunk.fileId} not found for chunk ${chunk.id}" }
                    return@mapNotNull null
                }

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
                logger.error(e) { "Failed to build result for embedding ${embedding.id}" }
                null
            }
        }

        val finalResults = if (config.includeContextChunks) {
            addContextChunks(
                results = results,
                similarityByChunkId = similarityByChunkId,
                chunksMap = chunksMap,
                filesMap = filesMap,
                config = config
            )
        } else {
            results
        }

        // Log similarity distribution for debugging
        val top5Similarities = allSimilarities
            .sortedByDescending { it.second }
            .take(5)
        logger.debug { "Top 5 similarities: ${top5Similarities.map { String.format("%.3f", it.second) }}" }
        logger.debug {
            "Threshold: ${config.similarityThreshold}, " +
                "Results above threshold: ${results.size}/${embeddings.size}"
        }

        // 4. Sort by similarity (descending) and take top K
        val topResults = finalResults
            .sortedByDescending { it.similarity }
            .take(config.topK)

        logger.info {
            "Search completed: ${topResults.size} results " +
                "(from ${results.size} above threshold ${config.similarityThreshold})"
        }

        return topResults
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

        if (keywords.isEmpty()) {
            return semanticResults.take(config.topK)
        }

        // 2. Re-rank with keyword boosting
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

    private fun addContextChunks(
        results: List<RagSearchResult>,
        similarityByChunkId: Map<Int, Float>,
        chunksMap: Map<Int, pl.jclab.refio.core.db.IndexChunk>,
        filesMap: Map<Int, pl.jclab.refio.core.db.IndexFile>,
        config: RagSearchConfig
    ): List<RagSearchResult> {
        if (results.isEmpty()) return results

        val topFileIds = results.take(3).map { it.fileId }.toSet()
        if (topFileIds.isEmpty()) return results

        val existingChunkIds = results.map { it.chunkId }.toSet()
        val contextThreshold = (config.similarityThreshold * 0.8f).coerceAtLeast(0.0f)

        val candidateChunkIds = similarityByChunkId
            .filterValues { it >= contextThreshold }
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
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same dimensions (a=${a.size}, b=${b.size})" }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        // Handle zero vectors
        if (normA == 0f || normB == 0f) {
            return 0f
        }

        return dotProduct / (sqrt(normA) * sqrt(normB))
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
