package pl.jclab.refio.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [RunConfigOverrides.parse] — turns CLI `--config key=value` pairs + optional `--config-file`
 * content into a validated run-scope override map (docs/0063). Fail-loud on unknown keys / bad
 * values; inline pairs win over the file on duplicate keys.
 */
class RunConfigOverridesTest {

    @Test
    fun `parses valid key=value pairs`() {
        val result = RunConfigOverrides.parse(
            listOf("agent.max_iterations=80", "tools.native_tools=never")
        )
        assertEquals("80", result["agent.max_iterations"])
        assertEquals("never", result["tools.native_tools"])
    }

    @Test
    fun `rejects an unknown config key`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            RunConfigOverrides.parse(listOf("totally.unknown.key=1"))
        }
        assertTrue(ex.message!!.contains("totally.unknown.key"))
    }

    @Test
    fun `rejects a value that fails parse or validation`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            RunConfigOverrides.parse(listOf("agent.max_iterations=not-a-number"))
        }
        assertTrue(ex.message!!.contains("agent.max_iterations"))
    }

    @Test
    fun `rejects an entry without an equals sign`() {
        assertFailsWith<IllegalArgumentException> {
            RunConfigOverrides.parse(listOf("agent.max_iterations"))
        }
    }

    @Test
    fun `inline pairs override config-file on duplicate keys`() {
        val file = """
            # profile
            agent.max_iterations=40
            tools.native_tools=always
        """.trimIndent()
        val result = RunConfigOverrides.parse(
            pairs = listOf("agent.max_iterations=80"),
            fileContent = file
        )
        assertEquals("80", result["agent.max_iterations"])   // inline wins
        assertEquals("always", result["tools.native_tools"]) // from file
    }

    @Test
    fun `blank lines and comments in the config-file are ignored`() {
        val file = "\n# comment\n\nagent.max_iterations=40\n"
        val result = RunConfigOverrides.parse(emptyList(), file)
        assertEquals(1, result.size)
        assertEquals("40", result["agent.max_iterations"])
    }
}
