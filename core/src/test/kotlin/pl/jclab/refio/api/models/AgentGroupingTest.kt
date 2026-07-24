package pl.jclab.refio.api.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentGroupingTest {

    private var seq = 0
    private fun msg(
        agentName: String? = null,
        agentInstanceId: String? = null,
    ) = Message(
        id = "m${seq++}",
        taskId = "t1",
        role = "assistant",
        content = "x",
        createdAt = 0L,
        agentName = agentName,
        agentInstanceId = agentInstanceId,
    )

    // The header opens each agent run and only its first message; a run of the same invocation
    // must not repeat the header on every bubble.
    @Test
    fun `header shows only on the first message of a contiguous agent run`() {
        val messages = listOf(
            msg(),                                              // top-level: no header
            msg(agentName = "reviewer", agentInstanceId = "a"), // opens run a -> header
            msg(agentName = "reviewer", agentInstanceId = "a"), // same run -> no header
            msg(agentName = "reviewer", agentInstanceId = "a"), // same run -> no header
        )

        assertEquals(listOf(false, true, false, false), AgentGrouping.showHeaderFlags(messages))
    }

    // Two separate invocations of the SAME subagent must each get their own header, so the user
    // can tell the runs apart - this is why the key is the instance id, not the name.
    @Test
    fun `two invocations of the same agent each open a new run`() {
        val messages = listOf(
            msg(agentName = "reviewer", agentInstanceId = "a"),
            msg(agentName = "reviewer", agentInstanceId = "a"),
            msg(agentName = "reviewer", agentInstanceId = "b"),
        )

        assertEquals(listOf(true, false, true), AgentGrouping.showHeaderFlags(messages))
    }

    // A non-agent (top-level) message between two runs of the same agent resets grouping, so the
    // second run re-emits its header instead of silently merging across the interruption.
    @Test
    fun `a top-level message between runs resets grouping`() {
        val messages = listOf(
            msg(agentName = "reviewer", agentInstanceId = "a"),
            msg(),                                              // interruption
            msg(agentName = "reviewer", agentInstanceId = "a"), // must re-show header
        )

        assertEquals(listOf(true, false, true), AgentGrouping.showHeaderFlags(messages))
    }

    // Legacy rows without an instance id fall back to the name so they still group by agent.
    @Test
    fun `legacy rows without instance id group by name`() {
        val messages = listOf(
            msg(agentName = "planner"),
            msg(agentName = "planner"),
            msg(agentName = "reviewer"),
        )

        assertEquals(listOf(true, false, true), AgentGrouping.showHeaderFlags(messages))
    }
}
