package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.*
import pl.jclab.refio.testutil.TestDatabase
import java.util.zip.DataFormatException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SnapshotRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: SnapshotRepository
    private lateinit var groupRepository: SnapshotGroupRepository
    private lateinit var taskRepository: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = SnapshotRepository()
        groupRepository = SnapshotGroupRepository()
        taskRepository = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun createTestTask(id: String = "task-123"): Task = transaction {
        taskRepository.create(
            name = "Test Task",
            mode = TaskMode.AGENT,
            projectId = "project-123",
            projectPath = "/test/project",
            id = id
        )
    }

    private fun createGroup(taskId: String, subtaskId: String? = null): SnapshotGroup =
        groupRepository.create(taskId = taskId, subtaskId = subtaskId)

    @Nested
    inner class CreateTests {

        @Test
        fun `should create snapshot`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id)

                val snapshot = repository.create(
                    taskId = task.id,
                    groupId = group.id,
                    filePath = "/path/to/file.txt",
                    content = "original content",
                    contentHash = "hash123"
                )

                assertNotNull(snapshot.id)
                assertEquals(task.id, snapshot.taskId)
                assertEquals(group.id, snapshot.groupId)
                assertEquals("/path/to/file.txt", snapshot.filePath)
            }
        }

        @Test
        fun `should create snapshot with subtask-tagged group`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id, subtaskId = "subtask-123")

                val snapshot = repository.create(
                    taskId = task.id,
                    groupId = group.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash456"
                )

                assertNotNull(snapshot.id)
                assertEquals(group.id, snapshot.groupId)
                assertEquals("subtask-123", groupRepository.findById(group.id)?.subtaskId)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find snapshot by ID`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id)
                val created = repository.create(
                    taskId = task.id,
                    groupId = group.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash"
                )

                val found = repository.findById(created.id)

                assertNotNull(found)
                assertEquals("/file.txt", found.filePath)
            }
        }

        @Test
        fun `should find snapshots by task ID`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id)
                repository.create(taskId = task.id, groupId = group.id, filePath = "/file1.txt", content = "c1", contentHash = "h1")
                repository.create(taskId = task.id, groupId = group.id, filePath = "/file2.txt", content = "c2", contentHash = "h2")

                val snapshots = repository.findByTaskId(task.id)

                assertEquals(2, snapshots.size)
            }
        }

        @Test
        fun `should find snapshots by file path`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id)
                repository.create(taskId = task.id, groupId = group.id, filePath = "/file.txt", content = "v1", contentHash = "h1")
                repository.create(taskId = task.id, groupId = group.id, filePath = "/file.txt", content = "v2", contentHash = "h2")

                val snapshots = repository.findByFilePath(task.id, "/file.txt")

                assertEquals(2, snapshots.size)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete snapshot`() {
            transaction {
                val task = createTestTask()
                val group = createGroup(task.id)
                val snapshot = repository.create(
                    taskId = task.id,
                    groupId = group.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash"
                )

                val deleted = repository.delete(snapshot.id)

                assertEquals(true, deleted)
            }
        }
    }

    @Nested
    inner class CompressionTests {

        @Test
        fun `should fail fast on truncated zlib payload instead of looping forever`() {
            val corrupted = Snapshot(
                id = "snap-corrupt",
                taskId = "task-123",
                groupId = "group-1",
                filePath = "/file.txt",
                contentHash = "hash",
                contentCompressed = byteArrayOf(0x78, 0x9C.toByte()), // valid zlib header, truncated body
                originalSize = 10,
                compressionRatio = 1.0,
                createdAt = 1L
            )

            assertFailsWith<DataFormatException> {
                repository.decompressContent(corrupted)
            }
        }
    }
}
