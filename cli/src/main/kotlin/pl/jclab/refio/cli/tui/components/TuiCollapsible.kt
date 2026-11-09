package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.Paint
import pl.jclab.refio.cli.tui.rendering.TuiColors

/**
 * Collapsible section component — expand/fold like in IntelliJ.
 */
object TuiCollapsible {
    fun render(terminal: Terminal, title: String, expanded: Boolean, content: String, color: Paint = TuiColors.accent) {
        val arrow = if (expanded) "▼" else "▶"
        terminal.println(color("$arrow $title"))
        if (expanded && content.isNotBlank()) {
            for (line in content.lines()) {
                terminal.println("  $line")
            }
        }
    }
}
