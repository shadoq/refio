package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    @Test
    fun `a command stopped at the timeout takes its child processes with it`() {
        assumePosixShell()

        val execution = runner.run(BACKGROUND_CHILD, workingDir.toFile(), 1)

        assertTrue(execution.timedOut)
        assertProcessGone(childPid())
    }

    @Test
    fun `an interrupted wait stops the command and its children`() {
        assumePosixShell()
        var thrown: Throwable? = null
        val caller = Thread {
            try {
                runner.run(BACKGROUND_CHILD, workingDir.toFile(), 60)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        caller.start()
        val pid = childPid()

        caller.interrupt()
        caller.join(10_000)

        assertFalse(caller.isAlive, "the call returned instead of waiting out its 60 s")
        assertTrue(thrown is InterruptedException, "was $thrown")
        assertProcessGone(pid)
    }

    /** Pid of the background child [BACKGROUND_CHILD] started, once the shell has written it. */
    private fun childPid(): Long {
        val file = workingDir.resolve("child.pid")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { Files.readString(file).trim() }.getOrNull()
            if (!text.isNullOrEmpty()) {
                return text.toLong()
            }
            Thread.sleep(50)
        }
        error("the command did not write its child's pid")
    }

    private fun assertProcessGone(pid: Long) {
        fun alive() = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        val deadline = System.currentTimeMillis() + 5_000
        while (alive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse(alive(), "child process $pid is still running")
    }

    private companion object {
        /** A shell that starts a long child, records its pid and waits for it. */
        const val BACKGROUND_CHILD = "sleep 30 & echo \$! > child.pid; wait"
    }

    @Test
    fun `a command that prints without end keeps only the end of its output`() {
        assumePosixShell()
        // A hung test that logs in a loop can print gigabytes before the timeout. Holding all of it
        // ran the IDE out of memory, while the next round is only ever shown the end.
        val execution = runner.run("yes 0123456789 | head -c 3000000; echo LAST-LINE", workingDir.toFile(), 60)

        assertTrue(
            execution.output.length <= ProcessVerificationCommandRunner.MAX_OUTPUT_CHARS + 100,
            "kept ${execution.output.length} characters",
        )
        assertTrue(execution.output.trimEnd().endsWith("LAST-LINE"), "the end of the output stays")
    }

    @Test
    fun `the command runs in the shell the runner was given`() {
        assumePosixShell()
        // The until check must run a command the way the agent's own terminal does; on Windows that
        // is PowerShell, where cmd.exe failed on commands like ./gradlew test in every round.
        val terminalLike = ProcessVerificationCommandRunner(shellCommand = { command -> listOf("/bin/sh", "-c", "echo terminal-shell: $command") })

        val execution = terminalLike.run("check-command", workingDir.toFile(), 30)

        assertEquals("terminal-shell: check-command", execution.output.trim())
    }
}
