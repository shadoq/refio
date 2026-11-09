package pl.jclab.refio.core.services.longtask

import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.GoalVerdicts
import pl.jclab.refio.core.services.turn.HarnessMessageTypes
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A long task is a loop of rounds: one turn of the agent, then a check of the end condition. It
 * must end on the condition, and also when it is stuck, out of rounds or out of working time, or
 * stopped by the user, and it must leave its state saved after every round so a restart can pick
 * it up.
 */
class LongTaskRunnerTest {

    private companion object {
        const val MIN = 60_000L
    }

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val store = TaskStateRepository()
    private var now = 0L
    private val requests = mutableListOf<TurnRequest>()
    private var filesPerRound = 1

    private val facts = object : RoundFacts {
        override fun filesWritten(taskId: String, sinceMs: Long) = filesPerRound
        override fun planSteps(taskId: String) = emptyList<AgentPlanStep>()
    }

    private val request = TurnRequest(taskId = "t1", userInput = "Implement the 12 stubs in src/", mode = TaskMode.AGENT)

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { TaskRepository().create(name = "Stubs", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
    }

    @AfterEach
    fun tearDown() {
        GoalVerdicts.clear("t1")
        db.keepAlive.close()
    }

    private fun spec(until: String? = "npm test", goal: String? = null, maxRounds: Int = 12, maxMinutes: Long = 240) =
        LongTaskSpec(untilCommand = until, goal = goal, maxRounds = maxRounds, maxMinutes = maxMinutes, maxNoProgressRounds = 3, checkTimeoutSeconds = 600)

    private class ScriptedCheck(private val results: List<CheckResult>) : RoundCheck {
        var calls = 0
        override val label = "until: npm test"
        override suspend fun check(turn: TurnResult): CheckResult = results[minOf(calls++, results.lastIndex)]
    }

    private fun failed(errorLines: Int = 5) =
        CheckResult(passed = false, exitCode = 1, errorLines = errorLines, tail = "FAILED: $errorLines tests", timedOut = false)

    private val passed = CheckResult(passed = true, exitCode = 0, errorLines = 0, tail = "all passing", timedOut = false)

    private fun completed() = TurnResult(
        success = true, response = "Done.", iterations = 10, tokensIn = 0, tokensOut = 0, cost = 0.0, stopReason = TurnStopReason.COMPLETED
    )

    private fun runner(check: RoundCheck, turn: suspend (TurnRequest) -> TurnResult = { completed() }) = LongTaskRunner(
        turnRunner = TurnRunner { req ->
            requests += req
            turn(req)
        },
        store = store,
        checkFactory = { _, _ -> check },
        progressFile = null,
        facts = facts,
        clock = { now },
        isCancelled = { false },
    )

    @Test
    fun `until passes in round three`() = runTest {
        val outcome = runner(ScriptedCheck(listOf(failed(5), failed(2), passed))).run(request, spec())

        assertEquals(LongTaskStatus.SUCCEEDED, outcome.state.status)
        assertEquals(3, outcome.state.roundsDone)
        assertEquals(3, requests.size)
        assertTrue(requests[0].userInput.startsWith("Implement the 12 stubs in src/"), "round 1 is the user's message")
        assertNull(requests[0].inputType)
        assertEquals(HarnessMessageTypes.LONG_TASK_ROUND, requests[1].inputType, "later rounds are started by the harness")
        assertTrue(requests[2].userInput.contains("FAILED: 2 tests"), "a round sees the last check's output: ${requests[2].userInput}")
    }

    @Test
    fun `three rounds without progress end incomplete`() = runTest {
        filesPerRound = 0

        val outcome = runner(ScriptedCheck(listOf(failed(5)))).run(request, spec())

        assertEquals(LongTaskStatus.INCOMPLETE, outcome.state.status)
        assertEquals(3, requests.size, "stopped long before the 12-round limit")
        assertTrue(outcome.state.stopReason!!.contains("progress"), outcome.state.stopReason)
    }

    @Test
    fun `round limit ends incomplete`() = runTest {
        val outcome = runner(ScriptedCheck(listOf(failed()))).run(request, spec(maxRounds = 2))

        assertEquals(LongTaskStatus.INCOMPLETE, outcome.state.status)
        assertEquals(2, requests.size)
        assertTrue(outcome.state.stopReason!!.contains("round"), outcome.state.stopReason)
    }

    @Test
    fun `time limit counts active time only`() = runTest {
        val saved = LongTaskState(taskId = "t1", spec = spec(), status = LongTaskStatus.INTERRUPTED, roundsDone = 4, activeMs = 200 * MIN)
        now = 10 * 60 * MIN // the process was closed for hours since the task started

        val outcome = runner(ScriptedCheck(listOf(failed()))) {
            now += 30 * MIN
            completed()
        }.run(request, spec(maxMinutes = 240), resumeFrom = saved)

        assertEquals(2, requests.size, "200 + 30 + 30 minutes of work crosses 240; the hours away do not count")
        assertEquals(LongTaskStatus.INCOMPLETE, outcome.state.status)
        assertEquals(260 * MIN, outcome.state.activeMs)
        assertEquals(6, outcome.state.roundsDone)
        assertEquals(HarnessMessageTypes.LONG_TASK_ROUND, requests[0].inputType, "a resumed loop starts with the next round, not the user's message again")
    }

    @Test
    fun `stop ends the loop as cancelled`() = runTest {
        val check = ScriptedCheck(listOf(failed()))

        val outcome = runner(check) { completed().copy(success = false, stopReason = TurnStopReason.CANCELLED) }.run(request, spec())

        assertEquals(LongTaskStatus.CANCELLED, outcome.state.status)
        assertEquals(1, requests.size)
        assertEquals(0, check.calls, "a stopped round is not checked")
        assertEquals("CANCELLED", store.get("t1")?.loopStatus)
    }

    @Test
    fun `goal mode ends when the judge passed`() = runTest {
        var round = 0
        val outcome = runner(GoalJudgeCheck("t1", "all 12 functions implemented")) {
            round++
            GoalVerdicts.record("t1", met = round == 2)
            completed()
        }.run(request, spec(until = null, goal = "all 12 functions implemented"))

        assertEquals(LongTaskStatus.SUCCEEDED, outcome.state.status)
        assertEquals(2, requests.size)
    }

    @Test
    fun `state saved after every round`() = runTest {
        val roundsSavedWhenTurnStarts = mutableListOf<Int?>()

        runner(ScriptedCheck(listOf(failed(5), failed(3), passed))) {
            roundsSavedWhenTurnStarts += store.get("t1")?.roundsDone
            completed()
        }.run(request, spec())

        assertEquals(listOf<Int?>(0, 1, 2), roundsSavedWhenTurnStarts)
        val row = store.get("t1")!!
        assertEquals("SUCCEEDED", row.loopStatus)
        assertEquals(3, row.roundsDone)
        assertEquals(30, row.totalIterations)
        assertEquals("all passing", row.lastCheckTail)
    }

    @Test
    fun `new message on a succeeded task starts a fresh loop`() = runTest {
        store.upsert(TaskStateRow(taskId = "t1", untilCommand = "npm test", loopStatus = "SUCCEEDED", roundsDone = 3, totalIterations = 90))

        val outcome = runner(ScriptedCheck(listOf(passed))).run(request, spec())

        assertEquals(LongTaskStatus.SUCCEEDED, outcome.state.status)
        assertEquals(1, outcome.state.roundsDone, "counted from round 1, not from the old loop's 3")
        assertEquals(1, requests.size)
        assertEquals("npm test", store.get("t1")?.untilCommand, "the task's own settings stay")
    }

    @Test
    fun `round that fails with an error counts as a round without progress`() = runTest {
        val check = ScriptedCheck(listOf(failed()))

        val outcome = runner(check) { throw IllegalStateException("Connection refused") }.run(request, spec())

        assertEquals(LongTaskStatus.INCOMPLETE, outcome.state.status)
        assertEquals(3, requests.size, "a server that is down ends the loop after three rounds, not at once")
        assertEquals(0, check.calls)
    }
}
