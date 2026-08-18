package pl.jclab.refio.core.services.turn

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic post-turn verification: command resolution (config vs marker-file autodetection),
 * error-line extraction, and pass/fail/skip outcomes. Process execution is faked - no real
 * processes are ever spawned here.
 */
class TurnVerifierTest {

    @TempDir
    lateinit var projectDir: Path

    private fun configService(
        enabled: Boolean = true,
        command: String = "",
        maxRepairRounds: Int = 2,
        timeoutSeconds: Int = 360,
    ): ConfigService {
        val configService = mockk<ConfigService>()
        every { configService.getTyped(ConfigKeys.VERIFY_ENABLED, any()) } returns enabled
        every { configService.getTyped(ConfigKeys.VERIFY_COMMAND, any()) } returns command
        every { configService.getTyped(ConfigKeys.VERIFY_MAX_REPAIR_ROUNDS, any()) } returns maxRepairRounds
        every { configService.getTyped(ConfigKeys.TOOL_EXECUTION_TIMEOUT, any()) } returns timeoutSeconds
        return configService
    }

    /** Fake runner: records invocations and replays queued executions (last one repeats). */
    private class FakeRunner(vararg executions: VerificationExecution) : VerificationCommandRunner {
        val invocations = mutableListOf<String>()
        private val queue = executions.toMutableList()

        override fun run(command: String, workingDir: File, timeoutSeconds: Int): VerificationExecution {
            invocations.add(command)
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    // ---- command autodetection: the marker file decides the build tool ----

    @Test
    fun `gradle project autodetects gradlew build`() {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        assertEquals("./gradlew build -q", TurnVerifier.autodetectCommand(projectDir.toFile()))
    }

    @Test
    fun `kotlin dsl gradle project autodetects gradlew build`() {
        projectDir.resolve("build.gradle.kts").toFile().writeText("// gradle kts")
        assertEquals("./gradlew build -q", TurnVerifier.autodetectCommand(projectDir.toFile()))
    }

    @Test
    fun `npm project autodetects npm test`() {
        projectDir.resolve("package.json").toFile().writeText("{}")
        assertEquals("npm test --silent", TurnVerifier.autodetectCommand(projectDir.toFile()))
    }

    @Test
    fun `cargo project autodetects cargo build`() {
        projectDir.resolve("Cargo.toml").toFile().writeText("[package]")
        assertEquals("cargo build -q", TurnVerifier.autodetectCommand(projectDir.toFile()))
    }

    @Test
    fun `unknown project type yields no command so verification stays off`() {
        assertNull(TurnVerifier.autodetectCommand(projectDir.toFile()))
    }

    @Test
    fun `explicit verify command wins over marker-file autodetection`() {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val verifier = TurnVerifier(configService(command = "make check"), projectDir, FakeRunner())
        assertEquals("make check", verifier.resolveCommand("task-1", projectDir.toFile()))
    }

    // ---- outcomes ----

    @Test
    fun `disabled config skips verification without running anything`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val runner = FakeRunner(VerificationExecution(exitCode = 1, output = "e: broken"))
        val verifier = TurnVerifier(configService(enabled = false), projectDir, runner)

        val outcome = verifier.verify("task-1")

        assertTrue(outcome is TurnVerifier.Outcome.Skipped)
        assertTrue(runner.invocations.isEmpty(), "disabled verification must not spawn the command")
    }

    @Test
    fun `missing project root skips verification`() = runTest {
        val runner = FakeRunner(VerificationExecution(exitCode = 0, output = ""))
        val verifier = TurnVerifier(configService(), projectRoot = null, runner = runner)

        assertTrue(verifier.verify("task-1") is TurnVerifier.Outcome.Skipped)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun `no config and no marker file skips verification`() = runTest {
        val runner = FakeRunner(VerificationExecution(exitCode = 1, output = "e: broken"))
        val verifier = TurnVerifier(configService(), projectDir, runner)

        assertTrue(verifier.verify("task-1") is TurnVerifier.Outcome.Skipped)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun `exit code zero passes`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"))
        )

        assertTrue(verifier.verify("task-1") is TurnVerifier.Outcome.Passed)
    }

