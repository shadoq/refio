package pl.jclab.refio.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The streaming character counter exists so a user can tell that generated tokens are
 * actually arriving while a code-editing tool runs. These tests pin the wording rules
 * that make that signal readable: correct singular/plural, locale-stable grouping, and
 * a graceful "no counter" form for non-streaming states.
 */
class StreamProgressFormatTest {

    @Test
    fun `single character uses singular unit`() {
        assertEquals("1 char", StreamProgressFormat.charCount(1))
    }

    @Test
    fun `multiple characters use plural unit`() {
        assertEquals("2 chars", StreamProgressFormat.charCount(2))
    }

    @Test
    fun `zero is shown so an idle-but-started stream still reads as live`() {
        assertEquals("0 chars", StreamProgressFormat.charCount(0))
    }

    @Test
    fun `large counts are grouped with a stable separator regardless of host locale`() {
        assertEquals("1,234 chars", StreamProgressFormat.charCount(1234))
        assertEquals("1,000,000 chars", StreamProgressFormat.charCount(1_000_000))
    }

    @Test
    fun `negative counts clamp to zero rather than rendering a minus sign`() {
        assertEquals("0 chars", StreamProgressFormat.charCount(-5))
    }

    @Test
    fun `a null count yields the plain label so non-streaming states show no counter`() {
        assertEquals("Generating...", StreamProgressFormat.withCharCount("Generating...", null))
    }

    @Test
    fun `a present count is appended after a middle-dot separator`() {
        assertEquals("Generating... · 1,234 chars", StreamProgressFormat.withCharCount("Generating...", 1234))
    }

    @Test
    fun `the standalone counter suffix matches the suffix used by withCharCount`() {
        // The plugin patches a separate counter label in place during streaming, so its wording
        // must stay byte-identical to the inline form to avoid a visible jump on the final render.
        assertEquals("· 1,234 chars", StreamProgressFormat.counterSuffix(1234))
        assertEquals("Generating... ${StreamProgressFormat.counterSuffix(1234)}",
            StreamProgressFormat.withCharCount("Generating...", 1234))
    }
}
