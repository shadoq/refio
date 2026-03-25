package pl.jclab.refio.cli.tui.input

import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiTab

/**
 * Key binding definitions for TUI navigation.
 *
 * NAVIGATION:
 *   F1-F7 / Ctrl+1..7  → Tab switching
 *   Ctrl+S              → Settings
 *   Ctrl+H              → History
 *   Escape              → Back to Chat
 *
 * CHAT:
 *   Enter       → Send message
 *   Ctrl+M      → Cycle mode (Chat→Plan→Agent)
 *   Ctrl+C      → Cancel current operation
 *   Ctrl+Q      → Quit
 */
sealed class TuiAction {
    data class SwitchTab(val tab: TuiTab) : TuiAction()
    data class SwitchScreen(val screen: TuiScreen) : TuiAction()
    data object SendMessage : TuiAction()
    data object CycleMode : TuiAction()
    data object CancelOperation : TuiAction()
    data object Quit : TuiAction()
    data object ScrollUp : TuiAction()
    data object ScrollDown : TuiAction()
    data object PageUp : TuiAction()
    data object PageDown : TuiAction()
    data object ToggleExpand : TuiAction()
    data object BackToMain : TuiAction()
    data class TypeChar(val char: Char) : TuiAction()
    data object Backspace : TuiAction()
    data object NewLine : TuiAction()
    data object AutocompleteNext : TuiAction()
    data object AutocompletePrev : TuiAction()
    data object AutocompleteAccept : TuiAction()
    data object AutocompleteDismiss : TuiAction()
    data object ToggleThinking : TuiAction()
    data object ToggleNoEgress : TuiAction()
    data object ToggleExecutionMode : TuiAction()
}

object TuiKeybindings {
    // F-key escape sequences (common terminal emulators)
    private val F_KEY_MAP = mapOf(
        "\u001bOP" to TuiAction.SwitchTab(TuiTab.CHAT),       // F1
        "\u001bOQ" to TuiAction.SwitchTab(TuiTab.STEPS),      // F2
        "\u001bOR" to TuiAction.SwitchTab(TuiTab.CONTEXT),    // F3
        "\u001bOS" to TuiAction.SwitchTab(TuiTab.RAG),        // F4
        "\u001b[15~" to TuiAction.SwitchTab(TuiTab.LOGS),     // F5
        "\u001b[17~" to TuiAction.SwitchTab(TuiTab.DEBUG),    // F6
        "\u001b[18~" to TuiAction.SwitchTab(TuiTab.API_LOGS), // F7
        "\u001b[19~" to TuiAction.SwitchScreen(TuiScreen.SETTINGS), // F8
    )

    // Ctrl+number (Ctrl+1 = 0x31 masked)
    private val CTRL_NUM_MAP = mapOf(
        1 to TuiAction.SwitchTab(TuiTab.CHAT),
        2 to TuiAction.SwitchTab(TuiTab.STEPS),
        3 to TuiAction.SwitchTab(TuiTab.CONTEXT),
        4 to TuiAction.SwitchTab(TuiTab.RAG),
        5 to TuiAction.SwitchTab(TuiTab.LOGS),
        6 to TuiAction.SwitchTab(TuiTab.DEBUG),
        7 to TuiAction.SwitchTab(TuiTab.API_LOGS),
    )

    fun resolveEscapeSequence(seq: String): TuiAction? {
        return F_KEY_MAP[seq] ?: when (seq) {
            "\u001b[A" -> TuiAction.ScrollUp      // Arrow Up
            "\u001b[B" -> TuiAction.ScrollDown     // Arrow Down
            "\u001b[5~" -> TuiAction.PageUp        // Page Up
            "\u001b[6~" -> TuiAction.PageDown      // Page Down
            else -> null
        }
    }

    fun resolveControlChar(code: Int): TuiAction? {
        return when (code) {
            3 -> TuiAction.CancelOperation    // Ctrl+C
            8 -> TuiAction.SwitchScreen(TuiScreen.HISTORY) // Ctrl+H
            5 -> TuiAction.ToggleExecutionMode // Ctrl+E
            13 -> TuiAction.SendMessage       // Enter (CR)
            10 -> TuiAction.SendMessage       // Enter (LF)
            17 -> TuiAction.Quit              // Ctrl+Q
            19 -> TuiAction.SwitchScreen(TuiScreen.SETTINGS) // Ctrl+S
            20 -> TuiAction.ToggleThinking    // Ctrl+T
            13 + 128 -> TuiAction.CycleMode   // Ctrl+M (0x0D = 13)
            127 -> TuiAction.Backspace         // Backspace/DEL
            27 -> TuiAction.BackToMain         // Escape
            else -> CTRL_NUM_MAP[code]
        }
    }
}
