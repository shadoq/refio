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
import kotlin.test.assertFalse

/**
 * Testy dla TaskRepository.
 */
class TaskRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Nested
    inner class CreateTests {

        @Test
        fun `should create task with generated ID`() {
            transaction {
                // When
                val task = repository.create(
                    name = "Test Task",
                    mode = TaskMode.AGENT,
                    projectId = "project-123",
                    projectPath = "/test/project"
                )

                // Then
                assertNotNull(task.id)
                assertEquals("Test Task", task.name)
                assertEquals(TaskMode.AGENT, task.mode)
                assertEquals(TaskStatus.NEW, task.status)  // Default status is NEW
                assertEquals("project-123", task.projectId)
                assertEquals("/test/project", task.projectPath)
                assertFalse(task.pinned)
                assertFalse(task.readOnly)
            }
        }

        @Test
        fun `should create task with explicit ID`() {
            transaction {
                // Given
                val explicitId = "custom-task-123"

                // When
                val task = repository.create(
                    name = "Custom ID Task",
                    mode = TaskMode.PLAN,
                    projectId = "project-123",
                    projectPath = "/test/project",
                    id = explicitId
                )

                // Then
                assertEquals(explicitId, task.id)
            }
        }

        @Test
        fun `should create task with all optional fields`() {
            transaction {
                // When
                val task = repository.create(
                    name = "Full Task",
                    mode = TaskMode.AGENT,
                    projectId = "project-123",
                    projectPath = "/test/project",
                    readOnly = true,
                    pinned = true,
                    executionMode = ExecutionMode.AUTO,
                    requiresPlanApproval = true,
                    planApproved = true,
                    uiState = """{"collapsed": true}""",
                    coreApiVersion = "1.0"
                )

                // Then
                assertTrue(task.readOnly)
                assertTrue(task.pinned)
                assertEquals(ExecutionMode.AUTO, task.executionMode)
                assertTrue(task.requiresPlanApproval)
                assertTrue(task.planApproved)
                assertEquals("""{"collapsed": true}""", task.uiState)
                assertEquals("1.0", task.coreApiVersion)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find task by ID`() {
            transaction {
                // Given
                val created = repository.create(
                    name = "Find Me",
                    mode = TaskMode.AGENT,
                    projectId = "project-123",
                    projectPath = "/test/project"
                )

                // When
                val found = repository.findById(created.id)

                // Then
                assertNotNull(found)
                assertEquals(created.id, found.id)
                assertEquals("Find Me", found.name)
            }
        }

        @Test
        fun `should return null when task not found`() {
            transaction {
                // When
                val found = repository.findById("nonexistent-id")

                // Then
                assertNull(found)
            }
        }

        @Test
        fun `should find all tasks without filters`() {
            transaction {
                // Given
                repository.create(name = "Task 1", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 2", mode = TaskMode.PLAN, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 3", mode = TaskMode.AGENT, projectId = "p2", projectPath = "/p2")

                // When
                val tasks = repository.findAll()

                // Then
                assertEquals(3, tasks.size)
            }
        }

        @Test
        fun `should filter tasks by mode`() {
            transaction {
                // Given
                repository.create(name = "Agent Task", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Plan Task", mode = TaskMode.PLAN, projectId = "p1", projectPath = "/p1")

                // When
                val agentTasks = repository.findAll(mode = TaskMode.AGENT)
                val planTasks = repository.findAll(mode = TaskMode.PLAN)

                // Then
                assertEquals(1, agentTasks.size)
                assertEquals(1, planTasks.size)
                assertEquals("Agent Task", agentTasks[0].name)
                assertEquals("Plan Task", planTasks[0].name)
            }
        }

        @Test
        fun `should filter tasks by status`() {
            transaction {
                // Given
                val task1 = repository.create(name = "Task 1", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 2", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.update(task1.id, status = TaskStatus.RUNNING)

                // When
                val newTasks = repository.findAll(status = TaskStatus.NEW)
                val runningTasks = repository.findAll(status = TaskStatus.RUNNING)

                // Then
                assertEquals(1, newTasks.size)
                assertEquals(1, runningTasks.size)
            }
        }

        @Test
        fun `should filter tasks by project ID`() {
            transaction {
                // Given
                repository.create(name = "Project A Task", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Project B Task", mode = TaskMode.AGENT, projectId = "proj-b", projectPath = "/b")

                // When
                val projATasks = repository.findAll(projectId = "proj-a")

                // Then
                assertEquals(1, projATasks.size)
                assertEquals("Project A Task", projATasks[0].name)
            }
        }

        @Test
        fun `should respect limit and offset`() {
            transaction {
                // Given
                repository.create(name = "Task 1", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 2", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 3", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val firstPage = repository.findAll(limit = 2, offset = 0)
                val secondPage = repository.findAll(limit = 2, offset = 2)

                // Then
                assertEquals(2, firstPage.size)
                assertEquals(1, secondPage.size)
            }
        }
    }

    @Nested
    inner class UpdateTests {

        @Test
        fun `should update task name`() {
            transaction {
                // Given
                val task = repository.create(name = "Original Name", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val updated = repository.update(task.id, name = "New Name")

                // Then
                assertNotNull(updated)
                assertEquals("New Name", updated.name)
            }
        }

        @Test
        fun `should update task status`() {
            transaction {
                // Given
                val task = repository.create(name = "Task", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val updated = repository.update(task.id, status = TaskStatus.RUNNING)

                // Then
                assertNotNull(updated)
                assertEquals(TaskStatus.RUNNING, updated.status)
            }
        }

        @Test
        fun `should update multiple fields at once`() {
            transaction {
                // Given
                val task = repository.create(name = "Task", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val updated = repository.update(
                    id = task.id,
                    name = "Updated Name",
                    status = TaskStatus.SUCCESS,
                    pinned = true,
                    rate = 5
                )

                // Then
                assertNotNull(updated)
                assertEquals("Updated Name", updated.name)
                assertEquals(TaskStatus.SUCCESS, updated.status)
                assertTrue(updated.pinned)
                assertEquals(5, updated.rate)
            }
        }

        @Test
        fun `should return null when updating nonexistent task`() {
            transaction {
                // When
                val result = repository.update("nonexistent", name = "New Name")

                // Then
                assertNull(result)
            }
        }
    }

    @Nested
    inner class MetricsTests {

        @Test
        fun `should increment metrics`() {
            transaction {
                // Given
                val task = repository.create(name = "Task", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val updated = repository.incrementMetrics(
                    id = task.id,
                    tokensIn = 100,
                    tokensOut = 50,
                    costUsd = 0.001
                )

                // Then
                assertNotNull(updated)
                assertEquals(100, updated.tokensIn)
                assertEquals(50, updated.tokensOut)
                assertEquals(0.001, updated.costUsd, 0.0001)
            }
        }

        @Test
        fun `should accumulate metrics on multiple increments`() {
            transaction {
                // Given
                val task = repository.create(name = "Task", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When - increment twice
                repository.incrementMetrics(task.id, 100, 50, 0.001)
                val updated = repository.incrementMetrics(task.id, 200, 100, 0.002)

                // Then
                assertNotNull(updated)
                assertEquals(300, updated.tokensIn)
                assertEquals(150, updated.tokensOut)
                assertEquals(0.003, updated.costUsd, 0.0001)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete existing task`() {
            transaction {
                // Given
                val task = repository.create(name = "To Delete", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val deleted = repository.delete(task.id)

                // Then
                assertTrue(deleted)
                assertNull(repository.findById(task.id))
            }
        }

        @Test
        fun `should return false when deleting nonexistent task`() {
            transaction {
                // When
                val deleted = repository.delete("nonexistent")

                // Then
                assertFalse(deleted)
            }
        }
    }

    @Nested
    inner class CountTests {

        @Test
        fun `should count all tasks`() {
            transaction {
                // Given
                repository.create(name = "Task 1", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 2", mode = TaskMode.PLAN, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Task 3", mode = TaskMode.AGENT, projectId = "p2", projectPath = "/p2")

                // When
                val count = repository.count()

                // Then
                assertEquals(3L, count)
            }
        }

        @Test
        fun `should count tasks by mode`() {
            transaction {
                // Given
                repository.create(name = "Agent 1", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Agent 2", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")
                repository.create(name = "Plan 1", mode = TaskMode.PLAN, projectId = "p1", projectPath = "/p1")

                // When
                val agentCount = repository.count(mode = TaskMode.AGENT)
                val planCount = repository.count(mode = TaskMode.PLAN)

                // Then
                assertEquals(2L, agentCount)
                assertEquals(1L, planCount)
            }
        }

        @Test
        fun `should count tasks by project`() {
            transaction {
                // Given
                repository.create(name = "Task 1", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Task 2", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Task 3", mode = TaskMode.AGENT, projectId = "proj-b", projectPath = "/b")

                // When
                val projACount = repository.count(projectId = "proj-a")

                // Then
                assertEquals(2L, projACount)
            }
        }
    }

    @Nested
    inner class QueryTests {

        @Test
        fun `should check if task exists`() {
            transaction {
                // Given
                val task = repository.create(name = "Exists", mode = TaskMode.AGENT, projectId = "p1", projectPath = "/p1")

                // When
                val exists = repository.exists(task.id)
                val notExists = repository.exists("nonexistent")

                // Then
                assertTrue(exists)
                assertFalse(notExists)
            }
        }

        @Test
        fun `should get tasks for project`() {
            transaction {
                // Given
                repository.create(name = "Project A Task 1", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Project A Task 2", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Project B Task", mode = TaskMode.AGENT, projectId = "proj-b", projectPath = "/b")

                // When
                val projATasks = repository.getForProject("proj-a")

                // Then
                assertEquals(2, projATasks.size)
                assertTrue(projATasks.all { it.projectId == "proj-a" })
            }
        }

        @Test
        fun `should get last task for project`() {
            transaction {
                // Given
                repository.create(name = "First Task", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")
                repository.create(name = "Last Task", mode = TaskMode.AGENT, projectId = "proj-a", projectPath = "/a")

                // When
                val lastTask = repository.getLastForProject("proj-a")

                // Then
                assertNotNull(lastTask)
                assertEquals("Last Task", lastTask.name)
            }
        }
    }

    @Nested
    inner class CompletionConditionTests {

        @Test
        fun `getCompletionCondition returns null for newly created task`() {
            transaction {
                val task = repository.create(
                    name = "Goal Task",
                    mode = TaskMode.AGENT,
                    projectId = "proj-1",
                    projectPath = "/x"
                )

                assertNull(repository.getCompletionCondition(task.id))
                assertNull(repository.findById(task.id)?.completionCondition)
            }
        }

        @Test
        fun `setCompletionCondition then get returns the same value`() {
            transaction {
                val task = repository.create(
                    name = "Goal Task",
                    mode = TaskMode.AGENT,
                    projectId = "proj-1",
                    projectPath = "/x"
                )
                val condition = "all tests in src/test pass and migration runs cleanly"

                val ok = repository.setCompletionCondition(task.id, condition)

                assertTrue(ok)
                assertEquals(condition, repository.getCompletionCondition(task.id))
                // Same value also visible through findById (full-row read)
                assertEquals(condition, repository.findById(task.id)?.completionCondition)
            }
        }

        @Test
        fun `setCompletionCondition with null clears existing condition`() {
            transaction {
                val task = repository.create(
                    name = "Goal Task",
                    mode = TaskMode.AGENT,
                    projectId = "proj-1",
                    projectPath = "/x"
                )
                repository.setCompletionCondition(task.id, "all tests pass")

                val ok = repository.setCompletionCondition(task.id, null)

                assertTrue(ok)
                assertNull(repository.getCompletionCondition(task.id))
            }
        }

        @Test
        fun `setCompletionCondition returns false for non-existent task`() {
            transaction {
                val ok = repository.setCompletionCondition("does-not-exist", "anything")
                assertFalse(ok)
            }
        }

        @Test
        fun `setCompletionCondition overwrites previous value`() {
            transaction {
                val task = repository.create(
                    name = "Goal Task",
                    mode = TaskMode.AGENT,
                    projectId = "proj-1",
                    projectPath = "/x"
                )
                repository.setCompletionCondition(task.id, "first condition")
                repository.setCompletionCondition(task.id, "second condition")

                assertEquals("second condition", repository.getCompletionCondition(task.id))
            }
        }
    }
}
