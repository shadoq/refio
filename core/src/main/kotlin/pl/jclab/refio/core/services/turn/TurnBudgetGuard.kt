package pl.jclab.refio.core.services.turn

/**
 * Pure decision for the per-turn wall-clock budget (`agent.max_turn_minutes`). A non-positive limit
 * disables it.
 *
 * Sits next to [CostLimitGuard] and exists because that guard cannot hold a local model back: an
 * Ollama call costs nothing, so a dollar ceiling never trips and the iteration cap becomes the only
 * brake. Minutes mean the same thing whether the model is billed or local.
 *
 * A token ceiling used to live here as well and was removed: a long turn on a local model is normal
 * (the tokens are free), so it fired on healthy runs far more often than on runaway ones. What it
 * was meant to catch - a turn that never converges - is bounded by the iteration cap and by this
 * wall-clock budget, neither of which punishes a turn for simply being large.
 *
 * Checked at the top of each iteration, so the turn stops before paying for another LLM call.
 */
object TurnBudgetGuard {

    /** Why the turn was stopped, or null when the budget still has room. */
    data class Breach(val elapsedMinutes: Long, val limitMinutes: Long)

    /**
     * @param elapsedMillis wall-clock time since the turn started
     */
    fun check(
        elapsedMillis: Long,
        maxMinutes: Long,
    ): Breach? {
        if (maxMinutes <= 0L) return null
        val elapsedMinutes = elapsedMillis / 60_000L
        return if (elapsedMinutes >= maxMinutes) Breach(elapsedMinutes, maxMinutes) else null
    }

    /** Operator-facing reason, mirroring the shape of the cost-limit message. */
    fun describe(breach: Breach): String =
        "TURN_TIME_LIMIT_EXCEEDED: this turn ran for ${breach.elapsedMinutes} minutes, reaching " +
            "the agent.max_turn_minutes ceiling of ${breach.limitMinutes}. Stopping the turn."
}
