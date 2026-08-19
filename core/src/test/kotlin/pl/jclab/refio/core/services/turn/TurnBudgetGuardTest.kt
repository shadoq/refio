package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wall-clock budget that bounds a local model. `agent.max_cost_usd` cannot: an Ollama call costs
 * nothing, so the dollar ceiling never trips and the iteration cap is the only brake left - and an
 * iteration cap cannot see a turn that is stuck waiting rather than iterating.
 */
class TurnBudgetGuardTest {

    private val minute = 60_000L

    @Test
    fun `a turn well inside the budget keeps running`() {
        assertNull(TurnBudgetGuard.check(elapsedMillis = 5 * minute, maxMinutes = 45))
    }

    // The case the iteration cap cannot see: the turn is waiting, not generating, so it makes no
    // iterations to count against while the wall clock runs.
    @Test
    fun `the time ceiling stops a turn that is stuck waiting rather than generating`() {
        val breach = TurnBudgetGuard.check(elapsedMillis = 46 * minute, maxMinutes = 45)

        assertEquals(TurnBudgetGuard.Breach(46, 45), breach)
        assertTrue(TurnBudgetGuard.describe(breach!!).contains("46"), "the reason must state how long it ran")
    }

    // Same convention as the cost guard, so a user can turn the budget off.
    @Test
    fun `a non-positive limit disables the budget`() {
        assertNull(TurnBudgetGuard.check(elapsedMillis = 10 * 60 * minute, maxMinutes = 0))
    }

    // A budget that fires exactly at its limit, not one unit past it, keeps the promise the config
    // key makes; off-by-one here would let a turn overshoot a ceiling the operator set on purpose.
    @Test
    fun `the budget trips on reaching its limit, not after passing it`() {
        assertEquals(TurnBudgetGuard.Breach(45, 45), TurnBudgetGuard.check(45 * minute, 45))
        assertNull(TurnBudgetGuard.check(44 * minute, 45))
    }
}
