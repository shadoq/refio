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
        // 1. BLOCK rules (most restrictive). Every built-in BLOCK rule is anchored (`^rm\s+-r`),
        // and an anchored pattern can only describe the first program on the line, so matching it
        // against the raw string alone made any prefix a bypass: `rm -rf /` was denied while
        // `env rm -rf /` or `git status && rm -rf /` ran. A hard deny has to hold for every command
        // the line runs, so each rule is tested against every command unit the shell would execute
        // (see [ShellCommandAnalyzer]); the raw line is the first unit, so nothing that matched
        // before stops matching.
        val blockRules = compiled[RuleAction.BLOCK].orEmpty()
        if (blockRules.isNotEmpty()) {
            val units = ShellCommandAnalyzer.commandUnits(rawCommand)
            for (cr in blockRules) {
                if (units.any { cr.regex.containsMatchIn(it) }) {
                    return MatchResult(RuleAction.BLOCK, cr.rule)
                }
            }
        }

        // 2. ALLOW rules — but never auto-approve a command that chains, substitutes or
        // redirects. An ALLOW rule validates only the leading program (e.g. `^git(\s+.*)?$`),
        // yet the whole string is executed: `git status; rm -rf /` matches the git rule and
        // would silently run the appended command, and `cat x > build.gradle.kts` lets a
        // vetted read-only program overwrite an arbitrary sandbox file. A command carrying
        // any of these operators is therefore held back from ALLOW and falls through to ASK,
        // where the user sees and approves the full line.
        if (!ShellCommandAnalyzer.hasControlOperators(rawCommand)) {
            for (cr in compiled[RuleAction.ALLOW].orEmpty()) {
                if (cr.regex.matches(rawCommand)) {
                    return MatchResult(RuleAction.ALLOW, cr.rule)
                }
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
