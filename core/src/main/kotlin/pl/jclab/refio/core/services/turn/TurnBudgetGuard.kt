package pl.jclab.refio.core.services.turn

/**
 * Pure decision for the per-turn token and wall-clock budgets
 * (`agent.max_turn_tokens` / `agent.max_turn_minutes`). A non-positive limit disables that budget.
 *
 * Sits next to [CostLimitGuard] and exists because that guard cannot hold a local model back: an
 * Ollama call costs nothing, so a dollar ceiling never trips and the iteration cap becomes the only
 * brake. Raising that cap therefore removes the last one. These two budgets are denominated in units
 * that mean the same thing whether the model is billed or local.
 *
 * The two catch different failures. Tokens bound a turn that keeps generating without converging;
 * wall-clock bounds a turn that is stuck waiting instead of generating, where the token count barely
 * moves. Either one alone leaves the other case open.
 *
 * Checked at the top of each iteration, so the turn stops before paying for another LLM call.
 */
object TurnBudgetGuard {

    /** Why the turn was stopped, or null when both budgets still have room. */
    sealed interface Breach {
        data class Tokens(val used: Long, val limit: Long) : Breach
        data class Time(val elapsedMinutes: Long, val limitMinutes: Long) : Breach
    }

    /**
     * @param tokensUsed total tokens (in + out) consumed by this turn so far
     * @param elapsedMillis wall-clock time since the turn started
     */
    fun check(
        tokensUsed: Long,
        maxTokens: Long,
        elapsedMillis: Long,
        maxMinutes: Long,
    ): Breach? {
        if (maxTokens > 0L && tokensUsed >= maxTokens) {
            return Breach.Tokens(tokensUsed, maxTokens)
        }
        if (maxMinutes > 0L) {
            val elapsedMinutes = elapsedMillis / 60_000L
            if (elapsedMinutes >= maxMinutes) {
                return Breach.Time(elapsedMinutes, maxMinutes)
            }
        }
        return null
    }

    /** Operator-facing reason, mirroring the shape of the cost-limit message. */
    fun describe(breach: Breach): String = when (breach) {
        is Breach.Tokens ->
            "TURN_TOKEN_LIMIT_EXCEEDED: this turn used ${breach.used} tokens, reaching the " +
                "agent.max_turn_tokens ceiling of ${breach.limit}. Stopping the turn."
        is Breach.Time ->
            "TURN_TIME_LIMIT_EXCEEDED: this turn ran for ${breach.elapsedMinutes} minutes, reaching " +
                "the agent.max_turn_minutes ceiling of ${breach.limitMinutes}. Stopping the turn."
    }
}
