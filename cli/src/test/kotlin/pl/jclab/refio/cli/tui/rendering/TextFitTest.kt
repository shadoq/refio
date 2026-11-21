package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFitTest {

    private fun width(s: String) = TuiRenderBuffer.visibleLength(s)

    @Test
    fun `long path keeps its start and file name and loses the middle`() {
        assertEquals("src/m…Foo.kt", TextFit.truncateMiddle("src/main/kotlin/pl/Foo.kt", 12))
    }

    @Test
    fun `text that fits is returned unchanged, styling included`() {
        val styled = "\u001b[1mqwen3.8:27b\u001b[0m"
        assertEquals(styled, TextFit.truncateMiddle(styled, 11))
        assertEquals(styled, TextFit.truncateEnd(styled, 20))
    }

    @Test
    fun `styled text is measured without its escape codes`() {
        val styled = "\u001b[38;5;110mabcdefghijkl\u001b[0m"
        val cut = TextFit.truncateEnd(styled, 6)
        assertEquals("abcde…", TuiRenderBuffer.stripAnsi(cut))
    }

    @Test
    fun `result is never wider than the requested width`() {
        val samples = listOf("src/main/kotlin/pl/jclab/refio/Foo.kt", "中文中文中文中文", "💬 chat 💬 chat", "a", "")
        for (s in samples) for (w in 0..20) {
            assertTrue(width(TextFit.truncateMiddle(s, w)) <= w, "middle '$s' at $w")
            assertTrue(width(TextFit.truncateEnd(s, w)) <= w, "end '$s' at $w")
        }
    }

    @Test
    fun `wrapping breaks between words so a narrow chat never cuts a word in half`() {
        val text = "The TUI computes its own window and falls back to 128K. I will read it from the same place as the plugin."
        val lines = TextFit.wrapWords(text, 76)
        assertEquals(listOf("The TUI computes its own window and falls back to 128K. I will read it from", "the same place as the plugin."), lines)
    }

    @Test
    fun `a word longer than the line is split, not cut, so no text is lost`() {
        assertEquals(
            listOf("see", "src/main/kot", "lin/pl/jclab", "/refio/Foo.k", "t now"),
            TextFit.wrapWords("see src/main/kotlin/pl/jclab/refio/Foo.kt now", 12)
        )
    }

    @Test
    fun `wrapped code or list keeps its indentation on every line`() {
        assertEquals(listOf("    val total =", "    a + b"), TextFit.wrapWords("    val total = a + b", 16))
    }

    @Test
    fun `wrapping measures columns, so wide characters never overflow`() {
        for (w in 2..12) assertTrue(TextFit.wrapWords("中文中文 中文中文中文 x", w).all { width(it) <= w }, "at $w")
    }

    @Test
    fun `tiny widths do not throw`() {
        assertEquals("", TextFit.truncateMiddle("abc", 0))
        assertEquals("a", TextFit.truncateMiddle("abc", 1))
        assertEquals("", TextFit.truncateEnd("abc", -3))
    }
}
