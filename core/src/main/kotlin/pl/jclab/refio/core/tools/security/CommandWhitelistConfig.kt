package pl.jclab.refio.core.tools.security

data class CommandWhitelistConfig(
    val enabled: Boolean = true,
    val mode: WhitelistMode = WhitelistMode.WHITELIST_ONLY,
    val allowedCommands: List<AllowedCommand> = CommandWhitelistDefaults.DEFAULT_COMMANDS,
    val globalBlockedPatterns: List<String> = CommandWhitelistDefaults.DEFAULT_BLOCKED_PATTERNS
)

enum class WhitelistMode {
    WHITELIST_ONLY,
    WHITELIST_PLUS_DENY
}

data class AllowedCommand(
    val program: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
    val blockedFlags: List<String> = emptyList(),
    val blockedSubcommands: List<String> = emptyList(),
    val blockedArgPatterns: List<String> = emptyList(),
    val allowedSubcommands: List<String> = emptyList(),
    val maxArgs: Int = 50,
    val requireConfirmation: Boolean = false
)
