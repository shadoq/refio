package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.TuiColors

/**
 * Animated spinner for async operations.
 */
object TuiSpinner {
    private val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    fun frame(tick: Long): String {
        val idx = (tick % frames.size).toInt()
        return TuiColors.accent(frames[idx])
    }

    fun render(tick: Long, message: String): String {
        return "${frame(tick)} ${TuiColors.streaming(message)}"
    }
}
