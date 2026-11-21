package pl.jclab.refio.core.services.longtask

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.VerificationCommandRunner
import pl.jclab.refio.core.services.turn.VerificationExecution
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end condition of a long task is a command run by the harness. A command that hangs must not
 * hang the loop, and a command that prints megabytes must not flood the next round's prompt.
 */
class UntilCommandCheckTest {

    private val turn = TurnResult(success = true, response = "done", iterations = 3, tokensIn = 0, tokensOut = 0, cost = 0.0)

    private class FakeRunner(private val execution: VerificationExecution) : VerificationCommandRunner {
        var timeoutSeconds: Int? = null
        override fun run(command: String, workingDir: File, timeoutSeconds: Int): VerificationExecution {
            this.timeoutSeconds = timeoutSeconds
            return execution
        }
    }

    @Test
    fun `exit code zero passes`() = runTest {
        val result = UntilCommandCheck(FakeRunner(VerificationExecution(0, "12 passing\n")), File("."), "npm test", 600).check(turn)

        assertTrue(result.passed)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `hanging check times out and counts as failed`() = runTest {
        val runner = FakeRunner(VerificationExecution(exitCode = -1, output = "waiting for the server...\n", timedOut = true))

        val result = UntilCommandCheck(runner, File("."), "npm run e2e", timeoutSeconds = 600).check(turn)

        assertEquals(600, runner.timeoutSeconds, "the configured limit reaches the process runner")
        assertFalse(result.passed)
        assertTrue(result.timedOut)
        assertNull(result.exitCode, "a killed command has no exit code of its own")
        assertTrue(result.tail.contains("600"), "the next round is told the check was stopped: ${result.tail}")
    }

    @Test
    fun `long check output is cut to the tail`() = runTest {
        val output = (1..20_000).joinToString("\n") { "line $it" } + "\nFAILED: 3 tests\n"

        val result = UntilCommandCheck(FakeRunner(VerificationExecution(1, output)), File("."), "npm test", 600).check(turn)

        assertTrue(result.tail.length <= 4_200, "tail is ${result.tail.length} chars")
        assertTrue(result.tail.contains("FAILED: 3 tests"), "the end of the output is kept")
        assertFalse(result.tail.contains("line 1\n"), "the beginning is cut")
        assertEquals(1, result.errorLines)
    }
}
