package pl.jclab.refio.core.tools.security

import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("CommandRuleMatcher")

/**
 * Simple regex-based command rule. Replaces the complex AllowedCommand model.
 *
 * @param pattern Regex matching the full command string
 * @param action What to do when command matches
 * @param description Human-readable description
 */
data class CommandRule(
    val pattern: String,
    val action: RuleAction,
    val description: String = ""
)

enum class RuleAction {
    ALLOW,  // Auto-execute without asking
    BLOCK,  // Hard deny — never execute
    ASK     // Ask user (Approve/Trust/Reject)
}

/**
 * Matches commands against a list of [CommandRule]s.
 *
 * Priority order: BLOCK rules first, then ALLOW, then ASK.
 * If no rule matches → default ASK.
 */
class CommandRuleMatcher(private val rules: List<CommandRule>) {

    data class MatchResult(
        val action: RuleAction,
        val matchedRule: CommandRule?
    )

    private data class CompiledRule(
        val rule: CommandRule,
        val regex: Regex
    )

    private val compiled: Map<RuleAction, List<CompiledRule>> by lazy {
        rules.mapNotNull { rule ->
            try {
                CompiledRule(rule, Regex(rule.pattern, RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                logger.warn { "Invalid command rule regex: '${rule.pattern}' — ${e.message}" }
                null
            }
        }.groupBy { it.rule.action }
    }

    /**
     * Match a raw command string against rules.
     * Checks BLOCK first, then ALLOW, then ASK.
     * Default: ASK if nothing matches.
     */
    fun match(rawCommand: String): MatchResult {
        // 1. BLOCK rules (most restrictive)
        for (cr in compiled[RuleAction.BLOCK].orEmpty()) {
            if (cr.regex.containsMatchIn(rawCommand)) {
                return MatchResult(RuleAction.BLOCK, cr.rule)
            }
        }

        // 2. ALLOW rules
        for (cr in compiled[RuleAction.ALLOW].orEmpty()) {
            if (cr.regex.matches(rawCommand)) {
                return MatchResult(RuleAction.ALLOW, cr.rule)
            }
        }

        // 3. ASK rules
        for (cr in compiled[RuleAction.ASK].orEmpty()) {
            if (cr.regex.containsMatchIn(rawCommand)) {
                return MatchResult(RuleAction.ASK, cr.rule)
            }
        }

        // 4. Default: ASK
        return MatchResult(RuleAction.ASK, null)
    }
}
