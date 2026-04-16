package pl.jclab.refio.core.tools.security

/**
 * Simple regex-based command rule.
 *
 * Regex is compiled eagerly in the init block — an invalid pattern fails **at
 * construction time**, not silently during matching. Callers loading rules from
 * config must be prepared to catch [IllegalArgumentException] and refuse startup
 * with a clear message (see `ConfigService`/`ToolPermissionsService`).
 *
 * @param pattern Regex matching the full command string
 * @param action What to do when command matches
 * @param description Human-readable description
 */
data class CommandRule(
    val pattern: String,
    val action: RuleAction,
    val description: String = ""
) {
    val compiledRegex: Regex = try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "Invalid command rule regex '$pattern' (action=$action, description='$description'): ${e.message}",
            e
        )
    }
}

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
        rules.map { rule ->
            CompiledRule(rule, rule.compiledRegex)
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
