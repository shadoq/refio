package pl.jclab.refio.core.api.routers

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.longtask.CheckResult
import pl.jclab.refio.core.services.longtask.LongTaskCommand
import pl.jclab.refio.core.services.longtask.LongTaskSpec
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.longtask.RoundCheck
import pl.jclab.refio.core.services.longtask.RoundFacts
import pl.jclab.refio.core.services.maintenance.ProcessOwner
import pl.jclab.refio.core.services.maintenance.TaskLease
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending a message goes through one entry point in the TUI, the IDE and the headless CLI. It runs a
 * single turn unless the user gave the task an end condition, and `/long off` must bring back plain
 * single turns.
 */
class LongTaskRouterTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val tasks = TaskRepository()
    private val store = TaskStateRepository()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val turns = mutableListOf<TurnRequest>()
    private var checks = 0
    private var stopInRound: Int? = null

    /** Runs while round n's turn is still going, like a command the user types mid-run. */
    private var duringTurn: (round: Int) -> Unit = {}

    private val failingCheck = object : RoundCheck {
        override val label = "until: npm test"
        override suspend fun check(turn: TurnResult): CheckResult {
            checks++
            return CheckResult(passed = false, exitCode = 1, errorLines = 5, tail = "FAILED", timedOut = false)
        }
    }

    private val facts = object : RoundFacts {
        override fun filesWritten(taskId: String, sinceMs: Long) = 1
        override fun planSteps(taskId: String) = emptyList<AgentPlanStep>()
    }

    private lateinit var router: LongTaskRouter

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { tasks.create(name = "Stubs", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        router = buildRouter()
    }

    private fun buildRouter(checkFactory: (String, LongTaskSpec) -> RoundCheck = { _, _ -> failingCheck }) = LongTaskRouter(
        store = store,
        taskRepository = tasks,
        configService = configService,
        runTurn = { request, _, _ ->
            turns += request
            duringTurn(turns.size)
            if (turns.size == stopInRound) {
                throw CancellationException("Stop pressed")
            }
            TurnResult(success = true, response = "Done.", iterations = 4, tokensIn = 0, tokensOut = 0, cost = 0.0, stopReason = TurnStopReason.COMPLETED)
        },
        facts = facts,
        checkFactory = checkFactory,
        originalRequest = { "Implement the stubs" },
        isCancelled = { false },
    )

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun send() = TurnRequest(taskId = "t1", userInput = "Implement the stubs", mode = TaskMode.AGENT)

    @Test
    fun `until runs rounds, long off returns to single turns`() = runTest {
        router.handle("t1", LongTaskCommand.Until("npm test"))
        router.handle("t1", LongTaskCommand.Rounds(2))

        router.runTurnOrLoop(send())

        assertEquals(2, turns.size, "two rounds with a failing check")
        assertEquals(LongTaskStatus.INCOMPLETE, router.state("t1")?.status)

        router.handle("t1", LongTaskCommand.Off)
        router.runTurnOrLoop(send())

        assertEquals(3, turns.size, "one plain turn after /long off")
        assertEquals(2, checks, "no check after a plain turn")
        assertNull(router.spec("t1"))
    }

    @Test
    fun `a goal alone keeps single turns until long on`() = runTest {
        tasks.setCompletionCondition("t1", "all 12 functions implemented")

        router.runTurnOrLoop(send())

        assertEquals(1, turns.size)
        assertEquals(0, checks)
        assertNull(router.spec("t1"), "/goal keeps its old meaning until the user turns the loop on")

        router.handle("t1", LongTaskCommand.On)

        assertEquals("all 12 functions implemented", router.spec("t1")?.goal)
    }

    @Test
    fun `long on without a condition says what is missing`() {
        val reply = router.handle("t1", LongTaskCommand.On)

        assertTrue(reply.contains("/until") && reply.contains("/goal"), reply)
        assertNull(router.spec("t1"))
    }

    @Test
    fun `plan mode runs a single turn even with until`() = runTest {
        router.handle("t1", LongTaskCommand.Until("npm test"))

        router.runTurnOrLoop(send().copy(mode = TaskMode.PLAN))

        assertEquals(1, turns.size, "a read-only turn cannot change what the check sees")
        assertEquals(0, checks)
    }

    @Test
    fun `a loop stopped mid-round leaves no running round behind`() = runTest {
        router.handle("t1", LongTaskCommand.Until("npm test"))
        stopInRound = 1

        assertFailsWith<CancellationException> { router.runTurnOrLoop(send()) }

        assertNotEquals(LongTaskStatus.RUNNING, router.progress.value["t1"]?.status, "the IDE bar would keep showing a round that ended")
    }

    @Test
    fun `a long task holds its lease through the checks between rounds`() = runTest {
        val leaseDuringCheck = mutableListOf<Long?>()
        val leased = LongTaskRouter(
            store = store,
            taskRepository = tasks,
            configService = configService,
            runTurn = { _, _, _ ->
                TurnResult(success = true, response = "Done.", iterations = 4, tokensIn = 0, tokensOut = 0, cost = 0.0, stopReason = TurnStopReason.COMPLETED)
            },
            facts = facts,
            checkFactory = { _, _ ->
                object : RoundCheck {
                    override val label = "until: npm test"
                    override suspend fun check(turn: TurnResult): CheckResult {
                        leaseDuringCheck += store.get("t1")?.ownerPid
                        return CheckResult(passed = false, exitCode = 1, errorLines = 5, tail = "FAILED", timedOut = false)
                    }
                }
            },
            isCancelled = { false },
            taskLease = TaskLease(store, ProcessOwner(pid = 4242, startedAt = 1)),
        )
        leased.handle("t1", LongTaskCommand.Until("npm test"))
        leased.handle("t1", LongTaskCommand.Rounds(2))

        leased.runTurnOrLoop(send())

        assertEquals(listOf<Long?>(4242, 4242), leaseDuringCheck, "a process that dies during a check must leave its lease behind")
        assertNull(store.get("t1")?.ownerPid, "released when the loop ends")
    }

    @Test
    fun `long off typed during a run ends the loop after the current round`() = runTest {
        router.handle("t1", LongTaskCommand.Until("npm test"))
        duringTurn = { round -> if (round == 1) router.handle("t1", LongTaskCommand.Off) }

        router.runTurnOrLoop(send())

        assertEquals(1, turns.size, "no new round starts once the user turned the mode off")
        assertNull(router.spec("t1"))
    }

    @Test
    fun `an end command changed during a run is checked from the next round`() = runTest {
        // The user sees the check command is wrong and sets another one; the loop kept running
        // the old, failing command until its limits.
        val commands = mutableListOf<String?>()
        router = buildRouter { _, spec ->
            commands += spec.untilCommand
            failingCheck
        }
        router.handle("t1", LongTaskCommand.Until("npm test"))
        router.handle("t1", LongTaskCommand.Rounds(2))
        duringTurn = { round -> if (round == 1) router.handle("t1", LongTaskCommand.Until("npm run test:unit")) }

        router.runTurnOrLoop(send())

        assertEquals(2, turns.size)
        assertEquals(listOf<String?>("npm test", "npm run test:unit"), commands, "round 2 is checked with the command set during round 1")
    }

    @Test
    fun `stop during the check leaves the loop ready to continue`() = runTest {
        // The check is often the longest part of a round (a test suite), so it is where Stop lands.
        router = buildRouter { _, _ ->
            object : RoundCheck {
                override val label = "until: npm test"
                override suspend fun check(turn: TurnResult): CheckResult = throw CancellationException("Stop pressed while npm test ran")
            }
        }
        router.handle("t1", LongTaskCommand.Until("npm test"))

        assertFailsWith<CancellationException> { router.runTurnOrLoop(send()) }

        assertEquals(LongTaskStatus.CANCELLED, router.state("t1")?.status, "a loop left as running never offers Continue")
        assertTrue(router.canResume("t1"))
    }

    @Test
    fun `continue after the round limit runs another allowance of rounds`() = runTest {
        router.handle("t1", LongTaskCommand.Until("npm test"))
        router.handle("t1", LongTaskCommand.Rounds(2))
        router.runTurnOrLoop(send())
        assertEquals(2, turns.size)
        assertTrue(router.canResume("t1"))

        router.resume(send())

        assertEquals(4, turns.size, "Continue after the limit must run rounds, not stop again at once")
        assertEquals(4, router.state("t1")?.roundsDone)
    }
}
