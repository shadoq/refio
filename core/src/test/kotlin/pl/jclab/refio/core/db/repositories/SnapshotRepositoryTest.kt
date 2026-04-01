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

/**
 * Testy dla SnapshotRepository.
 */
class SnapshotRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: SnapshotRepository
    private lateinit var taskRepository: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = SnapshotRepository()
        taskRepository = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun createTestTask(id: String = "task-123"): Task {
        return transaction {
            taskRepository.create(
                name = "Test Task",
                mode = TaskMode.AGENT,
                projectId = "project-123",
                projectPath = "/test/project",
                id = id
            )
        }
    }

    @Nested
    inner class CreateTests {

        @Test
        fun `should create snapshot`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val snapshot = repository.create(
                    taskId = task.id,
                    filePath = "/path/to/file.txt",
                    content = "original content",
                    contentHash = "hash123"
                )

                // Then
                assertNotNull(snapshot.id)
                assertEquals(task.id, snapshot.taskId)
                assertEquals("/path/to/file.txt", snapshot.filePath)
            }
        }

        @Test
        fun `should create snapshot with subtask`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val snapshot = repository.create(
                    taskId = task.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash456",
                    subtaskId = "subtask-123"
                )

                // Then
                assertNotNull(snapshot.id)
                assertEquals("subtask-123", snapshot.subtaskId)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find snapshot by ID`() {
            transaction {
                // Given
                val task = createTestTask()
                val created = repository.create(
                    taskId = task.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash"
                )

                // When
                val found = repository.findById(created.id)

                // Then
                assertNotNull(found)
                assertEquals("/file.txt", found.filePath)
            }
        }

        @Test
        fun `should find snapshots by task ID`() {
            transaction {
                // Given
                val task = createTestTask()
                repository.create(taskId = task.id, filePath = "/file1.txt", content = "c1", contentHash = "h1")
                repository.create(taskId = task.id, filePath = "/file2.txt", content = "c2", contentHash = "h2")

                // When
                val snapshots = repository.findByTaskId(task.id)

                // Then
                assertEquals(2, snapshots.size)
            }
        }

        @Test
        fun `should find snapshots by file path`() {
            transaction {
                // Given
                val task = createTestTask()
                repository.create(taskId = task.id, filePath = "/file.txt", content = "v1", contentHash = "h1")
                repository.create(taskId = task.id, filePath = "/file.txt", content = "v2", contentHash = "h2")

                // When
                val snapshots = repository.findByFilePath(task.id, "/file.txt")

                // Then
                assertEquals(2, snapshots.size)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete snapshot`() {
            transaction {
                // Given
                val task = createTestTask()
                val snapshot = repository.create(
                    taskId = task.id,
                    filePath = "/file.txt",
                    content = "content",
                    contentHash = "hash"
                )

                // When
                val deleted = repository.delete(snapshot.id)

                // Then
                assertEquals(true, deleted)
            }
        }
    }
}
