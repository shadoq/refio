package pl.jclab.refio.core.services

import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.db.Snapshot
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class SnapshotServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var service: SnapshotService

    @BeforeEach
    fun setup() {
        snapshotRepository = mockk(relaxed = true)
        service = SnapshotService(snapshotRepository, tempDir)
    }

    @Nested
    inner class CreateSnapshot {

        @Test
        fun `should create snapshot for existing files`() {
            val file = tempDir.resolve("src/main.kt")
            file.parent.createDirectories()
            file.writeText("fun main() {}")

            service.createSnapshot("task-1", "subtask-1", listOf("src/main.kt"))

            verify {
                snapshotRepository.create(
                    taskId = "task-1",
                    subtaskId = "subtask-1",
                    filePath = "src/main.kt",
                    content = "fun main() {}",
                    contentHash = any()
                )
            }
        }

        @Test
        fun `should skip nonexistent files`() {
            service.createSnapshot("task-1", "subtask-1", listOf("nonexistent.kt"))

            verify(exactly = 0) {
                snapshotRepository.create(any(), any(), any(), any(), any())
            }
        }

        @Test
        fun `should return subtaskId as snapshot id`() {
            val result = service.createSnapshot("task-1", "snap-42", emptyList())
            assertEquals("snap-42", result)
        }

        @Test
        fun `should snapshot multiple files`() {
            tempDir.resolve("a.kt").writeText("A")
            tempDir.resolve("b.kt").writeText("B")

            service.createSnapshot("task-1", "sub-1", listOf("a.kt", "b.kt"))

            verify(exactly = 2) {
                snapshotRepository.create(any(), any(), any(), any(), any())
            }
        }

        @Test
        fun `should compute SHA-256 hash`() {
            tempDir.resolve("test.txt").writeText("hello")

            val hashSlot = slot<String>()
            every {
                snapshotRepository.create(any(), any(), any(), any(), capture(hashSlot))
            } returns mockk(relaxed = true)

            service.createSnapshot("task-1", "sub-1", listOf("test.txt"))

            // SHA-256 of "hello" is well-known
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                hashSlot.captured
            )
        }
    }

    @Nested
    inner class GetSnapshot {

        @Test
        fun `should return file contents map`() {
            val snapshot1 = mockk<Snapshot> {
                every { filePath } returns "a.kt"
            }
            val snapshot2 = mockk<Snapshot> {
                every { filePath } returns "b.kt"
            }

            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snapshot1, snapshot2)
            every { snapshotRepository.decompressContent(snapshot1) } returns "content-A"
            every { snapshotRepository.decompressContent(snapshot2) } returns "content-B"

            val result = service.getSnapshot("snap-1")

            assertEquals(2, result.size)
            assertEquals("content-A", result["a.kt"])
            assertEquals("content-B", result["b.kt"])
        }

        @Test
        fun `should return empty map for unknown snapshot`() {
            every { snapshotRepository.findBySubtaskId("unknown") } returns emptyList()

            val result = service.getSnapshot("unknown")
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class GetFileContent {

        @Test
        fun `should return content for existing file`() {
            val snapshot = mockk<Snapshot> {
                every { filePath } returns "target.kt"
            }
            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snapshot)
            every { snapshotRepository.decompressContent(snapshot) } returns "file content"

            val result = service.getFileContent("snap-1", "target.kt")
            assertEquals("file content", result)
        }

        @Test
        fun `should return null for missing file`() {
            val snapshot = mockk<Snapshot> {
                every { filePath } returns "other.kt"
            }
            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snapshot)

            val result = service.getFileContent("snap-1", "target.kt")
            assertNull(result)
        }
    }

    @Nested
    inner class RestoreSnapshot {

        @Test
        fun `should restore files to project root`() {
            val snapshot = mockk<Snapshot> {
                every { filePath } returns "restored.kt"
            }
            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snapshot)
            every { snapshotRepository.decompressContent(snapshot) } returns "restored content"

            val result = service.restoreSnapshot("snap-1")

            assertTrue(result.success)
            assertEquals(listOf("restored.kt"), result.restoredFiles)
            assertEquals("restored content", tempDir.resolve("restored.kt").toFile().readText())
        }

        @Test
        fun `should filter to specific files when requested`() {
            val snap1 = mockk<Snapshot> { every { filePath } returns "a.kt" }
            val snap2 = mockk<Snapshot> { every { filePath } returns "b.kt" }

            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snap1, snap2)
            every { snapshotRepository.decompressContent(snap1) } returns "A"
            every { snapshotRepository.decompressContent(snap2) } returns "B"

            val result = service.restoreSnapshot("snap-1", filePaths = listOf("a.kt"))

            assertTrue(result.success)
            assertEquals(listOf("a.kt"), result.restoredFiles)
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `should create parent directories`() {
            val snapshot = mockk<Snapshot> {
                every { filePath } returns "deep/nested/dir/file.kt"
            }
            every { snapshotRepository.findBySubtaskId("snap-1") } returns listOf(snapshot)
            every { snapshotRepository.decompressContent(snapshot) } returns "deep content"

            val result = service.restoreSnapshot("snap-1")

            assertTrue(result.success)
            assertTrue(tempDir.resolve("deep/nested/dir/file.kt").toFile().exists())
        }
    }

    @Nested
    inner class CleanupOldSnapshots {

        @Test
        fun `should not delete when under limit`() {
            every { snapshotRepository.findByTaskId("task-1") } returns emptyList()

            val deleted = service.cleanupOldSnapshots("task-1", keepLatest = 10)
            assertEquals(0, deleted)
        }
    }
}
