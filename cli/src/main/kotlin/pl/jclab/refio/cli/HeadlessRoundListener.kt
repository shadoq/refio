package pl.jclab.refio.cli

import pl.jclab.refio.core.services.longtask.LongTaskListener
import pl.jclab.refio.core.services.longtask.LongTaskState
import pl.jclab.refio.core.services.longtask.RoundRecord

/** Prints the rounds of a headless long task to stderr, next to the turn progress lines. */
internal class HeadlessRoundListener : LongTaskListener {

    override fun onRoundStarted(round: Int, state: LongTaskState) {
        val condition = state.spec.untilCommand?.let { "until: $it" } ?: "goal"
        System.err.println("▶ round $round/${state.spec.maxRounds} ($condition)")
    }

    override fun onRoundFinished(record: RoundRecord, state: LongTaskState) {
        System.err.println("■ round ${record.round}: ${roundLine(record)}")
    }

    companion object {
        internal fun roundLine(record: RoundRecord): String {
            val check = when (record.checkPassed) {
                true -> "check passed"
                false -> "check failed" + (record.exitCode?.let { " (exit $it" + (record.errorLines?.let { n -> ", $n error lines" } ?: "") + ")" } ?: "")
                null -> "not checked (${record.turnStopReason.lowercase()})"
            }
            val progress = if (record.progress) "" else ", no progress"
            return "$check, ${record.filesWritten} files written, ${record.durationMs / 60_000} min$progress"
        }
    }
}
