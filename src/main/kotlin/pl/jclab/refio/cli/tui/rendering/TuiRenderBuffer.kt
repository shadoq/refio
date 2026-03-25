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

        /** Measure visible width of a string (stripping ANSI escape codes). */
        fun visibleLength(s: String): Int = stripAnsi(s).length

        /** Strip all ANSI escape sequences from a string. */
        fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

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
                    sb.append(s[i])
                    i++
                    visibleCount++
                }
            }
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
