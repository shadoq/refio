package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CostLimitGuard] — the `--max-cost` / `agent.max_cost_usd` budget decision (docs/0063 §6.1).
 */
class CostLimitGuardTest {

    @Test
    fun `exceeded when cost is at or above a positive limit`() {
        assertTrue(CostLimitGuard.isExceeded(currentCostUsd = 0.6, maxCostUsd = 0.5))
        assertTrue(CostLimitGuard.isExceeded(currentCostUsd = 0.5, maxCostUsd = 0.5)) // boundary is inclusive
    }

    @Test
    fun `not exceeded below the limit`() {
        assertFalse(CostLimitGuard.isExceeded(currentCostUsd = 0.3, maxCostUsd = 0.5))
    }

    @Test
    fun `disabled when the limit is zero or negative`() {
        assertFalse(CostLimitGuard.isExceeded(currentCostUsd = 99.0, maxCostUsd = 0.0))
        assertFalse(CostLimitGuard.isExceeded(currentCostUsd = 99.0, maxCostUsd = -1.0))
    }
}
