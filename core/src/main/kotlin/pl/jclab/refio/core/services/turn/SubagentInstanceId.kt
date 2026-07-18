package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnProfileOverrides

/**
 * Resolves the agent-instance id persisted on every chat row a turn writes. A non-null id isolates a
 * subagent's history from the parent thread (and from sibling subagents) both in prompt building and
 * in the UI; the main turn intentionally stays null, which marks the parent thread.
 *
 * A subagent turn (a subagent name is present) must always receive an id, even when the caller that
 * spawned it did not assign one - otherwise the subagent's intermediate steps leak into the parent
 * conversation and inflate its token budget.
 */
internal fun resolveSubagentInstanceId(overrides: TurnProfileOverrides?): String? {
    overrides?.agentInstanceId?.let { return it }
    if (overrides?.subagentName != null) {
        return java.util.UUID.randomUUID().toString()
    }
    return null
}
