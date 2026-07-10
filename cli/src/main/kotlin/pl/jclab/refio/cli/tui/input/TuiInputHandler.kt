package pl.jclab.refio.cli.tui.input

import com.github.ajalt.mordant.terminal.Terminal
import mu.KotlinLogging
import org.jline.keymap.BindingReader
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
     * Main input loop.
     *
     * When a JLine terminal is provided (interactive mode), enters raw mode
     * for single-key dispatch. Otherwise falls back to line-based input.
     *
     * The caller owns the JLine terminal lifecycle — this method does NOT close it.
     */
    fun startInputLoop(viewModel: TuiViewModel, jlineTerminal: org.jline.terminal.Terminal? = null) {
        if (jlineTerminal != null) {
            startRawInputLoop(jlineTerminal, viewModel)
        } else {
            startLineInputLoop(viewModel)
        }
    }

    /**
     * Raw mode input using JLine's [BindingReader] + [org.jline.keymap.KeyMap].
     *
     * JLine handles all platform differences: Windows Console API key codes,
     * Unix terminfo escape sequences, and macOS keycodes are all resolved
     * by the same cross-platform KeyMap infrastructure. No manual escape
     * sequence parsing or timeouts required.
     *
     * Note: does NOT close the terminal — caller manages lifecycle.
     */
    private fun startRawInputLoop(jlineTerminal: org.jline.terminal.Terminal, viewModel: TuiViewModel) {
        jlineTerminal.enterRawMode()
        val bindingReader = BindingReader(jlineTerminal.reader())
        val keyMap = TuiKeybindings.buildKeyMap(jlineTerminal)

        while (running) {
            val binding = try {
                bindingReader.readBinding(keyMap)
            } catch (e: Exception) {
                if (running) logger.error(e) { "Input read error" }
                break
            } ?: break // null = EOF / terminal closed

            // Printable ASCII chars have explicit KeyMap bindings and arrive as
            // ready-to-use TypeChar actions. The SELF_INSERT sentinel (char=\u0000)
            // is only returned for non-ASCII Unicode — resolve from lastBinding.
            val action = if (binding is TuiAction.TypeChar && binding.char == '\u0000') {
                val seq = bindingReader.lastBinding
                if (!seq.isNullOrEmpty()) TuiAction.TypeChar(seq[0]) else null
            } else {
                binding
            }

            if (action != null) {
                dispatchAction(action, viewModel)
            }
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
                        continue
                    }
                    if (tabNum == 0) { // 0 = tab 10
                        viewModel.setSettingsTab(9)
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
                    ":files", ":10" -> { viewModel.setActiveTab(TuiTab.FILES); continue }
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

    internal fun dispatchAction(action: TuiAction, viewModel: TuiViewModel) {
        val state = viewModel.stateFlow.value

        // If plan approval dialog is visible, intercept y/n keys
        if (state.pendingPlanApproval != null) {
            when (action) {
                is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                    'y' -> { viewModel.approvePlan(); return }
                    'n' -> { viewModel.rejectPlan(); return }
                    else -> return
                }
                is TuiAction.SendMessage -> { viewModel.approvePlan(); return }
                is TuiAction.BackToMain -> { viewModel.rejectPlan(); return }
                else -> return
            }
        }

        // If approval dialog is visible, intercept y/n/t keys
        if (state.pendingApprovals.isNotEmpty()) {
            val approval = state.pendingApprovals.first()
            when (action) {
                is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                    'y' -> { viewModel.approve(approval.id); return }
                    'n' -> { viewModel.reject(approval.id); return }
                    't' -> { viewModel.trustAgent(approval.agentId); viewModel.approve(approval.id); return }
                    else -> return // ignore other keys
                }
                is TuiAction.BackToMain -> { viewModel.reject(approval.id); return }
                is TuiAction.SendMessage -> { viewModel.approve(approval.id); return }
                else -> return // block all other actions while approval visible
            }
        }

        // If tool approval is pending (PermissionLevel.ASK), intercept y/t/n keys
        if (state.pendingToolApproval != null) {
            val req = state.pendingToolApproval
            when (action) {
                is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                    'y' -> { viewModel.approveToolExecution(req.requestId); return }
                    't' -> { viewModel.trustToolExecution(req.requestId, req.toolName); return }
                    'n' -> { viewModel.rejectToolExecution(req.requestId); return }
                    else -> return
                }
                is TuiAction.BackToMain -> { viewModel.rejectToolExecution(req.requestId); return }
                is TuiAction.SendMessage -> { viewModel.approveToolExecution(req.requestId); return }
                else -> return
            }
        }

        // File viewer overlay: intercept keys for scrolling and actions
        if (state.fileViewerVisible) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.fileViewerScrollUp(); return }
                is TuiAction.ScrollDown -> { viewModel.fileViewerScrollDown(); return }
                is TuiAction.PageUp -> { viewModel.fileViewerPageUp(); return }
                is TuiAction.PageDown -> { viewModel.fileViewerPageDown(); return }
                is TuiAction.BackToMain -> { viewModel.closeFileViewer(); return }
                is TuiAction.SendMessage -> { viewModel.closeFileViewer(); return }
                is TuiAction.TypeChar -> {
                    when (action.char.lowercaseChar()) {
                        'a' -> { if (state.fileViewerAllowAddContext) viewModel.fileViewerAddAsContext(); return }
                        'c' -> { viewModel.fileViewerCopyToClipboard(); return }
                    }
                    // The overlay swallows all other typing; tell the user how to
                    // get out instead of silently eating keystrokes.
                    viewModel.showFileViewerHint()
                    return
                }
                is TuiAction.CancelOperation -> { viewModel.closeFileViewer(); return }
                is TuiAction.Quit -> { viewModel.shutdown(); stop(); return }
                else -> return // block other actions while viewer is open
            }
        }

        // Settings screen: intercept keys for interactive editing
        if (state.screen == TuiScreen.SETTINGS) {
            // If editing a text field, capture all input
            if (state.settingsEditingField != null) {
                when (action) {
                    is TuiAction.TypeChar -> {
                        viewModel.settingsUpdateEditBuffer(state.settingsEditBuffer + action.char)
                        return
                    }
                    is TuiAction.Backspace -> {
                        if (state.settingsEditBuffer.isNotEmpty()) {
                            viewModel.settingsUpdateEditBuffer(state.settingsEditBuffer.dropLast(1))
                        }
                        return
                    }
                    is TuiAction.SendMessage -> { viewModel.settingsCommitEdit(); return }
                    is TuiAction.BackToMain -> { viewModel.settingsCancelEdit(); return }
                    else -> return
                }
            }

            when (action) {
                is TuiAction.BackToMain -> {
                    if (viewModel.isSettingsResetArmed()) {
                        viewModel.disarmSettingsReset()
                    } else {
                        viewModel.setScreen(TuiScreen.MAIN)
                    }
                    return
                }
                is TuiAction.ScrollUp -> { viewModel.disarmSettingsReset(); viewModel.settingsFieldUp(); return }
                is TuiAction.ScrollDown -> { viewModel.disarmSettingsReset(); viewModel.settingsFieldDown(); return }
                is TuiAction.ScrollLeft -> {
                    viewModel.setSettingsTab((state.settingsTab - 1).coerceIn(0, 10))
                    return
                }
                is TuiAction.ScrollRight -> {
                    viewModel.setSettingsTab((state.settingsTab + 1).coerceIn(0, 10))
                    return
                }
                is TuiAction.SendMessage -> {
                    viewModel.disarmSettingsReset()
                    // Toggle bool, cycle permission, or start editing text field
                    val field = TuiSettingsScreen.getSelectedField(state.settingsTab, state.settingsSelectedField)
                    if (field != null) {
                        if (field.sectionKey.startsWith(TuiSettingsScreen.TOOL_PERMISSION_PREFIX)) {
                            // toolperm.<tool>.<plan|agent> is persisted through the
                            // tool-permissions API, not the raw key/value config.
                            val rest = field.sectionKey.removePrefix(TuiSettingsScreen.TOOL_PERMISSION_PREFIX)
                            val toolName = rest.substringBeforeLast(".")
                            val agentMode = rest.substringAfterLast(".") == "agent"
                            viewModel.cycleToolPermission(toolName, agentMode)
                            return
                        }
                        val parts = field.sectionKey.split(".", limit = 2)
                        if (parts.size == 2) {
                            val section = parts[0]
                            val key = parts[1]
                            val config = viewModel.getConfigSection(section)
                            when (field.type) {
                                TuiSettingsScreen.FieldType.BOOL -> {
                                    val current = config[key]?.lowercase() in listOf("true", "1", "yes")
                                    viewModel.settingsToggleBool(section, key, current)
                                }
                                TuiSettingsScreen.FieldType.CYCLE -> {
                                    val options = field.options
                                    if (options.isNotEmpty()) {
                                        val current = config[key] ?: field.default
                                        val idx = options.indexOfFirst { it.equals(current, ignoreCase = true) }
                                        viewModel.updateConfig(section, key, options[(idx + 1).mod(options.size)])
                                    }
                                }
                                TuiSettingsScreen.FieldType.TEXT -> {
                                    val raw = config[key] ?: ""
                                    // Model slots store JSON {"modelId","provider"}; prefill the
                                    // friendly provider/model form so the user edits that, not raw JSON.
                                    val prefill = if (section == "default_model" && raw.trimStart().startsWith("{")) {
                                        val id = Regex(""""modelId"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.get(1)
                                        val prov = Regex(""""provider"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.get(1)
                                        if (id != null && prov != null) "$prov/$id" else raw
                                    } else {
                                        raw
                                    }
                                    viewModel.settingsStartEdit(field.sectionKey, prefill)
                                }
                            }
                        }
                    }
                    return
                }
                is TuiAction.TypeChar -> {
                    // Tab switching: 1-9, 0
                    val tabNum = action.char.toString().toIntOrNull()
                    if (tabNum != null) {
                        val tabIndex = if (tabNum == 0) 9 else tabNum - 1
                        if (tabIndex in 0..10) {
                            viewModel.setSettingsTab(tabIndex)
                            return
                        }
                    }
                    // Shortcuts accept both cases; footer shows lowercase hints.
                    when (action.char.uppercaseChar()) {
                        'R' -> {
                            if (viewModel.isSettingsResetArmed()) {
                                viewModel.disarmSettingsReset()
                                viewModel.resetAllSettings()
                                viewModel.addSystemMessage("Settings reset to defaults.")
                            } else {
                                // First press only arms the reset; the footer asks for a second R.
                                viewModel.armSettingsReset()
                            }
                            return
                        }
                        'E' -> { viewModel.disarmSettingsReset(); viewModel.exportUserConfig(); return }
                        'P' -> { viewModel.disarmSettingsReset(); viewModel.exportProjectConfig(); return }
                        'L' -> { viewModel.disarmSettingsReset(); viewModel.reloadConfig(); return }
                        'F' -> {
                            viewModel.disarmSettingsReset()
                            // Refresh models from providers (Models tab = index 2)
                            if (state.settingsTab == 2) {
                                viewModel.refreshSettingsModels()
                            }
                            return
                        }
                    }
                    // Unhandled characters are ignored on the Settings screen and must
                    // never leak into the chat input buffer.
                    viewModel.disarmSettingsReset()
                    return
                }
                else -> {} // fall through: F-key navigation, Quit etc. stay global
            }
        }

        // Help screen: scroll with arrows/PgUp/PgDn, Esc or F1 to close
        if (state.screen == TuiScreen.HELP) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.helpScrollUp(); return }
                is TuiAction.ScrollDown -> { viewModel.helpScrollDown(); return }
                is TuiAction.PageUp -> { viewModel.helpPageUp(); return }
                is TuiAction.PageDown -> { viewModel.helpPageDown(); return }
                is TuiAction.BackToMain -> { viewModel.setScreen(TuiScreen.MAIN); return }
                is TuiAction.SwitchScreen -> {
                    if (action.screen == TuiScreen.HELP) { viewModel.setScreen(TuiScreen.MAIN); return }
                    else { viewModel.setScreen(action.screen); return }
                }
                is TuiAction.SwitchTab -> { viewModel.setScreen(TuiScreen.MAIN); viewModel.setActiveTab(action.tab); return }
                is TuiAction.Quit -> { viewModel.shutdown(); stop(); return }
                else -> return // block other actions on help screen
            }
        }

        // History screen: intercept navigation keys
        if (state.screen == TuiScreen.HISTORY) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.selectHistoryUp(); return }
                is TuiAction.ScrollDown -> { viewModel.selectHistoryDown(); return }
                is TuiAction.SendMessage -> { viewModel.loadSelectedSession(); return }
                is TuiAction.BackToMain -> { viewModel.setScreen(TuiScreen.MAIN); return }
                is TuiAction.TypeChar -> {
                    when (action.char.lowercaseChar()) {
                        'p' -> { viewModel.togglePinSession(); return }
                        'r' -> { viewModel.refreshSessions(); return }
                        'd' -> { viewModel.deleteSelectedSession(); return }
                        'c' -> { viewModel.setHistoryFilter("CHAT"); return }
                        'l' -> { viewModel.setHistoryFilter("PLAN"); return }
                        'a' -> { viewModel.setHistoryFilter("AGENT"); return }
                        '*' -> { viewModel.setHistoryFilter("*"); return }
                        '/' -> { viewModel.setHistoryFilter("/"); return } // start search mode
                    }
                    // If in search mode (filter starts with /), append chars to search query
                    if (state.historyFilter.startsWith("/")) {
                        viewModel.setHistoryFilter(state.historyFilter + action.char)
                        return
                    }
                }
                is TuiAction.Backspace -> {
                    if (state.historyFilter.startsWith("/") && state.historyFilter.length > 1) {
                        viewModel.setHistoryFilter(state.historyFilter.dropLast(1))
                        return
                    } else if (state.historyFilter.startsWith("/")) {
                        viewModel.setHistoryFilter("*") // exit search mode
                        return
                    }
                }
                else -> return // block other actions on history screen
            }
        }

        // RAG tab: panel actions only when the panel has focus (Tab toggles);
        // with focus on the input, Enter/arrows fall through to chat handling.
        if (state.activeTab == TuiTab.RAG && state.screen == TuiScreen.MAIN) {
            when (action) {
                is TuiAction.ScrollUp -> { if (state.panelFocused) { viewModel.ragFileUp(); return } }
                is TuiAction.ScrollDown -> { if (state.panelFocused) { viewModel.ragFileDown(); return } }
                is TuiAction.SendMessage -> { if (state.panelFocused) { viewModel.ragOpenSelectedFile(); return } }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused) {
                        when (action.char.lowercaseChar()) {
                            'r' -> { viewModel.ragReindex(); return }
                            'e' -> { viewModel.ragGenerateEmbeddings(); return }
                            's' -> { viewModel.ragStopIndexing(); return }
                            'c' -> { viewModel.ragClearIndex(); return }
                            'v' -> { viewModel.ragViewSelectedChunks(); return }
                            'q' -> {
                                viewModel.addSystemMessage("Type search query in the chat input and press Enter, or use /rag-search <query>")
                                return
                            }
                        }
                    }
                }
                else -> {} // fall through
            }
        }

        // Context tab: navigate sections + detail view
        if (state.activeTab == TuiTab.CONTEXT && state.screen == TuiScreen.MAIN) {
            if (state.contextDetailVisible) {
                // Detail view mode: scroll content
                when (action) {
                    is TuiAction.ScrollUp -> { viewModel.contextDetailScrollUp(); return }
                    is TuiAction.ScrollDown -> { viewModel.contextDetailScrollDown(); return }
                    is TuiAction.SendMessage, is TuiAction.BackToMain -> { viewModel.toggleContextDetail(); return }
                    is TuiAction.PageUp -> { repeat(10) { viewModel.contextDetailScrollUp() }; return }
                    is TuiAction.PageDown -> { repeat(10) { viewModel.contextDetailScrollDown() }; return }
                    else -> return // block other actions in detail view
                }
            }
            when (action) {
                is TuiAction.ScrollUp -> { if (state.panelFocused) { viewModel.contextSectionUp(); return } }
                is TuiAction.ScrollDown -> { if (state.panelFocused) { viewModel.contextSectionDown(); return } }
                is TuiAction.SendMessage -> { if (state.panelFocused) { viewModel.toggleContextDetail(); return } }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused && action.char.lowercaseChar() == 'i') { viewModel.toggleContextDetail(); return }
                }
                else -> {} // fall through
            }
        }

        // Logs tab: navigate, pause, filter, detail (opens content viewer overlay)
        if (state.activeTab == TuiTab.LOGS && state.screen == TuiScreen.MAIN) {
            when (action) {
                is TuiAction.ScrollUp -> { if (state.panelFocused) { viewModel.logUp(); return } }
                is TuiAction.ScrollDown -> { if (state.panelFocused) { viewModel.logDown(); return } }
                is TuiAction.SendMessage -> { if (state.panelFocused) { viewModel.openLogDetailViewer(); return } }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused) {
                        when (action.char.lowercaseChar()) {
                            'p' -> { viewModel.toggleLogPause(); return }
                            'f' -> { viewModel.cycleLogFilter(); return }
                        }
                    }
                }
                else -> {} // fall through
            }
        }

        // API Logs tab: navigation, filter, detail via content viewer overlay
        if (state.activeTab == TuiTab.API_LOGS && state.screen == TuiScreen.MAIN) {
            when (action) {
                is TuiAction.ScrollUp -> { if (state.panelFocused) { viewModel.apiLogUp(); return } }
                is TuiAction.ScrollDown -> { if (state.panelFocused) { viewModel.apiLogDown(); return } }
                is TuiAction.SendMessage -> {
                    if (state.panelFocused) {
                        if (state.apiLogs.isNotEmpty()) viewModel.openApiLogDetailViewer()
                        return
                    }
                }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused && action.char.lowercaseChar() == 'f') {
                        viewModel.cycleApiLogsFilter()
                        return
                    }
                }
                else -> {} // fall through
            }
        }

        // Files tab: file browser navigation (letter keys only when panel focused)
        if (state.activeTab == TuiTab.FILES && state.screen == TuiScreen.MAIN) {
            when (action) {
                is TuiAction.ScrollUp -> { if (state.panelFocused) { viewModel.fileBrowserUp(); return } }
                is TuiAction.ScrollDown -> { if (state.panelFocused) { viewModel.fileBrowserDown(); return } }
                is TuiAction.SendMessage -> { if (state.panelFocused) { viewModel.fileBrowserEnter(); return } }
                is TuiAction.Backspace -> {
                    if (state.panelFocused) { viewModel.fileBrowserGoUp(); return }
                }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused) {
                        when (action.char.lowercaseChar()) {
                            'h' -> { viewModel.fileBrowserToggleHidden(); return }
                            'a' -> { viewModel.fileBrowserAddAsContext(); return }
                            'o' -> { viewModel.fileBrowserOpenExternal(); return }
                            'i' -> { viewModel.fileBrowserShowInfo(); return }
                            'r' -> { viewModel.fileBrowserRefresh(); return }
                        }
                    }
                }
                is TuiAction.PageUp -> { if (state.panelFocused) { repeat(10) { viewModel.fileBrowserUp() }; return } }
                is TuiAction.PageDown -> { if (state.panelFocused) { repeat(10) { viewModel.fileBrowserDown() }; return } }
                else -> {} // fall through
            }
        }

        // Debug tab: scroll with arrow keys
        if (state.activeTab == TuiTab.DEBUG && state.screen == TuiScreen.MAIN) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.debugScrollUp(); return }
                is TuiAction.ScrollDown -> { viewModel.debugScrollDown(); return }
                is TuiAction.PageUp -> { repeat(10) { viewModel.debugScrollUp() }; return }
                is TuiAction.PageDown -> { repeat(10) { viewModel.debugScrollDown() }; return }
                else -> {} // fall through
            }
        }

        // Steps tab: intercept navigation and action keys when Steps tab is active
        // Letter keys only intercepted when panel is focused (Tab to toggle)
        if (state.activeTab == TuiTab.STEPS && state.subtasks.isNotEmpty()) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.selectStepUp(); return }
                is TuiAction.ScrollDown -> { viewModel.selectStepDown(); return }
                is TuiAction.TypeChar -> {
                    if (state.panelFocused) {
                        val idx = state.selectedStepIndex
                        val subtask = state.subtasks.getOrNull(idx)
                        when (action.char.lowercaseChar()) {
                            'a' -> { subtask?.let { viewModel.approveSubtask(it.id) }; return }
                            's' -> { subtask?.let { viewModel.skipSubtask(it.id) }; return }
                            'd' -> { subtask?.let { viewModel.deleteSubtask(it.id) }; return }
                            'u' -> { viewModel.moveStepUp(idx); return }
                            'j' -> { viewModel.moveStepDown(idx); return }
                            'p' -> { viewModel.togglePause(); return }
                            'r' -> { viewModel.replanSteps(); return }
                            else -> {} // fall through for other chars (typing in input)
                        }
                        if (action.char == 'C') { viewModel.cancelAllPending(); return }
                    }
                    // When not panel-focused, fall through to input handling
                }
                else -> {} // fall through
            }
        }

        // Model selector: intercept keys when model selector popup is visible
        if (state.modelSelectorVisible) {
            when (action) {
                is TuiAction.ScrollUp -> { viewModel.modelSelectorPrev(); return }
                is TuiAction.ScrollDown -> { viewModel.modelSelectorNext(); return }
                is TuiAction.SendMessage -> { viewModel.modelSelectorAccept(); return }
                is TuiAction.BackToMain -> { viewModel.dismissModelSelector(); return }
                else -> return
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
                    viewModel.insertAtCursor(action.char)
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
            is TuiAction.SwitchTab -> {
                viewModel.clearMessageSelection()
                // F1-F8 from a full screen (Settings/Help/History) must bring the
                // user back to the main screen, not switch tabs underneath it.
                if (state.screen != TuiScreen.MAIN) viewModel.setScreen(TuiScreen.MAIN)
                viewModel.setActiveTab(action.tab)
            }
            is TuiAction.SwitchScreen -> {
                if (action.screen == TuiScreen.HISTORY) viewModel.loadSessions()
                viewModel.setScreen(action.screen)
            }
            is TuiAction.BackToMain -> viewModel.setScreen(TuiScreen.MAIN)
            is TuiAction.SendMessage -> {
                val input = state.inputBuffer
                viewModel.clearPasteMarker()
                if (input.isNotBlank()) {
                    handleCommand(input, viewModel) || run {
                        viewModel.sendMessage(input)
                        true
                    }
                }
            }
            is TuiAction.TypeChar -> {
                viewModel.insertAtCursor(action.char)
                val newBuffer = viewModel.stateFlow.value.inputBuffer
                // Trigger autocomplete on '@', '!', '/'
                when (action.char) {
                    '@' -> viewModel.triggerAutocomplete()
                    '!' -> viewModel.triggerSubagentAutocomplete()
                    '/' -> {
                        // Trigger slash command autocomplete when "/" is typed at start of input
                        // (allow leading whitespace)
                        if (newBuffer.trimStart().startsWith("/") && !newBuffer.trimStart().contains(" ")) {
                            viewModel.triggerCommandAutocomplete()
                        }
                    }
                }
                // Clear paste marker on manual typing
                viewModel.clearPasteMarker()
            }
            is TuiAction.Backspace -> {
                viewModel.deleteAtCursor()
            }
            is TuiAction.CycleMode -> viewModel.cycleMode()
            is TuiAction.ToggleThinking -> viewModel.toggleThinking()
            is TuiAction.ToggleNoEgress -> viewModel.toggleNoEgress()
            is TuiAction.ToggleExecutionMode -> viewModel.toggleExecutionMode()
            is TuiAction.SelectModel -> viewModel.showModelSelector()
            is TuiAction.NewSession -> viewModel.showNewSessionDialog()
            is TuiAction.ContinueConversation -> viewModel.continueConversation()
            is TuiAction.SummarizeConversation -> viewModel.summarizeConversation()
            is TuiAction.CopyLastMessage -> viewModel.copyLastMessageToClipboard()
            is TuiAction.CycleAgentFilter -> viewModel.cycleAgentFilter()
            is TuiAction.MessageSelectionUp -> {
                if (state.activeTab == TuiTab.CHAT) viewModel.messageSelectionUp()
            }
            is TuiAction.MessageSelectionDown -> {
                if (state.activeTab == TuiTab.CHAT) viewModel.messageSelectionDown()
            }
            is TuiAction.CancelOperation -> {
                if (state.isStreaming) {
                    viewModel.cancelCurrentOperation()
                } else if (state.modelSelectorVisible) {
                    viewModel.dismissModelSelector()
                } else if (state.autocompleteVisible) {
                    viewModel.autocompleteDismiss()
                } else if (state.screen != TuiScreen.MAIN) {
                    viewModel.setScreen(TuiScreen.MAIN)
                }
            }
            is TuiAction.Quit -> {
                viewModel.shutdown()
                stop()
            }
            is TuiAction.ScrollUp -> {
                // On Chat tab: scroll chat up
                if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) {
                    viewModel.chatScrollUp()
                }
            }
            is TuiAction.ScrollDown -> {
                // On Chat tab: scroll chat down
                if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) {
                    viewModel.chatScrollDown()
                }
            }
            is TuiAction.ScrollLeft -> {
                // Move cursor left in input buffer
                viewModel.moveCursorLeft()
            }
            is TuiAction.ScrollRight -> {
                // Move cursor right in input buffer
                viewModel.moveCursorRight()
            }
            is TuiAction.PageUp -> {
                if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) {
                    repeat(10) { viewModel.chatScrollUp() }
                }
            }
            is TuiAction.PageDown -> {
                if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) {
                    repeat(10) { viewModel.chatScrollDown() }
                }
            }
            is TuiAction.ToggleExpand -> { /* handled by view */ }
            is TuiAction.NewLine -> viewModel.updateInputBuffer(
                state.inputBuffer + "\n"
            )
            is TuiAction.TogglePanelFocus -> viewModel.togglePanelFocus()
            is TuiAction.AutocompleteNext -> { /* handled above */ }
            is TuiAction.AutocompletePrev -> { /* handled above */ }
            is TuiAction.AutocompleteAccept -> { /* handled above */ }
            is TuiAction.AutocompleteDismiss -> { /* handled above */ }
        }
    }

    /**
     * Handle slash prompts (prompt templates only).
     * Returns true if the input was handled.
     *
     * All system operations (history, settings, export, etc.) are accessed
     * through GUI keybindings and screens, not slash prompts.
     * Slash prompts: /explain, /refactor, etc. — prompt templates from SlashPrompt.BUILTINS
     *
     * One control-style exception: `/goal …` sets/clears/inspects the per-task completion
     * condition consumed by `NextSpeakerJudgeGuardian`. Treated here rather than expanded as
     * a prompt template because it mutates persistent task state instead of producing a
     * user message.
     */
    internal fun handleCommand(input: String, viewModel: TuiViewModel): Boolean {
        if (!input.startsWith("/goal")) return false
        val args = input.removePrefix("/goal").trim()
        when {
            args.isEmpty() -> viewModel.showGoalStatus()
            args.equals("clear", ignoreCase = true) ||
                args.equals("stop", ignoreCase = true) ||
                args.equals("off", ignoreCase = true) ||
                args.equals("reset", ignoreCase = true) ||
                args.equals("none", ignoreCase = true) ||
                args.equals("cancel", ignoreCase = true) -> viewModel.clearGoal()
            else -> viewModel.setGoal(args)
        }
        return true
    }
}
