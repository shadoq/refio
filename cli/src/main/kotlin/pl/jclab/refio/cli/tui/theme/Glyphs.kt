package pl.jclab.refio.cli.tui.theme

import java.nio.charset.Charset

/**
 * Status, gauge and frame glyphs. Every glyph is one terminal column wide; the ASCII set is used when the user
 * asks for it (`REFIO_ASCII=1`) or the terminal cannot show UTF-8.
 */
class Glyphs private constructor(
    val done: String,
    val failed: String,
    val running: String,
    val pending: String,
    val attention: String,
    val collapsed: String,
    val expanded: String,
    val gutter: String,
    val gaugeFull: String,
    val gaugeHalf: String,
    val gaugeEmpty: String,
    val selectionBar: String,
    val ellipsis: String,
    val topLeft: String,
    val topRight: String,
    val bottomLeft: String,
    val bottomRight: String,
    val horizontal: String,
    val vertical: String,
    val dashedHorizontal: String,
    val dashedVertical: String,
    val dot: String,
    val arrowDown: String,
    val arrowUp: String,
    val arrowRight: String,
    val separator: String
) {
    val isAscii: Boolean get() = this === ASCII

    companion object {
        val UNICODE = Glyphs(
            done = "✓", failed = "✕", running = "◐", pending = "○", attention = "◆",
            collapsed = "▸", expanded = "▾", gutter = "⎿",
            gaugeFull = "█", gaugeHalf = "▌", gaugeEmpty = "░", selectionBar = "▌", ellipsis = "…",
            topLeft = "╭", topRight = "╮", bottomLeft = "╰", bottomRight = "╯", horizontal = "─", vertical = "│",
            dashedHorizontal = "╌", dashedVertical = "╎",
            dot = "●", arrowDown = "↓", arrowUp = "↑", arrowRight = "→", separator = "│"
        )

        val ASCII = Glyphs(
            done = "+", failed = "x", running = "*", pending = "o", attention = "!",
            collapsed = ">", expanded = "v", gutter = "|",
            gaugeFull = "#", gaugeHalf = ".", gaugeEmpty = " ", selectionBar = ">", ellipsis = "~",
            topLeft = "+", topRight = "+", bottomLeft = "+", bottomRight = "+", horizontal = "-", vertical = "|",
            dashedHorizontal = "-", dashedVertical = ":",
            dot = "*", arrowDown = "v", arrowUp = "^", arrowRight = "->", separator = "|"
        )

        /**
         * The first non-empty of `LC_ALL`, `LC_CTYPE`, `LANG` decides, as in the C library; with none set,
         * the terminal's own [charset] does.
         */
        fun forEnvironment(env: Map<String, String> = System.getenv(), charset: Charset = Charset.defaultCharset()): Glyphs {
            val forced = env["REFIO_ASCII"]
            if (!forced.isNullOrEmpty() && forced != "0" && !forced.equals("false", ignoreCase = true)) return ASCII
            val locale = listOf("LC_ALL", "LC_CTYPE", "LANG").firstNotNullOfOrNull { key -> env[key]?.takeIf { it.isNotEmpty() } }
            return if (isUtf8(locale ?: charset.name())) UNICODE else ASCII
        }

        private fun isUtf8(name: String): Boolean = name.lowercase().let { "utf-8" in it || "utf8" in it }
    }
}
