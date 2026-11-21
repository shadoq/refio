package pl.jclab.refio.core.services.longtask

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recipe that makes many rounds add up: round 1 plans small steps a command can check, and
 * every later round starts from the first step that is still open.
 */
class LongTaskPromptsTest {

    private val spec = LongTaskSpec(
        untilCommand = "npm test", goal = null, maxRounds = 12, maxMinutes = 240,
        maxNoProgressRounds = 3, checkTimeoutSeconds = 600,
    )

    private val state = LongTaskState(
        taskId = "t1", spec = spec, status = LongTaskStatus.RUNNING, roundsDone = 2,
        rounds = listOf(round(1), round(2)), lastCheckTail = "FAILED: 2 tests",
    )

    private fun round(n: Int) = RoundRecord(
        round = n, durationMs = 600_000, iterations = 20, turnStopReason = "COMPLETED",
        checkPassed = false, exitCode = 1, errorLines = 2, filesWritten = 1, planStepsDone = n, progress = true,
    )

    @Test
    fun `first round asks for a plan of checkable steps`() {
        // Without a plan each round starts over from the whole request; with steps a command can
        // check, a later round knows where to start and when a step is really done.
        val text = LongTaskPrompts.firstRound("Implement the 12 stubs in src/", spec)

        assertTrue(text.startsWith("Implement the 12 stubs in src/"), "the user's words come first: $text")
        assertTrue(text.contains("`tasks` tool"), text)
        assertTrue(text.contains("check with a command"), text)
        assertTrue(text.contains("`npm test`"), "the end condition is still stated: $text")
    }

    @Test
    fun `a later round starts from the first open step`() {
        val steps = listOf(
            AgentPlanStep(index = 0, title = "Parse the input", status = PlanStepStatus.COMPLETED),
            AgentPlanStep(index = 1, title = "Write tokenizer tests"),
            AgentPlanStep(index = 2, title = "Wire the CLI"),
        )

        val text = LongTaskPrompts.nextRound("Implement the 12 stubs in src/", state, steps)

        assertTrue(text.contains("Work on step 2 (Write tokenizer tests) first"), text)
    }

    @Test
    fun `a later round without a plan asks for one`() {
        val text = LongTaskPrompts.nextRound("Implement the 12 stubs in src/", state, emptyList())

        assertTrue(text.contains("no plan yet"), text)
        assertTrue(text.contains("`tasks` tool"), text)
    }

    @Test
    fun `a finished plan with a failing check asks what is still missing`() {
        // Every step ticked off while the check still fails: the plan missed something, and the
        // round must look for it instead of ending at once.
        val steps = listOf(
            AgentPlanStep(index = 0, title = "Parse the input", status = PlanStepStatus.COMPLETED),
            AgentPlanStep(index = 1, title = "Wire the CLI", status = PlanStepStatus.COMPLETED),
        )

        val text = LongTaskPrompts.nextRound("Implement the 12 stubs in src/", state, steps)

        assertTrue(text.contains("All plan steps are marked completed"), text)
    }

    @Test
    fun `a resumed loop works on the request that started it`() {
        // A message typed while the loop ran is stored as the user's too. Taking the last one made it
        // the request of every resumed round, and round 1's note told a resumed round to plan anew.
        val started = LongTaskPrompts.firstRound("Implement the 12 stubs in src/", spec)

        val request = LongTaskPrompts.originalRequest(listOf("What is in src/?", started, "Also keep an eye on the logs"))

        assertEquals("Implement the 12 stubs in src/", request)
    }

    @Test
    fun `without a message that started a loop the last message is the request`() {
        assertEquals("Fix the parser", LongTaskPrompts.originalRequest(listOf("What is in src/?", "Fix the parser")))
    }
}
