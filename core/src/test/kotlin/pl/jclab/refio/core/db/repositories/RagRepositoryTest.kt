package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.*
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testy dla RagRepository.
 */
class RagRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: RagRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = RagRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Nested
    inner class IndexedFileTests {

        @Test
        fun `should create indexed file`() {
            transaction {
                // When
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/src/main.kt",
                    fileHash = "hash123",
                    fileSize = 1024,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )

                // Then
                assertNotNull(fileId)
                assertTrue(fileId > 0)
            }
        }

        @Test
        fun `should find indexed files by project root`() {
            transaction {
                // Given
                repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/file1.kt",
                    fileHash = "h1",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/file2.kt",
                    fileHash = "h2",
                    fileSize = 200,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )

                // When
                val files = repository.getIndexedFiles("/test/project")

                // Then
                assertEquals(2, files.size)
            }
        }
    }

    @Nested
    inner class ChunkTests {

        @Test
        fun `should create chunk`() {
            transaction {
                // Given
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/test.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )

                // When
                val chunkId = repository.createChunk(
                    fileId = fileId,
                    chunkIndex = 0,
                    content = "test content",
                    startLine = 1,
                    endLine = 5
                )

                // Then
                assertNotNull(chunkId)
                assertTrue(chunkId > 0)
            }
        }

        @Test
        fun `should get chunks for file`() {
            transaction {
                // Given
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/test.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                repository.createChunk(fileId, 0, "chunk 1", 1, 5)
                repository.createChunk(fileId, 1, "chunk 2", 6, 10)

                // When
                val chunks = repository.getChunksForFile(fileId)

                // Then
                assertEquals(2, chunks.size)
            }
        }

        @Test
        fun `createChunksBatch inserts every chunk in one transaction`() {
            transaction {
                // Given
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/test.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )

                // When — batched path used by RagIndexingService instead of N createChunk calls
                val inserted = repository.createChunksBatch(
                    listOf(
                        ChunkInsert(fileId, 0, "chunk 0", 1, 5),
                        ChunkInsert(fileId, 1, "chunk 1", 6, 10),
                        ChunkInsert(fileId, 2, "chunk 2", 11, 15)
                    )
                )

                // Then — all rows persisted, order/content preserved (intent: indexing must
                // not silently drop chunks when we batch the insert).
                assertEquals(3, inserted)
                val chunks = repository.getChunksForFile(fileId).sortedBy { it.chunkIndex }
                assertEquals(3, chunks.size)
                assertEquals("chunk 1", chunks[1].content)
            }
        }

        @Test
        fun `createChunksBatch on empty list is a no-op`() {
            transaction {
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/empty.kt",
                    fileHash = "hash",
                    fileSize = 0,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )

                assertEquals(0, repository.createChunksBatch(emptyList()))
                assertEquals(0, repository.getChunksForFile(fileId).size)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete chunks for file`() {
            transaction {
                // Given
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/test.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                repository.createChunk(fileId, 0, "chunk 1", 1, 5)
                repository.createChunk(fileId, 1, "chunk 2", 6, 10)

                // When
                val deleted = repository.deleteChunksForFile(fileId)

                // Then
                assertEquals(2, deleted)
            }
        }
    }

    @Nested
    inner class EmbeddingBatchTests {

        @Test
        fun `createEmbeddingsBatch inserts an embedding per chunk`() {
            transaction {
                // Given
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/emb.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                val c0 = repository.createChunk(fileId, 0, "chunk 0", 1, 5)
                val c1 = repository.createChunk(fileId, 1, "chunk 1", 6, 10)
                val model = "ollama/nomic-embed-text"

                // When — batched path used by RagEmbeddingService instead of N createEmbedding calls
                val inserted = repository.createEmbeddingsBatch(
                    listOf(
                        EmbeddingInsert(c0, model, byteArrayOf(1, 2, 3, 4), 4),
                        EmbeddingInsert(c1, model, byteArrayOf(5, 6, 7, 8), 4)
                    )
                )

                // Then — every chunk ends up with an embedding (intent: batching must not
                // drop embeddings, or RAG search silently loses recall).
                assertEquals(2, inserted)
                assertNotNull(repository.getEmbedding(c0, model))
                assertNotNull(repository.getEmbedding(c1, model))
            }
        }

        @Test
        fun `createEmbeddingsBatch ignores a duplicate (chunkId, model)`() {
            transaction {
                // Given an existing embedding
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/emb2.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                val c0 = repository.createChunk(fileId, 0, "chunk 0", 1, 5)
                val model = "ollama/nomic-embed-text"
                repository.createEmbedding(c0, model, byteArrayOf(1, 2), 2)

                // When re-inserting the same (chunkId, model) — must not throw (INSERT OR IGNORE,
                // matching createEmbedding's semantics during concurrent re-index).
                repository.createEmbeddingsBatch(listOf(EmbeddingInsert(c0, model, byteArrayOf(9, 9), 2)))

                // Then the original survives.
                assertNotNull(repository.getEmbedding(c0, model))
            }
        }
    }

    @Nested
    inner class MissingEmbeddingTests {

        @Test
        fun `getChunksWithoutEmbeddings returns only chunks lacking an embedding for the model`() {
            transaction {
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/missing.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                val embedded = repository.createChunk(fileId, 0, "embedded chunk", 1, 5)
                val missing = repository.createChunk(fileId, 1, "missing chunk", 6, 10)
                val model = "ollama/nomic-embed-text"

                repository.createEmbedding(embedded, model, byteArrayOf(1, 2, 3, 4), 4)
                // An embedding for ANOTHER model must not satisfy the requested model
                repository.createEmbedding(missing, "other-model", byteArrayOf(9, 9), 2)

                val result = repository.getChunksWithoutEmbeddings("/test/project", model)

                assertEquals(listOf(missing), result.map { it.id })
            }
        }

        @Test
        fun `getChunkIdsByIndexForFile maps chunkIndex to id`() {
            transaction {
                val fileId = repository.createIndexedFile(
                    projectRoot = "/test/project",
                    filePath = "/map.kt",
                    fileHash = "hash",
                    fileSize = 100,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "text/x-kotlin"
                )
                val c0 = repository.createChunk(fileId, 0, "chunk 0", 1, 5)
                val c1 = repository.createChunk(fileId, 1, "chunk 1", 6, 10)

                assertEquals(mapOf(0 to c0, 1 to c1), repository.getChunkIdsByIndexForFile(fileId))
            }
        }
    }
}
