package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import mu.KotlinLogging
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiViewModel

private val logger = KotlinLogging.logger {}

/**
 * Handles terminal input and dispatches actions to TuiViewModel.
 *
 * Two modes:
 * - **Raw mode** (real TTY): JLine3 raw input with F-keys, Ctrl+combinations, single-char dispatch
 * - **Line mode** (no TTY / dumb terminal): Reads full lines from System.in, supports /commands and chat
 */
class TuiInputHandler(private val terminal: Terminal) {

    @Volatile
    private var running = true

    /**
     * Main input loop. Tries raw mode first, falls back to line mode if no TTY.
     */
    fun startInputLoop(viewModel: TuiViewModel) {
        try {
            // Suppress JLine's java.util.logging WARNING on dumb terminals
            java.util.logging.Logger.getLogger("org.jline").level = java.util.logging.Level.OFF

            val jlineTerminal = org.jline.terminal.TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .build()

            // Check if we got a real terminal (not dumb)
            if (jlineTerminal.type == "dumb" || !isRealTerminal(jlineTerminal)) {
                logger.info { "No interactive terminal detected, falling back to line mode" }
                jlineTerminal.close()
                startLineInputLoop(viewModel)
                return
            }

            startRawInputLoop(jlineTerminal, viewModel)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to create JLine terminal, falling back to line mode" }
            startLineInputLoop(viewModel)
        }
    }

    /**
     * Raw mode input — single character dispatch, F-keys, escape sequences.
     * Requires a real TTY (macOS Terminal, iTerm2, Windows Terminal, Linux TTY).
     */
    private fun startRawInputLoop(jlineTerminal: org.jline.terminal.Terminal, viewModel: TuiViewModel) {
        jlineTerminal.enterRawMode()
        val reader = jlineTerminal.reader()

        try {
            while (running) {
                val ch = reader.read()
                if (ch == -1) break

                val action = resolveInput(ch, reader)
                if (action != null) {
                    dispatchAction(action, viewModel)
                }
            }
        } finally {
            jlineTerminal.close()
        }
    }

