package pl.jclab.refio.ui.settings

import pl.jclab.refio.core.services.ConfigService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context-size dropdown offers a fixed set of sizes, but the underlying config key has no
 * validator, so a user can legitimately configure any number. These tests pin what the field
 * then shows, against the same option list the panel uses.
 *
 * Regression: a non-editable combo box rejects a selection outside its model, so such a value
 * used to leave the field displaying the value it was constructed with (32768) instead of
 * anything derived from the configuration.
 */
class NumericDropdownValueTest {

    private val contextSizes = ContextSizeOptions.GENERIC_OPENAI

    @Test
    fun `offered sizes double up to 256k and then grow by 128k`() {
        // Doubling past 256k skips too much of the range local servers actually run.
        assertEquals(
            listOf(
                "2048", "4096", "8192", "16384", "32768", "65536", "131072", "262144",
                "393216", "524288", "655360", "786432", "917504", "1048576"
            ),
            contextSizes
        )
    }

    @Test
    fun `every provider offers the default context size, so the field starts on a real option`() {
        // A default outside the offered set would be normalized away on open, silently showing
        // the user a different number than the one in effect.
        val default = ConfigService.DEFAULT_CONTEXT_SIZE.toString()
        assertTrue(default in ContextSizeOptions.OLLAMA, "Ollama")
        assertTrue(default in ContextSizeOptions.LM_STUDIO, "LM Studio")
        assertTrue(default in ContextSizeOptions.GENERIC_OPENAI, "custom OpenAI-compatible")
    }

    @Test
    fun `a configured window between two offered sizes shows the larger size that still fits`() {
        // 760000 is a real llama.cpp window and is not on the list. Rounding down keeps the
        // displayed value safe to send; rounding up would over-declare the server's window.
        assertEquals("655360", nearestNumericOption(contextSizes, "760000"))
    }

    @Test
    fun `a configured window above every offered size shows the largest one`() {
        assertEquals("1048576", nearestNumericOption(contextSizes, "9000000"))
    }

    @Test
    fun `a configured window below every offered size shows the smallest one`() {
        assertEquals("2048", nearestNumericOption(contextSizes, "1000"))
    }

    @Test
    fun `a configured window that is offered is shown unchanged`() {
        assertEquals("131072", nearestNumericOption(contextSizes, "131072"))
        assertEquals("786432", nearestNumericOption(contextSizes, "786432"))
    }

    @Test
    fun `a non-numeric value passes through so the helper is safe for every dropdown`() {
        assertEquals("http://localhost:1234/v1", nearestNumericOption(contextSizes, "http://localhost:1234/v1"))
    }

    @Test
    fun `a dropdown with no numeric options leaves the value alone`() {
        assertEquals("42", nearestNumericOption(listOf("auto", "manual"), "42"))
    }
}
