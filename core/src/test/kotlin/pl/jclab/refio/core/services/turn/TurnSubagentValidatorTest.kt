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

    @Test
    fun `validateRecursion allows a subagent with an empty ancestor chain`() {
        // Contract: subagentChain holds ANCESTORS ONLY, never self. The first tool-enabled
        // delegate_to_strong_model from a top-level agent produces exactly this shape
        // (name="strong-model", chain=[]). It broke because the tool pre-added its own name,
        // making the chain [strong-model] and tripping a false recursion here. With an empty
        // ancestor chain the agent is a fresh child and must be allowed.
        validator.validateRecursion(
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = "strong-model",
                subagentChain = emptyList()
            )
        )
    }

    @Test
    fun `validateRecursion ignores the top-level DEFAULT profile`() {
        // A non-subagent turn (main agent) must never be subject to the recursion check, regardless
        // of what chain data is attached — the guard is scoped to SUBAGENT runs only.
        validator.validateRecursion(
            runProfile = TurnRunProfile.DEFAULT,
            profileOverrides = TurnProfileOverrides(
                subagentName = "strong-model",
                subagentChain = listOf("strong-model")
            )
        )
    }
}
