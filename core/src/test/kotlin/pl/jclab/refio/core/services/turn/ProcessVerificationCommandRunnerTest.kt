package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The only tests in this package that spawn real OS processes: the drain contract between the
 * reader thread and the caller cannot be observed through a fake runner. Skipped on Windows -
 * the commands are POSIX shell.
 */
class ProcessVerificationCommandRunnerTest {

    @TempDir
    lateinit var workingDir: Path

    private val runner = ProcessVerificationCommandRunner()

    private fun assumePosixShell() {
        assumeFalse(
            System.getProperty("os.name").lowercase().contains("win"),
            "POSIX shell required",
        )
    }

    @Test
    fun `a normal command returns its full output and reports it complete`() {
        assumePosixShell()

        val execution = runner.run("printf 'first\\nsecond\\n'", workingDir.toFile(), 30)

        assertEquals(0, execution.exitCode)
        assertTrue(execution.output.contains("first") && execution.output.contains("second"))
        assertFalse(execution.timedOut)
        assertFalse(execution.outputTruncated, "nothing blocked the reader, so the output is whole")
    }

    @Test
    fun `a surviving grandchild neither stalls the call nor yields output claimed complete but short`() {
        assumePosixShell()

        // The shell exits immediately while the backgrounded child keeps the write end of the pipe
        // open. Two platform behaviours exist here and both are acceptable; what is NOT acceptable
        // is the third: blocking until the grandchild exits, or handing the model a short output
        // that claims to be whole.
        //   - Windows: the reader stays blocked in read(), so the drain is cut short and the
        //     output must be FLAGGED as possibly partial.
        //   - Linux: the JDK process reaper drains the buffered bytes and closes the pipe when the
        //     process exits, so the reader finishes at once with the output COMPLETE.
        val start = System.currentTimeMillis()
        val execution = runner.run("echo compile-error; sleep 20 &", workingDir.toFile(), 30)
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(0, execution.exitCode, "the command itself succeeded; only the pipe stayed open")
        assertFalse(execution.timedOut, "the process finished well inside the timeout")
        assertTrue(
            elapsedMs < 20_000,
            "the call must not wait for the grandchild to exit, took ${elapsedMs}ms",
        )
        assertTrue(
            execution.outputTruncated || execution.output.contains("compile-error"),
            "output reported as complete must actually be complete, was '${execution.output}'",
        )
    }
}
