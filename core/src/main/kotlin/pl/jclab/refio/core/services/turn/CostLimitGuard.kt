package pl.jclab.refio.core.services.turn

/**
 * Pure decision for the per-session cost budget guard (`--max-cost` / `agent.max_cost_usd`,
 * docs/0063 §6.1). A non-positive [maxCostUsd] disables the guard.
 *
 * Checked at the top of each [pl.jclab.refio.core.services.AgentTurnLoop] iteration against the
 * session's live cost (auto-incremented in `LLMClient.complete`), so the turn stops before paying
 * for another LLM call once the budget is reached.
 */
object CostLimitGuard {
    fun isExceeded(currentCostUsd: Double, maxCostUsd: Double): Boolean =
        maxCostUsd > 0.0 && currentCostUsd >= maxCostUsd
}
