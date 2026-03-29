package pl.jclab.refio.cli.tui.input

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TuiCompleterSlashCommandsTest {

    @Test
    fun `slash command names should be available from SlashCommand BUILTINS`() {
        val builtins = pl.jclab.refio.api.models.SlashCommand.BUILTINS
        assertTrue(builtins.isNotEmpty(), "BUILTINS should not be empty")
        assertTrue(builtins.any { it.name == "explain" }, "Should have /explain")
        assertTrue(builtins.any { it.name == "fix" }, "Should have /fix")
        assertTrue(builtins.any { it.name == "test" }, "Should have /test")
        assertTrue(builtins.any { it.name == "refactor" }, "Should have /refactor")
    }

    @Test
    fun `slash commands should have templates`() {
        val builtins = pl.jclab.refio.api.models.SlashCommand.BUILTINS
        for (cmd in builtins) {
            assertTrue(cmd.template.isNotBlank(), "Command ${cmd.name} should have a template")
        }
    }

    @Test
    fun `slash commands should have descriptions`() {
        val builtins = pl.jclab.refio.api.models.SlashCommand.BUILTINS
        for (cmd in builtins) {
            assertTrue(cmd.description.isNotBlank(), "Command ${cmd.name} should have a description")
        }
    }

    @Test
    fun `slash command names should not have leading slash`() {
        val builtins = pl.jclab.refio.api.models.SlashCommand.BUILTINS
        for (cmd in builtins) {
            assertFalse(cmd.name.startsWith("/"), "Command name '${cmd.name}' should not start with /")
        }
    }
}
