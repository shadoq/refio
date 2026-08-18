package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The budgets that bound a local model. `agent.max_cost_usd` cannot: an Ollama call costs nothing,
 * so the dollar ceiling never trips and the iteration cap is the only brake left. Measured on the
 * e2e set, one scenario burned 1.56M input tokens against a 50k-150k norm and still failed.
 */
class TurnBudgetGuardTest {

    private val minute = 60_000L

    @Test
    fun `a turn well inside both budgets keeps running`() {
        assertNull(
            TurnBudgetGuard.check(
                tokensUsed = 120_000,
                maxTokens = 600_000,
                elapsedMillis = 5 * minute,
                maxMinutes = 45,
            )
        )
    }

    // The runaway this exists for: cost stays at zero, so only the token count reveals it.
    @Test
    fun `the token ceiling stops a turn that keeps generating without converging`() {
        val breach = TurnBudgetGuard.check(
            tokensUsed = 1_564_900,
            maxTokens = 600_000,
            elapsedMillis = 2 * minute,
            maxMinutes = 45,
        )

        assertEquals(TurnBudgetGuard.Breach.Tokens(1_564_900, 600_000), breach)
        assertTrue(TurnBudgetGuard.describe(breach!!).contains("1564900"), "the reason must state what was used")
    }

    // The case a token budget cannot see: the turn is waiting, not generating, so the token count
    // barely moves while the wall clock runs.
    @Test
    fun `the time ceiling stops a turn that is stuck waiting rather than generating`() {
        val breach = TurnBudgetGuard.check(
            tokensUsed = 3_000,
            maxTokens = 600_000,
            elapsedMillis = 46 * minute,
            maxMinutes = 45,
        )

        assertEquals(TurnBudgetGuard.Breach.Time(46, 45), breach)
    }

    @Test
    fun `the token budget is reported first when both are blown`() {
        val breach = TurnBudgetGuard.check(
            tokensUsed = 700_000,
            maxTokens = 600_000,
            elapsedMillis = 90 * minute,
            maxMinutes = 45,
        )

        assertTrue(breach is TurnBudgetGuard.Breach.Tokens, "tokens name the cause more precisely than elapsed time")
    }

    // Same convention as the cost guard, so a user can turn either budget off on its own.
    @Test
    fun `a non-positive limit disables that budget without touching the other`() {
        assertNull(
            TurnBudgetGuard.check(
                tokensUsed = 9_000_000,
                maxTokens = 0,
                elapsedMillis = 5 * minute,
                maxMinutes = 45,
            )
        )
        assertNull(
            TurnBudgetGuard.check(
                tokensUsed = 1_000,
                maxTokens = 600_000,
                elapsedMillis = 10 * 60 * minute,
                maxMinutes = 0,
            )
        )
    }

    // A budget that fires exactly at its limit, not one unit past it, keeps the promise the config
    // key makes; off-by-one here would let a turn overshoot a ceiling the operator set on purpose.
    @Test
    fun `a budget trips on reaching its limit, not after passing it`() {
        assertTrue(
            TurnBudgetGuard.check(600_000, 600_000, 0, 45) is TurnBudgetGuard.Breach.Tokens
        )
        assertTrue(
            TurnBudgetGuard.check(0, 600_000, 45 * minute, 45) is TurnBudgetGuard.Breach.Time
        )
    }
}
