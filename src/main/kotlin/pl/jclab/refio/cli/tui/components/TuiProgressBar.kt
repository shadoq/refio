package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.TuiColors

/**
 * Renders a text-based progress bar: [████░░░░ 45%]
 */
object TuiProgressBar {
    fun render(current: Int, max: Int, width: Int = 20): String {
        if (max <= 0) return "[${TuiColors.progressEmpty("░".repeat(width))}]"
        val percent = ((current.toDouble() / max) * 100).toInt().coerceIn(0, 100)
        val filled = ((current.toDouble() / max) * width).toInt().coerceIn(0, width)
        val empty = width - filled
        return "[${TuiColors.progressFilled("█".repeat(filled))}${TuiColors.progressEmpty("░".repeat(empty))} $percent%]"
    }
}
