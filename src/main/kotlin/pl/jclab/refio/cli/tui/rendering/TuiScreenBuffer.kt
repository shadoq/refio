package pl.jclab.refio.cli.tui.rendering

import java.io.Writer

/**
 * Full-screen framebuffer for TUI rendering.
 *
 * All views render into this buffer. Overlays (autocomplete popups, modals,
 * confirmation dialogs) are "painted" on top of the base content before flushing.
 * The terminal receives one atomic write per frame — no cursor repositioning
 * between partial draws.
 *
 * Usage:
 * ```
 * val screen = TuiScreenBuffer(width, height)
 * screen.setRows(0, baseContentLines)
 * screen.overlay(row, col, popupLines)        // modal on top
 * screen.flush(output)                        // one atomic write
 * screen.positionCursorAndShow(output, row, col)
 * ```
 */
class TuiScreenBuffer(val width: Int, val height: Int) {

    /** Row storage — each row is an ANSI string padded to exactly [width] visible chars. */
    private val rows = Array(height) { " ".repeat(width) }

    /** Set a single row (will be padded/truncated to width). */
    fun setRow(row: Int, text: String) {
        if (row in 0 until height) {
            rows[row] = TuiRenderBuffer.fitToWidth(text, width)
        }
    }

    /** Set multiple consecutive rows starting at [startRow]. */
    fun setRows(startRow: Int, lines: List<String>) {
        for (i in lines.indices) {
            setRow(startRow + i, lines[i])
        }
    }

    /**
     * Paint an overlay block at arbitrary position.
     * Overlays splice into existing rows — characters before and after the overlay
     * are preserved from the base content.
     *
     * This is how modals, popups, and autocomplete render on top of the base screen.
     */
    fun overlay(startRow: Int, startCol: Int, lines: List<String>) {
        for (i in lines.indices) {
            val row = startRow + i
            if (row !in 0 until height) continue
            rows[row] = spliceAnsiString(rows[row], lines[i], startCol)
        }
    }

    /**
     * Flush the entire screen buffer in one atomic write.
     *
     * Uses the provided [Writer] (typically JLine terminal's writer) to ensure
     * raw ANSI control sequences reach the terminal unprocessed. Each row uses
     * absolute cursor positioning to prevent scroll caused by line wrapping.
     */
    fun flush(output: Writer, clearScreen: Boolean = false) {
        val sb = StringBuilder(width * height + height * 20)
        sb.append("\u001b[?25l") // hide cursor
        if (clearScreen) {
            sb.append("\u001b[2J")
        }
        for (i in 0 until height) {
            // Absolute cursor positioning per row (1-based ANSI coordinates)
            sb.append("\u001b[${i + 1};1H")
            sb.append(rows[i])
            sb.append("\u001b[K") // clear to end of line
        }
        output.write(sb.toString())
        output.flush()
    }

    /** Position cursor at (row, col) and show it. 1-based for ANSI. */
    fun positionCursorAndShow(output: Writer, row: Int, col: Int) {
        output.write("\u001b[${row};${col}H\u001b[?25h")
        output.flush()
    }

    /** Show cursor without repositioning. */
    fun showCursor(output: Writer) {
        output.write("\u001b[?25h")
        output.flush()
    }

    // --- ANSI string splicing ---

    companion object {
        /**
         * Splice [overlay] text into [baseLine] at visible column [startCol].
         * Characters before startCol and after startCol+overlayWidth come from baseLine.
         * Handles ANSI escape codes in both base and overlay.
         */
        fun spliceAnsiString(baseLine: String, overlay: String, startCol: Int): String {
            val overlayWidth = TuiRenderBuffer.visibleLength(overlay)

            val before = takeVisibleChars(baseLine, startCol)
            val after = dropVisibleChars(baseLine, startCol + overlayWidth)

            return "$before\u001b[0m$overlay\u001b[0m$after"
        }

        /**
         * Take the first [n] visible characters from an ANSI string,
         * preserving ANSI escape codes within that range.
         */
        fun takeVisibleChars(s: String, n: Int): String {
            if (n <= 0) return ""
            val sb = StringBuilder()
            var visible = 0
            var i = 0
            while (i < s.length && visible < n) {
                if (s[i] == '\u001b' && i + 1 < s.length && s[i + 1] == '[') {
                    val end = s.indexOf('m', i)
                    if (end >= 0) {
                        sb.append(s, i, end + 1)
                        i = end + 1
                    } else {
                        sb.append(s[i]); i++; visible++
                    }
                } else {
                    val cp = s.codePointAt(i)
                    val w = TuiRenderBuffer.charDisplayWidth(cp)
                    if (visible + w > n) break
                    sb.appendCodePoint(cp)
                    i += Character.charCount(cp)
                    visible += w
                }
            }
            return sb.toString()
        }

        /**
         * Drop the first [n] visible characters from an ANSI string,
         * returning everything after (including ANSI codes).
         */
        fun dropVisibleChars(s: String, n: Int): String {
            var visible = 0
            var i = 0
            while (i < s.length && visible < n) {
                if (s[i] == '\u001b' && i + 1 < s.length && s[i + 1] == '[') {
                    val end = s.indexOf('m', i)
                    if (end >= 0) {
                        i = end + 1
                    } else {
                        i++; visible++
                    }
                } else {
                    val cp = s.codePointAt(i)
                    val w = TuiRenderBuffer.charDisplayWidth(cp)
                    i += Character.charCount(cp)
                    visible += w
                }
            }
            return if (i < s.length) s.substring(i) else ""
        }
    }
}
