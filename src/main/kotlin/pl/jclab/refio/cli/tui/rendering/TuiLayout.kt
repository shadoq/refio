package pl.jclab.refio.cli.tui.rendering

/**
 * Layout calculations for split-pane TUI.
 *
 * When Chat tab is active → full-width chat (no split).
 * When any other tab is active → left panel (chat) + right panel (active tab).
 */
data class TuiLayoutRegions(
    val width: Int,
    val height: Int,
    val tabBarHeight: Int = 1,
    val statusBarHeight: Int = 0,
    val separatorHeight: Int = 1,
    val promptHeight: Int = 3,
    val isSplitMode: Boolean = true,
    val splitRatio: Double = 0.55,
) {
    /** Total content area between tab bar+separator and bottom of terminal. */
    val contentHeight: Int get() = (height - tabBarHeight - separatorHeight).coerceAtLeast(5)

    /** Full-width content (for non-split mode). */
    val contentWidth: Int get() = (width - 2).coerceAtLeast(20)

    // --- Split-pane dimensions ---

    /** Left panel width (chat). */
    val leftPanelWidth: Int get() = if (isSplitMode)
        ((width * splitRatio).toInt()).coerceAtLeast(30)
    else
        width

    /** Right panel width (active tab). */
    val rightPanelWidth: Int get() = if (isSplitMode)
        (width - leftPanelWidth - 1).coerceAtLeast(20) // -1 for separator
    else
        0

    /** Chat message area height (left panel minus prompt). */
    val chatHeight: Int get() = (contentHeight - promptHeight).coerceAtLeast(3)

    /** Right panel content height (full content height, no prompt). */
    val rightContentHeight: Int get() = contentHeight

    companion object {
        const val MIN_WIDTH = 80
        const val MIN_HEIGHT = 24

        fun fromTerminal(width: Int, height: Int, isSplitMode: Boolean = true): TuiLayoutRegions {
            return TuiLayoutRegions(
                width = width.coerceAtLeast(MIN_WIDTH),
                height = height.coerceAtLeast(MIN_HEIGHT),
                isSplitMode = isSplitMode
            )
        }
    }
}
