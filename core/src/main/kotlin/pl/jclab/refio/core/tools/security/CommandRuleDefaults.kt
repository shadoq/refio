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
     * Build tools, package managers, VCS, and common dev utilities — always allowed.
     *
     * Format: list of (program name, description). Aliases handled as separate entries.
     * Network/privilege-sensitive tools (docker, kubectl, ssh, scp, rsync, wget, sudo, su,
     * systemctl, service) are intentionally absent here — they're covered by ASK_RULES.
     */
    private val ALLOW_PROGRAMS: List<Pair<String, String>> = listOf(
        // Version control
        "git" to "Git version control",
        // Build tools — Gradle / Maven / Make
        "gradle" to "Gradle build tool",
        "gradlew" to "Gradle wrapper",
        "gradlew.bat" to "Gradle wrapper (Windows)",
        "./gradlew" to "Gradle wrapper (relative)",
        "mvn" to "Maven build tool",
        "mvnw" to "Maven wrapper",
        "make" to "Make build tool",
        "cmake" to "CMake build tool",
        "ninja" to "Ninja build tool",
        // Node / JS ecosystem
        "node" to "Node.js runtime",
        "npm" to "npm package manager",
        "npx" to "npm package runner",
        "yarn" to "Yarn package manager",
        "pnpm" to "pnpm package manager",
        "tsc" to "TypeScript compiler",
        "deno" to "Deno runtime",
        "bun" to "Bun runtime",
        // Python
        "python" to "Python interpreter",
        "python3" to "Python 3 interpreter",
        "pip" to "Python package manager",
        "pip3" to "Python 3 package manager",
        "pytest" to "Python test runner",
        "poetry" to "Python dependency manager",
        "uv" to "Python installer/resolver",
        // JVM / Kotlin
        "java" to "Java runtime",
        "javac" to "Java compiler",
        "kotlin" to "Kotlin runtime",
        "kotlinc" to "Kotlin compiler",
        // Other languages
        "go" to "Go toolchain",
        "cargo" to "Rust package manager",
        "rustc" to "Rust compiler",
        "ruby" to "Ruby interpreter",
        "bundle" to "Ruby bundler",
        "php" to "PHP interpreter",
        "composer" to "PHP dependency manager",
        "dotnet" to ".NET CLI",
        // Read-only filesystem inspection
        "ls" to "List directory",
        "dir" to "List directory (Windows)",
        "cat" to "Print file contents",
        "type" to "Print file contents (Windows)",
        "head" to "Print file head",
        "tail" to "Print file tail",
        "wc" to "Word count",
        "find" to "Find files",
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
        "env" to "Print environment",
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
            CommandRule("^exit(\\s+\\d+)?$", RuleAction.ALLOW, "Exit with code")
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
