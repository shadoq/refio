package pl.jclab.refio.core.services.turn

/**
 * The turn's budget as the model sees it: iterations and minutes used and left, plus a warning once
 * the nearer of the two limits is half, four fifths or nineteen twentieths used.
 *
 * The model cannot see the clock. On a local model the time limit (`agent.max_turn_minutes`) is
 * usually the one that ends the turn, long before the iteration cap, so without this the model plans
 * as if time were unlimited and is cut off in the middle of a step.
 */
object TurnBudgetStatus {

    private const val NOTE_AT = 0.5
    private const val WARNING_AT = 0.8
    private const val CRITICAL_AT = 0.95
    private const val MINUTE_MS = 60_000L

    /**
     * @param iteration the iteration about to run, counted from 1
     * @param elapsedMs time since the turn started
     * @param maxMinutes the turn's time limit; 0 or less means none, and then no minutes are shown
     */
    fun render(iteration: Int, maxIterations: Int, elapsedMs: Long, maxMinutes: Long): String {
        val lines = mutableListOf("Iteration $iteration of $maxIterations, ${(maxIterations - iteration).coerceAtLeast(0)} left.")
        val iterationShare = if (maxIterations > 0) iteration.toDouble() / maxIterations else 0.0

        var timeShare = 0.0
        if (maxMinutes > 0) {
            val limitMs = maxMinutes * MINUTE_MS
            val elapsed = elapsedMs.coerceAtLeast(0L)
            timeShare = elapsed.toDouble() / limitMs
            lines += "Time: ${elapsed / MINUTE_MS} of $maxMinutes minutes used, ${(limitMs - elapsed).coerceAtLeast(0L) / MINUTE_MS} left."
        }

        val nearer = if (timeShare > iterationShare) "time" to timeShare else "iterations" to iterationShare
        warning(limit = nearer.first, share = nearer.second)?.let { lines += it }
        return "<turn_status>\n${lines.joinToString("\n")}\n</turn_status>"
    }

    private fun warning(limit: String, share: Double): String? {
        val used = "${(share * 100).toInt().coerceAtMost(100)}% of this turn's $limit ${if (limit == "time") "is" else "are"} used"
        return when {
            share >= CRITICAL_AT ->
                "CRITICAL: $used and the turn ends very soon. Finish the step in progress, then reply with what is done and what is left. Start nothing new."
            share >= WARNING_AT -> "WARNING: $used. Finish and verify the work in progress before starting anything new."
            share >= NOTE_AT -> "Note: $used. Plan the remaining work to fit."
            else -> null
        }
    }
}
