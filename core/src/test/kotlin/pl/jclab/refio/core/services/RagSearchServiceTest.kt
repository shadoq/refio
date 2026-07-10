package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.IndexChunk
import pl.jclab.refio.core.db.IndexFile
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.rag.RagSearchConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagSearchServiceTest {

    private lateinit var ragRepository: RagRepository
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var service: RagSearchService

    private val projectRoot = "/test/project"
    private val model = "test-model"

    @BeforeEach
    fun setup() {
        ragRepository = mockk()
        embeddingProvider = mockk()
        service = RagSearchService(ragRepository, embeddingProvider)
    }

    /**
     * Serialize a float array to bytes in little-endian format (matching RagSearchService.deserializeVector).
     */
    private fun serializeVector(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Create a unit vector (normalized) in a given dimension. Useful for predictable cosine similarity.
     */
    private fun unitVector(dimension: Int, size: Int): FloatArray {
        val vector = FloatArray(size) { 0f }
        vector[dimension] = 1f
        return vector
    }

    private fun createEmbeddingRecord(id: Int, chunkId: Int, vector: FloatArray) =
        pl.jclab.refio.core.db.Embedding(
            id = id,
            chunkId = chunkId,
            model = model,
            vector = serializeVector(vector),
            dimensions = vector.size,
            createdAt = System.currentTimeMillis()
        )

    private fun createChunk(id: Int, fileId: Int, content: String) = IndexChunk(
        id = id,
        fileId = fileId,
        content = content,
        contentHash = "hash-$id",
        startLine = 1,
        endLine = 10,
        startChar = null,
        endChar = null,
        chunkIndex = 0,
        metadata = null,
        createdAt = System.currentTimeMillis()
    )

    private fun createChunkRanged(id: Int, fileId: Int, content: String, startLine: Int, endLine: Int) = IndexChunk(
        id = id,
        fileId = fileId,
        content = content,
        contentHash = "hash-$id",
        startLine = startLine,
        endLine = endLine,
        startChar = null,
        endChar = null,
        chunkIndex = 0,
        metadata = null,
        createdAt = System.currentTimeMillis()
    )

    private fun createFile(id: Int, path: String) = IndexFile(
        id = id,
        projectRoot = projectRoot,
        filePath = path,
        fileHash = "hash-$id",
        checksum = null,
        fileSize = 100L,
        mimeType = "text/plain",
        lastModified = System.currentTimeMillis(),
        contentType = RagContentType.PROJECT_CODE,
        sourceUrl = null,
        metadata = null,
        indexedAt = System.currentTimeMillis()
    )

    @Nested
    inner class EmptyIndexTests {

        @Test
        fun `should return empty results when no embeddings exist`() = runBlocking {
            // Given
            val queryVector = floatArrayOf(1f, 0f, 0f)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 0

            // When
            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 5
            )

            // Then
            assertTrue(results.isEmpty())
        }

        @Test
        fun `should return empty results for blank query`() = runBlocking {
            // When
            val results = service.search(
                projectRoot = projectRoot,
                query = "   ",
                model = model,
                topK = 5
            )

            // Then
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class SimilarityThresholdTests {

        @Test
        fun `should filter results below similarity threshold`() = runBlocking {
            // Given - query vector is [1,0,0], embeddings are [1,0,0] (similar) and [0,1,0] (orthogonal)
            val dims = 3
            val queryVector = unitVector(0, dims) // [1,0,0]
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 2

            val embeddings = listOf(
                createEmbeddingRecord(1, 1, unitVector(0, dims)),  // similarity = 1.0
                createEmbeddingRecord(2, 2, unitVector(1, dims))   // similarity = 0.0
            )
            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns embeddings

            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunk(1, 1, "relevant code")
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "src/main.kt")
            )

            // When - threshold at 0.5 should exclude the orthogonal vector
            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 10,
                similarityThreshold = 0.5f
            )

            // Then
            assertEquals(1, results.size)
            assertEquals("src/main.kt", results[0].filePath)
            assertTrue(results[0].similarity >= 0.5f)
        }

        @Test
        fun `should return empty when all results below threshold`() = runBlocking {
            // Given - query is [1,0,0], all embeddings are orthogonal
            val dims = 3
            val queryVector = unitVector(0, dims)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 1

            val embeddings = listOf(
                createEmbeddingRecord(1, 1, unitVector(1, dims))  // similarity = 0.0
            )
            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns embeddings

            // When
            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 10,
                similarityThreshold = 0.5f
            )

            // Then
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class TopKLimitingTests {

        @Test
        fun `should limit results to topK`() = runBlocking {
            // Given - 3 similar embeddings, topK = 2
            val dims = 3
            val queryVector = floatArrayOf(1f, 1f, 1f)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 3

            // All embeddings are somewhat similar to query (positive dot products)
            val embeddings = listOf(
                createEmbeddingRecord(1, 1, floatArrayOf(1f, 0f, 0f)),   // partial similarity
                createEmbeddingRecord(2, 2, floatArrayOf(0f, 1f, 0f)),   // partial similarity
                createEmbeddingRecord(3, 3, floatArrayOf(1f, 1f, 0f))    // higher similarity
            )
            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns embeddings

            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunk(1, 1, "code A"),
                createChunk(2, 2, "code B"),
                createChunk(3, 3, "code C")
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "a.kt"),
                createFile(2, "b.kt"),
                createFile(3, "c.kt")
            )

            // When
            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 2,
                similarityThreshold = 0.1f
            )

            // Then
            assertTrue(results.size <= 2)
        }

        @Test
        fun `should sort results by similarity descending`() = runBlocking {
            // Given
            val dims = 4
            val queryVector = floatArrayOf(1f, 1f, 0f, 0f)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 2

            val embeddings = listOf(
                createEmbeddingRecord(1, 1, floatArrayOf(0f, 1f, 0f, 0f)),  // lower similarity
                createEmbeddingRecord(2, 2, floatArrayOf(1f, 1f, 0f, 0f))   // highest similarity (1.0)
            )
            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns embeddings

            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunk(1, 1, "code A"),
                createChunk(2, 2, "code B")
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "a.kt"),
                createFile(2, "b.kt")
            )

            // When
            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 10,
                similarityThreshold = 0.1f
            )

            // Then - highest similarity should come first
            assertTrue(results.size >= 2)
            assertTrue(results[0].similarity >= results[1].similarity)
        }
    }

    @Nested
    inner class KeywordScoreTests {

        @Test
        fun `hybridSearch should return semantic results when no keywords`() = runBlocking {
            // Given
            val dims = 3
            val queryVector = unitVector(0, dims)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 1

            val embeddings = listOf(
                createEmbeddingRecord(1, 1, unitVector(0, dims))
            )
            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns embeddings

            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunk(1, 1, "relevant content")
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "src/main.kt")
            )

            // When
            val results = service.hybridSearch(
                projectRoot = projectRoot,
                query = "test query",
                keywords = emptyList(),
                model = model,
                topK = 5
            )

            // Then
            assertEquals(1, results.size)
        }
    }

    @Nested
    inner class RedundantRegionDedupTests {

        @Test
        fun `collapses byte-identical chunks into a single result`() = runBlocking {
            // Regression: session 1fc544f9 returned 5 identical fragments (same text, same
            // embedding) for every query. Two distinct chunkIds with identical content must
            // collapse to one result regardless of which files they live in.
            val dims = 3
            val queryVector = unitVector(0, dims)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 2

            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns listOf(
                createEmbeddingRecord(1, 1, unitVector(0, dims)),  // similarity = 1.0
                createEmbeddingRecord(2, 2, unitVector(0, dims))   // similarity = 1.0 (identical)
            )
            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunk(1, 1, "IDENTICAL CONTENT"),
                createChunk(2, 2, "IDENTICAL CONTENT")
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "a.kt"),
                createFile(2, "b.kt")
            )

            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 5,
                similarityThreshold = 0.5f
            )

            assertEquals(1, results.size, "Identical-content chunks must collapse to one result")
        }

        @Test
        fun `drops a chunk whose range is contained in a higher-similarity result from the same file`() = runBlocking {
            // Overlapping chunks of one region (full-file/class ⊃ method). When the larger,
            // containing chunk ranks higher, the contained one is redundant and must be dropped.
            val dims = 3
            val queryVector = floatArrayOf(1f, 1f, 0f)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 2

            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns listOf(
                createEmbeddingRecord(1, 1, floatArrayOf(1f, 1f, 0f)),  // similarity 1.0 (outer)
                createEmbeddingRecord(2, 2, floatArrayOf(1f, 0f, 0f))   // similarity ~0.707 (inner)
            )
            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunkRanged(1, 1, "outer region text", startLine = 1, endLine = 100),
                createChunkRanged(2, 1, "inner method text", startLine = 10, endLine = 20)
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "same.kt")
            )

            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 5,
                similarityThreshold = 0.1f
            )

            assertEquals(1, results.size, "Contained chunk must be dropped")
            assertEquals(1, results[0].startLine, "The containing (outer) chunk must be the survivor")
        }

        @Test
        fun `keeps distinct non-overlapping chunks from the same file`() = runBlocking {
            // Guard: dedup is narrow — adjacent, non-overlapping regions of one file are
            // legitimately distinct and must both survive.
            val dims = 3
            val queryVector = floatArrayOf(1f, 1f, 0f)
            coEvery { embeddingProvider.generateEmbedding("test query", model) } returns queryVector
            every { ragRepository.countEmbeddings(projectRoot, model, null) } returns 2

            every {
                ragRepository.getEmbeddingsBatch(projectRoot, model, null, 0, 500)
            } returns listOf(
                createEmbeddingRecord(1, 1, floatArrayOf(1f, 1f, 0f)),
                createEmbeddingRecord(2, 2, floatArrayOf(1f, 0f, 0f))
            )
            every { ragRepository.getChunksBatch(any()) } returns listOf(
                createChunkRanged(1, 1, "first method", startLine = 1, endLine = 20),
                createChunkRanged(2, 1, "second method", startLine = 30, endLine = 50)
            )
            every { ragRepository.getFilesBatch(any()) } returns listOf(
                createFile(1, "same.kt")
            )

            val results = service.search(
                projectRoot = projectRoot,
                query = "test query",
                model = model,
                topK = 5,
                similarityThreshold = 0.1f
            )

            assertEquals(2, results.size, "Non-overlapping distinct regions must both survive")
        }
    }

    @Nested
    inner class RagSearchConfigTests {

        @Test
        fun `default config should have expected values`() {
            val config = RagSearchConfig()
            assertEquals(RagSearchConfig.DEFAULT_SIMILARITY_THRESHOLD, config.similarityThreshold)
            assertEquals(5, config.topK)
            assertEquals(false, config.hybridSearch)
        }

        @Test
        fun `forCodeSearch should use higher threshold`() {
            val config = RagSearchConfig.forCodeSearch()
            assertEquals(0.65f, config.similarityThreshold)
            assertEquals(10, config.topK)
            assertEquals(RagContentType.PROJECT_CODE, config.contentType)
            assertTrue(config.includeContextChunks)
        }

        @Test
        fun `forDocumentation should enable hybrid search`() {
            val config = RagSearchConfig.forDocumentation()
            assertTrue(config.hybridSearch)
            assertEquals(RagContentType.DOCUMENTATION, config.contentType)
        }

        @Test
        fun `withThreshold should create copy with new threshold`() {
            val original = RagSearchConfig()
            val modified = original.withThreshold(0.8f)
            assertEquals(0.8f, modified.similarityThreshold)
            assertEquals(original.topK, modified.topK)
        }

        @Test
        fun `withTopK should create copy with new topK`() {
            val original = RagSearchConfig()
            val modified = original.withTopK(20)
            assertEquals(20, modified.topK)
            assertEquals(original.similarityThreshold, modified.similarityThreshold)
        }

        @Test
        fun `withKeywords should enable hybrid search`() {
            val config = RagSearchConfig().withKeywords("auth", "login")
            assertTrue(config.hybridSearch)
            assertEquals(listOf("auth", "login"), config.keywords)
        }
    }
}
