package pl.jclab.refio.core.tools.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandWhitelistTest {

    @Test
    fun `should allow command present on whitelist`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("git status")

        assertTrue(result.allowed)
    }

    @Test
    fun `should block command not on whitelist in strict mode`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("rm -rf /")

        assertFalse(result.allowed)
    }

    @Test
    fun `should block command with blocked flag`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("git status --force")

        assertFalse(result.allowed)
    }

    @Test
    fun `should block command with blocked subcommand`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("git push origin main")

        assertFalse(result.allowed)
    }

    @Test
    fun `should allow pipeline with allowed commands only`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("git log --oneline | head -20")

        assertTrue(result.allowed)
    }

    @Test
    fun `should block pipeline that invokes shell`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("git log | sh")

        assertFalse(result.allowed)
    }

    @Test
    fun `should block command substitution`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("echo $(cat /etc/passwd)")

        assertFalse(result.allowed)
    }

    @Test
    fun `should allow non-whitelisted command in whitelist plus deny mode when denylist allows it`() {
        val whitelist = CommandWhitelist(
            config = CommandWhitelistConfig(
                mode = WhitelistMode.WHITELIST_PLUS_DENY,
                allowedCommands = emptyList(),
                globalBlockedPatterns = emptyList()
            ),
            denylist = CommandDenylist.DEFAULT
        )

        val result = whitelist.validate("echo hello")

        assertTrue(result.allowed)
    }

    @Test
    fun `should block non-whitelisted command in whitelist plus deny mode when denylist blocks it`() {
        val whitelist = CommandWhitelist(
            config = CommandWhitelistConfig(
                mode = WhitelistMode.WHITELIST_PLUS_DENY,
                allowedCommands = emptyList(),
                globalBlockedPatterns = emptyList()
            ),
            denylist = CommandDenylist.DEFAULT
        )

        val result = whitelist.validate("rm -rf /")

        assertFalse(result.allowed)
    }

    @Test
    fun `should normalize executable path for whitelisted command`() {
        val whitelist = createWhitelist()
        val result = whitelist.validate("\"C:\\Program Files\\Git\\bin\\git.exe\" status")

        assertTrue(result.allowed)
    }

    private fun createWhitelist(): CommandWhitelist {
        val config = CommandWhitelistConfig(
            mode = WhitelistMode.WHITELIST_ONLY,
            allowedCommands = listOf(
                AllowedCommand(
                    program = "git",
                    allowedSubcommands = listOf("status", "log", "add", "commit"),
                    blockedSubcommands = listOf("push"),
                    blockedFlags = listOf("--force")
                ),
                AllowedCommand(program = "head")
            ),
            globalBlockedPatterns = CommandWhitelistDefaults.DEFAULT_BLOCKED_PATTERNS
        )
        return CommandWhitelist(config, CommandDenylist.DEFAULT)
    }
}
