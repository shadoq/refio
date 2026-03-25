package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.*

/**
 * ANSI color palette for TUI — counterpart of LCATheme from IntelliJ plugin.
 */
object TuiColors {
    // === Message role colors (counterpart of ChatView bubble colors) ===
    val user = brightGreen
    val assistant = brightCyan
    val tool = brightYellow
    val system = brightRed
    val streaming = gray

    // === Per-agent colors (counterpart of ChatMessageMapper.agentColors) ===
    val agentColors = listOf(
        brightCyan, brightGreen, brightYellow, brightMagenta,
        brightBlue, brightRed, brightWhite, yellow
    )

    fun forAgent(index: Int): TextStyle = agentColors[index % agentColors.size]

    // === Status colors (counterpart of StepsQueueView badges) ===
    val statusNew = white
    val statusPending = yellow
    val statusRunning = brightBlue
    val statusSuccess = brightGreen
    val statusFailed = brightRed

    // === Log level colors (counterpart of LogsPanel row colors) ===
    val logDebug = gray
    val logInfo = white
    val logWarn = yellow
    val logError = brightRed

    // === Context section colors (counterpart of ContextSectionColorPalette) ===
    val contextProject = brightCyan
    val contextUser = brightGreen
    val contextRag = brightMagenta
    val contextConversation = brightYellow
    val contextTools = brightBlue

    // === UI elements ===
    val tabActive: TextStyle = bold + brightWhite
    val tabInactive: TextStyle = gray
    val border: TextStyle = gray
    val accent = brightCyan
    val progressFilled = brightGreen
    val progressEmpty = gray
    val muted = gray
    val highlight = brightWhite

    // === Markdown code blocks ===
    val codeBlock = gray
    val codeKeyword = brightBlue
}
