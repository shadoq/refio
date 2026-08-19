package pl.jclab.refio.core.services

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnLoopConfigTest {

    @Test
    fun `both modes share the same iteration backstop`() {
        assertEquals(200, TurnLoopConfig.agent().maxIterations)
        assertEquals(200, TurnLoopConfig.plan().maxIterations)
    }

    // A tool budget shorter than the work it guards turns into a false failure that costs the whole
    // turn. AGENT gets the longer one because its editing tools generate whole files through a model.
    @Test
    fun `the agent tool budget is the more generous one`() {
        assertEquals(Duration.ofMinutes(2), TurnLoopConfig.plan().toolTimeout)
        assertEquals(Duration.ofMinutes(5), TurnLoopConfig.agent().toolTimeout)
    }

    // Network-bound tools wait on someone else's server, so their budget does not depend on the
    // mode and has to outlast the per-tool HTTP limits it sits above.
    @Test
    fun `the network tool budget is mode-independent and outlasts the in-process one`() {
        val plan = TurnLoopConfig.plan()
        val agent = TurnLoopConfig.agent()

        assertEquals(plan.networkToolTimeout, agent.networkToolTimeout)
        assertTrue(
            plan.networkToolTimeout >= plan.toolTimeout,
            "a network call must not be cut shorter than in-process work",
        )
    }
}
