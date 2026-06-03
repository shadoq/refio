package pl.jclab.refio.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ConfigPrintView] — renders the resolved config for the CLI `--print-config` flag (docs/0063),
 * redacting secrets and marking run-scope overrides.
 */
class ConfigPrintViewTest {

    @Test
    fun `isSecret detects api key fields but not normal keys`() {
        assertTrue(ConfigPrintView.isSecret("providers.openai.openai_api_key"))
        assertTrue(ConfigPrintView.isSecret("providers.anthropic.anthropic_api_key"))
        assertFalse(ConfigPrintView.isSecret("agent.max_iterations"))
        assertFalse(ConfigPrintView.isSecret("providers.ollama.ollama_endpoint"))
    }

    @Test
    fun `render redacts secret values but shows normal ones`() {
        val out = ConfigPrintView.render(
            listOf(
                ConfigPrintView.Entry("providers.openai.openai_api_key", "sk-secret-123", isOverride = false),
                ConfigPrintView.Entry("agent.max_iterations", "80", isOverride = false),
            )
        )
        assertFalse(out.contains("sk-secret-123"))
        assertTrue(out.contains("***redacted***"))
        assertTrue(out.contains("agent.max_iterations = 80"))
    }

    @Test
    fun `render marks overridden entries with a marker`() {
        val out = ConfigPrintView.render(
            listOf(
                ConfigPrintView.Entry("tools.native_tools", "never", isOverride = true),
                ConfigPrintView.Entry("general.thinking_enabled", "false", isOverride = false),
            )
        )
        val overridden = out.lineSequence().first { it.startsWith("tools.native_tools ") }
        val normal = out.lineSequence().first { it.startsWith("general.thinking_enabled ") }
        assertTrue(overridden.contains("[override]"))
        assertFalse(normal.contains("[override]"))
    }
}
