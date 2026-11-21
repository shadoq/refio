package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The only tests in this package that spawn real OS processes: the drain contract between the
 * reader thread and the caller cannot be observed through a fake runner. Most are skipped on
 * Windows - their commands are POSIX shell; the default-shell test runs on Windows only.
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
    @EnabledOnOs(OS.WINDOWS)
    fun `by default a command runs the way the agent's terminal runs it`() {
        // The check after a turn runs the command the agent itself would use. On Windows that is
        // PowerShell, which resolves ./gradlew to gradlew.bat; cmd.exe rejected it within
        // milliseconds, so every Gradle project's check failed before any build had started.
        Files.writeString(workingDir.resolve("wrapper.bat"), "@echo wrapper-ran\r\n")

        val execution = runner.run("./wrapper build", workingDir.toFile(), 60)

        assertEquals(0, execution.exitCode, "output was: ${execution.output}")
        assertTrue(execution.output.contains("wrapper-ran"), "output was: ${execution.output}")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a background child left holding the output does not hang the check on Windows`() {
        // Gradle leaves its daemon running with the check's output pipe inherited. On Windows,
        // closing that pipe from our side blocks for as long as the reader is inside read(), so
        // the check hung until the daemon exited - for an hour and a half in one e2e run. The
        // child here, like the daemon, writes nothing and keeps the pipe open for 30 s; cmd stays
        // alive long enough to be seen.
        val start = System.currentTimeMillis()
        val execution = runner.run(
            "cmd /c \"start /b powershell -NoProfile -Command Start-Sleep 30 & ping -n 2 127.0.0.1 > nul\"; Write-Output check-done",
            workingDir.toFile(),
            60,
        )
        val elapsedMs = System.currentTimeMillis() - start

        assertFalse(execution.timedOut, "the command itself finished, output: ${execution.output}")
        assertTrue(elapsedMs < 15_000, "the check waited ${elapsedMs}ms for the background child")
        assertTrue(execution.output.contains("check-done"), "output was: ${execution.output}")
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
