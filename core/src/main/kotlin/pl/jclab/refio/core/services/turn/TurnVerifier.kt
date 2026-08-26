package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger = dualLogger("TurnVerifier")

/**
 * Raw result of one verification command execution.
 */
data class VerificationExecution(
    val exitCode: Int,
    /** Combined stdout + stderr of the command. */
    val output: String,
    val timedOut: Boolean = false,
    /**
     * True when the runner gave up before reading the command's output to the end, so [output] is
     * an unknown subset of what the command printed.
     */
    val outputTruncated: Boolean = false,
)

/**
 * Executes the verification command as an OS process. Injectable so unit tests can substitute a
 * fake and never spawn real processes.
 */
interface VerificationCommandRunner {
    fun run(command: String, workingDir: File, timeoutSeconds: Int): VerificationExecution
}

/**
 * Default [VerificationCommandRunner]: runs the command through the platform shell with the
 * project root as working directory, merged stdout/stderr, and a hard timeout after which the
 * process tree is killed.
 */
class ProcessVerificationCommandRunner : VerificationCommandRunner {

    override fun run(command: String, workingDir: File, timeoutSeconds: Int): VerificationExecution {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shellCommand = if (isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }
        val process = ProcessBuilder(shellCommand)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        // Drain output on a separate thread so a chatty build cannot deadlock on a full pipe
        // buffer while we block in waitFor. StringBuffer, not StringBuilder: the reader may still
        // be appending when this thread snapshots the text (see [awaitReader]), and Thread.join
        // with a timeout neither reports success nor establishes happens-before, so a plain
        // builder would be read unsynchronized and yield an arbitrarily mangled excerpt.
        val output = StringBuffer()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    output.appendLine(line)
                }
            } catch (e: IOException) {
                // Expected when [awaitReader] closes the pipe to unblock a reader that outlived
                // the process; anything already drained stays in `output`.
                logger.debug { "[VERIFY] output reader stopped: ${e.message}" }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
        }
        val fullyDrained = awaitReader(reader, process)
        return VerificationExecution(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.toString(),
            timedOut = !finished,
            outputTruncated = !fullyDrained,
        )
    }

    /**
     * Wait for the drain thread to finish and report whether it did.
     *
     * The process having exited does NOT close the pipe: a grandchild that inherited the write end
     * (a backgrounded server, a detached build daemon) keeps it open, and the reader then blocks in
     * read() indefinitely. Closing our end is a best-effort attempt to release it - on some
     * platforms a thread already blocked in read() is not woken - so we never wait on the second
     * join for long, and report the output as incomplete rather than pretending it is whole.
     */
    private fun awaitReader(reader: Thread, process: Process): Boolean {
        reader.join(READER_JOIN_MS)
        if (!reader.isAlive) {
            return true
        }
        runCatching { process.inputStream.close() }
        reader.join(READER_CLOSE_JOIN_MS)
        logger.warn {
            "[VERIFY] output reader did not finish within ${READER_JOIN_MS}ms - the command left the " +
                "pipe open (likely a surviving background child); reported output may be incomplete"
        }
        return false
    }

    companion object {
        /** Grace period for a killed process to actually die before we stop waiting on it. */
        private const val KILL_GRACE_SECONDS = 5L

        /** How long to wait for the drain thread after the process exits. */
        private const val READER_JOIN_MS = 2000L

        /** Extra wait after closing the pipe, only to let an unblocked reader publish its last line. */
        private const val READER_CLOSE_JOIN_MS = 200L
    }
}

/**
 * Deterministic post-turn verification: after the agent finishes a turn that wrote files, the
 * LOOP CODE (not the model) runs the project's build/test command. The exit code is a hard fact:
 * it either confirms the deliverable or produces a concrete error list the model must repair.
 *
 * The command comes from `verify.command` (project-scope config); when absent it is autodetected
 * from project marker files (Gradle / npm / Cargo), and when nothing is detected verification is
 * silently skipped. `verify.enabled=false` disables the whole step.
 */
