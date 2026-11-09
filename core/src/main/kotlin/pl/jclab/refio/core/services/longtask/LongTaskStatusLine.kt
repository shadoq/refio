package pl.jclab.refio.core.services.longtask

/** The one-line state of a running long task, shown under the prompt in the TUI and the IDE. */
object LongTaskStatusLine {

    /** E.g. "round 2/12 · 47m of 4h · until: npm test · round 1: check failed (exit 1, 5 error lines)". */
    fun of(state: LongTaskState, round: Int = state.roundsDone): String {
        val spec = state.spec
        val time = if (spec.maxMinutes > 0) "${duration(state.activeMs / MS_PER_MINUTE)} of ${duration(spec.maxMinutes)}" else duration(state.activeMs / MS_PER_MINUTE)
        val condition = spec.untilCommand?.let { "until: $it" } ?: "goal"
        val last = state.rounds.lastOrNull()?.let { " · round ${it.round}: ${check(it)}" }.orEmpty()
        return "round $round/${spec.maxRounds} · $time · $condition$last"
    }

    private fun check(record: RoundRecord): String = when (record.checkPassed) {
        true -> "check passed"
        false -> "check failed" + (record.exitCode?.let { code -> " (exit $code" + (record.errorLines?.let { ", $it error lines" } ?: "") + ")" } ?: "")
        null -> "not checked"
    }

    private fun duration(minutes: Long): String = when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    private const val MS_PER_MINUTE = 60_000L
}
