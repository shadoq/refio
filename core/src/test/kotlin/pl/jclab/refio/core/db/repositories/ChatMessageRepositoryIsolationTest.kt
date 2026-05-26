package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that ChatMessageRepository.findHistoryForInvocation isolates subagent
 * histories from the parent and from each other within a single task.
 */
class ChatMessageRepositoryIsolationTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repo: ChatMessageRepository
    private lateinit var taskRepo: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repo = ChatMessageRepository()
        taskRepo = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun seedTask(id: String = "task-iso"): String {
        transaction {
            taskRepo.create(
                name = "Isolation Task",
                mode = TaskMode.AGENT,
                projectId = "project-iso",
                projectPath = "/test/iso",
                id = id
            )
        }
        return id
    }

    @Test
    fun `parent run sees only rows with null agentInstanceId`() {
        val taskId = seedTask()
        transaction {
            repo.create(taskId = taskId, role = MessageRole.USER, content = "parent question")
            repo.create(
                taskId = taskId,
                role = MessageRole.USER,
                content = "subagent goal",
                agentInstanceId = "sub-1",
                agentName = "reviewer",
                agentDepth = 1
            )
            repo.create(taskId = taskId, role = MessageRole.ASSISTANT, content = "parent answer")
        }

        val parentHistory = repo.findHistoryForInvocation(taskId, agentInstanceId = null)

        assertEquals(2, parentHistory.size, "Parent must not see subagent rows")
        assertTrue(parentHistory.all { it.agentInstanceId == null })
        assertEquals(listOf("parent question", "parent answer"), parentHistory.map { it.content })
    }

    @Test
    fun `subagent invocation sees only its own rows`() {
        val taskId = seedTask()
        transaction {
            repo.create(taskId = taskId, role = MessageRole.USER, content = "parent question")
            repo.create(
                taskId = taskId,
                role = MessageRole.USER,
                content = "goal A",
                agentInstanceId = "sub-A",
                agentName = "reviewer",
                agentDepth = 1
            )
            repo.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = "A's intermediate step",
                agentInstanceId = "sub-A",
                agentName = "reviewer",
                agentDepth = 1
            )
        }

        val subHistory = repo.findHistoryForInvocation(taskId, agentInstanceId = "sub-A")

        assertEquals(2, subHistory.size)
        assertTrue(subHistory.all { it.agentInstanceId == "sub-A" })
        assertEquals(listOf("goal A", "A's intermediate step"), subHistory.map { it.content })
    }

    @Test
    fun `sibling subagents are isolated from each other`() {
        val taskId = seedTask()
        transaction {
            repo.create(taskId = taskId, role = MessageRole.USER, content = "parent")
            repo.create(
                taskId = taskId,
                role = MessageRole.USER,
                content = "A goal",
                agentInstanceId = "sub-A",
                agentDepth = 1
            )
            repo.create(
                taskId = taskId,
                role = MessageRole.USER,
                content = "B goal",
                agentInstanceId = "sub-B",
                agentDepth = 1
            )
            repo.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = "B step 1",
                agentInstanceId = "sub-B",
                agentDepth = 1
            )
        }

        val a = repo.findHistoryForInvocation(taskId, "sub-A")
        val b = repo.findHistoryForInvocation(taskId, "sub-B")

        assertEquals(listOf("A goal"), a.map { it.content })
        assertEquals(listOf("B goal", "B step 1"), b.map { it.content })
    }

    @Test
    fun `rows are returned in seq ascending order`() {
        val taskId = seedTask()
        transaction {
            repo.create(taskId = taskId, role = MessageRole.USER, content = "first")
            Thread.sleep(2) // ensure distinct System.nanoTime() seq values
            repo.create(taskId = taskId, role = MessageRole.ASSISTANT, content = "second")
            Thread.sleep(2)
            repo.create(taskId = taskId, role = MessageRole.USER, content = "third")
        }

        val history = repo.findHistoryForInvocation(taskId, agentInstanceId = null)

        assertEquals(listOf("first", "second", "third"), history.map { it.content })
    }

    @Test
    fun `different tasks are isolated even with same instance id`() {
        val task1 = seedTask("task-iso-1")
        val task2 = seedTask("task-iso-2")
        transaction {
            repo.create(
                taskId = task1,
                role = MessageRole.USER,
                content = "T1 sub",
                agentInstanceId = "shared",
                agentDepth = 1
            )
            repo.create(
                taskId = task2,
                role = MessageRole.USER,
                content = "T2 sub",
                agentInstanceId = "shared",
                agentDepth = 1
            )
        }

        val t1 = repo.findHistoryForInvocation(task1, "shared")
        val t2 = repo.findHistoryForInvocation(task2, "shared")

        assertEquals(listOf("T1 sub"), t1.map { it.content })
        assertEquals(listOf("T2 sub"), t2.map { it.content })
    }
}