class TurnVerifier(
    private val configService: ConfigService,
    private val projectRoot: Path?,
    private val runner: VerificationCommandRunner = ProcessVerificationCommandRunner(),
) {

    /** Result of the pre-write baseline run, kept per task for the lifetime of this verifier. */
    private enum class Baseline { PASSED, FAILED }

    private val baselines = java.util.concurrent.ConcurrentHashMap<String, Baseline>()

    sealed class Outcome {
        /** Verification did not run (disabled, no project root, or no command found). */
        data class Skipped(val reason: String) : Outcome()

        /** The verification command exited 0. */
        object Passed : Outcome()

        /** The verification command failed; [errors] holds only the extracted error lines. */
        data class Failed(
            val exitCode: Int,
            val errors: List<String>,
            val timedOut: Boolean = false,
        ) : Outcome()
    }

    /** Maximum repair rounds (fail -> feed errors back -> re-verify) before giving up. */
    fun maxRepairRounds(taskId: String): Int =
        configService.getTyped(ConfigKeys.VERIFY_MAX_REPAIR_ROUNDS, taskId)

    /**
     * Capture the verification result of the UNMODIFIED project, called once per task right
     * before the turn's first file write executes. Without this baseline a verification failure
     * cannot be attributed: a project whose build/test command was already failing (missing test
     * script, broken fixture, red build) would blame the agent for an environmental problem and
     * push it into a repair loop it can never win. No-op when verification is disabled, no
     * command resolves, or a baseline for this task already exists.
     */
    suspend fun captureBaseline(taskId: String) {
        if (baselines.containsKey(taskId)) {
            return
        }
        if (!configService.getTyped(ConfigKeys.VERIFY_ENABLED, taskId)) {
            return
        }
        val root = projectRoot?.toFile() ?: return
        val command = resolveCommand(taskId, root) ?: return
        val timeoutSeconds = configService.getTyped(ConfigKeys.TOOL_EXECUTION_TIMEOUT, taskId)
        logger.info { "[VERIFY_BASELINE] taskId=$taskId running '$command' before first write (timeout=${timeoutSeconds}s)" }
        val execution = withContext(Dispatchers.IO) {
            runner.run(command, root, timeoutSeconds)
        }
        val baseline = if (!execution.timedOut && execution.exitCode == 0) Baseline.PASSED else Baseline.FAILED
        baselines[taskId] = baseline
        logger.info {
            "[VERIFY_BASELINE] taskId=$taskId baseline=$baseline " +
                "(exit=${execution.exitCode}, timedOut=${execution.timedOut})"
        }
    }

    /**
     * Run the verification command for the project, if one is enabled and resolvable.
     */
    suspend fun verify(taskId: String): Outcome {
        if (!configService.getTyped(ConfigKeys.VERIFY_ENABLED, taskId)) {
            return Outcome.Skipped("verification disabled via verify.enabled")
        }
        val root = projectRoot?.toFile()
            ?: return Outcome.Skipped("no project root available")
        val command = resolveCommand(taskId, root)
            ?: return Outcome.Skipped("no verify.command configured and no known project marker detected")
        val timeoutSeconds = configService.getTyped(ConfigKeys.TOOL_EXECUTION_TIMEOUT, taskId)
        logger.info { "[VERIFY] taskId=$taskId running '$command' in ${root.absolutePath} (timeout=${timeoutSeconds}s)" }
        val execution = withContext(Dispatchers.IO) {
            runner.run(command, root, timeoutSeconds)
        }
        if (!execution.timedOut && execution.exitCode == 0) {
            logger.info { "[VERIFY] taskId=$taskId passed (exit 0)" }
            return Outcome.Passed
        }
        if (baselines[taskId] == Baseline.FAILED) {
            logger.warn {
                "[VERIFY] taskId=$taskId failed (exit=${execution.exitCode}) but the pre-write " +
                    "baseline was already failing - skipping, not attributable to this turn"
            }
            return Outcome.Skipped(
                "verification command '$command' was already failing before this turn's changes - " +
                    "failure not attributable to the agent"
            )
        }
        val errors = extractErrorLines(execution.output).ifEmpty {
            listOf(
                if (execution.timedOut) {
                    "Verification command '$command' timed out after ${timeoutSeconds}s."
                } else {
                    "Verification command '$command' exited with code ${execution.exitCode} (no recognizable error lines in output)."
                }
            )
        } + if (execution.outputTruncated) {
            // The model is told to repair exactly this list. If we could not read the command's
            // output to the end, a silently short list reads as "these are all the failures".
            listOf(TRUNCATED_OUTPUT_NOTE)
        } else {
            emptyList()
        }
        logger.warn {
            "[VERIFY] taskId=$taskId failed (exit=${execution.exitCode}, timedOut=${execution.timedOut}, " +
                "truncated=${execution.outputTruncated}, errors=${errors.size})"
        }
        return Outcome.Failed(exitCode = execution.exitCode, errors = errors, timedOut = execution.timedOut)
    }

    /**
     * The command to run: explicit `verify.command` wins; otherwise autodetected from project
     * marker files; null when neither yields a command.
     */
    internal fun resolveCommand(taskId: String, root: File): String? {
        val configured = configService.getTyped(ConfigKeys.VERIFY_COMMAND, taskId)
        if (configured.isNotBlank()) {
            return configured
        }
        return autodetectCommand(root)
    }

    companion object {
        /** Cap on error lines fed back to the model; full build output never enters the context. */
        const val MAX_ERROR_LINES = 50

        /** Appended to the error list when the command's output could not be read to the end. */
        const val TRUNCATED_OUTPUT_NOTE =
            "Note: the verification output could not be read to the end (the command left a " +
                "background process holding its output stream), so this error list may be incomplete."

        /**
         * Autodetect the verification command from well-known project marker files.
         * Returns null when the project type is not recognized (verification then skips).
         */
        fun autodetectCommand(root: File): String? = when {
            File(root, "build.gradle").exists() || File(root, "build.gradle.kts").exists() ->
                "./gradlew build -q"
            File(root, "package.json").exists() -> "npm test --silent"
            File(root, "Cargo.toml").exists() -> "cargo build -q"
            else -> null
        }

        /**
         * Extract only the error lines from build output (Kotlin "e: ", javac/cargo "error:",
         * Gradle test "FAILED"), capped at [MAX_ERROR_LINES].
         */
        fun extractErrorLines(output: String): List<String> =
            output.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.startsWith("e: ") || line.contains("error:") || line.contains("FAILED")
                }
                .take(MAX_ERROR_LINES)
                .toList()
    }
}
