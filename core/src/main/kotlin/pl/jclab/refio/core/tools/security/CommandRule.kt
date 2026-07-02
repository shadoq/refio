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

        // 2. ALLOW rules — but never auto-approve a command that chains, substitutes or
        // redirects. An ALLOW rule validates only the leading program (e.g. `^git(\s+.*)?$`),
        // yet the whole string is executed: `git status; rm -rf /` matches the git rule and
        // would silently run the appended command, and `cat x > build.gradle.kts` lets a
        // vetted read-only program overwrite an arbitrary sandbox file. A command carrying
        // any of these operators is therefore held back from ALLOW and falls through to ASK,
        // where the user sees and approves the full line.
        if (!hasShellControlOperators(rawCommand)) {
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

    /**
     * True when the command contains a shell operator that chains, pipes, substitutes or
     * redirects — the vectors that let a vetted leading program smuggle in a second, unvetted
     * command, or turn a read-only program into a file write (`>` / `>>`). Detection is a
     * plain substring scan, so an operator inside a quoted string is treated conservatively
     * (downgraded to ASK rather than auto-approved); for a security gate, an extra approval
     * prompt is the right side to err on. Bare `$VAR` expansion is intentionally not matched
     * — only `$(` opens a subshell.
     */
    private fun hasShellControlOperators(command: String): Boolean {
        return SHELL_CONTROL_OPERATORS.any { command.contains(it) }
    }

    companion object {
        private val SHELL_CONTROL_OPERATORS: List<String> = listOf(
            ";",    // command separator
            "&",    // background / && and-chain
            "|",    // pipe / || or-chain
            "`",    // backtick command substitution
            "$(",   // command substitution
            ">",    // output redirection — lets an allowed read-only program overwrite files (also catches >>, 2>, &>)
            "<",    // input redirection (also catches <( process substitution and <<< here-string)
            "\n",   // newline-injected second command
            "\r"
        )
    }
}
