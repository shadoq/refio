package pl.jclab.refio.cli.tui.rendering

import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/** A named style from the palette; applying it goes through the current [TuiColors.theme]. */
data class Paint(val fg: ThemeColor, val bold: Boolean = false) {
    operator fun invoke(text: String): String = TuiColors.theme.style(text, fg = fg, bold = bold)
}

/**
 * Style names the views use, mapped onto the [Theme] palette. Every colour goes through one theme, so the
 * terminal's colour depth and `NO_COLOR` apply to the whole screen; the renderer sets [theme] at start.
 */
object TuiColors {
    @Volatile
    var theme: Theme = Theme(ColorDepth.TRUECOLOR)

    // === Message roles ===
    val user = Paint(ThemeColor.ACCENT, bold = true)
    val assistant = Paint(ThemeColor.ACCENT, bold = true)
    val tool = Paint(ThemeColor.WARN)
    val system = Paint(ThemeColor.ERR)
    val streaming = Paint(ThemeColor.DIM)

    // === Per-agent colours ===
    val agentColors = listOf(
        Paint(ThemeColor.INFO), Paint(ThemeColor.OK), Paint(ThemeColor.WARN), Paint(ThemeColor.VIOLET),
        Paint(ThemeColor.TEAL), Paint(ThemeColor.ERR), Paint(ThemeColor.ACCENT), Paint(ThemeColor.TEXT)
    )

    fun forAgent(index: Int): Paint = agentColors[index % agentColors.size]

    // === Status ===
    val statusNew = Paint(ThemeColor.TEXT)
    val statusPending = Paint(ThemeColor.WARN)
    val statusRunning = Paint(ThemeColor.INFO)
    val statusSuccess = Paint(ThemeColor.OK)
    val statusFailed = Paint(ThemeColor.ERR)
    val statusIncomplete = Paint(ThemeColor.WARN, bold = true)

    // === Log levels ===
    val logDebug = Paint(ThemeColor.DIM)
    val logInfo = Paint(ThemeColor.TEXT)
    val logWarn = Paint(ThemeColor.WARN)
    val logError = Paint(ThemeColor.ERR)

    // === Context sections ===
    val contextProject = Paint(ThemeColor.INFO)
    val contextUser = Paint(ThemeColor.OK)
    val contextRag = Paint(ThemeColor.VIOLET)
    val contextConversation = Paint(ThemeColor.WARN)
    val contextTools = Paint(ThemeColor.TEAL)

    // === UI elements ===
    val tabActive = Paint(ThemeColor.ACCENT, bold = true)
    val tabFocused = Paint(ThemeColor.INFO, bold = true)
    val tabInactive = Paint(ThemeColor.DIM)
    val border = Paint(ThemeColor.FRAME)
    val accent = Paint(ThemeColor.ACCENT)
    val progressFilled = Paint(ThemeColor.OK)
    val progressEmpty = Paint(ThemeColor.FRAME)
    val muted = Paint(ThemeColor.DIM)
    val highlight = Paint(ThemeColor.TEXT, bold = true)

    // === Code blocks ===
    val codeBlock = Paint(ThemeColor.DIM)
    val codeKeyword = Paint(ThemeColor.INFO)
}
