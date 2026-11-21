package pl.jclab.refio.cli.tui.theme

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTest {

    private val sgr = Regex("\u001b\\[([0-9;]*)m")

    private fun sgrParams(s: String): List<String> =
        sgr.findAll(s).flatMap { it.groupValues[1].split(';') }.filter { it.isNotEmpty() }.toList()

    private fun renderEverything(theme: Theme): String = buildString {
        ThemeColor.entries.forEach { color ->
            append(theme.style("x", fg = color))
            append(theme.style("x", bg = color))
        }
        listOf("CHAT", "PLAN", "AGENT").forEach { append(theme.badge(it)) }
    }

    @Test
    fun `no colour codes when NO_COLOR is set, only bold and reverse`() {
        val params = sgrParams(renderEverything(Theme(ColorDepth.NONE)))
        val colourParams = params.filter { p -> p.toInt().let { it in 30..49 || it in 90..107 } }
        assertEquals(emptyList(), colourParams)
        assertTrue(params.contains("7"), "badges stay visible through reverse video")
    }

    @Test
    fun `16 colour terminal never gets truecolor or 256 colour codes`() {
        val out = renderEverything(Theme(ColorDepth.ANSI16))
        assertFalse(out.contains("38;2") || out.contains("48;2") || out.contains("38;5") || out.contains("48;5"))
    }

    @Test
    fun `256 colour terminal never gets truecolor codes`() {
        val out = renderEverything(Theme(ColorDepth.ANSI256))
        assertFalse(out.contains("38;2") || out.contains("48;2"))
        assertTrue(out.contains("38;5;"))
    }

    @Test
    fun `truecolor terminal gets the exact palette`() {
        assertEquals("\u001b[38;2;241;230;207mx\u001b[0m", Theme(ColorDepth.TRUECOLOR).style("x", fg = ThemeColor.ACCENT))
    }

    @Test
    fun `ascii glyphs when asked for or when the locale is not UTF-8`() {
        val utf8 = StandardCharsets.UTF_8
        assertEquals("+", Glyphs.forEnvironment(mapOf("REFIO_ASCII" to "1", "LANG" to "en_US.UTF-8"), utf8).done)
        assertEquals("+", Glyphs.forEnvironment(mapOf("LANG" to "C"), utf8).done)
        assertEquals("+", Glyphs.forEnvironment(mapOf("LC_ALL" to "pl_PL.ISO8859-2", "LANG" to "pl_PL.UTF-8"), utf8).done)
        assertEquals("+", Glyphs.forEnvironment(emptyMap(), StandardCharsets.US_ASCII).done)
    }

    @Test
    fun `unicode glyphs in a UTF-8 locale`() {
        val utf8 = StandardCharsets.UTF_8
        assertEquals("✓", Glyphs.forEnvironment(mapOf("LANG" to "pl_PL.UTF-8"), StandardCharsets.US_ASCII).done)
        assertEquals("✓", Glyphs.forEnvironment(mapOf("LC_CTYPE" to "en_US.utf8"), utf8).done)
        assertEquals("✓", Glyphs.forEnvironment(mapOf("REFIO_ASCII" to "0"), utf8).done)
    }
}
