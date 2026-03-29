package pl.jclab.refio.core.services.monitoring

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalMetricsMultiAgentTest {

    @AfterEach
    fun cleanup() {
        GlobalMetrics.removeAgent("agent-a")
        GlobalMetrics.removeAgent("agent-b")
        GlobalMetrics.removeAgent("default")
        GlobalMetrics.resetCancellation()
    }

    @Nested
    inner class PerAgentMetrics {

        @Test
        fun `forAgent should create new metrics`() {
            val metrics = GlobalMetrics.forAgent("agent-a")
            assertNotNull(metrics)
            assertEquals("agent-a", metrics.agentId)
        }

        @Test
        fun `forAgent should return same instance for same id`() {
            val m1 = GlobalMetrics.forAgent("agent-a")
            val m2 = GlobalMetrics.forAgent("agent-a")
            assertTrue(m1 === m2)
        }

        @Test
        fun `per-agent cancellation does not affect other agents`() {
            val metricsA = GlobalMetrics.forAgent("agent-a")
            val metricsB = GlobalMetrics.forAgent("agent-b")

            metricsA.resetCancellation()
            metricsB.resetCancellation()
            metricsA.requestCancellation()

            assertTrue(metricsA.isCancelled())
            assertFalse(metricsB.isCancelled())
        }

        @Test
        fun `per-agent operation tracking is independent`() {
            val metricsA = GlobalMetrics.forAgent("agent-a")
            val metricsB = GlobalMetrics.forAgent("agent-b")

            metricsA.setCurrentOperation(OperationInfo.ChatRequest("model-a"))
            metricsB.setCurrentOperation(OperationInfo.TurnLoop(1, 5, "AGENT"))

            assertEquals("Chat: model-a", metricsA.currentOperation.value.toString())
            assertEquals("Turn 1/5 (AGENT)", metricsB.currentOperation.value.toString())
        }

        @Test
        fun `per-agent token tracking`() {
            val metrics = GlobalMetrics.forAgent("agent-a")
            metrics.recordTokens(100, 50, 0.01)
            metrics.recordTokens(200, 100, 0.02)

            assertEquals(300, metrics.totalTokensIn)
            assertEquals(150, metrics.totalTokensOut)
            assertEquals(0.03, metrics.totalCostUsd, 0.001)
        }

        @Test
        fun `removeAgent should clean up`() {
            GlobalMetrics.forAgent("agent-a")
            assertTrue(GlobalMetrics.allAgentMetrics().containsKey("agent-a"))

            GlobalMetrics.removeAgent("agent-a")
            assertFalse(GlobalMetrics.allAgentMetrics().containsKey("agent-a"))
        }

        @Test
        fun `allAgentMetrics should return all active agents`() {
            GlobalMetrics.forAgent("agent-a")
            GlobalMetrics.forAgent("agent-b")

            val all = GlobalMetrics.allAgentMetrics()
            assertTrue(all.containsKey("agent-a"))
            assertTrue(all.containsKey("agent-b"))
        }
    }

    @Nested
    inner class BackwardCompat {

        @Test
        fun `global setCurrentOperation still works`() {
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
            assertEquals(OperationInfo.Idle, GlobalMetrics.currentOperation.value)
        }

        @Test
        fun `global cancellation still works`() {
            GlobalMetrics.resetCancellation()
            assertFalse(GlobalMetrics.isCancelled())
            GlobalMetrics.requestCancellation()
            assertTrue(GlobalMetrics.isCancelled())
            GlobalMetrics.resetCancellation()
        }

        @Test
        fun `global cancellation is independent from per-agent`() {
            val agentMetrics = GlobalMetrics.forAgent("agent-a")
            agentMetrics.resetCancellation()
            GlobalMetrics.resetCancellation()

            agentMetrics.requestCancellation()
            assertTrue(agentMetrics.isCancelled())
            assertFalse(GlobalMetrics.isCancelled()) // Global not affected
        }
    }
}
