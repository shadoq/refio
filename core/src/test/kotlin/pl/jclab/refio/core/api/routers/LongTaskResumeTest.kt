package pl.jclab.refio.core.api.routers

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.longtask.CheckResult
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.longtask.RoundCheck
import pl.jclab.refio.core.services.longtask.RoundFacts
import pl.jclab.refio.core.services.turn.HarnessMessageTypes
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A long task stopped by the user or by a closed process goes on with its next round when the user
 * asks to continue: not from round 1, and not with "Continue" as if it were a new request.
 */
class LongTaskResumeTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val store = TaskStateRepository()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val turns = mutableListOf<TurnRequest>()

    private val passingCheck = object : RoundCheck {
        override val label = "until: npm test"
        override suspend fun check(turn: TurnResult) = CheckResult(passed = true, exitCode = 0, errorLines = 0, tail = "12 passing", timedOut = false)
    }

    private val facts = object : RoundFacts {
        override fun filesWritten(taskId: String, sinceMs: Long) = 1
        override fun planSteps(taskId: String) = emptyList<AgentPlanStep>()
    }

    private lateinit var router: LongTaskRouter

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { TaskRepository().create(name = "Stubs", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        router = LongTaskRouter(
            store = store,
            taskRepository = TaskRepository(),
            configService = configService,
            runTurn = { request, _, _ ->
                turns += request
                TurnResult(success = true, response = "Done.", iterations = 4, tokensIn = 0, tokensOut = 0, cost = 0.0, stopReason = TurnStopReason.COMPLETED)
            },
            facts = facts,
            checkFactory = { _, _ -> passingCheck },
            originalRequest = { "Implement the 12 stubs in src/" },
            isCancelled = { false },
        )
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun continueRequest() = TurnRequest(taskId = "t1", userInput = "", mode = TaskMode.AGENT)

    @Test
    fun `resume continues with the next round`() = runTest {
        store.upsert(TaskStateRow(taskId = "t1", untilCommand = "npm test", loopStatus = "INTERRUPTED", roundsDone = 2, activeMs = 60_000))

        assertTrue(router.canResume("t1"))
        router.resume(continueRequest())

        assertEquals(1, turns.size)
        assertEquals(HarnessMessageTypes.LONG_TASK_ROUND, turns[0].inputType)
        assertTrue(turns[0].userInput.contains("Round 3 of 12"), turns[0].userInput)
        assertTrue(turns[0].userInput.contains("Implement the 12 stubs in src/"), "the round carries the original request")
        assertEquals(LongTaskStatus.SUCCEEDED, router.state("t1")?.status)
        assertEquals(3, router.state("t1")?.roundsDone)
    }

    @Test
    fun `nothing to resume after the condition held`() = runTest {
        store.upsert(TaskStateRow(taskId = "t1", untilCommand = "npm test", loopStatus = "SUCCEEDED", roundsDone = 3))

        assertFalse(router.canResume("t1"))
        assertNull(router.resume(continueRequest()))
        assertEquals(0, turns.size)
    }
}
