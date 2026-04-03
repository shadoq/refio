package pl.jclab.refio.core.tools.security

/**
 * Default command rules converted from AllowedCommand model.
 * Uses regex-based matching instead of the complex 8-field AllowedCommand.
 */
object CommandRuleDefaults {

    /**
     * Hard-blocked patterns — always denied regardless of other rules.
     */
    val BLOCK_RULES = listOf(
        // Destructive filesystem operations
        CommandRule("^rm\\s+-r", RuleAction.BLOCK, "Recursive delete"),
        CommandRule("^rm\\s+.*-f", RuleAction.BLOCK, "Force delete"),
        CommandRule("^mkfs\\b", RuleAction.BLOCK, "Format filesystem"),
        CommandRule("^dd\\b.*of=", RuleAction.BLOCK, "dd with output (disk write)"),
        CommandRule("^format\\b", RuleAction.BLOCK, "Format disk (Windows)"),
        CommandRule(""":\(\)\s*\{""", RuleAction.BLOCK, "Fork bomb"),
        CommandRule("^shred\\b", RuleAction.BLOCK, "Secure file deletion"),

        // Destructive git operations
        CommandRule("^git\\s+reset\\s+--hard", RuleAction.BLOCK, "Git hard reset"),
        CommandRule("^git\\s+clean\\s+-f", RuleAction.BLOCK, "Git force clean"),
        CommandRule("^git\\s+push\\s+.*--force", RuleAction.BLOCK, "Git force push"),
        CommandRule("^git\\s+push\\s+-f\\b", RuleAction.BLOCK, "Git force push (-f)"),

        // Package publishing
        CommandRule("^npm\\s+publish\\b", RuleAction.BLOCK, "npm publish"),
        CommandRule("^yarn\\s+publish\\b", RuleAction.BLOCK, "yarn publish"),
        CommandRule("^pip\\s+.*upload\\b", RuleAction.BLOCK, "pip upload"),

        // Environment destruction
        CommandRule("^chmod\\s+777", RuleAction.BLOCK, "chmod 777 (insecure)"),
        CommandRule("""^>\s*/dev/sd""", RuleAction.BLOCK, "Overwrite block device"),

        // Credential exposure
        CommandRule("^npm\\s+(adduser|login|token)\\b", RuleAction.BLOCK, "npm credentials"),
        CommandRule("^gh\\s+(repo|org)\\s+delete\\b", RuleAction.BLOCK, "GitHub destructive ops"),
        CommandRule("^docker\\s+system\\s+prune\\s+-a", RuleAction.BLOCK, "Docker prune all"),

        // Database destruction
        CommandRule("^(mysql|psql|mongo).*DROP\\s+DATABASE", RuleAction.BLOCK, "Drop database"),
    )

    /**
     * Build tools, package managers, and development utilities — always allowed.
     */
    /** Programs managed by explicit ASK rules — exclude from auto-generated ALLOW rules */
    private val ASK_MANAGED_PROGRAMS = setOf(
        "docker", "kubectl", "ssh", "scp", "rsync", "wget", "sudo", "su",
        "systemctl", "service"
    )

    val ALLOW_RULES: List<CommandRule> by lazy {
        val rules = mutableListOf<CommandRule>()

        // Convert all AllowedCommand entries (that don't require confirmation
        // and aren't managed by explicit ASK rules) into ALLOW rules
        for (cmd in CommandWhitelistDefaults.DEFAULT_COMMANDS) {
            if (cmd.requireConfirmation) continue
            if (cmd.program in ASK_MANAGED_PROGRAMS) continue

            val programs = listOf(cmd.program) + cmd.aliases
            for (prog in programs) {
                val escapedProg = Regex.escape(prog)
                rules.add(CommandRule(
                    pattern = "^$escapedProg(\\s+.*)?$",
                    action = RuleAction.ALLOW,
                    description = cmd.description
                ))
            }
        }

        // Common utilities not in AllowedCommand but safe
        rules.addAll(listOf(
            CommandRule("^echo(\\s+.*)?$", RuleAction.ALLOW, "Echo text"),
            CommandRule("^printf(\\s+.*)?$", RuleAction.ALLOW, "Print formatted"),
            CommandRule("^true$", RuleAction.ALLOW, "Always succeed"),
            CommandRule("^false$", RuleAction.ALLOW, "Always fail"),
        ))

        rules
    }

    /**
     * Commands that should ask for user approval.
     */
    val ASK_RULES = listOf(
        CommandRule("^docker\\s+.*", RuleAction.ASK, "Docker commands"),
        CommandRule("^kubectl\\s+.*", RuleAction.ASK, "Kubernetes commands"),
        CommandRule("^ssh\\s+.*", RuleAction.ASK, "SSH connections"),
        CommandRule("^scp\\s+.*", RuleAction.ASK, "SCP file transfer"),
        CommandRule("^rsync\\s+.*", RuleAction.ASK, "Rsync sync"),
        CommandRule("^curl\\s+.*-X\\s+(POST|PUT|DELETE|PATCH)\\b", RuleAction.ASK, "curl write requests"),
        CommandRule("^wget\\s+.*", RuleAction.ASK, "wget downloads"),
        CommandRule("^sudo\\s+.*", RuleAction.ASK, "Sudo commands"),
        CommandRule("^su\\s+.*", RuleAction.ASK, "Switch user"),
        CommandRule("^systemctl\\s+.*", RuleAction.ASK, "Systemd management"),
        CommandRule("^service\\s+.*", RuleAction.ASK, "Service management"),
        // Catch-all: anything not matched → ASK
        CommandRule(".*", RuleAction.ASK, "Unknown command (default)"),
    )

    /**
     * All default rules combined, in priority order.
     */
    val DEFAULT_RULES: List<CommandRule> by lazy {
        BLOCK_RULES + ALLOW_RULES + ASK_RULES
    }

    /**
     * Create a default [CommandRuleMatcher] with all built-in rules.
     */
    fun createDefaultMatcher(): CommandRuleMatcher {
        return CommandRuleMatcher(DEFAULT_RULES)
    }
}
