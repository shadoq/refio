package pl.jclab.refio.core.llm

import pl.jclab.refio.core.config.ConfigKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reasoning-effort level is the user-facing replacement for the old boolean thinking
 * toggle. These tests pin the config parsing (including legacy `true`/`false` rejection) and
 * the level -> wire-value translation that every adapter depends on.
 */
class ReasoningEffortTest {

    @Test
    fun `parse accepts the enum names case-insensitively and trims`() {
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.parse("OFF"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.parse("high"))
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.parse("  Medium "))
    }

    @Test
    fun `parse rejects the legacy boolean values so migration must map them explicitly`() {
        assertNull(ReasoningEffort.parse("true"))
        assertNull(ReasoningEffort.parse("false"))
        assertNull(ReasoningEffort.parse(null))
        assertNull(ReasoningEffort.parse("bogus"))
    }

    @Test
    fun `OFF is the only level that is not on and it maps to no effort string`() {
        assertFalse(ReasoningEffort.OFF.isOn)
        assertNull(ReasoningEffort.OFF.toEffortString())
        assertTrue(ReasoningEffort.LOW.isOn)
        assertEquals("low", ReasoningEffort.LOW.toEffortString())
        assertEquals("medium", ReasoningEffort.MEDIUM.toEffortString())
        assertEquals("high", ReasoningEffort.HIGH.toEffortString())
    }

    @Test
    fun `fromThinkingKwarg reads the boolean-or-string thinking kwarg adapters receive`() {
        // Legacy Boolean true means "on, unspecified magnitude" -> MEDIUM.
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromThinkingKwarg(true))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromThinkingKwarg("high"))
        // Off / absent / blank all mean OFF.
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.fromThinkingKwarg(false))
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.fromThinkingKwarg(null))
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.fromThinkingKwarg(""))
        // A non-blank unknown string still means "on" so an unexpected value never silently disables.
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromThinkingKwarg("weird"))
    }

    @Test
    fun `config key defaults to OFF and round-trips through its serializer`() {
        val key = ConfigKeys.GENERAL_REASONING_EFFORT
        assertEquals(ReasoningEffort.OFF, key.default)
        assertEquals(ReasoningEffort.HIGH, key.parser("HIGH"))
        assertNull(key.parser("true"))
        assertEquals("HIGH", key.serializer(ReasoningEffort.HIGH))
        assertTrue(key.acceptsRaw("MEDIUM"))
        assertFalse(key.acceptsRaw("true"))
    }
}
