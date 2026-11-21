package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model cannot see the clock. On a local model the time limit is usually the one that ends the
 * turn, so the status tells it how many iterations and minutes are left and warns once the nearer of
 * the two limits is mostly used.
 */
class TurnBudgetStatusTest {

    private val minute = 60_000L

    @Test
    fun `status shows minutes and iterations left`() {
        val status = TurnBudgetStatus.render(iteration = 12, maxIterations = 200, elapsedMs = 9 * minute, maxMinutes = 45)

        assertTrue(status.contains("12 of 200") && status.contains("188 left"), status)
        assertTrue(status.contains("9 of 45 minutes") && status.contains("36 left"), status)
        assertFalse(status.contains("WARNING") || status.contains("CRITICAL"), status)
    }

    @Test
    fun `warns at 80 percent of the nearer limit`() {
        // A tenth of the iterations is used, but time is the limit that will end this turn.
        val past = TurnBudgetStatus.render(iteration = 20, maxIterations = 200, elapsedMs = 37 * minute, maxMinutes = 45)
        val before = TurnBudgetStatus.render(iteration = 20, maxIterations = 200, elapsedMs = 35 * minute, maxMinutes = 45)

        assertTrue(past.contains("WARNING") && past.contains("time"), past)
        assertFalse(before.contains("WARNING"), before)
    }

    @Test
    fun `turn without a time limit shows iterations only`() {
        val status = TurnBudgetStatus.render(iteration = 190, maxIterations = 200, elapsedMs = 300 * minute, maxMinutes = 0)

        assertFalse(status.contains("minutes"), "no time limit, so no minutes to count down: $status")
        assertTrue(status.contains("CRITICAL") && status.contains("iterations"), status)
    }
}
