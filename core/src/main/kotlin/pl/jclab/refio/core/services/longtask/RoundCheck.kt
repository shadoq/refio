package pl.jclab.refio.core.services.longtask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.GoalVerdicts
import pl.jclab.refio.core.services.turn.TurnVerifier
import pl.jclab.refio.core.services.turn.VerificationCommandRunner
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private val logger = dualLogger("RoundCheck")

/** How the end-of-round check went. [tail] is what the next round is shown of it. */
data class CheckResult(
    val passed: Boolean,
    val exitCode: Int?,
    val errorLines: Int?,
    val tail: String,
    val timedOut: Boolean,
)

/** The condition a long task runs until, checked after every round. */
interface RoundCheck {
    /** Short description for status lines, e.g. "until: npm test". */
    val label: String

    /** Called before each round's turn starts. */
    fun beforeRound() {}

    suspend fun check(turn: TurnResult): CheckResult
}

/**
 * The condition holds when [command] exits with 0. The command runs in [workDir] and is killed after
 * [timeoutSeconds]; the next round sees only the last few kilobytes of what it printed.
 */
class UntilCommandCheck(
    private val runner: VerificationCommandRunner,
    private val workDir: File,
    private val command: String,
    private val timeoutSeconds: Int,
) : RoundCheck {
    override val label: String = "until: $command"

    override suspend fun check(turn: TurnResult): CheckResult {
        val execution = try {
            // Interruptible, so stopping the task also stops a check that is still running.
            runInterruptible(Dispatchers.IO) { runner.run(command, workDir, timeoutSeconds) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "[LONG_TASK] check command could not run: ${e.message}" }
            return CheckResult(
                passed = false, exitCode = null, errorLines = null,
                tail = "The check command could not be started: ${e.message}", timedOut = false,
            )
        }
        val errorLines = TurnVerifier.extractErrorLines(execution.output).size
        if (execution.timedOut) {
            val notice = "The command did not finish within $timeoutSeconds s and was stopped.\n"
            return CheckResult(
                passed = false, exitCode = null, errorLines = errorLines,
                tail = notice + tailOf(execution.output, MAX_TAIL_CHARS - notice.length), timedOut = true,
            )
        }
        return CheckResult(
            passed = execution.exitCode == 0, exitCode = execution.exitCode, errorLines = errorLines,
            tail = tailOf(execution.output, MAX_TAIL_CHARS), timedOut = false,
        )
    }

    companion object {
        /** Most of the check output that reaches the next round's prompt. */
        const val MAX_TAIL_CHARS = 4_096

        private const val CUT_MARKER = "[... earlier output cut ...]\n"

        /** The end of [output] in at most [maxChars] characters, starting at a line boundary when cut. */
        internal fun tailOf(output: String, maxChars: Int): String {
            val text = output.trimEnd()
            if (text.length <= maxChars) {
                return text
            }
            val cut = text.takeLast(maxChars - CUT_MARKER.length)
            val lineStart = cut.indexOf('\n')
            val whole = if (lineStart in 0 until cut.length - 1) cut.substring(lineStart + 1) else cut
            return CUT_MARKER + whole
        }
    }
}

/**
 * The condition holds when the judge at the end of the round's turn found the task's goal met. It
 * reads the verdict that judge already gave and makes no model call of its own.
 */
class GoalJudgeCheck(private val taskId: String, private val goal: String) : RoundCheck {
    override val label: String = "goal: $goal"

    override fun beforeRound() {
        GoalVerdicts.await(taskId)
    }

    override suspend fun check(turn: TurnResult): CheckResult {
        val met = GoalVerdicts.take(taskId) == true && turn.success
        val tail = if (met) {
            "The judge at the end of the turn found the goal met."
        } else {
            "The judge at the end of the turn did not find the goal met yet."
        }
        return CheckResult(passed = met, exitCode = null, errorLines = null, tail = tail, timedOut = false)
    }
}
