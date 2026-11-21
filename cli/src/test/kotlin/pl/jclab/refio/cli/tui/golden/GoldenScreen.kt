package pl.jclab.refio.cli.tui.golden

import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.theme.ThemeColor
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Compares a composed screen with a stored pattern: one file with the visible characters and one "style map"
 * with a letter per terminal column, so both layout and colouring regressions show up as a readable diff.
 *
 * Patterns live in `src/test/resources/tui-golden/`. Run the tests with `REFIO_GOLDEN_UPDATE=1` to (re)write
 * them on purpose; without it a missing or different pattern fails the test.
 */
object GoldenScreen {

    private val dir = File("src/test/resources/tui-golden")
    private val actualDir = File("build/tui-golden-actual")
    private val update = System.getenv("REFIO_GOLDEN_UPDATE") == "1"

    fun assertText(name: String, rows: List<String>) = assertPattern("$name.txt", rows.map(TuiRenderBuffer::stripAnsi))

    fun assertStyles(name: String, rows: List<String>) = assertPattern("$name.style", rows.map(::styleMap))

    /** Every row of a screen must cover exactly the terminal width, or the right edge drifts. */
    fun assertRowWidths(width: Int, rows: List<String>) {
        rows.forEachIndexed { i, row ->
            assertEquals(width, TuiRenderBuffer.visibleLength(row), "row $i: '${TuiRenderBuffer.stripAnsi(row)}'")
        }
    }

    private fun assertPattern(fileName: String, lines: List<String>) {
        val actual = lines.joinToString("\n") + "\n"
        val file = File(dir, fileName)
        if (update) {
            dir.mkdirs()
            file.writeText(actual)
            return
        }
        actualDir.mkdirs()
        File(actualDir, fileName).writeText(actual)
        if (!file.exists()) fail("Missing pattern ${file.path}; run the tests with REFIO_GOLDEN_UPDATE=1 to create it")
        val expected = file.readText()
        if (expected == actual) return
        val e = expected.lines()
        val a = actual.lines()
        val row = (0 until maxOf(e.size, a.size)).first { e.getOrNull(it) != a.getOrNull(it) }
        fail(
            "Screen differs from ${file.path} at row $row (actual written to ${File(actualDir, fileName).path})\n" +
                "expected: ${e.getOrNull(row)}\nactual:   ${a.getOrNull(row)}"
        )
    }

    /**
     * One letter per column for the foreground colour: `.` default, `b s l f t d a o w e i c v` for the palette
     * tokens (upper case when bold, `*` for bold without colour), `!` for reverse video. Spaces without reverse
     * video are kept as spaces, so the map stays readable.
     */
    fun styleMap(row: String): String {
        val out = StringBuilder()
        var fg: Char = '.'
        var bold = false
        var reverse = false
        var i = 0
        while (i < row.length) {
            if (row[i] == '\u001b' && i + 1 < row.length && row[i + 1] == '[') {
                val end = row.indexOf('m', i)
                val params = row.substring(i + 2, end).split(';').filter { it.isNotEmpty() }.map { it.toInt() }
                var p = 0
                if (params.isEmpty()) { fg = '.'; bold = false; reverse = false }
                while (p < params.size) {
                    when (val code = params[p]) {
                        0 -> { fg = '.'; bold = false; reverse = false }
                        1 -> bold = true
                        7 -> reverse = true
                        22 -> bold = false
                        27 -> reverse = false
                        39 -> fg = '.'
                        38 -> {
                            if (params[p + 1] == 2) {
                                val rgb = (params[p + 2] shl 16) or (params[p + 3] shl 8) or params[p + 4]
                                fg = ThemeColor.entries.firstOrNull { it.rgb == rgb }?.let(::letter) ?: '?'
                                p += 4
                            } else {
                                fg = ThemeColor.entries.firstOrNull { it.xterm256 == params[p + 2] }?.let(::letter) ?: '?'
                                p += 2
                            }
                        }
                        48 -> p += if (params[p + 1] == 2) 4 else 2
                        in 30..37, in 90..97 -> fg = ANSI16_LETTERS[code] ?: '?'
                    }
                    p++
                }
                i = end + 1
                continue
            }
            val cp = row.codePointAt(i)
            val columns = TuiRenderBuffer.charDisplayWidth(cp)
            val mark = when {
                reverse -> '!'
                cp == ' '.code -> ' '
                bold && fg == '.' -> '*'
                bold -> fg.uppercaseChar()
                else -> fg
            }
            repeat(columns) { out.append(mark) }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun letter(color: ThemeColor): Char = when (color) {
        ThemeColor.BG -> 'b'
        ThemeColor.SURFACE2 -> 's'
        ThemeColor.SELECTION -> 'l'
        ThemeColor.FRAME -> 'f'
        ThemeColor.TEXT -> 't'
        ThemeColor.DIM -> 'd'
        ThemeColor.ACCENT -> 'a'
        ThemeColor.OK -> 'o'
        ThemeColor.WARN -> 'w'
        ThemeColor.ERR -> 'e'
        ThemeColor.INFO -> 'i'
        ThemeColor.TEAL -> 'c'
        ThemeColor.VIOLET -> 'v'
    }

    /** In 16 colours `frame` and `dim` share bright black; the map shows what the terminal shows. */
    private val ANSI16_LETTERS = mapOf(
        90 to 'f', 37 to 't', 97 to 'a', 32 to 'o', 33 to 'w', 31 to 'e', 34 to 'i', 36 to 'c', 35 to 'v'
    )
}
