private val glyphs: Map<String, Char> = mapOf(
    " _ \n| |\n|_|" to '0',
    "   \n  |\n  |" to '1',
    " _ \n _|\n|_ " to '2',
    " _ \n _|\n _|" to '3',
    "   \n|_|\n  |" to '4',
    " _ \n|_ \n _|" to '5',
    " _ \n|_ \n|_|" to '6',
    " _ \n  |\n  |" to '7',
    " _ \n|_|\n|_|" to '8',
    " _ \n|_|\n _|" to '9',
)

fun readDigits(lines: List<String>): String {
    require(lines.size % 4 == 0) { "the number of lines must be a multiple of 4" }
    val width = lines.maxOfOrNull { it.length } ?: 0
    require(width % 3 == 0) { "the width must be a multiple of 3" }
    val padded = lines.map { it.padEnd(width) }
    return padded.chunked(4).joinToString(",") { row ->
        (0 until width / 3).joinToString("") { c ->
            val cell = row.map { it.substring(3 * c, 3 * c + 3) }
            when {
                cell.all { it.isBlank() } -> " "
                cell[3].isNotBlank() -> "?"
                else -> (glyphs[cell.take(3).joinToString("\n")] ?: '?').toString()
            }
        }
    }
}
