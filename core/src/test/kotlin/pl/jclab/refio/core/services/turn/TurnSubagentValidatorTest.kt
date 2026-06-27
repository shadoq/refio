package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import kotlin.test.assertTrue

/**
 * Regression guard for the subagent nesting ceiling (docs/0063 §1/§5.1). The limit already works;
 * these tests exist so a silent lift of the cap (which would let runaway fan-out exhaust resources)
 * fails the build — Rule 9: encode WHY the ceiling matters, not just that it is checked.
 */
class TurnSubagentValidatorTest {

    private val validator = TurnSubagentValidator()  // default maxSubagentDepth = 3

    @Test
    fun `validateDepth allows depth at and below the inclusive ceiling`() {
        // chain root = 0 -> 1 -> 2 -> 3; the boundary (3) is allowed.
        for (d in 0..3) {
            validator.validateDepth(TurnProfileOverrides(depth = d))
        }
    }

    @Test
    fun `validateDepth rejects depth above the ceiling`() {
        val ex = assertThrows<IllegalArgumentException> {
            validator.validateDepth(TurnProfileOverrides(depth = 4))
        }
        assertTrue(ex.message!!.contains("depth", ignoreCase = true))
    }

    @Test
    fun `validateDepth treats missing overrides as depth zero`() {
        validator.validateDepth(null)  // null -> depth 0 -> allowed
    }

    @Test
    fun `validateRecursion rejects a subagent already in its own ancestor chain`() {
        val ex = assertThrows<IllegalArgumentException> {
            validator.validateRecursion(
                runProfile = TurnRunProfile.SUBAGENT,
                profileOverrides = TurnProfileOverrides(
                    subagentName = "reviewer",
                    subagentChain = listOf("reviewer")
                )
            )
        }
        assertTrue(ex.message!!.contains("recursion", ignoreCase = true))
    }

    @Test
    fun `validateRecursion allows a distinct subagent in the chain`() {
        validator.validateRecursion(
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = "editor",
                subagentChain = listOf("reviewer")
            )
        )
    }
}
