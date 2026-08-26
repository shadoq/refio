package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reindexing must never leave a file registered as up-to-date while its chunks are gone:
 * classification trusts the stored checksum, so such a file would be skipped by every later
 * run and stay invisible to RAG search until the whole index is cleared by hand.
 */
class RagIndexingServiceReindexTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: RagRepository
    private lateinit var configService: ConfigService
    private lateinit var service: RagIndexingService

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectRoot: Path
    private lateinit var projectKey: String

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = RagRepository()
        configService = mockk()
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        service = RagIndexingService(repository, configService)

        projectRoot = tempDir.resolve("project").createDirectories()
        projectKey = projectRoot.toAbsolutePath().normalize().toString()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun indexOnce() = runBlocking {
        service.indexProject(projectRoot).collect { }
    }

    @Test
    fun `rebuilds a file whose chunks are missing although its checksum looks current`() {
        projectRoot.resolve("Main.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        indexOnce()

        val indexed = repository.getIndexedFiles(projectKey).single()
        assertTrue(repository.getChunksForFile(indexed.id).isNotEmpty(), "Precondition: first pass indexes the file")

        // The state an interrupted reindex leaves behind: fresh checksum on the file row,
        // no chunks under it.
        repository.deleteChunksForFile(indexed.id)

        indexOnce()

        assertTrue(
            repository.getChunksForFile(indexed.id).isNotEmpty(),
            "A file with a current checksum but no chunks must be reindexed, not reported as unchanged"
        )
    }

    @Test
    fun `reindex of an edited file replaces its chunks and commits the new checksum`() {
        val file = projectRoot.resolve("Main.kt")
        file.writeText("fun main() {\n    println(\"first version\")\n}\n")
        indexOnce()

        val firstChecksum = repository.getIndexedFiles(projectKey).single().checksum

        file.writeText("fun main() {\n    println(\"second version\")\n}\n")
        indexOnce()

        val indexed = repository.getIndexedFiles(projectKey).single()
        val chunks = repository.getChunksForFile(indexed.id)

        assertTrue(indexed.checksum != firstChecksum, "Checksum must follow the new content")
        assertTrue(chunks.isNotEmpty(), "Chunks must exist after reindexing")
        assertTrue(
            chunks.all { "first version" !in it.content },
            "Stale chunks of the previous content must be gone"
        )
        assertTrue(
            chunks.any { "second version" in it.content },
            "Chunks must carry the new content"
        )
    }

    @Test
    fun `an empty file is not reindexed on every run`() {
        // Guard for the missing-chunk safety net: a file that legitimately produces no chunks
        // must not be classified as stale forever.
        projectRoot.resolve("empty.txt").writeText("")
        indexOnce()

        val indexed = repository.getIndexedFiles(projectKey).single()
        val indexedAtAfterFirstRun = indexed.indexedAt

        Thread.sleep(5)
        indexOnce()

        assertEquals(
            indexedAtAfterFirstRun,
            repository.getIndexedFiles(projectKey).single().indexedAt,
            "An empty file has nothing to rebuild, so the second run must leave it alone"
        )
    }
}
