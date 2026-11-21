package pl.jclab.refio.cli.tui.rendering

/**
 * Fixed-width line buffer for split-pane rendering.
 * Each view renders into a buffer, then the compositor merges buffers side-by-side.
 *
 * Handles ANSI-aware string measurement, truncation, and padding.
 */
class TuiRenderBuffer(val maxWidth: Int, val maxHeight: Int) {

    private val lines = mutableListOf<String>()

    /** Add a single line, truncating to maxWidth visible chars. */
    fun addLine(text: String = "") {
        lines.add(fitToWidth(text, maxWidth))
    }

    /** Add multiple lines from multiline text. */
    fun addLines(text: String) {
        for (line in text.lines()) {
            if (lines.size >= maxHeight) break
            addLine(line)
        }
    }

    /** Add text with word-wrapping to maxWidth. */
    fun addWrapped(text: String) {
        for (rawLine in text.lines()) {
            if (lines.size >= maxHeight) break
            val stripped = stripAnsi(rawLine)
            if (visibleLength(rawLine) <= maxWidth) {
                addLine(rawLine)
            } else {
                // Simple char-based wrapping for plain text
                var remaining = stripped
                while (remaining.isNotEmpty() && lines.size < maxHeight) {
                    val chunk = remaining.take(maxWidth)
                    remaining = remaining.drop(maxWidth)
                    addLine(chunk)
                }
            }
        }
    }

    /** Fill remaining space with blank lines up to maxHeight. */
    fun fill() {
        while (lines.size < maxHeight) {
            lines.add(" ".repeat(maxWidth))
        }
    }

    /** Return exactly maxHeight lines, each padded to maxWidth. */
    fun getLines(): List<String> {
        fill()
        return lines.take(maxHeight).map { fitToWidth(it, maxWidth) }
    }

    /** Return only the last maxHeight lines (for scrollable content). */
    fun getVisibleLines(): List<String> {
        fill()
        val result = if (lines.size > maxHeight) lines.takeLast(maxHeight) else lines.take(maxHeight)
        return result.map { fitToWidth(it, maxWidth) }
    }

    val lineCount: Int get() = lines.size

    companion object {
        private val ANSI_REGEX = Regex("\u001b\\[[0-9;]*m")

        // Control characters are written as regex escapes, never as raw characters in the pattern.
        /**
         * Any escape sequence: CSI, OSC (ended by BEL or ESC \), DCS/SOS/PM/APC (ended by ESC \) or a two-character one,
         * and the one-character CSI and OSC introducers some terminals also obey.
         */
        private val ESCAPE_SEQUENCE = Regex(
            "\\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001b]*(?:\\u0007|\\u001b\\\\)?|[PX^_][^\\u001b]*(?:\\u001b\\\\)?|[ -~]?)" +
                "|\\u009b[0-?]*[ -/]*[@-~]|\\u009d[^\\u0007\\u009c]*(?:\\u0007|\\u009c)?"
        )
        private val SGR = Regex("\\u001b\\[[0-9;:]*m")
        /** Control characters a drawn row must not contain; ESC is handled by [ESCAPE_SEQUENCE]. */
        private val CONTROL_CHARS = Regex("[\\u0000-\\u001a\\u001c-\\u001f\\u007f-\\u009f]")

        private fun hasControl(s: String): Boolean = s.any { it < ' ' || it in '\u007f'..'\u009f' }

        /**
         * Text from outside the app (tool output, file content, model or user text) made safe to draw: escape
         * sequences and control characters are removed, so they can neither move the cursor nor talk to the terminal,
         * and tabs become spaces, so widths stay right. Line breaks are kept.
         */
        fun cleanText(s: String): String {
            if (!hasControl(s)) return s
            val noEscapes = ESCAPE_SEQUENCE.replace(s.replace("\t", "    ").replace("\r\n", "\n"), "")
            return CONTROL_CHARS.replace(noEscapes) { if (it.value == "\n") "\n" else "" }
        }

        /** A composed screen row with only its colours left: any other escape sequence or control character is dropped. */
        fun keepOnlyColours(row: String): String {
            if (!hasControl(row.replace(SGR, ""))) return row
            val coloursOnly = ESCAPE_SEQUENCE.replace(row) { if (SGR.matches(it.value)) it.value else "" }
            return CONTROL_CHARS.replace(coloursOnly, "")
        }

        /** Code points below U+FFFF with default emoji presentation (East Asian Width "W" since Unicode 9). */
        private val BMP_EMOJI_PRESENTATION: List<IntRange> = listOf(
            0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
            0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
            0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
            0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
            0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
            0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
            0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55
        )

        /** Measure visible (display) width of a string, stripping ANSI escape codes.
         *  Accounts for wide characters (emoji, CJK) that take 2 terminal columns. */
        fun visibleLength(s: String): Int {
            val stripped = stripAnsi(s)
            var width = 0
            var i = 0
            while (i < stripped.length) {
                val cp = stripped.codePointAt(i)
                width += charDisplayWidth(cp)
                i += Character.charCount(cp)
            }
            return width
        }

        /** Strip all ANSI escape sequences from a string. */
        fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

        /** Approximate terminal display width for a Unicode code point. */
        internal fun charDisplayWidth(cp: Int): Int {
            // Zero-width: combining marks, ZWJ, ZWNJ, variation selectors, zero-width joiner
            if (cp == 0x200D || cp == 0x200B || cp == 0x200C || cp == 0xFEFF) return 0
            if (Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() ||
                        it == Character.ENCLOSING_MARK.toInt() ||
                        it == Character.FORMAT.toInt() }) return 0
            // Variation selectors (FE00-FE0F) are zero-width modifiers
            if (cp in 0xFE00..0xFE0F) return 0
            // Emoji: ALL emoji are width 2 in modern terminals (including BMP emoji)
            if (cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
                cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FA6F ||
                cp in 0x1FA70..0x1FAFF) return 2
            // BMP symbols that terminals draw as emoji (2 columns). Their neighbours in the same blocks
            // (✓ ◆ ▸ ⎿ ☰ ...) are plain text symbols and take 1 column.
            if (BMP_EMOJI_PRESENTATION.any { cp in it }) return 2
            // CJK Unified Ideographs, Hangul, Katakana/Hiragana, fullwidth forms
            if (cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3040..0x33BF ||
                cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
                cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
                cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x2FA1F) return 2
            return 1
        }

