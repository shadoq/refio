package pl.jclab.refio.core.services.rag

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.IndexChunk
import pl.jclab.refio.core.db.repositories.RagRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BM25ScorerTest {

    private val ragRepository = mockk<RagRepository>()
    private val projectRoot = "/test/project"

    private fun chunk(id: Int, content: String) = IndexChunk(
        id = id, fileId = 1, chunkIndex = 0, content = content,
        contentHash = "hash$id", metadata = null,
        startLine = 1, endLine = 10, startChar = null, endChar = null,
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun `score with empty query returns empty map`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "some content here")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("", projectRoot)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `score with punctuation-only query returns empty map`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "some content here")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("... !!!", projectRoot)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `score with no chunks in project returns empty map`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns emptyList()

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("kotlin test", projectRoot)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `score returns scores normalized between 0 and 1`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "kotlin is a programming language"),
            chunk(2, "java is also a programming language"),
            chunk(3, "kotlin and java are jvm languages kotlin kotlin")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("kotlin", projectRoot)

        assertTrue(result.isNotEmpty())
        result.values.forEach { score ->
            assertTrue(score >= 0.0f, "Score $score should be >= 0.0")
            assertTrue(score <= 1.0f, "Score $score should be <= 1.0")
        }
        // The highest scoring document should be normalized to 1.0
        assertEquals(1.0f, result.values.max())
    }

    @Test
    fun `score ranks documents containing query terms higher than those without`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "kotlin coroutines are powerful"),
            chunk(2, "java streams are useful"),
            chunk(3, "python decorators are elegant")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("kotlin", projectRoot)

        // Only the chunk containing "kotlin" should be scored
        assertTrue(result.containsKey(1), "Chunk with 'kotlin' should be scored")
        assertTrue(!result.containsKey(2), "Chunk without 'kotlin' should not be scored")
        assertTrue(!result.containsKey(3), "Chunk without 'kotlin' should not be scored")
    }

    @Test
    fun `documents containing more query terms score higher`() {
        // All chunks have roughly equal length and each query term appears in
        // the same number of documents so IDF is uniform across terms.
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "alpha word word word word word word"),
            chunk(2, "alpha beta word word word word word"),
            chunk(3, "alpha beta gamma word word word word")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("alpha beta gamma", projectRoot)

        val score1 = result[1] ?: 0f
        val score2 = result[2] ?: 0f
        val score3 = result[3] ?: 0f

        assertTrue(score3 > score2, "Chunk matching 3 terms ($score3) should score higher than chunk matching 2 terms ($score2)")
        assertTrue(score2 > score1, "Chunk matching 2 terms ($score2) should score higher than chunk matching 1 term ($score1)")
    }

    @Test
    fun `custom k1 and b parameters affect scores`() {
        val chunks = listOf(
            chunk(1, "kotlin kotlin kotlin programming"),
            chunk(2, "kotlin is a modern language for jvm development and server side applications")
        )
        every { ragRepository.getChunksForProject(projectRoot) } returns chunks

        val defaultScorer = BM25Scorer(ragRepository, k1 = 1.5f, b = 0.75f)
        val customScorer = BM25Scorer(ragRepository, k1 = 0.5f, b = 0.0f)

        val defaultScores = defaultScorer.score("kotlin", projectRoot)
        val customScores = customScorer.score("kotlin", projectRoot)

        // Both should produce scores
        assertTrue(defaultScores.isNotEmpty(), "Default scorer should produce scores")
        assertTrue(customScores.isNotEmpty(), "Custom scorer should produce scores")

        // With b=0.0 length normalization is disabled, so the relative ranking
        // may differ from the default parameters. We just verify they produce
        // different score distributions.
        val defaultRatio = (defaultScores[1] ?: 0f) / (defaultScores[2] ?: 1f)
        val customRatio = (customScores[1] ?: 0f) / (customScores[2] ?: 1f)

        assertTrue(
            defaultRatio != customRatio,
            "Different k1/b parameters should produce different score ratios (default=$defaultRatio, custom=$customRatio)"
        )
    }

    @Test
    fun `score is case insensitive`() {
        every { ragRepository.getChunksForProject(projectRoot) } returns listOf(
            chunk(1, "Kotlin is Great"),
            chunk(2, "KOTLIN IS POWERFUL")
        )

        val scorer = BM25Scorer(ragRepository)
        val result = scorer.score("kotlin", projectRoot)

        assertTrue(result.containsKey(1), "Case-insensitive match should find 'Kotlin'")
        assertTrue(result.containsKey(2), "Case-insensitive match should find 'KOTLIN'")
    }
}
