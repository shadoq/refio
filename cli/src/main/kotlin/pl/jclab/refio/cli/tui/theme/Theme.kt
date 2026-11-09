package pl.jclab.refio.cli.tui.theme

/**
 * Palette tokens with their fallbacks: exact RGB for truecolor terminals, the nearest xterm-256 index, and a
 * basic 16-colour foreground code (`null` = terminal default). [boldIn16] makes up for the missing shade.
 */
enum class ThemeColor(val rgb: Int, val xterm256: Int, val ansi16: Int?, val boldIn16: Boolean = false) {
    BG(0x161A1C, 234, null),
    SURFACE2(0x1A1F21, 235, null),
    SELECTION(0x2A3236, 236, null),
    FRAME(0x3B4448, 238, 90),
    TEXT(0xD8D4CA, 252, 37),
    DIM(0x8E9496, 246, 90),
    ACCENT(0xF1E6CF, 230, 97, boldIn16 = true),
    OK(0x7FD1A0, 114, 32),
    WARN(0xE3B86B, 179, 33),
    ERR(0xEE8A80, 210, 31),
    INFO(0x8AB8E6, 110, 34),
    TEAL(0x6FD6C9, 80, 36),
    VIOLET(0xC9A6F0, 183, 35)
}

/**
 * Turns palette tokens into escape codes for the detected [depth]. Escape codes are built here rather than by
 * Mordant because styled strings reach the terminal through JLine without being reduced to what it supports.
 * With [ColorDepth.NONE] only bold and reverse video remain, so focus and selection stay visible.
 */
class Theme(val depth: ColorDepth) {

    fun style(
        text: String,
        fg: ThemeColor? = null,
        bg: ThemeColor? = null,
        bold: Boolean = false,
        reverse: Boolean = false
    ): String {
        val params = mutableListOf<String>()
        if (bold) params += "1"
        if (reverse) params += "7"
        if (fg != null) params += colourParams(fg, background = false)
        if (bg != null) params += colourParams(bg, background = true)
        val distinct = params.distinct()
        if (distinct.isEmpty()) return text
        return "\u001b[${distinct.joinToString(";")}m$text$RESET"
    }

    /** Mode badge in reverse video: CHAT blue, PLAN cream, AGENT amber. */
    fun badge(mode: String): String {
        val colour = when (mode.uppercase()) {
            "CHAT" -> ThemeColor.INFO
            "PLAN" -> ThemeColor.ACCENT
            "AGENT" -> ThemeColor.WARN
            else -> ThemeColor.DIM
        }
        val label = " ${mode.uppercase()} "
        return when (depth) {
            ColorDepth.TRUECOLOR, ColorDepth.ANSI256 -> style(label, fg = ThemeColor.BG, bg = colour, bold = true)
            ColorDepth.ANSI16, ColorDepth.NONE -> style(label, fg = colour, bold = true, reverse = true)
        }
    }

    private fun colourParams(color: ThemeColor, background: Boolean): List<String> = when (depth) {
        ColorDepth.TRUECOLOR -> {
            val r = (color.rgb shr 16) and 0xFF
            val g = (color.rgb shr 8) and 0xFF
            val b = color.rgb and 0xFF
            listOf("${if (background) 48 else 38};2;$r;$g;$b")
        }
        ColorDepth.ANSI256 -> listOf("${if (background) 48 else 38};5;${color.xterm256}")
        ColorDepth.ANSI16 -> when {
            background && color == ThemeColor.SELECTION -> listOf("7")
            color.ansi16 == null -> emptyList()
            background -> listOf("${color.ansi16 + 10}")
            color.boldIn16 -> listOf("${color.ansi16}", "1")
            else -> listOf("${color.ansi16}")
        }
        ColorDepth.NONE -> if (background && color == ThemeColor.SELECTION) listOf("7") else emptyList()
    }

    companion object {
        const val RESET = "\u001b[0m"
    }
}
