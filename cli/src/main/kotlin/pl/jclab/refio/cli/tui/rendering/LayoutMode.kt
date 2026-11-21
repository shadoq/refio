package pl.jclab.refio.cli.tui.rendering

/**
 * Screen layout chosen from the terminal size: wide terminals get a sidebar, chat and a right column; medium
 * ones drop the sidebar; narrow ones show one pane at a time; below the minimum only a message fits. The right
 * column is there only while something is open in it; otherwise the chat takes its width.
 */
sealed interface LayoutMode {
    data object Wide : LayoutMode
    data object Medium : LayoutMode
    data object Narrow : LayoutMode
    data object TooSmall : LayoutMode

    /** Column widths; frames side by side are separated by one blank column each. */
    data class Columns(val sidebar: Int, val chat: Int, val right: Int) {
        val gaps: Int get() = listOf(sidebar, chat, right).count { it > 0 } - 1
    }

    companion object {
        const val WIDE_MIN_WIDTH = 180
        const val MEDIUM_MIN_WIDTH = 120
        const val MIN_WIDTH = 80
        const val MIN_HEIGHT = 24
        const val SIDEBAR_WIDTH = 26
        const val RIGHT_WIDTH_WIDE = 58
        const val RIGHT_WIDTH_MEDIUM = 48

        fun of(width: Int, height: Int): LayoutMode = when {
            width < MIN_WIDTH || height < MIN_HEIGHT -> TooSmall
            width >= WIDE_MIN_WIDTH -> Wide
            width >= MEDIUM_MIN_WIDTH -> Medium
            else -> Narrow
        }

        fun columns(mode: LayoutMode, width: Int, rightOpen: Boolean = true): Columns = when {
            mode == Wide && rightOpen -> Columns(SIDEBAR_WIDTH, width - SIDEBAR_WIDTH - RIGHT_WIDTH_WIDE - 2, RIGHT_WIDTH_WIDE)
            mode == Wide -> Columns(SIDEBAR_WIDTH, width - SIDEBAR_WIDTH - 1, 0)
            mode == Medium && rightOpen -> Columns(0, width - RIGHT_WIDTH_MEDIUM - 1, RIGHT_WIDTH_MEDIUM)
            else -> Columns(0, width, 0)
        }
    }
}