        /**
         * Pad or truncate a string to exactly [width] visible characters,
         * preserving ANSI escape codes within the visible portion.
         */
        fun fitToWidth(s: String, width: Int): String {
            val visible = visibleLength(s)
            return when {
                visible == width -> s
                visible < width -> s + " ".repeat(width - visible)
                else -> truncateToWidth(s, width)
            }
        }

        /**
         * Truncate a string to [width] visible characters, preserving ANSI state.
         * Appends reset sequence at the end if the string contained ANSI codes.
         */
        private fun truncateToWidth(s: String, width: Int): String {
            val sb = StringBuilder()
            var visibleCount = 0
            var i = 0
            var hasAnsi = false
            while (i < s.length && visibleCount < width) {
                if (s[i] == '\u001b' && i + 1 < s.length && s[i + 1] == '[') {
                    // Copy full ANSI sequence
                    val end = s.indexOf('m', i)
                    if (end >= 0) {
                        sb.append(s, i, end + 1)
                        i = end + 1
                        hasAnsi = true
                    } else {
                        sb.append(s[i])
                        i++
                        visibleCount++
                    }
                } else {
                    val cp = s.codePointAt(i)
                    val charWidth = charDisplayWidth(cp)
                    if (visibleCount + charWidth > width) break
                    sb.appendCodePoint(cp)
                    i += Character.charCount(cp)
                    visibleCount += charWidth
                }
            }
            // Pad if wide char caused us to stop 1 short
            if (visibleCount < width) sb.append(" ".repeat(width - visibleCount))
            if (hasAnsi) sb.append("\u001b[0m")
            return sb.toString()
        }

        /** Merge two lists of lines side-by-side with a separator column. */
        fun mergeSideBySide(
            left: List<String>, leftWidth: Int,
            right: List<String>, rightWidth: Int,
            separator: String = "│"
        ): List<String> {
            val height = maxOf(left.size, right.size)
            return (0 until height).map { i ->
                val l = if (i < left.size) fitToWidth(left[i], leftWidth) else " ".repeat(leftWidth)
                val r = if (i < right.size) fitToWidth(right[i], rightWidth) else " ".repeat(rightWidth)
                "$l$separator$r"
            }
        }
    }
}
