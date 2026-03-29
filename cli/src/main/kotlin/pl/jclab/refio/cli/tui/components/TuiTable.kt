package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Generic table component with columns, scrolling support, and Mordant rendering.
 */
object TuiTable {
    fun render(
        terminal: Terminal,
        headers: List<String>,
        rows: List<List<String>>,
        maxRows: Int = 20
    ) {
        terminal.println(table {
            header { row(headers) }
            body {
                for (r in rows.takeLast(maxRows)) {
                    row(r)
                }
            }
        })
    }
}
