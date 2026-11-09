package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Frames are aligned by counting columns, so every glyph must be measured the way terminals draw it:
 * one column off and the right border of a frame lands in the wrong place.
 */
class CharDisplayWidthTest {

    private fun widthOf(glyph: String): Int = TuiRenderBuffer.visibleLength(glyph)

    @Test
    fun `frame and status glyphs take one column`() {
        val glyphs = listOf(
            "✓", "✕", "✗", "◐", "○", "●", "◆", "▸", "▾", "⎿", "█", "▌", "░",
            "╭", "╮", "╰", "╯", "│", "─", "╌", "╎", "›", "≡", "◔", "⌕", "☰", "◇", "⇄", "▤", "✱", "⋯", "⚙", "⟳"
        )
        val wrong = glyphs.filter { widthOf(it) != 1 }.map { "$it=${widthOf(it)}" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `emoji and CJK still take two columns`() {
        val glyphs = listOf("💬", "📝", "🤖", "😀", "⚡", "✅", "❌", "⭐", "⌛", "中")
        val wrong = glyphs.filter { widthOf(it) != 2 }.map { "$it=${widthOf(it)}" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `combining marks and variation selectors take no column`() {
        assertEquals(1, widthOf("é"))
        assertEquals(1, widthOf("✓︎"))
    }
}