    /**
     * Line mode input — reads full lines from System.in.
     * Works in any environment (IDE, piped input, dumb terminals).
     * Supports /commands and sends chat messages on Enter.
     */
    private fun startLineInputLoop(viewModel: TuiViewModel) {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()

                if (trimmed.isEmpty()) continue

                // Handle slash commands
                if (handleCommand(trimmed, viewModel)) continue

                // Handle settings tab switching (when on settings screen)
                if (viewModel.stateFlow.value.screen == TuiScreen.SETTINGS) {
                    val tabNum = trimmed.toIntOrNull()
                    if (tabNum != null && tabNum in 1..11) {
                        viewModel.setSettingsTab(tabNum - 1)
                        TuiSettingsScreen.invalidateCache()
                        continue
                    }
                    if (tabNum == 0) { // 0 = tab 10
                        viewModel.setSettingsTab(9)
                        TuiSettingsScreen.invalidateCache()
                        continue
                    }
                }

                // Handle tab switching shortcuts
                when (trimmed.lowercase()) {
                    ":chat", ":1" -> { viewModel.setActiveTab(TuiTab.CHAT); continue }
                    ":steps", ":2" -> { viewModel.setActiveTab(TuiTab.STEPS); continue }
                    ":context", ":3" -> { viewModel.setActiveTab(TuiTab.CONTEXT); continue }
                    ":rag", ":4" -> { viewModel.setActiveTab(TuiTab.RAG); continue }
                    ":logs", ":5" -> { viewModel.setActiveTab(TuiTab.LOGS); continue }
                    ":debug", ":6" -> { viewModel.setActiveTab(TuiTab.DEBUG); continue }
                    ":api", ":7" -> { viewModel.setActiveTab(TuiTab.API_LOGS); continue }
                }

                // Regular message — send to chat
                viewModel.sendMessage(trimmed)
            }
        } catch (e: Exception) {
            if (running) {
                logger.error(e) { "Line input error" }
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun isRealTerminal(jlineTerminal: org.jline.terminal.Terminal): Boolean {
        return try {
            // A real terminal supports getting size
            val size = jlineTerminal.size
            size.columns > 0 && size.rows > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveInput(firstChar: Int, reader: java.io.Reader): TuiAction? {
        // Escape sequence
        if (firstChar == 27) {
            val sb = StringBuilder()
            sb.append(27.toChar())

            val next = reader.read()
            if (next == -1) return TuiAction.BackToMain // Pure Escape

            sb.append(next.toChar())

            if (next == '['.code || next == 'O'.code) {
                var c = reader.read()
                while (c != -1) {
                    sb.append(c.toChar())
                    if (c.toChar().isLetter() || c.toChar() == '~') break
                    c = reader.read()
                }
            }

            return TuiKeybindings.resolveEscapeSequence(sb.toString())
        }

        // Control character
        if (firstChar < 32 || firstChar == 127) {
            return TuiKeybindings.resolveControlChar(firstChar)
        }

        // Regular character
        return TuiAction.TypeChar(firstChar.toChar())
    }

    internal fun dispatchAction(action: TuiAction, viewModel: TuiViewModel) {
        val state = viewModel.stateFlow.value

        // If approval dialog is visible, intercept y/n keys
        if (state.pendingApprovals.isNotEmpty()) {
            val approvalId = state.pendingApprovals.first().id
            when (action) {
                is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                    'y' -> { viewModel.approve(approvalId); return }
                    'n' -> { viewModel.reject(approvalId); return }
                    else -> return // ignore other keys
                }
                is TuiAction.BackToMain -> { viewModel.reject(approvalId); return }
                is TuiAction.SendMessage -> { viewModel.approve(approvalId); return }
                else -> return // block all other actions while approval visible
            }
        }

        // If autocomplete is visible, intercept navigation keys
        if (state.autocompleteVisible) {
            when (action) {
                is TuiAction.ScrollDown, is TuiAction.AutocompleteNext -> {
                    viewModel.autocompleteNext(); return
                }
                is TuiAction.ScrollUp, is TuiAction.AutocompletePrev -> {
                    viewModel.autocompletePrev(); return
                }
                is TuiAction.SendMessage, is TuiAction.AutocompleteAccept -> {
                    viewModel.autocompleteAccept(); return
                }
                is TuiAction.BackToMain, is TuiAction.AutocompleteDismiss -> {
                    viewModel.autocompleteDismiss(); return
                }
                is TuiAction.Backspace -> {
                    // Let backspace through but also check if we should dismiss autocomplete
                    val current = state.inputBuffer
                    if (current.isNotEmpty()) {
                        viewModel.updateInputBuffer(current.dropLast(1))
                        viewModel.updateAutocompleteFilter()
                    }
                    return
                }
                is TuiAction.TypeChar -> {
                    // Continue typing while autocomplete is visible
                    viewModel.updateInputBuffer(state.inputBuffer + action.char)
                    viewModel.updateAutocompleteFilter()
                    return
                }
                else -> {
                    viewModel.autocompleteDismiss()
                    // Fall through to normal handling
                }
            }
        }

        when (action) {
            is TuiAction.SwitchTab -> viewModel.setActiveTab(action.tab)
            is TuiAction.SwitchScreen -> viewModel.setScreen(action.screen)
            is TuiAction.BackToMain -> viewModel.setScreen(TuiScreen.MAIN)
            is TuiAction.SendMessage -> {
                val input = state.inputBuffer
                if (input.isNotBlank()) {
                    handleCommand(input, viewModel) || run {
                        viewModel.sendMessage(input)
                        true
                    }
                }
            }
            is TuiAction.TypeChar -> {
                val newBuffer = state.inputBuffer + action.char
                viewModel.updateInputBuffer(newBuffer)
                // Trigger autocomplete on '@', '!', '/'
                when (action.char) {
                    '@' -> viewModel.triggerAutocomplete()
                    '!' -> viewModel.triggerSubagentAutocomplete()
                    '/' -> {
                        // Only trigger at start of input (slash commands)
                        if (newBuffer.trimStart() == "/") {
                            viewModel.triggerCommandAutocomplete()
                        }
                    }
                }
            }
            is TuiAction.Backspace -> {
                val current = state.inputBuffer
                if (current.isNotEmpty()) {
                    viewModel.updateInputBuffer(current.dropLast(1))
                }
            }
            is TuiAction.CycleMode -> viewModel.cycleMode()
            is TuiAction.ToggleThinking -> viewModel.toggleThinking()
            is TuiAction.ToggleNoEgress -> viewModel.toggleNoEgress()
            is TuiAction.ToggleExecutionMode -> viewModel.toggleExecutionMode()
            is TuiAction.CancelOperation -> {
                // Cancel streaming or return to main
            }
            is TuiAction.Quit -> {
                viewModel.shutdown()
                stop()
            }
            is TuiAction.ScrollUp -> { /* handled by view */ }
            is TuiAction.ScrollDown -> { /* handled by view */ }
            is TuiAction.PageUp -> { /* handled by view */ }
            is TuiAction.PageDown -> { /* handled by view */ }
            is TuiAction.ToggleExpand -> { /* handled by view */ }
            is TuiAction.NewLine -> viewModel.updateInputBuffer(
                state.inputBuffer + "\n"
            )
            is TuiAction.AutocompleteNext -> { /* handled above */ }
            is TuiAction.AutocompletePrev -> { /* handled above */ }
            is TuiAction.AutocompleteAccept -> { /* handled above */ }
            is TuiAction.AutocompleteDismiss -> { /* handled above */ }
        }
    }

    /**
     * Handle slash commands. Returns true if the input was a command.
     */
    private fun handleCommand(input: String, viewModel: TuiViewModel): Boolean {
        val trimmed = input.trim()
        return when {
            trimmed == "/quit" || trimmed == "/q" -> {
                viewModel.shutdown()
                stop()
                true
            }
            trimmed == "/resend" -> {
                viewModel.resendLastMessage()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/clear" -> {
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/help" || trimmed == "/?" -> {
                viewModel.addSystemMessage(buildHelpText())
                viewModel.updateInputBuffer("")
                true
            }
            trimmed.startsWith("/mode") -> {
                viewModel.cycleMode()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/thinking" -> {
                viewModel.toggleThinking()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/no-egress" || trimmed == "/noegress" -> {
                viewModel.toggleNoEgress()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/auto" || trimmed == "/interactive" -> {
                viewModel.toggleExecutionMode()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/history" -> {
                viewModel.loadSessions()
                viewModel.setScreen(TuiScreen.HISTORY)
                viewModel.updateInputBuffer("")
                true
            }
            trimmed.startsWith("/history-delete ") -> {
                val id = trimmed.removePrefix("/history-delete ").trim()
                viewModel.deleteSession(id)
                viewModel.updateInputBuffer("")
                true
            }
            trimmed.startsWith("/model ") -> {
                val model = trimmed.removePrefix("/model ").trim()
                viewModel.setModel(model)
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/model" -> {
                // Show current model
                viewModel.updateInputBuffer("")
                true
            }
            trimmed.startsWith("/export ") -> {
                val path = trimmed.removePrefix("/export ").trim()
                viewModel.exportConversation(path)
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/settings" -> {
                viewModel.setScreen(TuiScreen.SETTINGS)
                viewModel.updateInputBuffer("")
                true
            }
            trimmed == "/settings-reset" -> {
                viewModel.resetAllSettings()
                TuiSettingsScreen.invalidateCache()
                viewModel.updateInputBuffer("")
                true
            }
            trimmed.startsWith("/set ") -> {
                handleSetCommand(trimmed.removePrefix("/set ").trim(), viewModel)
                viewModel.updateInputBuffer("")
                true
            }
            else -> false
        }
    }

    private fun buildHelpText(): String = """
Commands:
  /mode              — Cycle mode (CHAT → PLAN → AGENT)
  /thinking          — Toggle thinking mode (🧠)
  /no-egress         — Toggle no-egress mode (🔒)
  /auto, /interactive — Toggle execution mode (⚡/🤚)
  /model <name>      — Switch model (e.g. /model ollama/qwen2.5-coder:7b)
  /history           — Browse session history
  /history-delete <id> — Delete session
  /settings          — Open settings screen
  /set <key> <value> — Set config (e.g. /set general.streaming_enabled true)
  /settings-reset    — Reset all settings to defaults
  /export <path>     — Export conversation to Markdown file
  /resend            — Resend last user message
  /clear             — Clear input buffer
  /quit, /q          — Exit Refio

Keyboard shortcuts (raw TTY mode):
  F1-F7              — Switch tabs (Chat, Steps, Context, RAG, Logs, Debug, API)
  F8                 — Settings
  Ctrl+M             — Cycle mode
  Ctrl+T             — Toggle thinking
  Ctrl+E             — Toggle execution mode (auto/interactive)
  Ctrl+Q             — Quit
  Escape             — Back to main / dismiss autocomplete
  @                  — Context autocomplete popup
  !                  — Subagent autocomplete popup
  /                  — Command autocomplete popup
""".trim()

    /**
     * Handle /set section.key value command.
     */
    private fun handleSetCommand(args: String, viewModel: TuiViewModel) {
        val spaceIdx = args.indexOf(' ')
        if (spaceIdx < 0) {
            logger.info { "Usage: /set section.key value" }
            return
        }
        val fullKey = args.substring(0, spaceIdx)
        val value = args.substring(spaceIdx + 1).trim()
        val dotIdx = fullKey.indexOf('.')
        if (dotIdx < 0) {
            logger.info { "Key must be in section.key format, e.g. general.streaming_enabled" }
            return
        }
        val section = fullKey.substring(0, dotIdx)
        val key = fullKey.substring(dotIdx + 1)
        viewModel.updateConfig(section, key, value)
        TuiSettingsScreen.invalidateCache()
    }
}
