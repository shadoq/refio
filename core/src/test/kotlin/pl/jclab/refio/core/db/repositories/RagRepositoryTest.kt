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
}
