package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnProfileOverrides
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Isolation contract for subagent chat history. A subagent invocation must be tagged with its own
 * instance id so its intermediate steps never leak into the parent conversation; the main (parent)
 * turn must stay untagged so it owns the shared thread.
 */
class SubagentInstanceIdTest {

    @Test
    fun `main turn stays untagged so it owns the parent thread`() {
        val mainTurn = TurnProfileOverrides(subagentName = null, agentInstanceId = null)

        assertNull(resolveSubagentInstanceId(mainTurn))
    }

    @Test
    fun `an explicit invocation id is preserved verbatim`() {
        val overrides = TurnProfileOverrides(subagentName = "reviewer", agentInstanceId = "fixed-id-123")

        assertEquals("fixed-id-123", resolveSubagentInstanceId(overrides))
    }

    @Test
    fun `a subagent turn missing an id is still isolated by a generated one`() {
        // The invoke_subagent tool path historically forgot to assign an id; the backstop guarantees
        // the subagent is isolated regardless of which path spawned it.
        val untaggedSubagent = TurnProfileOverrides(subagentName = "reviewer", agentInstanceId = null)

        assertNotNull(
            resolveSubagentInstanceId(untaggedSubagent),
            "a subagent turn must never persist a null instance id - that mixes it into the parent"
        )
    }

    @Test
    fun `two untagged subagent turns get distinct isolation ids`() {
        val first = resolveSubagentInstanceId(TurnProfileOverrides(subagentName = "reviewer", agentInstanceId = null))
        val second = resolveSubagentInstanceId(TurnProfileOverrides(subagentName = "reviewer", agentInstanceId = null))

        assertNotEquals(first, second, "sibling subagent invocations must not share a history bucket")
    }

    @Test
    fun `a null overrides resolves to the parent thread`() {
        assertNull(resolveSubagentInstanceId(null))
    }
}
