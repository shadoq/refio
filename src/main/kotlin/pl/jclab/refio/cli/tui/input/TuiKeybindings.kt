package pl.jclab.refio.cli.tui.input

import org.jline.keymap.KeyMap
import org.jline.utils.InfoCmp.Capability
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiTab

/**
 * Key binding definitions for TUI navigation.
 *
 * Uses JLine's [KeyMap] for cross-platform key resolution.
 * JLine handles platform differences (Windows Console API, Unix terminfo, etc.)
 * so escape sequences, F-keys, and special keys work on all platforms.
 *
 * NAVIGATION:
 *   F1           → Help
 *   F2-F8        → Tab switching (Steps, Context, RAG, Logs, Debug, API, Files)
 *   F9           → Settings
 *   Alt+H        → History
 *   Escape       → Back to Chat
 *   ←/→          → Settings tab switching (when on Settings screen)
 *
 * CHAT:
 *   Enter        → Send message
 *   Alt+M        → Cycle mode (Chat→Plan→Agent)
 *   Ctrl+C       → Cancel current operation
 *   Ctrl+Q       → Quit
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
    data object ScrollLeft : TuiAction()
    data object ScrollRight : TuiAction()
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
    data object SelectModel : TuiAction()
    data object NewSession : TuiAction()
    data object ContinueConversation : TuiAction()
    data object SummarizeConversation : TuiAction()
    data object CopyLastMessage : TuiAction()
    data object CycleAgentFilter : TuiAction()
    data object MessageSelectionUp : TuiAction()
    data object MessageSelectionDown : TuiAction()
}

object TuiKeybindings {

    /**
     * Sentinel returned by [KeyMap] for any unbound printable character.
     * The caller retrieves the actual character from [org.jline.keymap.BindingReader.getLastBinding].
     */
    val SELF_INSERT: TuiAction = TuiAction.TypeChar('\u0000')

    /**
     * Build a cross-platform [KeyMap] using JLine's terminal capabilities.
     *
     * JLine queries the terminal's terminfo/termcap database (Unix) or
     * uses the Windows Console API (via Jansi/JNA) to determine the correct
     * byte sequences for each key. This replaces manual escape sequence
     * parsing and works on macOS, Linux, and Windows.
     */
    fun buildKeyMap(terminal: org.jline.terminal.Terminal): KeyMap<TuiAction> {
        val keyMap = KeyMap<TuiAction>()
        keyMap.setAmbiguousTimeout(100L)

        // --- Printable ASCII: bind each character explicitly ---
        // Using setUnicode() causes JLine to return bindings with remaining=-1,
        // which triggers the ambiguous timeout branch in BindingReader.readBinding().
        // This delays character delivery by ambiguousTimeout ms and can merge/drop
        // characters typed in quick succession. Explicit bindings give remaining=0,
        // bypassing the ambiguous check entirely.
        for (c in ' '..'~') {
            keyMap.bind(TuiAction.TypeChar(c), c.toString())
        }
        // Non-ASCII fallback (e.g. Polish ąęśćżź, Unicode) — slight delay possible
        keyMap.setUnicode(SELF_INSERT)

        // --- Backspace: both BS (code 8, Windows) and DEL (code 127, macOS/Linux) ---
        keyMap.bind(TuiAction.Backspace, "\u0008")   // BS — Windows terminals
        keyMap.bind(TuiAction.Backspace, "\u007f")   // DEL — macOS Terminal, iTerm2, most Linux
        bindCapability(keyMap, terminal, Capability.key_backspace, TuiAction.Backspace)

        // --- Enter ---
        keyMap.bind(TuiAction.SendMessage, "\r")     // CR (code 13)
        keyMap.bind(TuiAction.SendMessage, "\n")     // LF (code 10)

        // --- Arrow keys: standard VT sequences + terminal capabilities ---
        keyMap.bind(TuiAction.ScrollUp, "\u001b[A")
        keyMap.bind(TuiAction.ScrollDown, "\u001b[B")
        keyMap.bind(TuiAction.ScrollRight, "\u001b[C")
        keyMap.bind(TuiAction.ScrollLeft, "\u001b[D")
        bindCapability(keyMap, terminal, Capability.key_up, TuiAction.ScrollUp)
        bindCapability(keyMap, terminal, Capability.key_down, TuiAction.ScrollDown)
        bindCapability(keyMap, terminal, Capability.key_right, TuiAction.ScrollRight)
        bindCapability(keyMap, terminal, Capability.key_left, TuiAction.ScrollLeft)

        // --- Page Up / Page Down ---
        keyMap.bind(TuiAction.PageUp, "\u001b[5~")
        keyMap.bind(TuiAction.PageDown, "\u001b[6~")
        bindCapability(keyMap, terminal, Capability.key_ppage, TuiAction.PageUp)
        bindCapability(keyMap, terminal, Capability.key_npage, TuiAction.PageDown)

        // --- F-keys: standard xterm sequences + terminal capabilities ---
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.HELP), "\u001bOP")         // F1
        keyMap.bind(TuiAction.SwitchTab(TuiTab.STEPS), "\u001bOQ")              // F2
        keyMap.bind(TuiAction.SwitchTab(TuiTab.CONTEXT), "\u001bOR")            // F3
        keyMap.bind(TuiAction.SwitchTab(TuiTab.RAG), "\u001bOS")                // F4
        keyMap.bind(TuiAction.SwitchTab(TuiTab.LOGS), "\u001b[15~")             // F5
        keyMap.bind(TuiAction.SwitchTab(TuiTab.DEBUG), "\u001b[17~")            // F6
        keyMap.bind(TuiAction.SwitchTab(TuiTab.API_LOGS), "\u001b[18~")         // F7
        keyMap.bind(TuiAction.SwitchTab(TuiTab.FILES), "\u001b[19~")            // F8
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.SETTINGS), "\u001b[20~")   // F9
        // Alternative xterm F-key sequences (some terminals use \u001b[11~ for F1, etc.)
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.HELP), "\u001b[11~")       // F1 alt
        keyMap.bind(TuiAction.SwitchTab(TuiTab.STEPS), "\u001b[12~")            // F2 alt
        keyMap.bind(TuiAction.SwitchTab(TuiTab.CONTEXT), "\u001b[13~")          // F3 alt
        keyMap.bind(TuiAction.SwitchTab(TuiTab.RAG), "\u001b[14~")              // F4 alt
        bindCapability(keyMap, terminal, Capability.key_f1, TuiAction.SwitchScreen(TuiScreen.HELP))
        bindCapability(keyMap, terminal, Capability.key_f2, TuiAction.SwitchTab(TuiTab.STEPS))
        bindCapability(keyMap, terminal, Capability.key_f3, TuiAction.SwitchTab(TuiTab.CONTEXT))
        bindCapability(keyMap, terminal, Capability.key_f4, TuiAction.SwitchTab(TuiTab.RAG))
        bindCapability(keyMap, terminal, Capability.key_f5, TuiAction.SwitchTab(TuiTab.LOGS))
        bindCapability(keyMap, terminal, Capability.key_f6, TuiAction.SwitchTab(TuiTab.DEBUG))
        bindCapability(keyMap, terminal, Capability.key_f7, TuiAction.SwitchTab(TuiTab.API_LOGS))
        bindCapability(keyMap, terminal, Capability.key_f8, TuiAction.SwitchTab(TuiTab.FILES))
        bindCapability(keyMap, terminal, Capability.key_f9, TuiAction.SwitchScreen(TuiScreen.SETTINGS))

        // --- Ctrl combinations ---
        // Note: Ctrl+H (code 8) is NOT bound here — it conflicts with Backspace on Windows.
        // History is available via Alt+H instead.
        keyMap.bind(TuiAction.MessageSelectionDown, KeyMap.ctrl('B'))            // Ctrl+B
        keyMap.bind(TuiAction.CancelOperation, KeyMap.ctrl('C'))                // Ctrl+C
        keyMap.bind(TuiAction.SummarizeConversation, KeyMap.ctrl('D'))          // Ctrl+D
        keyMap.bind(TuiAction.ToggleExecutionMode, KeyMap.ctrl('E'))            // Ctrl+E
        keyMap.bind(TuiAction.CycleAgentFilter, KeyMap.ctrl('F'))               // Ctrl+F
        keyMap.bind(TuiAction.ContinueConversation, KeyMap.ctrl('L'))           // Ctrl+L
        keyMap.bind(TuiAction.ToggleNoEgress, KeyMap.ctrl('N'))                 // Ctrl+N
        keyMap.bind(TuiAction.SelectModel, KeyMap.ctrl('O'))                    // Ctrl+O
        keyMap.bind(TuiAction.MessageSelectionUp, KeyMap.ctrl('P'))             // Ctrl+P
        keyMap.bind(TuiAction.Quit, KeyMap.ctrl('Q'))                           // Ctrl+Q
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.SETTINGS), KeyMap.ctrl('S')) // Ctrl+S
        keyMap.bind(TuiAction.ToggleThinking, KeyMap.ctrl('T'))                 // Ctrl+T
        keyMap.bind(TuiAction.NewSession, KeyMap.ctrl('W'))                     // Ctrl+W
        keyMap.bind(TuiAction.CopyLastMessage, KeyMap.ctrl('Y'))                // Ctrl+Y

        // --- Alt combinations ---
        keyMap.bind(TuiAction.CycleMode, KeyMap.alt('m'))                       // Alt+M
        keyMap.bind(TuiAction.CycleMode, KeyMap.alt('M'))
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.HISTORY), KeyMap.alt('h')) // Alt+H
        keyMap.bind(TuiAction.SwitchScreen(TuiScreen.HISTORY), KeyMap.alt('H'))

        // --- Escape (pure ESC, not start of a sequence) ---
        // JLine's KeyMap uses ambiguousTimeout to distinguish ESC from escape sequences.
        keyMap.bind(TuiAction.BackToMain, "\u001b")

        return keyMap
    }

    /**
     * Bind a terminal capability to an action, if the terminal supports it.
     * Silently skips if the capability is not available.
     */
    private fun bindCapability(
        keyMap: KeyMap<TuiAction>,
        terminal: org.jline.terminal.Terminal,
        capability: Capability,
        action: TuiAction
    ) {
        try {
            val seq = KeyMap.key(terminal, capability)
            if (seq != null) {
                keyMap.bind(action, seq)
            }
        } catch (_: Exception) {
            // Terminal doesn't support this capability — skip
        }
    }

    // --- Legacy methods kept for line-mode fallback and tests ---

    private val F_KEY_MAP = mapOf(
        "\u001bOP" to TuiAction.SwitchScreen(TuiScreen.HELP),
        "\u001bOQ" to TuiAction.SwitchTab(TuiTab.STEPS),
        "\u001bOR" to TuiAction.SwitchTab(TuiTab.CONTEXT),
        "\u001bOS" to TuiAction.SwitchTab(TuiTab.RAG),
        "\u001b[15~" to TuiAction.SwitchTab(TuiTab.LOGS),
        "\u001b[17~" to TuiAction.SwitchTab(TuiTab.DEBUG),
        "\u001b[18~" to TuiAction.SwitchTab(TuiTab.API_LOGS),
        "\u001b[19~" to TuiAction.SwitchTab(TuiTab.FILES),
        "\u001b[20~" to TuiAction.SwitchScreen(TuiScreen.SETTINGS),
    )

    fun resolveEscapeSequence(seq: String): TuiAction? {
        return F_KEY_MAP[seq] ?: when (seq) {
            "\u001b[A" -> TuiAction.ScrollUp
            "\u001b[B" -> TuiAction.ScrollDown
            "\u001b[C" -> TuiAction.ScrollRight
            "\u001b[D" -> TuiAction.ScrollLeft
            "\u001b[5~" -> TuiAction.PageUp
            "\u001b[6~" -> TuiAction.PageDown
            "\u001bm", "\u001bM" -> TuiAction.CycleMode
            "\u001bh", "\u001bH" -> TuiAction.SwitchScreen(TuiScreen.HISTORY)
            else -> null
        }
    }

    fun resolveControlChar(code: Int): TuiAction? {
        return when (code) {
            2 -> TuiAction.MessageSelectionDown
            3 -> TuiAction.CancelOperation
            4 -> TuiAction.SummarizeConversation
            5 -> TuiAction.ToggleExecutionMode
            6 -> TuiAction.CycleAgentFilter
            8 -> TuiAction.Backspace              // BS — Backspace on Windows (was Ctrl+H → History)
            10 -> TuiAction.SendMessage            // LF
            12 -> TuiAction.ContinueConversation
            13 -> TuiAction.SendMessage            // CR
            14 -> TuiAction.ToggleNoEgress
            15 -> TuiAction.SelectModel
            16 -> TuiAction.MessageSelectionUp
            17 -> TuiAction.Quit
            19 -> TuiAction.SwitchScreen(TuiScreen.SETTINGS)
            20 -> TuiAction.ToggleThinking
            23 -> TuiAction.NewSession
            25 -> TuiAction.CopyLastMessage
            27 -> TuiAction.BackToMain
            127 -> TuiAction.Backspace             // DEL — Backspace on macOS/Linux
            else -> null
        }
    }
}
