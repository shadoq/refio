package pl.jclab.refio.cli.tui.theme

import com.github.ajalt.mordant.rendering.AnsiLevel

/** How many colours the terminal can show; decides which escape codes [Theme] emits. */
enum class ColorDepth {
    TRUECOLOR, ANSI256, ANSI16, NONE;

    companion object {
        /**
         * Detection is left to Mordant (`NO_COLOR`, `FORCE_COLOR`, `COLORTERM`, `TERM`, Windows Terminal,
         * iTerm, ...), so Markdown rendered by Mordant and the frames drawn by [Theme] always agree.
         */
        fun fromAnsiLevel(level: AnsiLevel): ColorDepth = when (level) {
            AnsiLevel.TRUECOLOR -> TRUECOLOR
            AnsiLevel.ANSI256 -> ANSI256
            AnsiLevel.ANSI16 -> ANSI16
            AnsiLevel.NONE -> NONE
        }
    }
}
