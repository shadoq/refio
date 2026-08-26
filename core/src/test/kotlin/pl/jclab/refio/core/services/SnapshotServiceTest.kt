package pl.jclab.refio.core.services

import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.db.Snapshot
import pl.jclab.refio.core.db.SnapshotGroup
import pl.jclab.refio.core.db.repositories.SnapshotGroupRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class SnapshotServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var snapshotGroupRepository: SnapshotGroupRepository
    private lateinit var service: SnapshotService

    @BeforeEach
    fun setup() {
        snapshotRepository = mockk(relaxed = true)
        snapshotGroupRepository = mockk(relaxed = true)
        every { snapshotGroupRepository.create(any(), any()) } answers {
            SnapshotGroup(
                id = "group-generated",
                taskId = firstArg(),
                subtaskId = secondArg(),
                createdAt = 0L
            )
        }
        service = SnapshotService(snapshotRepository, snapshotGroupRepository, tempDir)
    }

    @Nested
    inner class CreateSnapshot {

        @Test
        fun `should create snapshot for existing files`() {
            val file = tempDir.resolve("src/main.kt")
            file.parent.createDirectories()
            file.writeText("fun main() {}")

            val groupId = service.createSnapshot("task-1", "subtask-1", listOf("src/main.kt"))

            assertEquals("group-generated", groupId)
            verify {
                snapshotGroupRepository.create(taskId = "task-1", subtaskId = "subtask-1")
                snapshotRepository.create(
                    taskId = "task-1",
                    groupId = "group-generated",
                    filePath = "src/main.kt",
                    content = "fun main() {}",
                    contentHash = any()
                )
            }
        }

        @Test
        fun `should return null when no existing files`() {
            val result = service.createSnapshot("task-1", "subtask-1", listOf("nonexistent.kt"))

            assertNull(result)
            verify(exactly = 0) {
                snapshotGroupRepository.create(any(), any())
                snapshotRepository.create(any(), any(), any(), any(), any())
            }
        }

        @Test
        fun `should return null for empty path list`() {
            val result = service.createSnapshot("task-1", "snap-42", emptyList())
            assertNull(result)
        }

        @Test
        fun `should snapshot multiple files under one group`() {
            tempDir.resolve("a.kt").writeText("A")
            tempDir.resolve("b.kt").writeText("B")

            val groupId = service.createSnapshot("task-1", "sub-1", listOf("a.kt", "b.kt"))

            assertEquals("group-generated", groupId)
            verify(exactly = 1) { snapshotGroupRepository.create(any(), any()) }
            verify(exactly = 2) { snapshotRepository.create(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `should compute SHA-256 hash`() {
            tempDir.resolve("test.txt").writeText("hello")

            val hashSlot = slot<String>()
            every {
                snapshotRepository.create(any(), any(), any(), any(), capture(hashSlot))
            } returns mockk(relaxed = true)

            service.createSnapshot("task-1", "sub-1", listOf("test.txt"))

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
            val snapshot1 = mockk<Snapshot> { every { filePath } returns "a.kt" }
            val snapshot2 = mockk<Snapshot> { every { filePath } returns "b.kt" }

            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot1, snapshot2)
            every { snapshotRepository.decompressContent(snapshot1) } returns "content-A"
            every { snapshotRepository.decompressContent(snapshot2) } returns "content-B"

            val result = service.getSnapshot("grp-1")

            assertEquals(2, result.size)
            assertEquals("content-A", result["a.kt"])
            assertEquals("content-B", result["b.kt"])
        }

        @Test
        fun `should return empty map for unknown snapshot`() {
            every { snapshotRepository.findByGroupId("unknown") } returns emptyList()

            val result = service.getSnapshot("unknown")
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class GetFileContent {

        @Test
        fun `should return content for existing file`() {
            val snapshot = mockk<Snapshot> { every { filePath } returns "target.kt" }
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)
            every { snapshotRepository.decompressContent(snapshot) } returns "file content"

            val result = service.getFileContent("grp-1", "target.kt")
            assertEquals("file content", result)
        }

        @Test
        fun `should return null for missing file`() {
            val snapshot = mockk<Snapshot> { every { filePath } returns "other.kt" }
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)

            val result = service.getFileContent("grp-1", "target.kt")
            assertNull(result)
        }
    }

    @Nested
    inner class NonUtf8Files {

        @Test
        fun `should capture non-UTF-8 bytes without replacing them`() {
            // 0xFF 0xFE is not valid UTF-8. Kotlin's readText substitutes U+FFFD instead of
            // throwing, so a decode-then-fallback never fires and the original bytes are lost.
            val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte(), 0x62)
            Files.write(tempDir.resolve("legacy.txt"), bytes)

            val contentSlot = slot<String>()
            val hashSlot = slot<String>()
            every {
                snapshotRepository.create(any(), any(), any(), capture(contentSlot), capture(hashSlot))
            } returns mockk(relaxed = true)

            service.createSnapshot("task-1", "sub-1", listOf("legacy.txt"))

            assertContentEquals(
                bytes,
                contentSlot.captured.toByteArray(Charsets.ISO_8859_1),
                "snapshot content must round-trip back to the exact bytes on disk"
            )
            assertEquals(sha256Hex(bytes), hashSlot.captured, "hash must cover the raw file bytes")
        }

        @Test
        fun `should restore the original bytes of a non-UTF-8 file`() {
            val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte(), 0x62)
            val stored = String(bytes, Charsets.ISO_8859_1)
            val snapshot = mockk<Snapshot> {
                every { filePath } returns "legacy.txt"
                every { contentHash } returns sha256Hex(bytes)
            }
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)
            every { snapshotRepository.decompressContent(snapshot) } returns stored

            val result = service.restoreSnapshot("grp-1")

            assertTrue(result.success)
            assertContentEquals(bytes, Files.readAllBytes(tempDir.resolve("legacy.txt")))
        }
    }

    @Nested
    inner class RestoreSnapshot {

        @Test
        fun `should restore files to project root`() {
            val snapshot = snapshotMock("restored.kt", "restored content")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)

            val result = service.restoreSnapshot("grp-1")

            assertTrue(result.success)
            assertEquals(listOf("restored.kt"), result.restoredFiles)
            assertEquals("restored content", tempDir.resolve("restored.kt").toFile().readText())
        }

        @Test
        fun `should filter to specific files when requested`() {
            val snap1 = snapshotMock("a.kt", "A")
            val snap2 = snapshotMock("b.kt", "B")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snap1, snap2)

            val result = service.restoreSnapshot("grp-1", filePaths = listOf("a.kt"))

            assertTrue(result.success)
            assertEquals(listOf("a.kt"), result.restoredFiles)
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `should create parent directories`() {
            val snapshot = snapshotMock("deep/nested/dir/file.kt", "deep content")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)

            val result = service.restoreSnapshot("grp-1")

            assertTrue(result.success)
            assertTrue(tempDir.resolve("deep/nested/dir/file.kt").toFile().exists())
        }

        @Test
        fun `should refuse to write outside the project root`() {
            val snapshot = snapshotMock("../escaped.kt", "malicious")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)

            val result = service.restoreSnapshot("grp-1")

            assertFalse(result.success)
            assertTrue(result.restoredFiles.isEmpty())
            assertFalse(tempDir.parent.resolve("escaped.kt").toFile().exists())
        }

        @Test
        fun `should snapshot the current content before overwriting it`() {
            // Restore is destructive: without a backup the user cannot undo the undo.
            tempDir.resolve("target.kt").writeText("content after the agent edit")
            val snapshot = snapshotMock("target.kt", "content before the agent edit")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(snapshot)

            val result = service.restoreSnapshot("grp-1")

            assertTrue(result.success)
            assertEquals("group-generated", result.backupSnapshotId)
            verify {
                snapshotRepository.create(
                    taskId = any(),
                    groupId = "group-generated",
                    filePath = "target.kt",
                    content = "content after the agent edit",
                    contentHash = any()
                )
            }
        }
    }

    @Nested
    inner class PlanRestore {

        @Test
        fun `should report per-file state so the caller can confirm before overwriting`() {
            tempDir.resolve("modified.kt").writeText("current content")
            tempDir.resolve("untouched.kt").writeText("same as snapshot")
            val modified = snapshotMock("modified.kt", "snapshot content")
            val untouched = snapshotMock("untouched.kt", "same as snapshot")
            val deleted = snapshotMock("deleted.kt", "snapshot content")
            every { snapshotRepository.findByGroupId("grp-1") } returns listOf(modified, untouched, deleted)

            val plan = service.planRestore("grp-1")

            assertEquals(
                mapOf(
                    "modified.kt" to SnapshotService.RestoreFileState.DIFFERS_FROM_SNAPSHOT,
                    "untouched.kt" to SnapshotService.RestoreFileState.MATCHES_SNAPSHOT,
                    "deleted.kt" to SnapshotService.RestoreFileState.MISSING_ON_DISK
                ),
                plan.files.associate { it.path to it.state }
            )
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

    private fun snapshotMock(path: String, content: String): Snapshot {
        val snapshot = mockk<Snapshot> {
            every { filePath } returns path
            every { taskId } returns "task-1"
            every { contentHash } returns sha256Hex(content.toByteArray(Charsets.UTF_8))
        }
        every { snapshotRepository.decompressContent(snapshot) } returns content
        return snapshot
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
