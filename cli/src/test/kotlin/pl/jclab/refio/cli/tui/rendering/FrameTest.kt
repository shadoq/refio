package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTest {

    private val frame = Frame(Theme(ColorDepth.TRUECOLOR), Glyphs.UNICODE)

    @Test
    fun `every row is exactly as wide as the frame`() {
        val body = listOf("✓ read_file  src/main/kotlin/Foo.kt", "\u001b[1m◆ needs you\u001b[0m", "中文 💬", "")
        for (w in 0..60) for (h in 0..5) for (state in FrameState.entries) {
            val rows = frame.render(w, h, "TOOLS", "3 calls · 120 ms", state, body)
            assertEquals(h, rows.size, "height at ${w}x$h")
            rows.forEachIndexed { i, row ->
                assertEquals(w, TuiRenderBuffer.visibleLength(row), "row $i at ${w}x$h $state: '${TuiRenderBuffer.stripAnsi(row)}'")
            }
        }
    }

    @Test
    fun `meta is shortened before the title`() {
        val top = TuiRenderBuffer.stripAnsi(frame.render(30, 3, "CONTEXT", "qwen3.8:27b-mlx · idle · 32K", FrameState.INACTIVE, emptyList())[0])
        assertTrue(top.startsWith("╭─ CONTEXT "), top)
        assertTrue(top.contains("…"), top)
    }

    @Test
    fun `title is upper case and body sits inside the padding`() {
        val rows = frame.render(20, 3, "chat", null, FrameState.FOCUSED, listOf("hello"), padding = 2).map(TuiRenderBuffer::stripAnsi)
        assertEquals("╭─ CHAT ───────────╮", rows[0])
        assertEquals("│  hello           │", rows[1])
        assertEquals("╰──────────────────╯", rows[2])
        assertEquals(14 to 1, frame.innerSize(20, 3, padding = 2))
    }
}
