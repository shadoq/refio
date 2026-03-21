package pl.jclab.refio.core.tools.security

import pl.jclab.refio.services.logging.dualLogger

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
     * Default blocked command patterns — only truly destructive operations
     */
    private val defaultBlockedPatterns = listOf(
        // ── Destructive filesystem operations ──
        "rm -rf /",
        "rm -rf /*",
        "rm -fr /",
        "rm -fr /*",
        "rm --recursive --force /",
        "rmdir /s /q c:\\",
        "rmdir /s /q c:/",
        "del /f /s /q c:\\",
        "format c:",
        "mkfs",
        "dd if=",
        "dd of=/dev",

        // ── System-level modifications ──
        "chmod 777 /",
        "chown -r",
        "chgrp -r",

        // ── Download-and-execute patterns ──
        "curl.*\\|.*sh",
        "wget.*\\|.*sh",
        "curl.*\\|.*bash",
        "wget.*\\|.*bash",

        // ── Privilege escalation ──
        "sudo",
        "su root",
        "su -",

        // ── System administration ──
        "passwd",
        "useradd",
        "userdel",
        "groupadd",
        "groupdel",
        "reboot",
        "shutdown",
        "halt",
        "poweroff",
        "init 0",
        "init 6",

        // ── Sensitive file access ──
        "cat /etc/shadow",
        "cat /etc/gshadow",
        "cat.*\\.ssh/id_",
        "cat.*\\.ssh/.*_key",
        "cat.*\\.aws/credentials",
        "cat.*\\.gnupg/",

        // ── System package managers (modify OS) ──
        "apt-get install",
        "apt install",
        "yum install",
        "dnf install",
        "pacman -s",
        "brew install",
        "choco install",
        "winget install",
        "snap install",

        // ── Encoding tricks / code injection ──
        "base64 -d.*\\|.*sh",
        "base64 -d.*\\|.*bash",
        "eval",

        // ── Fork bombs and resource exhaustion ──
        ":(){ :|:& };:",
        "while true; do",
        "while :; do",
        "for(;;)",

        // ── Registry / system config (Windows) ──
        "reg delete",
        "reg add.*hklm",
        "bcdedit",
        "diskpart"
    )

    private val allBlockedPatterns = defaultBlockedPatterns + customBlockedPatterns

    /**
     * Check if command is blocked by denylist
     *
     * @param command Command string to check
     * @return true if command matches any blocked pattern
     */
    fun isBlocked(command: String): Boolean {
        val normalizedCommand = command.lowercase().trim()

        for (pattern in allBlockedPatterns) {
            if (normalizedCommand.contains(pattern.lowercase())) {
                logger.warn { "Blocked dangerous command: $command (matched pattern: $pattern)" }
                return true
            }
        }

        return false
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
                // Block all network operations
                "curl", "wget", "nc", "netcat",

                // Block all remote git operations
                "git push", "git pull", "git clone",

                // Block docker container execution
                "docker run", "docker exec", "docker-compose",

                // Block SSH and remote access
                "ssh", "scp", "rsync", "ftp", "telnet"
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
