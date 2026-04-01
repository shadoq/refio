package pl.jclab.refio.core.tools.security

import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("CommandDenylist")

/**
 * Security denylist for terminal commands.
 *
 * Blocks truly dangerous commands that could:
 * - Destroy filesystems or partitions (rm -rf /, format, dd, mkfs)
 * - Compromise system integrity (passwd, useradd, shutdown)
 * - Read highly sensitive system files (/etc/shadow, .ssh private keys)
 * - Cause resource exhaustion (fork bombs)
 *
 * Development commands (build, test, package install, docker, git, ssh)
 * are NOT blocked here — use CommandWhitelist for fine-grained control.
 */
class CommandDenylist(
    private val customBlockedPatterns: List<String> = emptyList()
) {
    /**
     * Default blocked command patterns — only truly destructive operations.
     *
     * These are compiled as regex patterns with word boundary checks where appropriate.
     * Patterns use regex syntax — special chars must be escaped.
     */
    private val defaultBlockedPatterns = listOf(
        // ── Destructive filesystem operations ──
        // Match rm with any combination of -r/-f flags targeting root
        """rm\s+(-[a-z]*r[a-z]*\s+-[a-z]*f[a-z]*|-[a-z]*f[a-z]*\s+-[a-z]*r[a-z]*|-[a-z]*rf[a-z]*|-[a-z]*fr[a-z]*|--recursive\s+--force|--force\s+--recursive)\s+/""",
        """rmdir\s+/s\s+/q\s+c:[/\\]""",
        """del\s+/f\s+/s\s+/q\s+c:[/\\]""",
        """format\s+c:""",
        """\bmkfs\b""",
        """\bdd\s+if=""",
        """\bdd\s+of=/dev""",

        // ── System-level modifications ──
        """chmod\s+777\s+/""",
        """chown\s+-[rR]""",
        """chgrp\s+-[rR]""",

        // ── Download-and-execute patterns (pipe to shell) ──
        """curl\s.*\|\s*(sh|bash|zsh|dash)\b""",
        """wget\s.*\|\s*(sh|bash|zsh|dash)\b""",

        // ── Privilege escalation ──
        """\bsudo\b""",
        """\bsu\s+root\b""",
        """\bsu\s+-\s*$""",

        // ── System administration ──
        """\bpasswd\b""",
        """\buseradd\b""",
        """\buserdel\b""",
        """\bgroupadd\b""",
        """\bgroupdel\b""",
        """\breboot\b""",
        """\bshutdown\b""",
        """\bhalt\b""",
        """\bpoweroff\b""",
        """\binit\s+[06]\b""",

        // ── Sensitive file access ──
        """\bcat\s+/etc/shadow""",
        """\bcat\s+/etc/gshadow""",
        """\bcat\s.*\.ssh/id_""",
        """\bcat\s.*\.ssh/.*_key""",
        """\bcat\s.*\.aws/credentials""",
        """\bcat\s.*\.gnupg/""",

        // ── System package managers (modify OS) ──
        """\bapt-get\s+install\b""",
        """\bapt\s+install\b""",
        """\byum\s+install\b""",
        """\bdnf\s+install\b""",
        """\bpacman\s+-[sS]""",
        """\bbrew\s+install\b""",
        """\bchoco\s+install\b""",
        """\bwinget\s+install\b""",
        """\bsnap\s+install\b""",

        // ── Encoding tricks / code injection ──
        """base64\s+-d\s.*\|\s*(sh|bash)\b""",
        """\beval\s""",

        // ── Fork bombs and resource exhaustion ──
        """:\(\)\s*\{""",
        """\bwhile\s+(true|:)\s*;\s*do\b""",
        """\bfor\s*\(\s*;\s*;\s*\)""",

        // ── Command substitution / shell escapes to bypass filters ──
        """\$\(.*\b(rm|dd|mkfs|passwd|shutdown|reboot)\b""",
        """`.*\b(rm|dd|mkfs|passwd|shutdown|reboot)\b""",

        // ── Registry / system config (Windows) ──
        """\breg\s+delete\b""",
        """\breg\s+add\s.*\bhklm\b""",
        """\bbcdedit\b""",
        """\bdiskpart\b"""
    )

    private val allBlockedPatterns = defaultBlockedPatterns + customBlockedPatterns

    /** Compiled regex patterns for efficient matching */
    private val compiledPatterns: List<Pair<String, Regex>> = allBlockedPatterns.map { pattern ->
        pattern to Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /**
     * Check if command is blocked by denylist.
     *
     * Uses regex matching with word boundaries to avoid false positives
     * (e.g. "evaluation" no longer matches the "eval" pattern).
     *
     * @param command Command string to check
     * @return true if command matches any blocked pattern
     */
    fun isBlocked(command: String): Boolean {
        val normalizedCommand = normalizeCommand(command)

        for ((pattern, regex) in compiledPatterns) {
            if (regex.containsMatchIn(normalizedCommand)) {
                logger.warn { "Blocked dangerous command: $command (matched pattern: $pattern)" }
                return true
            }
        }

        return false
    }

    /**
     * Normalize command string before matching:
     * - Collapse multiple spaces/tabs to single space
     * - Trim whitespace
     * - Lowercase
     */
    private fun normalizeCommand(command: String): String {
        return command.trim().replace(Regex("""\s+"""), " ").lowercase()
    }

    /**
     * Get list of all blocked patterns (for documentation)
     */
    fun getBlockedPatterns(): List<String> {
        return allBlockedPatterns
    }

    companion object {
        /**
         * Default denylist instance
         */
        val DEFAULT = CommandDenylist()

        /**
         * Strict denylist with additional restrictions
         */
        fun strict(): CommandDenylist {
            val additionalPatterns = listOf(
                // Block all network operations (with word boundaries)
                """\bcurl\b""", """\bwget\b""", """\bnc\b""", """\bnetcat\b""",

                // Block all remote git operations
                """\bgit\s+push\b""", """\bgit\s+pull\b""", """\bgit\s+clone\b""",

                // Block docker container execution
                """\bdocker\s+run\b""", """\bdocker\s+exec\b""", """\bdocker-compose\b""",

                // Block SSH and remote access
                """\bssh\b""", """\bscp\b""", """\brsync\b""", """\bftp\b""", """\btelnet\b"""
            )

            return CommandDenylist(additionalPatterns)
        }
    }
}

/**
 * Command execution limits
 */
data class CommandLimits(
    /**
     * Maximum command execution time in seconds
     */
    val timeoutSeconds: Long = 120,

    /**
     * Maximum output size in characters
     */
    val maxOutputSize: Int = 200_000, // 200 KB

    /**
     * Maximum number of concurrent commands
     */
    val maxConcurrentCommands: Int = 5
) {
    companion object {
        val DEFAULT = CommandLimits()

        val STRICT = CommandLimits(
            timeoutSeconds = 10,
            maxOutputSize = 10_000,
            maxConcurrentCommands = 1
        )
    }
}
