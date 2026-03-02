package pl.jclab.refio.core.tools.security

import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("CommandDenylist")

/**
 * Security denylist for terminal commands.
 *
 * Blocks dangerous commands that could:
 * - Damage the system (rm -rf, format, dd)
 * - Compromise security (curl | sh, wget | sh)
 * - Consume resources (fork bombs, infinite loops)
 * - Access sensitive data (ssh, scp, credentials)
 */
class CommandDenylist(
    private val customBlockedPatterns: List<String> = emptyList()
) {
    /**
     * Default blocked command patterns
     */
    private val defaultBlockedPatterns = listOf(
        // Destructive file operations
        "rm -rf", "rm -fr", "rm --recursive --force",
        "rmdir /s", "rmdir /q", "del /f /s /q",
        "format", "mkfs", "dd if=", "dd of=/dev",

        // Network operations that download and execute
        "curl.*\\|.*sh", "wget.*\\|.*sh", "curl.*\\|.*bash", "wget.*\\|.*bash",

        // System modification
        "chmod 777", "chown", "chgrp",
        "sudo", "su -", "su root",

        // Process/system manipulation
        "kill -9", "killall", "pkill",
        "reboot", "shutdown", "halt", "poweroff",

        // Sensitive operations
        "passwd", "useradd", "userdel", "groupadd", "groupdel",

        // Remote access
        "ssh", "scp", "rsync", "ftp", "telnet",

        // Credential access
        "cat.*\\.ssh", "cat.*\\.aws", "cat.*\\.env",
        "grep.*password", "grep.*api_key", "grep.*secret",

        // Encoding tricks
        "base64 -d", "eval", "exec",

        // Fork bombs and resource exhaustion
        ":(){ :|:& };:", "while true; do", "while :; do",

        // Package managers (could install malware)
        "npm install -g", "pip install", "gem install",
        "apt-get install", "yum install", "brew install"
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

                // Block all git operations
                "git push", "git pull", "git clone",

                // Block docker
                "docker run", "docker exec", "docker-compose",

                // Block all sudo/root escalation
                "sudo", "su"
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
    val timeoutSeconds: Long = 30,

    /**
     * Maximum output size in characters
     */
    val maxOutputSize: Int = 100_000, // 100 KB

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
