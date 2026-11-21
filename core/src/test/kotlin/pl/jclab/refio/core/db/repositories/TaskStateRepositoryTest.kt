package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A long task has to outlive the process that runs it: after a closed IDE or a crash the next start
 * reads where the loop stood and which process held it. The plan and the loop state are written by
 * different parts of the program into one row, so neither may wipe the other's columns.
 */
class TaskStateRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val repository = TaskStateRepository(clock = { 1_000L })

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction {
            TaskRepository().create(name = "Long", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1")
            TaskRepository().create(name = "Other", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t2")
        }
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `task state round trips`() {
        val row = TaskStateRow(
            taskId = "t1", untilCommand = "npm test", maxRounds = 12, maxMinutes = 240,
            loopStatus = "RUNNING", roundsDone = 3, noProgressRounds = 1, totalIterations = 87, activeMs = 2_820_000,
            roundsJson = """[{"round":1}]""", stopReason = null, lastCheckTail = "5 failing",
        )

        repository.upsert(row)

        assertEquals(row.copy(updatedAt = 1_000L), repository.get("t1"))
        assertNull(repository.get("t2"), "no row until a task uses it")
    }

    @Test
    fun `saving the loop state keeps the plan`() {
        repository.savePlan("t1", """[{"title":"Write tests"}]""")
        repository.upsert(TaskStateRow(taskId = "t1", loopStatus = "RUNNING", roundsDone = 1))

        assertEquals("""[{"title":"Write tests"}]""", repository.loadPlan("t1"))
        assertEquals(1, repository.get("t1")?.roundsDone)
    }

    @Test
    fun `lease names the process running the task until released`() {
        repository.upsert(TaskStateRow(taskId = "t1", loopStatus = "RUNNING"))
        repository.claimLease("t1", pid = 4242, startedAt = 777)
        repository.claimLease("t2", pid = 4242, startedAt = 777)
        repository.upsert(TaskStateRow(taskId = "t1", loopStatus = "RUNNING", roundsDone = 2))

        assertEquals(setOf("t1", "t2"), repository.findLeased().map { it.taskId }.toSet())
        assertEquals(4242L to 777L, repository.get("t1")!!.let { it.ownerPid to it.ownerStartedAt }, "a round saved later keeps the lease")

        repository.releaseLease("t1")

        assertEquals(listOf("t2"), repository.findLeased().map { it.taskId })
    }
}
