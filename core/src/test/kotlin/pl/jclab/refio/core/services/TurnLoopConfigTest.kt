package pl.jclab.refio.core.services

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnLoopConfigTest {

    @Test
    fun `agent config should allow longer runs`() {
        val config = TurnLoopConfig.agent()

        assertEquals(200, config.maxIterations)
        assertEquals(3, config.maxFormatRetries)
    }

    @Test
    fun `plan config should allow more analysis retries`() {
        val config = TurnLoopConfig.plan()

        assertEquals(200, config.maxIterations)
        assertEquals(3, config.maxFormatRetries)
    }

    // The iteration cap is a runaway backstop, not the cost budget: the error-rate, repetition,
    // noop-write and blocked-tool trackers abort long before it, and the cost limit caps spend.
    // Warning well before the cap keeps the operator informed without ending useful work.
    @Test
    fun `the iteration warning arrives well before the cap in both modes`() {
        listOf(TurnLoopConfig.plan(), TurnLoopConfig.agent()).forEach { config ->
            assertTrue(
                config.warningThreshold < config.maxIterations,
                "warning must precede the cap, got ${config.warningThreshold} of ${config.maxIterations}",
            )
            assertEquals(50, config.warningThreshold)
        }
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
