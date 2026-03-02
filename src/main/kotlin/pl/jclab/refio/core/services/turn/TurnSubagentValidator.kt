package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("TurnSubagentValidator")

/**
 * Validates subagent execution constraints.
 *
 * Enforces:
 * - Maximum nesting depth
 * - No recursion (subagent cannot call itself)
 */
class TurnSubagentValidator(
    private val maxSubagentDepth: Int = 3
) {
    /**
     * Validate that subagent depth is within limits.
     */
    fun validateDepth(profileOverrides: TurnProfileOverrides?) {
        val depth = profileOverrides?.depth ?: 0
        require(depth <= maxSubagentDepth) {
            "Max subagent depth exceeded: $depth/$maxSubagentDepth"
        }
    }

    /**
     * Validate that subagent is not calling itself (recursion detection).
     */
    fun validateRecursion(
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?
    ) {
        if (runProfile != TurnRunProfile.SUBAGENT) return
        val subagentName = profileOverrides?.subagentName?.trim().orEmpty()
        if (subagentName.isBlank()) return

        val normalized = subagentName.lowercase()
        val chain = profileOverrides?.subagentChain.orEmpty().map { it.lowercase() }
        require(normalized !in chain) {
            "Subagent recursion detected for '$subagentName': ${chain.joinToString(" -> ")}"
        }
    }
}
