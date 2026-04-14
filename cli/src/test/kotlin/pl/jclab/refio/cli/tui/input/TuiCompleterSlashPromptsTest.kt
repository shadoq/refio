package pl.jclab.refio.cli.tui.input

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TuiCompleterSlashPromptsTest {

    @Test
    fun `slash prompt names should be available from SlashPrompt BUILTINS`() {
        val builtins = pl.jclab.refio.api.models.SlashPrompt.BUILTINS
        assertTrue(builtins.isNotEmpty(), "BUILTINS should not be empty")
        assertTrue(builtins.any { it.name == "explain" }, "Should have /explain")
        assertTrue(builtins.any { it.name == "fix" }, "Should have /fix")
        assertTrue(builtins.any { it.name == "test" }, "Should have /test")
        assertTrue(builtins.any { it.name == "refactor" }, "Should have /refactor")
    }

    @Test
    fun `slash prompts should have templates`() {
        val builtins = pl.jclab.refio.api.models.SlashPrompt.BUILTINS
        for (sp in builtins) {
            assertTrue(sp.template.isNotBlank(), "Prompt ${sp.name} should have a template")
        }
    }

    @Test
    fun `slash prompts should have descriptions`() {
        val builtins = pl.jclab.refio.api.models.SlashPrompt.BUILTINS
        for (sp in builtins) {
            assertTrue(sp.description.isNotBlank(), "Prompt ${sp.name} should have a description")
        }
    }

    @Test
    fun `slash prompt names should not have leading slash`() {
        val builtins = pl.jclab.refio.api.models.SlashPrompt.BUILTINS
        for (sp in builtins) {
            assertFalse(sp.name.startsWith("/"), "Prompt name '${sp.name}' should not start with /")
        }
    }
}
