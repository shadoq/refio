package pl.jclab.refio.core.services.monitoring

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Reported session cost has to survive calls that cost less than a cent, which is the normal
 * price of a single request on a cheap model. Dropping them makes a session that really spent
 * several dollars report $0.00.
 */
class GlobalMetricsCostTest {

    @BeforeEach
    fun resetBefore() {
        GlobalMetrics.reset()
    }

    @AfterEach
    fun resetAfter() {
        GlobalMetrics.reset()
        GlobalMetrics.removeAgent(COST_AGENT)
    }

    @Test
    fun `800 requests at six tenths of a cent report the dollars actually spent`() {
        repeat(800) {
            GlobalMetrics.recordRequest(tokensIn = 100, tokensOut = 50, costUsd = 0.006, success = true)
        }

        assertEquals(4.80, GlobalMetrics.metrics.value.totalCostUsd, 0.001)
    }

    @Test
    fun `a single sub-cent request is not reported as free`() {
        GlobalMetrics.recordRequest(tokensIn = 10, tokensOut = 5, costUsd = 0.004, success = true)

        assertEquals(0.004, GlobalMetrics.metrics.value.totalCostUsd, 1e-9)
    }

    @Test
    fun `the performance summary shows the same cost as the metrics snapshot`() {
        repeat(800) {
            GlobalMetrics.recordRequest(tokensIn = 100, tokensOut = 50, costUsd = 0.006, success = true)
        }

        assertEquals("Cost: \$4.8000", GlobalMetrics.getPerformanceSummary().lines().first { it.startsWith("Cost:") })
    }

    @Test
    fun `per-agent cost accumulates sub-cent requests too`() {
        val agent = GlobalMetrics.forAgent(COST_AGENT)

        repeat(800) { agent.recordTokens(tokensIn = 100, tokensOut = 50, costUsd = 0.006) }

        assertEquals(4.80, agent.totalCostUsd, 0.001)
    }

    private companion object {
        const val COST_AGENT = "cost-test-agent"
    }
}
