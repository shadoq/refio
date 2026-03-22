package pl.jclab.refio.core.services.rag

import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.services.logging.dualLogger
import kotlin.math.ln

private val logger = dualLogger("BM25Scorer")

/**
 * BM25 scoring for RAG search results.
 *
 * Implements the Okapi BM25 ranking function for keyword-based relevance scoring.
 * Used as a complement to semantic (embedding-based) search in hybrid search mode.
 *
 * @param ragRepository Repository for accessing indexed chunks
 * @param k1 Term frequency saturation parameter (default 1.5)
 * @param b Length normalization parameter (default 0.75)
 */
class BM25Scorer(
    private val ragRepository: RagRepository,
    private val k1: Float = 1.5f,
    private val b: Float = 0.75f
) {
    companion object {
        private val TOKENIZE_REGEX = Regex("[\\s\\p{Punct}]+")
    }

    /**
     * Compute BM25 scores for all chunks in the project against the given query.
     *
     * @param query Search query string
     * @param projectRoot Project root directory for chunk isolation
     * @return Map of chunkId to normalized BM25 score (0.0 to 1.0)
     */
    fun score(query: String, projectRoot: String): Map<Int, Float> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) {
            logger.debug { "Empty query after tokenization, returning empty scores" }
            return emptyMap()
        }

        val chunks = ragRepository.getChunksForProject(projectRoot)
        if (chunks.isEmpty()) {
            logger.debug { "No chunks found for project=$projectRoot" }
            return emptyMap()
        }

        val n = chunks.size
        logger.debug { "BM25 scoring: ${queryTerms.size} query terms against $n chunks" }

        // Tokenize all documents
        val docTokens = chunks.associate { chunk ->
            chunk.id to tokenize(chunk.content)
        }

        // Compute average document length
        val avgdl = docTokens.values.map { it.size.toFloat() }.average().toFloat()
        if (avgdl == 0f) {
            return emptyMap()
        }

        // Compute document frequency for each query term: how many documents contain the term
        val docFrequency = mutableMapOf<String, Int>()
        for (term in queryTerms) {
            docFrequency[term] = docTokens.values.count { tokens -> tokens.contains(term) }
        }

        // Compute BM25 score for each document
        val scores = mutableMapOf<Int, Float>()
        for (chunk in chunks) {
            val tokens = docTokens[chunk.id] ?: continue
            val dl = tokens.size.toFloat()

            // Count term frequencies in this document
            val termFreqs = mutableMapOf<String, Int>()
            for (token in tokens) {
                if (token in queryTerms) {
                    termFreqs[token] = (termFreqs[token] ?: 0) + 1
                }
            }

            if (termFreqs.isEmpty()) continue

            var docScore = 0f
            for (term in queryTerms) {
                val tf = (termFreqs[term] ?: 0).toFloat()
                if (tf == 0f) continue

                val df = docFrequency[term] ?: 0
                // IDF(t) = ln((N - n(t) + 0.5) / (n(t) + 0.5) + 1)
                val idf = ln(((n - df + 0.5f) / (df + 0.5f) + 1f).toDouble()).toFloat()

                // BM25 term score
                val tfComponent = (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * dl / avgdl))
                docScore += idf * tfComponent
            }

            if (docScore > 0f) {
                scores[chunk.id] = docScore
            }
        }

        // Normalize to [0, 1]
        if (scores.isEmpty()) {
            return emptyMap()
        }

        val maxScore = scores.values.max()
        if (maxScore <= 0f) {
            return emptyMap()
        }

        val normalized = scores.mapValues { (_, score) -> score / maxScore }

        logger.debug {
            "BM25 scoring complete: ${normalized.size}/$n chunks scored, " +
                "max raw score=${String.format("%.3f", maxScore)}"
        }

        return normalized
    }

    /**
     * Tokenize text into lowercase terms, splitting on whitespace and punctuation.
     */
    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(TOKENIZE_REGEX)
            .filter { it.isNotBlank() }
            .toSet()
    }
}