    @Test
    fun `failed build feeds back only the error lines, never the full output`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val buildOutput = """
            > Task :core:compileKotlin
            some informational chatter
            e: file.kt:12:5 Unresolved reference: fooBar
            more chatter that must never reach the model
            MyTest > shouldWork FAILED
            BUILD FAILED in 4s
        """.trimIndent()
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = 1, output = buildOutput))
        )

        val outcome = verifier.verify("task-1") as TurnVerifier.Outcome.Failed

        assertEquals(1, outcome.exitCode)
        assertTrue(outcome.errors.any { it.contains("Unresolved reference: fooBar") })
        assertTrue(outcome.errors.any { it.contains("shouldWork FAILED") })
        assertFalse(outcome.errors.any { it.contains("chatter") }, "non-error lines must be filtered out")
    }

    @Test
    fun `error lines are capped so a broken build cannot flood the context`() {
        val output = (1..80).joinToString("\n") { "e: file.kt:$it:1 error number $it" }
        assertEquals(TurnVerifier.MAX_ERROR_LINES, TurnVerifier.extractErrorLines(output).size)
    }

    @Test
    fun `failure without recognizable error lines still reports the exit code`() = runTest {
        projectDir.resolve("Cargo.toml").toFile().writeText("[package]")
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = 3, output = "something odd happened"))
        )

        val outcome = verifier.verify("task-1") as TurnVerifier.Outcome.Failed

        assertEquals(1, outcome.errors.size)
        assertTrue(outcome.errors.single().contains("exited with code 3"))
    }

    @Test
    fun `timed-out command fails with a timeout message instead of faking success`() = runTest {
        projectDir.resolve("package.json").toFile().writeText("{}")
        val verifier = TurnVerifier(
            configService(timeoutSeconds = 5),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = -1, output = "", timedOut = true))
        )

        val outcome = verifier.verify("task-1") as TurnVerifier.Outcome.Failed

        assertTrue(outcome.timedOut)
        assertTrue(outcome.errors.single().contains("timed out"))
    }

    @Test
    fun `an output that could not be fully drained says so instead of shipping a silent excerpt`() = runTest {
        // The error list is what the model is told to repair. When the runner could not drain the
        // command's output to the end, that list is an unknown subset of the real errors, and a
        // silently short list reads as "these are all the failures". Say it out loud.
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(
                VerificationExecution(
                    exitCode = 1,
                    output = "e: Main.kt:1:1 Unresolved reference: fooBar",
                    outputTruncated = true,
                )
            )
        )

        val outcome = verifier.verify("task-1") as TurnVerifier.Outcome.Failed

        assertTrue(outcome.errors.any { it.contains("Unresolved reference: fooBar") })
        assertTrue(
            outcome.errors.any { it.contains("may be incomplete", ignoreCase = true) },
            "the model must be told the error list is partial, got: ${outcome.errors}",
        )
    }

    @Test
    fun `a fully drained failure carries no incompleteness note`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = 1, output = "e: Main.kt:1:1 broken")),
        )

        val outcome = verifier.verify("task-1") as TurnVerifier.Outcome.Failed

        assertFalse(
            outcome.errors.any { it.contains("may be incomplete", ignoreCase = true) },
            "a complete error list must not warn about truncation, got: ${outcome.errors}",
        )
    }

    // ---- pre-write baseline: an already-red project is not blamed on the agent ----

    @Test
    fun `verify skips when the baseline was already failing before the turn`() = runTest {
        // The project's build/test command is broken independently of the agent (missing test
        // script, red fixture). The agent must not be pushed into an unwinnable repair loop.
        projectDir.resolve("package.json").toFile().writeText("{}")
        val runner = FakeRunner(VerificationExecution(exitCode = 1, output = "npm ERR! missing script: test"))
        val verifier = TurnVerifier(configService(), projectDir, runner)

        verifier.captureBaseline("task-1")
        val outcome = verifier.verify("task-1")

        assertTrue(outcome is TurnVerifier.Outcome.Skipped)
        assertTrue(
            (outcome as TurnVerifier.Outcome.Skipped).reason.contains("already failing"),
            "skip reason must name the pre-existing failure"
        )
    }

    @Test
    fun `verify still fails when the baseline passed but the turn broke the build`() = runTest {
        // Baseline green, post-change red => the agent introduced a regression: real failure.
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val runner = FakeRunner(
            VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"),
            VerificationExecution(exitCode = 1, output = "e: Main.kt:1:1 broken")
        )
        val verifier = TurnVerifier(configService(), projectDir, runner)

        verifier.captureBaseline("task-1")
        val outcome = verifier.verify("task-1")

        assertTrue(outcome is TurnVerifier.Outcome.Failed)
        assertEquals(2, runner.invocations.size, "baseline + finalization = two runs")
    }

    @Test
    fun `captureBaseline runs the command only once per task`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val runner = FakeRunner(VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"))
        val verifier = TurnVerifier(configService(), projectDir, runner)

        verifier.captureBaseline("task-1")
        verifier.captureBaseline("task-1")

        assertEquals(1, runner.invocations.size, "the baseline must be captured at most once per task")
    }

    @Test
    fun `captureBaseline is a no-op when verification is disabled`() = runTest {
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val runner = FakeRunner(VerificationExecution(exitCode = 1, output = "e: broken"))
        val verifier = TurnVerifier(configService(enabled = false), projectDir, runner)

        verifier.captureBaseline("task-1")

        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun `without a captured baseline a failure is still reported as a real failure`() = runTest {
        // Backward compatibility: turns that never call captureBaseline (e.g. the write happened
        // via a path that did not gate on it) keep the original blame-the-agent behaviour.
        projectDir.resolve("build.gradle").toFile().writeText("// gradle")
        val verifier = TurnVerifier(
            configService(),
            projectDir,
            FakeRunner(VerificationExecution(exitCode = 1, output = "e: Main.kt:1:1 broken"))
        )

        assertTrue(verifier.verify("task-1") is TurnVerifier.Outcome.Failed)
    }
}
