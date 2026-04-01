package pl.jclab.refio.core.services.rag

import pl.jclab.refio.core.db.RagContentType

/**
 * Configuration for RAG search behavior.
 */
data class RagSearchConfig(
    val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    val topK: Int = DEFAULT_TOP_K,
    val contentType: RagContentType? = null,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val hybridSearch: Boolean = false,
    val keywords: List<String> = emptyList(),
    val semanticWeight: Float = DEFAULT_SEMANTIC_WEIGHT,
    val includeContextChunks: Boolean = false
) {
    companion object {
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.5f
        private const val MIN_SIMILARITY_THRESHOLD = 0.0f
        private const val MAX_SIMILARITY_THRESHOLD = 1.0f
        private const val DEFAULT_TOP_K = 5
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SEMANTIC_WEIGHT = 0.7f

        fun forCodeSearch(): RagSearchConfig = RagSearchConfig(
            similarityThreshold = 0.65f,
            topK = 10,
            contentType = RagContentType.PROJECT_CODE,
            includeContextChunks = true
        )

        fun forDocumentation(): RagSearchConfig = RagSearchConfig(
            similarityThreshold = 0.45f,
            topK = 15,
            contentType = RagContentType.DOCUMENTATION,
            hybridSearch = true
        )

        fun forExploration(): RagSearchConfig = RagSearchConfig(
            similarityThreshold = 0.35f,
            topK = 20,
            hybridSearch = true,
            includeContextChunks = true
        )

        fun forExamples(keywords: List<String> = emptyList()): RagSearchConfig = RagSearchConfig(
            similarityThreshold = 0.5f,
            topK = 12,
            hybridSearch = true,
            keywords = keywords
        )

        fun forDebugging(): RagSearchConfig = RagSearchConfig(
            similarityThreshold = 0.7f,
            topK = 8,
            contentType = RagContentType.PROJECT_CODE
        )
    }

    init {
        require(similarityThreshold in MIN_SIMILARITY_THRESHOLD..MAX_SIMILARITY_THRESHOLD) {
            "Similarity threshold must be between 0.0 and 1.0, got $similarityThreshold"
        }
        require(topK > 0) { "topK must be positive, got $topK" }
        require(timeoutMs > 0) { "timeout must be positive, got $timeoutMs" }
        require(semanticWeight in 0.0f..1.0f) {
            "Semantic weight must be between 0.0 and 1.0, got $semanticWeight"
        }
    }

    fun withThreshold(threshold: Float): RagSearchConfig = copy(similarityThreshold = threshold)

    fun withTopK(k: Int): RagSearchConfig = copy(topK = k)

    fun withKeywords(vararg keywords: String): RagSearchConfig = copy(
        hybridSearch = true,
        keywords = keywords.toList()
    )
}
