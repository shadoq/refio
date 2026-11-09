package pl.jclab.refio.cli.tui.rendering

/**
 * Shortening text to a column budget. Widths are terminal columns (wide glyphs count 2, escape codes 0).
 * Text that fits is returned untouched; shortened text comes back without styling, so callers style it after.
 */
object TextFit {

    /** `abcdefghij` -> `abcd…` */
    fun truncateEnd(text: String, width: Int, ellipsis: String = "…"): String {
        if (width <= 0) return ""
        if (TuiRenderBuffer.visibleLength(text) <= width) return text
        val plain = TuiRenderBuffer.stripAnsi(text)
        val ellipsisWidth = TuiRenderBuffer.visibleLength(ellipsis)
        if (width <= ellipsisWidth) return takeColumns(plain, width)
        return takeColumns(plain, width - ellipsisWidth) + ellipsis
    }

    /** `src/main/kotlin/Foo.kt` -> `src/m…Foo.kt`: keeps both ends, which matters for paths and model names. */
    fun truncateMiddle(text: String, width: Int, ellipsis: String = "…"): String {
        if (width <= 0) return ""
        if (TuiRenderBuffer.visibleLength(text) <= width) return text
        val plain = TuiRenderBuffer.stripAnsi(text)
        val ellipsisWidth = TuiRenderBuffer.visibleLength(ellipsis)
        if (width <= ellipsisWidth) return takeColumns(plain, width)
        val available = width - ellipsisWidth
        val head = available / 2
        return takeColumns(plain, head) + ellipsis + takeLastColumns(plain, available - head)
    }

    /**
     * Breaks [text] into lines of at most [width] columns at spaces. A word longer than a line is split, so
     * nothing is lost; leading indentation is kept on every line. Styling is dropped.
     */
    fun wrapWords(text: String, width: Int): List<String> {
        if (width <= 0) return emptyList()
        val plain = TuiRenderBuffer.stripAnsi(text)
        val indent = plain.takeWhile { it == ' ' }.takeIf { it.length * 2 < width } ?: ""
        val room = width - indent.length
        val lines = mutableListOf<String>()
        var line = ""
        for (word in plain.trimStart(' ').split(' ').flatMap { splitColumns(it, room) }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && TuiRenderBuffer.visibleLength(candidate) > room) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.map { indent + it }
    }

    private fun splitColumns(word: String, columns: Int): List<String> {
        if (TuiRenderBuffer.visibleLength(word) <= columns) return listOf(word)
        val parts = mutableListOf<String>()
        var rest = word
        while (rest.isNotEmpty()) {
            // A character wider than the whole line still has to go somewhere.
            val head = takeColumns(rest, columns).ifEmpty { rest.substring(0, Character.charCount(rest.codePointAt(0))) }
            parts += head
            rest = rest.substring(head.length)
        }
        return parts
    }

    private fun takeColumns(plain: String, columns: Int): String {
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < plain.length) {
            val cp = plain.codePointAt(i)
            val w = TuiRenderBuffer.charDisplayWidth(cp)
            if (used + w > columns) break
            sb.appendCodePoint(cp)
            used += w
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun takeLastColumns(plain: String, columns: Int): String {
        var used = 0
        var start = plain.length
        while (start > 0) {
            val cp = plain.codePointBefore(start)
            val w = TuiRenderBuffer.charDisplayWidth(cp)
            if (used + w > columns) break
            used += w
            start -= Character.charCount(cp)
        }
        return plain.substring(start)
    }
}
