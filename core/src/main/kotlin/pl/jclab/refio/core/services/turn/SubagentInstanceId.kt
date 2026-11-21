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

/**
 * [overrides] carrying the instance id this run is stored under. Resolved once at the start of the
 * run, so the prompt row, the replies, the tool steps and the working memory all land in one thread.
 * Without it a subagent spawned without an id stored its prompt in the parent thread and its steps
 * as if the parent had taken them.
 */
internal fun withResolvedInstanceId(overrides: TurnProfileOverrides?): TurnProfileOverrides? {
    val instanceId = resolveSubagentInstanceId(overrides) ?: return overrides
    return if (overrides?.agentInstanceId == instanceId) overrides else overrides?.copy(agentInstanceId = instanceId)
}

/**
 * Nesting level stored on every row of a subagent run, null for the main agent. The chat view indents
 * a row by it: 1 is a subagent called by the main agent, 2 one called by that subagent. invoke_subagent
 * already passes the child's level as [TurnProfileOverrides.depth]; a subagent the user starts from the
 * prompt runs at depth 0 and is still one level below the main agent.
 */
internal fun storedAgentDepth(overrides: TurnProfileOverrides?): Int? =
    overrides?.subagentName?.let { maxOf(overrides.depth, 1) }
