package pl.jclab.refio.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ConfigKey.acceptsRaw] — used by run-scope override parsing (docs/0063) to validate raw
 * `--config key=value` strings against a key's parser AND validator before they become overrides.
 */
class ConfigKeyTest {

    @Test
    fun `acceptsRaw is true for a parseable value`() {
        assertTrue(ConfigKeys.MAX_ITERATIONS.acceptsRaw("80"))
    }

    @Test
    fun `acceptsRaw is false for an unparseable value`() {
        assertFalse(ConfigKeys.MAX_ITERATIONS.acceptsRaw("not-a-number"))
    }

    @Test
    fun `acceptsRaw is false when the validator rejects the value`() {
        // API_CALL_TIMEOUT parses "0" fine, but its validator requires > 0.
        assertFalse(ConfigKeys.API_CALL_TIMEOUT.acceptsRaw("0"))
    }

    @Test
    fun `acceptsRaw is true for an allowed enum-like value`() {
        assertTrue(ConfigKeys.NATIVE_TOOLS_MODE.acceptsRaw("never"))
    }

    @Test
    fun `acceptsRaw is false for a value outside the allowed set`() {
        assertFalse(ConfigKeys.NATIVE_TOOLS_MODE.acceptsRaw("bogus"))
    }
}
