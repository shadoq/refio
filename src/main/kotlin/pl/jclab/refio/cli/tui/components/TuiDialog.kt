package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.rendering.TextStyles.*
import pl.jclab.refio.cli.tui.rendering.TuiColors

/**
 * Modal dialog component for confirmations and inputs.
 */
object TuiDialog {
    fun renderConfirm(terminal: Terminal, title: String, message: String) {
        terminal.println()
        terminal.println(TuiColors.border("┌${"─".repeat(60)}┐"))
        terminal.println(TuiColors.border("│") + " " + bold(title).padEnd(59) + TuiColors.border("│"))
        terminal.println(TuiColors.border("├${"─".repeat(60)}┤"))
        terminal.println(TuiColors.border("│") + " " + message.take(59).padEnd(59) + TuiColors.border("│"))
        terminal.println(TuiColors.border("│") + " " + TuiColors.muted("[Y]es  [N]o").toString().padEnd(59) + TuiColors.border("│"))
        terminal.println(TuiColors.border("└${"─".repeat(60)}┘"))
    }
}
