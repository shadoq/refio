package pl.jclab.refio.core.tools.security

/**
 * Default command rules. Regex-based ALLOW/BLOCK/ASK matcher for terminal commands.
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

        // Destructive filesystem operations — Windows cmd.exe / PowerShell.
        // The agent runs commands through powershell.exe on Windows, where del/erase/
        // rm/rmdir/rd/ri are all aliases for Remove-Item — none of which the POSIX
        // `rm` rules above catch. Mirror the rm -r / rm -f philosophy: block the
        // recursive/force/quiet mass-delete variants; a plain single-file delete
        // (no flags) stays ASK like plain `rm`.
        CommandRule("^(del|erase)\\s+.*/[fsq]\\b", RuleAction.BLOCK, "del/erase force/recursive/quiet (Windows)"),
        CommandRule("^(rmdir|rd)\\s+.*/s\\b", RuleAction.BLOCK, "Recursive directory delete (Windows)"),
        CommandRule("^(remove-item|ri|del|erase|rmdir|rd|rm)\\s+.*\\s-rec", RuleAction.BLOCK, "Recursive delete (PowerShell)"),
        CommandRule("^(remove-item|ri|del|erase|rmdir|rd|rm)\\s+.*\\s-for", RuleAction.BLOCK, "Force delete (PowerShell)"),
        CommandRule("^(format-volume|clear-disk)\\b", RuleAction.BLOCK, "Disk/volume destruction (PowerShell)"),

        // Destructive git operations
        CommandRule("^git\\s+reset\\s+--hard", RuleAction.BLOCK, "Git hard reset"),
        // Force-clean deletes untracked files. Block it in any flag order/grouping
        // (-fdx, -xfd, -d -x -f, --force) while leaving non-destructive -n/--dry-run alone.
        CommandRule("^git\\s+clean\\b(?=.*\\s-(?:-force\\b|[a-z]*f))", RuleAction.BLOCK, "Git force clean"),
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
     * Build tools, package managers, VCS, and common dev utilities — always allowed.
     *
     * Format: list of (program name, description). Aliases handled as separate entries.
     * Network/privilege-sensitive tools (docker, kubectl, ssh, scp, rsync, wget, sudo, su,
     * systemctl, service) are intentionally absent here — they're covered by ASK_RULES.
     *
     * A program belongs here only if vetting its NAME says something about what it will do.
     * `find` used to be on this list and does not qualify: `-exec`, `-execdir`, `-ok` run an
     * arbitrary program and `-delete` empties a tree, so an allow-list entry for `find` allowed
     * whatever came after it. Deciding per argument would mean a deny list nested inside an allow
     * list, exactly the fragile shape that made the anchored BLOCK rules bypassable, so `find`
     * falls through to ASK - where the user sees the whole line, `-delete` included, before
     * approving it. `env` is allowed only in its bare, argument-free form (below), since
     * `env <cmd>` is just another way to spell `<cmd>`.
     */
    private val ALLOW_PROGRAMS: List<Pair<String, String>> = listOf(
        // Version control
        "git" to "Git version control",
        // Build tools — Gradle / Maven / Make
        // Read-only filesystem inspection
        "ls" to "List directory",
        "dir" to "List directory (Windows)",
        "cat" to "Print file contents",
        "type" to "Print file contents (Windows)",
        "head" to "Print file head",
        "tail" to "Print file tail",
        "wc" to "Word count",
        "grep" to "Text search",
        "rg" to "Ripgrep search",
        "fd" to "fd-find",
        "pwd" to "Print working directory",
        "cd" to "Change directory",
        "tree" to "Print directory tree",
        "stat" to "File metadata",
        "file" to "File type detection",
        // Read-only git helpers often typed explicitly
        "gh" to "GitHub CLI (non-destructive ops via ASK for delete)",
        "hg" to "Mercurial",
        // Environment
        "which" to "Locate executable",
        "where" to "Locate executable (Windows)"
    )

    val ALLOW_RULES: List<CommandRule> by lazy {
        val rules = ALLOW_PROGRAMS.map { (prog, desc) ->
            CommandRule(
                pattern = "^${Regex.escape(prog)}(\\s+.*)?$",
                action = RuleAction.ALLOW,
                description = desc
            )
        }.toMutableList()

        // Primitive utilities
        rules.addAll(listOf(
            CommandRule("^echo(\\s+.*)?$", RuleAction.ALLOW, "Echo text"),
            CommandRule("^printf(\\s+.*)?$", RuleAction.ALLOW, "Print formatted"),
            CommandRule("^true$", RuleAction.ALLOW, "Always succeed"),
            CommandRule("^false$", RuleAction.ALLOW, "Always fail"),
            CommandRule("^exit(\\s+\\d+)?$", RuleAction.ALLOW, "Exit with code"),
            // Bare `env` prints the environment; `env <cmd>` runs a program, so only the
            // argument-free form is auto-allowed.
            CommandRule("^env$", RuleAction.ALLOW, "Print environment")
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
