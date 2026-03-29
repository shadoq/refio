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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testy dla SubtaskRepository.
 */
class SubtaskRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: SubtaskRepository
    private lateinit var taskRepository: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = SubtaskRepository()
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
        fun `should create subtask`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val subtask = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.READ_FILE,
                    description = "Read file.txt"
                )

                // Then
                assertNotNull(subtask.id)
                assertEquals(task.id, subtask.taskId)
                assertEquals(1, subtask.orderIndex)
                assertEquals(SubtaskKind.READ_FILE, subtask.kind)
                assertEquals(TaskStatus.PENDING, subtask.status)
            }
        }

        @Test
        fun `should create subtask with all fields`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val subtask = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.CODE_EDITING,
                    description = "Edit file",
                    paramsJson = """{"path": "file.txt"}""",
                    stepPlanJson = """{"steps": ["step1", "step2"]}""",
                    requiresApproval = true
                )

                // Then
                assertNotNull(subtask.id)
                assertEquals(SubtaskKind.CODE_EDITING, subtask.kind)
                assertEquals("""{"path": "file.txt"}""", subtask.paramsJson)
                assertTrue(subtask.requiresApproval)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find subtask by ID`() {
            transaction {
                // Given
                val task = createTestTask()
                val created = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.READ_FILE,
                    description = "Find me"
                )

                // When
                val found = repository.findById(created.id)

                // Then
                assertNotNull(found)
                assertEquals("Find me", found.description)
            }
        }

        @Test
        fun `should find subtasks by task ID`() {
            transaction {
                // Given
                val task = createTestTask()
                repository.create(taskId = task.id, orderIndex = 1, kind = SubtaskKind.READ_FILE, description = "Subtask 1")
                repository.create(taskId = task.id, orderIndex = 2, kind = SubtaskKind.CODE_EDITING, description = "Subtask 2")

                // When
                val subtasks = repository.findByTaskId(task.id)

                // Then
                assertEquals(2, subtasks.size)
            }
        }
    }

    @Nested
    inner class UpdateTests {

        @Test
        fun `should update subtask status`() {
            transaction {
                // Given
                val task = createTestTask()
                val subtask = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.READ_FILE,
                    description = "Test"
                )

                // When
                val updated = repository.updateStatus(subtask.id, TaskStatus.RUNNING)

                // Then
                assertNotNull(updated)
                assertEquals(TaskStatus.RUNNING, updated.status)
            }
        }

        @Test
        fun `should update subtask result`() {
            transaction {
                // Given
                val task = createTestTask()
                val subtask = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.READ_FILE,
                    description = "Test"
                )

                // When
                val updated = repository.updateResult(
                    id = subtask.id,
                    result = "Success",
                    errorMessage = null
                )

                // Then
                assertNotNull(updated)
                assertEquals("Success", updated.result)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete subtask`() {
            transaction {
                // Given
                val task = createTestTask()
                val subtask = repository.create(
                    taskId = task.id,
                    orderIndex = 1,
                    kind = SubtaskKind.READ_FILE,
                    description = "Delete me"
                )

                // When
                val deleted = repository.delete(subtask.id)

                // Then
                assertTrue(deleted)
                assertNull(repository.findById(subtask.id))
            }
        }

        @Test
        fun `should delete all subtasks for task`() {
            transaction {
                // Given
                val task = createTestTask()
                repository.create(taskId = task.id, orderIndex = 1, kind = SubtaskKind.READ_FILE, description = "S1")
                repository.create(taskId = task.id, orderIndex = 2, kind = SubtaskKind.CODE_EDITING, description = "S2")

                // When
                val count = repository.deleteByTaskId(task.id)

                // Then
                assertEquals(2, count)
                assertEquals(0, repository.findByTaskId(task.id).size)
            }
        }
    }
}
