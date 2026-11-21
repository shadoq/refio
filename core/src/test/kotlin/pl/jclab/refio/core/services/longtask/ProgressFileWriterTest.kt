package pl.jclab.refio.core.services.longtask

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.refio/progress.md` is how a person, or the agent in a later round, sees at a glance where an
 * hours-long task stands without reading the whole conversation.
 */
class ProgressFileWriterTest {

    @TempDir
    lateinit var project: Path

    private fun round(n: Int, errorLines: Int, files: Int) = RoundRecord(
        round = n, durationMs = 600_000, iterations = 20, turnStopReason = "COMPLETED",
        checkPassed = false, exitCode = 1, errorLines = errorLines, filesWritten = files, planStepsDone = n, progress = true,
    )

    @Test
    fun `progress file shows goal, rounds and open steps`() {
        val state = LongTaskState(
            taskId = "t1",
            spec = LongTaskSpec(untilCommand = "npm test", goal = null, maxRounds = 12, maxMinutes = 240, maxNoProgressRounds = 3, checkTimeoutSeconds = 600),
            status = LongTaskStatus.RUNNING, roundsDone = 2, totalIterations = 40, activeMs = 47 * 60_000L,
            rounds = listOf(round(1, errorLines = 5, files = 3), round(2, errorLines = 2, files = 1)),
        )
        val steps = listOf(
            AgentPlanStep(index = 0, title = "Parse the input", status = PlanStepStatus.COMPLETED),
            AgentPlanStep(index = 1, title = "Write tokenizer tests", status = PlanStepStatus.IN_PROGRESS),
            AgentPlanStep(index = 2, title = "Wire the CLI"),
        )

        val file = ProgressFileWriter(project).write(state, goal = "Implement the 12 stubs in src/", steps = steps)
        val text = Files.readString(file)

        assertEquals(project.resolve(".refio/progress.md"), file)
        assertTrue(text.contains("Implement the 12 stubs in src/"), text)
        assertTrue(text.contains("npm test"), text)
        assertTrue(text.contains("round 2 of 12"), text)
        assertTrue(text.contains("5 error lines") && text.contains("2 error lines"), text)
        val openSteps = text.substringAfter("## Open steps")
        assertTrue(openSteps.contains("Write tokenizer tests") && openSteps.contains("Wire the CLI"), text)
        assertFalse(openSteps.contains("Parse the input"), "a finished step is not open: $text")
    }

    @Test
    fun `progress file names the next step and the command that checks it`() {
        // A later round, or a person opening the file, sees what comes next and how to check it.
        val state = LongTaskState(
            taskId = "t1",
            spec = LongTaskSpec(untilCommand = "npm test", goal = null, maxRounds = 12, maxMinutes = 240, maxNoProgressRounds = 3, checkTimeoutSeconds = 600),
            status = LongTaskStatus.RUNNING, roundsDone = 1, rounds = listOf(round(1, errorLines = 5, files = 3)),
        )
        val steps = listOf(
            AgentPlanStep(index = 0, title = "Parse the input", status = PlanStepStatus.COMPLETED),
            AgentPlanStep(index = 1, title = "Write tokenizer tests", description = "npm test -- tokenizer"),
            AgentPlanStep(index = 2, title = "Wire the CLI"),
        )

        val text = Files.readString(ProgressFileWriter(project).write(state, goal = "Implement the stubs", steps = steps))

        assertTrue(text.contains("Plan: 1 of 3 steps done"), text)
        assertTrue(text.contains("2. Write tokenizer tests - next"), text)
        assertTrue(text.contains("npm test -- tokenizer"), text)
        assertFalse(text.contains("Wire the CLI - next"), "only the first open step is next: $text")
    }
}
