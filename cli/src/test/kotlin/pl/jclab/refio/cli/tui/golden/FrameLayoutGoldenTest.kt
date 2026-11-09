package pl.jclab.refio.cli.tui.golden

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/**
 * Nested frames, status glyphs and the column layout at the three reference sizes and three colour depths:
 * the building blocks every screen is made of must line up before any real screen uses them.
 */
class FrameLayoutGoldenTest {

    @ParameterizedTest(name = "{0}x{1} {2}")
    @CsvSource(
        "200, 50, TRUECOLOR", "200, 50, ANSI16", "200, 50, NONE",
        "140, 42, TRUECOLOR", "140, 42, ANSI16", "140, 42, NONE",
        "80, 24, TRUECOLOR", "80, 24, ANSI16", "80, 24, NONE"
    )
    fun `frames line up at reference sizes`(width: Int, height: Int, depth: ColorDepth) {
        val rows = composeDemoScreen(width, height, Theme(depth), Glyphs.UNICODE)
        GoldenScreen.assertRowWidths(width, rows)
        GoldenScreen.assertText("frames-${width}x$height", rows)
        GoldenScreen.assertStyles("frames-${width}x$height-${depth.name.lowercase()}", rows)
    }

    private fun composeDemoScreen(width: Int, height: Int, theme: Theme, g: Glyphs): List<String> {
        val frame = Frame(theme, g)
        val cols = LayoutMode.columns(LayoutMode.of(width, height), width)
        val bodyHeight = height - 1

        val promptHeight = 4
        val (chatInner, _) = frame.innerSize(cols.chat, bodyHeight - promptHeight, padding = 2)
        val tools = frame.render(
            chatInner, 5, "tools", "3 calls · 1.2 s", FrameState.INACTIVE,
            listOf(
                "${theme.style(g.done, fg = ThemeColor.OK)} ${theme.style("read_file", fg = ThemeColor.WARN)}  src/main/kotlin/pl/jclab/refio/cli/tui/TuiApp.kt",
                "${theme.style(g.failed, fg = ThemeColor.ERR)} ${theme.style("grep_search", fg = ThemeColor.WARN)}  \"charDisplayWidth\"",
                "${theme.style(g.running, fg = ThemeColor.WARN)} ${theme.style("code_editing", fg = ThemeColor.WARN)}  TuiRenderBuffer.kt"
            )
        )
        val chatBody = listOf(
            theme.style("YOU", fg = ThemeColor.ACCENT, bold = true) + theme.style("  12:04", fg = ThemeColor.DIM),
            "Make the frames line up, including ${g.done} ${g.attention} ${g.gutter} and 中文.",
            "",
            theme.style("REFIO", fg = ThemeColor.ACCENT, bold = true) + theme.style("  12:04", fg = ThemeColor.DIM)
        ) + tools + listOf(" ${theme.style(g.gutter, fg = ThemeColor.DIM)} 3 files read")
        val chatColumn = frame.render(cols.chat, bodyHeight - promptHeight, "chat", "qwen3.8:27b-mlx · idle", FrameState.INACTIVE, chatBody, padding = 2) +
            frame.render(cols.chat, promptHeight, "prompt", "AGENT · Enter send", FrameState.FOCUSED, listOf("> make it so"))

        val columns = mutableListOf<List<String>>()
        if (cols.sidebar > 0) {
            columns += frame.render(cols.sidebar, 13, "refio", null, FrameState.INACTIVE, listOf("›_ Chat      F1", "≡  Steps     F2", "◔  Context   F3", "⌕  RAG       F4")) +
                frame.render(cols.sidebar, 10, "sessions", "3", FrameState.INACTIVE, listOf("${g.selectionBar} Fix frame widths", "  Port settings")) +
                frame.render(cols.sidebar, bodyHeight - 23, "other", null, FrameState.PLACEHOLDER, listOf("more ${g.collapsed}"))
        }
        columns += chatColumn
        if (cols.right > 0) {
            val gauge = g.gaugeFull.repeat(2) + g.gaugeHalf + g.gaugeEmpty.repeat(7)
            columns += frame.render(cols.right, 12, "steps", "1/3 done", FrameState.WARNING, listOf("${g.done} Read sources", "${g.running} Fix widths", "${g.pending} Run tests")) +
                frame.render(cols.right, bodyHeight - 12, "context", "16.7K / 32K", FrameState.INACTIVE, listOf("ctx ${theme.style(gauge, fg = ThemeColor.OK)} 13%"))
        }

        val joined = (0 until bodyHeight).map { r -> columns.joinToString(" ") { it[r] } }
        val status = theme.badge("AGENT") + " qwen3.8:27b-mlx │ ${theme.style(g.attention, fg = ThemeColor.WARN)} waiting for approval │ local · \$0"
        return joined + TuiRenderBuffer.fitToWidth(status, width)
    }
}
