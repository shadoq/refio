package pl.jclab.refio.cli.tui.input

import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.core.services.longtask.LongTaskCommand
import com.github.ajalt.mordant.terminal.Terminal
import mu.KotlinLogging
import org.jline.keymap.BindingReader
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.components.ApprovalFrame
import pl.jclab.refio.cli.tui.components.OutputLimitNotice
import pl.jclab.refio.cli.tui.state.TranscriptUnit
import pl.jclab.refio.cli.tui.state.TuiState
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

    /** Layout currently on screen; set by the app once the terminal exists, so keys never target a hidden pane. */
    @Volatile
    var layoutMode: () -> LayoutMode = { LayoutMode.Wide }

    internal fun dispatchAction(action: TuiAction, viewModel: TuiViewModel) {
        val rawState = viewModel.stateFlow.value
        val narrowPanel = layoutMode() == LayoutMode.Narrow && rawState.screen == TuiScreen.MAIN && rawState.activeTab != TuiTab.CHAT
        val state = if (narrowPanel && !rawState.panelFocused) rawState.copy(panelFocused = true) else rawState

        val secondClear = state.activeTab == TuiTab.RAG && action is TuiAction.TypeChar && action.char.lowercaseChar() == 'c'
        if (state.ragClearArmed && !secondClear) viewModel.disarmRagClear()

        if (state.pendingPlanApproval != null) { handlePlanApprovalAction(action, viewModel); return }
        if (state.fileViewerVisible) { handleFileViewerAction(action, state, viewModel); return }

        if (ApprovalFrame.isPending(state) && state.screen == TuiScreen.MAIN && ApprovalFrame.keysActive(state) &&
            viewModel.liveInputBuffer().isEmpty() && handleApprovalKey(action, state, viewModel)
        ) {
            return
        }

        if (state.screen == TuiScreen.SETTINGS && handleSettingsAction(action, state, viewModel)) return
        if (state.screen == TuiScreen.HELP) { handleHelpAction(action, state, viewModel); return }
        if (state.screen == TuiScreen.HISTORY) { handleHistoryAction(action, state, viewModel); return }

        if (handleActiveTabAction(action, state, viewModel)) return
        if (state.modelSelectorVisible) { handleModelSelectorAction(action, viewModel); return }
        if (state.autocompleteVisible && handleAutocompleteAction(action, state, viewModel)) return

        val chatVisible = state.screen == TuiScreen.MAIN && !narrowPanel && !(state.panelFocused && state.activeTab != TuiTab.CHAT)
        if (chatVisible && state.selectedMessageIndex >= 0 && handleSelectionKey(action, viewModel)) return
        if (chatVisible && action is TuiAction.TypeChar && OutputLimitNotice.isActive(state) &&
            viewModel.liveInputBuffer().isEmpty() && handleNoticeKey(action.char, state, viewModel)
        ) {
            return
        }
        if (action is TuiAction.BackToMain && state.screen == TuiScreen.MAIN && state.activeTab != TuiTab.CHAT) {
            viewModel.setActiveTab(TuiTab.CHAT)
            return
        }

        handleGlobalAction(action, rawState, state, viewModel)
    }

    private fun handlePlanApprovalAction(action: TuiAction, viewModel: TuiViewModel) {
        when (action) {
            is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                'y' -> viewModel.approvePlan()
                'n' -> viewModel.rejectPlan()
                else -> Unit
            }
            is TuiAction.SendMessage -> viewModel.approvePlan()
            is TuiAction.BackToMain -> viewModel.rejectPlan()
            else -> Unit
        }
    }

    private fun handleFileViewerAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel) {
        when (action) {
            is TuiAction.ScrollUp -> viewModel.fileViewerScrollUp()
            is TuiAction.ScrollDown -> viewModel.fileViewerScrollDown()
            is TuiAction.PageUp -> viewModel.fileViewerPageUp()
            is TuiAction.PageDown -> viewModel.fileViewerPageDown()
            is TuiAction.BackToMain, is TuiAction.SendMessage, is TuiAction.CancelOperation -> viewModel.closeFileViewer()
            is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                'a' -> if (state.fileViewerAllowAddContext) viewModel.fileViewerAddAsContext()
                'c' -> viewModel.fileViewerCopyToClipboard()
                else -> viewModel.showFileViewerHint()
            }
            is TuiAction.Quit -> { viewModel.shutdown(); stop() }
            else -> Unit
        }
    }

    private fun handleSettingsAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (state.settingsEditingField != null) {
            when (action) {
                is TuiAction.TypeChar -> viewModel.settingsUpdateEditBuffer(state.settingsEditBuffer + action.char)
                is TuiAction.Backspace -> if (state.settingsEditBuffer.isNotEmpty()) {
                    viewModel.settingsUpdateEditBuffer(state.settingsEditBuffer.dropLast(1))
                }
                is TuiAction.SendMessage -> viewModel.settingsCommitEdit()
                is TuiAction.BackToMain -> viewModel.settingsCancelEdit()
                else -> Unit
            }
            return true
        }

        when (action) {
            is TuiAction.BackToMain -> {
                if (viewModel.isSettingsResetArmed()) viewModel.disarmSettingsReset()
                else viewModel.setScreen(TuiScreen.MAIN)
                return true
            }
            is TuiAction.ScrollUp -> { viewModel.disarmSettingsReset(); viewModel.settingsFieldUp(); return true }
            is TuiAction.ScrollDown -> { viewModel.disarmSettingsReset(); viewModel.settingsFieldDown(); return true }
            is TuiAction.ScrollLeft -> { viewModel.setSettingsTab((state.settingsTab - 1).coerceIn(0, 10)); return true }
            is TuiAction.ScrollRight -> { viewModel.setSettingsTab((state.settingsTab + 1).coerceIn(0, 10)); return true }
            is TuiAction.SendMessage -> { activateSelectedSettingsField(state, viewModel); return true }
            is TuiAction.TogglePanelFocus -> {
                TuiSettingsScreen.nextGroupField(state.settingsTab, state.settingsSelectedField)?.let { viewModel.settingsSelectField(it) }
                return true
            }
            is TuiAction.TypeChar -> { handleSettingsChar(action.char, state, viewModel); return true }
            else -> return false
        }
    }

    private fun activateSelectedSettingsField(state: TuiState, viewModel: TuiViewModel) {
        viewModel.disarmSettingsReset()
        val field = TuiSettingsScreen.getSelectedField(state.settingsTab, state.settingsSelectedField) ?: return
        if (field.sectionKey.startsWith(TuiSettingsScreen.TOOL_PERMISSION_PREFIX)) {
            val rest = field.sectionKey.removePrefix(TuiSettingsScreen.TOOL_PERMISSION_PREFIX)
            val toolName = rest.substringBeforeLast(".")
            val agentMode = rest.substringAfterLast(".") == "agent"
            viewModel.cycleToolPermission(toolName, agentMode)
            return
        }
        val parts = field.sectionKey.split(".", limit = 2)
        if (parts.size != 2) return
        val (section, key) = parts
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
                val prefill = if (section == "default_model" && raw.trimStart().startsWith("{")) {
                    val id = Regex(""""modelId"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.get(1)
                    val prov = Regex(""""provider"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.get(1)
                    if (id != null && prov != null) "$prov/$id" else raw
                } else raw
                viewModel.settingsStartEdit(field.sectionKey, prefill)
            }
        }
    }

    private fun handleSettingsChar(ch: Char, state: TuiState, viewModel: TuiViewModel) {
        if (ch == '-') { viewModel.setSettingsTab(10); return }
        val groupValue = when (ch) { 'a' -> "ON"; 'k' -> "ASK"; 'x' -> "OFF"; else -> null }
        if (groupValue != null && TuiSettingsScreen.tabTitle(state.settingsTab) == "Tools") {
            TuiSettingsScreen.toolGroupAt(state.settingsTab, state.settingsSelectedField)?.let { (tools, agentColumn) ->
                viewModel.setToolGroupPermission(tools, agentColumn, groupValue)
            }
            return
        }
        val tabNum = ch.toString().toIntOrNull()
        if (tabNum != null) {
            val tabIndex = if (tabNum == 0) 9 else tabNum - 1
            if (tabIndex in 0..10) { viewModel.setSettingsTab(tabIndex); return }
        }
        when (ch.uppercaseChar()) {
            'R' -> {
                if (viewModel.isSettingsResetArmed()) {
                    viewModel.disarmSettingsReset()
                    viewModel.resetAllSettings()
                } else {
                    viewModel.armSettingsReset()
                }
                return
            }
            'E' -> { viewModel.disarmSettingsReset(); viewModel.exportUserConfig(); return }
            'P' -> { viewModel.disarmSettingsReset(); viewModel.exportProjectConfig(); return }
            'L' -> { viewModel.disarmSettingsReset(); viewModel.reloadConfig(); return }
            'F' -> {
                viewModel.disarmSettingsReset()
                if (state.settingsTab == 2) viewModel.refreshSettingsModels()
                return
            }
        }
        viewModel.disarmSettingsReset()
    }

    private fun handleHelpAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel) {
        if (state.helpFilter != null) {
            when (action) {
                is TuiAction.TypeChar -> { viewModel.setHelpFilter(state.helpFilter + action.char); return }
                is TuiAction.Backspace -> { viewModel.setHelpFilter(state.helpFilter.dropLast(1)); return }
                is TuiAction.BackToMain -> { viewModel.setHelpFilter(null); return }
                else -> Unit
            }
        } else if (action is TuiAction.TypeChar && action.char == '/') {
            viewModel.setHelpFilter("")
            return
        }
        when (action) {
            is TuiAction.ScrollUp -> viewModel.helpScrollUp()
            is TuiAction.ScrollDown -> viewModel.helpScrollDown()
            is TuiAction.PageUp -> viewModel.helpPageUp()
            is TuiAction.PageDown -> viewModel.helpPageDown()
            is TuiAction.BackToMain -> viewModel.setScreen(TuiScreen.MAIN)
            is TuiAction.SwitchScreen -> {
                if (action.screen == TuiScreen.HELP) viewModel.setScreen(TuiScreen.MAIN)
                else viewModel.setScreen(action.screen)
            }
            is TuiAction.SwitchTab -> { viewModel.setScreen(TuiScreen.MAIN); viewModel.setActiveTab(action.tab) }
            is TuiAction.Quit -> { viewModel.shutdown(); stop() }
            else -> Unit
        }
    }

    private fun handleHistoryAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel) {
        when (action) {
            is TuiAction.ScrollUp -> viewModel.selectHistoryUp()
            is TuiAction.ScrollDown -> viewModel.selectHistoryDown()
            is TuiAction.SendMessage -> viewModel.loadSelectedSession()
            is TuiAction.BackToMain -> viewModel.setScreen(TuiScreen.MAIN)
            is TuiAction.TypeChar -> {
                when (action.char.lowercaseChar()) {
                    'p' -> { viewModel.togglePinSession(); return }
                    'r' -> { viewModel.refreshSessions(); return }
                    'd' -> { viewModel.deleteSelectedSession(); return }
                    'c' -> { viewModel.setHistoryFilter("CHAT"); return }
                    'l' -> { viewModel.setHistoryFilter("PLAN"); return }
                    'a' -> { viewModel.setHistoryFilter("AGENT"); return }
                    '*' -> { viewModel.setHistoryFilter("*"); return }
                    '/' -> { viewModel.setHistoryFilter("/"); return }
                }
                if (state.historyFilter.startsWith("/")) {
                    viewModel.setHistoryFilter(state.historyFilter + action.char)
                }
            }
            is TuiAction.Backspace -> {
                if (state.historyFilter.startsWith("/") && state.historyFilter.length > 1) {
                    viewModel.setHistoryFilter(state.historyFilter.dropLast(1))
                } else if (state.historyFilter.startsWith("/")) {
                    viewModel.setHistoryFilter("*")
                }
            }
            else -> Unit
        }
    }

    private fun handleActiveTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (state.screen == TuiScreen.MAIN) {
            when (state.activeTab) {
                TuiTab.RAG -> if (handleRagTabAction(action, state, viewModel)) return true
                TuiTab.CONTEXT -> if (handleContextTabAction(action, state, viewModel)) return true
                TuiTab.LOGS -> if (handleLogsTabAction(action, state, viewModel)) return true
                TuiTab.API_LOGS -> if (handleApiLogsTabAction(action, state, viewModel)) return true
                TuiTab.FILES -> if (handleFilesTabAction(action, state, viewModel)) return true
                TuiTab.DEBUG -> if (handleDebugTabAction(action, viewModel)) return true
                else -> Unit
            }
        }
        if (state.activeTab == TuiTab.STEPS && state.subtasks.isNotEmpty()) {
            return handleStepsTabAction(action, state, viewModel)
        }
        return false
    }

    private fun handleRagTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (!state.panelFocused) return false
        when (action) {
            is TuiAction.ScrollUp -> { viewModel.ragFileUp(); return true }
            is TuiAction.ScrollDown -> { viewModel.ragFileDown(); return true }
            is TuiAction.SendMessage -> { viewModel.ragOpenSelectedFile(); return true }
            is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                'r' -> { viewModel.ragReindex(); return true }
                'e' -> { viewModel.ragGenerateEmbeddings(); return true }
                's' -> { viewModel.ragStopIndexing(); return true }
                'c' -> { viewModel.ragClearPressed(); return true }
                'v' -> { viewModel.ragViewSelectedChunks(); return true }
                'q' -> {
                    viewModel.addSystemMessage("Type search query in the chat input and press Enter, or use /rag-search <query>")
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    private fun handleContextTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (state.contextDetailVisible) {
            when (action) {
                is TuiAction.ScrollUp -> viewModel.contextDetailScrollUp()
                is TuiAction.ScrollDown -> viewModel.contextDetailScrollDown()
                is TuiAction.SendMessage, is TuiAction.BackToMain -> viewModel.toggleContextDetail()
                is TuiAction.PageUp -> repeat(10) { viewModel.contextDetailScrollUp() }
                is TuiAction.PageDown -> repeat(10) { viewModel.contextDetailScrollDown() }
                else -> Unit
            }
            return true
        }
        if (!state.panelFocused) return false
        return when (action) {
            is TuiAction.ScrollUp -> { viewModel.contextSectionUp(); true }
            is TuiAction.ScrollDown -> { viewModel.contextSectionDown(); true }
            is TuiAction.SendMessage -> { viewModel.toggleContextDetail(); true }
            is TuiAction.TypeChar -> if (action.char.lowercaseChar() == 'i') { viewModel.toggleContextDetail(); true } else false
            else -> false
        }
    }

    private fun handleLogsTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (!state.panelFocused) return false
        return when (action) {
            is TuiAction.ScrollUp -> { viewModel.logUp(); true }
            is TuiAction.ScrollDown -> { viewModel.logDown(); true }
            is TuiAction.SendMessage -> { viewModel.openLogDetailViewer(); true }
            is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                'p' -> { viewModel.toggleLogPause(); true }
                'f' -> { viewModel.cycleLogFilter(); true }
                else -> false
            }
            else -> false
        }
    }

    private fun handleApiLogsTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (!state.panelFocused) return false
        return when (action) {
            is TuiAction.ScrollUp -> { viewModel.apiLogUp(); true }
            is TuiAction.ScrollDown -> { viewModel.apiLogDown(); true }
            is TuiAction.SendMessage -> {
                if (state.apiLogs.isNotEmpty()) viewModel.openApiLogDetailViewer()
                true
            }
            is TuiAction.TypeChar -> if (action.char.lowercaseChar() == 'f') { viewModel.cycleApiLogsFilter(); true } else false
            else -> false
        }
    }

    private fun handleFilesTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        if (!state.panelFocused) return false
        return when (action) {
            is TuiAction.ScrollUp -> { viewModel.fileBrowserUp(); true }
            is TuiAction.ScrollDown -> { viewModel.fileBrowserDown(); true }
            is TuiAction.SendMessage -> { viewModel.fileBrowserEnter(); true }
            is TuiAction.Backspace -> { viewModel.fileBrowserGoUp(); true }
            is TuiAction.TypeChar -> when (action.char.lowercaseChar()) {
                'h' -> { viewModel.fileBrowserToggleHidden(); true }
                'a' -> { viewModel.fileBrowserAddAsContext(); true }
                'o' -> { viewModel.fileBrowserOpenExternal(); true }
                'i' -> { viewModel.fileBrowserShowInfo(); true }
                'r' -> { viewModel.fileBrowserRefresh(); true }
                else -> false
            }
            is TuiAction.PageUp -> { repeat(10) { viewModel.fileBrowserUp() }; true }
            is TuiAction.PageDown -> { repeat(10) { viewModel.fileBrowserDown() }; true }
            else -> false
        }
    }

    private fun handleDebugTabAction(action: TuiAction, viewModel: TuiViewModel): Boolean = when (action) {
        is TuiAction.ScrollUp -> { viewModel.debugScrollUp(); true }
        is TuiAction.ScrollDown -> { viewModel.debugScrollDown(); true }
        is TuiAction.PageUp -> { repeat(10) { viewModel.debugScrollUp() }; true }
        is TuiAction.PageDown -> { repeat(10) { viewModel.debugScrollDown() }; true }
        else -> false
    }

    private fun handleStepsTabAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean = when (action) {
        is TuiAction.ScrollUp -> { viewModel.selectStepUp(); true }
        is TuiAction.ScrollDown -> { viewModel.selectStepDown(); true }
        is TuiAction.TypeChar -> {
            if (!state.panelFocused) false
            else {
                val idx = state.selectedStepIndex
                val subtask = state.subtasks.getOrNull(idx)
                when {
                    action.char.lowercaseChar() == 'a' -> { subtask?.let { viewModel.approveSubtask(it.id) }; true }
                    action.char.lowercaseChar() == 's' -> { subtask?.let { viewModel.skipSubtask(it.id) }; true }
                    action.char.lowercaseChar() == 'd' -> { subtask?.let { viewModel.deleteSubtask(it.id) }; true }
                    action.char.lowercaseChar() == 'u' -> { viewModel.moveStepUp(idx); true }
                    action.char.lowercaseChar() == 'j' -> { viewModel.moveStepDown(idx); true }
                    action.char.lowercaseChar() == 'p' -> { viewModel.togglePause(); true }
                    action.char == 'r' -> { viewModel.replanSteps(); true }
                    action.char == 'C' -> { viewModel.cancelAllPending(); true }
                    else -> false
                }
            }
        }
        else -> false
    }

    private fun handleModelSelectorAction(action: TuiAction, viewModel: TuiViewModel) {
        when (action) {
            is TuiAction.ScrollUp -> viewModel.modelSelectorPrev()
            is TuiAction.ScrollDown -> viewModel.modelSelectorNext()
            is TuiAction.SendMessage -> viewModel.modelSelectorAccept()
            is TuiAction.BackToMain -> viewModel.dismissModelSelector()
            else -> Unit
        }
    }

    private fun handleAutocompleteAction(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        when (action) {
            is TuiAction.ScrollDown, is TuiAction.AutocompleteNext -> { viewModel.autocompleteNext(); return true }
            is TuiAction.ScrollUp, is TuiAction.AutocompletePrev -> { viewModel.autocompletePrev(); return true }
            is TuiAction.SendMessage -> {
                val selected = state.autocompleteCandidates.getOrNull(state.autocompleteSelectedIndex)
                if (selected != null && SlashActions.find(selected, viewModel.slashTemplateNames()) != null) {
                    viewModel.autocompleteDismiss()
                    handleCommand(selected, viewModel)
                } else {
                    viewModel.autocompleteAccept()
                }
                return true
            }
            is TuiAction.TogglePanelFocus, is TuiAction.AutocompleteAccept -> { viewModel.autocompleteAccept(); return true }
            is TuiAction.BackToMain, is TuiAction.AutocompleteDismiss -> { viewModel.autocompleteDismiss(); return true }
            is TuiAction.Backspace -> {
                val current = state.inputBuffer
                if (current.isNotEmpty()) {
                    viewModel.updateInputBuffer(current.dropLast(1))
                    viewModel.updateAutocompleteFilter()
                }
                return true
            }
            is TuiAction.TypeChar -> {
                viewModel.insertAtCursor(action.char)
                viewModel.updateAutocompleteFilter()
                return true
            }
            else -> {
                viewModel.autocompleteDismiss()
                return false
            }
        }
    }

    private fun handleGlobalAction(action: TuiAction, rawState: TuiState, state: TuiState, viewModel: TuiViewModel) {
        when (action) {
            is TuiAction.SwitchTab -> {
                viewModel.clearMessageSelection()
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
                when (action.char) {
                    '@' -> viewModel.triggerAutocomplete()
                    '!' -> viewModel.triggerSubagentAutocomplete()
                    '/' -> {
                        if (newBuffer.trimStart().startsWith("/") && !newBuffer.trimStart().contains(" ")) {
                            viewModel.triggerCommandAutocomplete()
                        }
                    }
                }
                viewModel.clearPasteMarker()
            }
            is TuiAction.Backspace -> viewModel.deleteAtCursor()
            is TuiAction.CycleMode -> viewModel.cycleMode()
            is TuiAction.ToggleThinking -> viewModel.cycleReasoningEffort()
            is TuiAction.ToggleNoEgress -> viewModel.toggleNoEgress()
            is TuiAction.ToggleExecutionMode -> viewModel.toggleExecutionMode()
            is TuiAction.SelectModel -> viewModel.showModelSelector()
            is TuiAction.NewSession -> viewModel.showNewSessionDialog()
            is TuiAction.ContinueConversation -> viewModel.continueConversation()
            is TuiAction.SummarizeConversation -> viewModel.summarizeConversation()
            is TuiAction.CopyLastMessage -> viewModel.copyLastMessageToClipboard()
            is TuiAction.CycleAgentFilter -> viewModel.cycleAgentFilter()
            is TuiAction.MessageSelectionUp -> if (state.activeTab == TuiTab.CHAT) viewModel.messageSelectionUp()
            is TuiAction.MessageSelectionDown -> if (state.activeTab == TuiTab.CHAT) viewModel.messageSelectionDown()
            is TuiAction.CancelOperation -> {
                when {
                    state.isStreaming -> viewModel.cancelCurrentOperation()
                    state.modelSelectorVisible -> viewModel.dismissModelSelector()
                    state.autocompleteVisible -> viewModel.autocompleteDismiss()
                    state.screen != TuiScreen.MAIN -> viewModel.setScreen(TuiScreen.MAIN)
                }
            }
            is TuiAction.Quit -> { viewModel.shutdown(); stop() }
            is TuiAction.ScrollUp -> if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) viewModel.chatScrollUp()
            is TuiAction.ScrollDown -> if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) viewModel.chatScrollDown()
            is TuiAction.ScrollLeft -> viewModel.moveCursorLeft()
            is TuiAction.ScrollRight -> viewModel.moveCursorRight()
            is TuiAction.PageUp -> if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) repeat(10) { viewModel.chatScrollUp() }
            is TuiAction.PageDown -> if (state.activeTab == TuiTab.CHAT && state.screen == TuiScreen.MAIN) repeat(10) { viewModel.chatScrollDown() }
            is TuiAction.ToggleExpand -> Unit
            is TuiAction.NewLine -> viewModel.updateInputBuffer(state.inputBuffer + "\n")
            is TuiAction.TogglePanelFocus -> {
                val panelBeside = state.activeTab != TuiTab.CHAT && layoutMode() != LayoutMode.Narrow
                if (panelBeside || rawState.panelFocused) viewModel.togglePanelFocus()
            }
            is TuiAction.AutocompleteNext,
            is TuiAction.AutocompletePrev,
            is TuiAction.AutocompleteAccept,
            is TuiAction.AutocompleteDismiss -> Unit
        }
    }

    /**
     * Keys while a message or tool row is selected in the chat: Space/Enter expand a tool row, `o` opens its
     * whole output, `v` shows a whole message, Esc returns to the prompt. Any other letter also returns to
     * the prompt and is typed there. Returns true when the key was used here.
     */
    /** Keys of the approval frame: a choice by its letter (`t` = `a`), ↑↓ and Enter, Esc = no. */
    private fun handleApprovalKey(action: TuiAction, state: TuiState, viewModel: TuiViewModel): Boolean {
        val choices = ApprovalFrame.choices(state)
        if (choices.isEmpty()) return false
        val choose: (Char) -> Boolean = { key -> choices.any { it.key == key } && runApprovalChoice(key, state, viewModel) }
        return when (action) {
            is TuiAction.TypeChar -> action.char.lowercaseChar().let { choose(if (it == 't') 'a' else it) }
            is TuiAction.ScrollUp -> { viewModel.approvalChoiceUp(); true }
            is TuiAction.ScrollDown -> { viewModel.approvalChoiceDown(choices.size); true }
            is TuiAction.SendMessage -> choose(choices[state.approvalChoice.coerceIn(0, choices.lastIndex)].key)
            // Esc is not "no": keys the terminal map does not know (Delete, Home, Alt+letter) arrive as Esc.
            else -> false
        }
    }

    private fun runApprovalChoice(key: Char, state: TuiState, viewModel: TuiViewModel): Boolean {
        val agent = state.pendingApprovals.firstOrNull()
        if (agent != null) {
            when (key) {
                'y' -> viewModel.approve(agent.id)
                'a' -> { viewModel.trustAgent(agent.agentId); viewModel.approve(agent.id) }
                'n' -> viewModel.reject(agent.id)
                else -> return false
            }
            return true
        }
        val req = state.pendingToolApproval ?: return false
        when (key) {
            'y' -> viewModel.approveToolExecution(req.requestId)
            'a' -> viewModel.trustToolExecution(req.requestId, req.toolName)
            'd' -> viewModel.openApprovalDiff()
            'n' -> viewModel.rejectWithChangeRequest(req.requestId)
            else -> return false
        }
        return true
    }

    private fun handleSelectionKey(action: TuiAction, viewModel: TuiViewModel): Boolean {
        val unit = viewModel.selectedTranscriptUnit() ?: return false
        return when (action) {
            is TuiAction.BackToMain -> {
                viewModel.clearMessageSelection()
                true
            }
            is TuiAction.SendMessage -> {
                if (unit is TranscriptUnit.Tool) viewModel.toggleToolRowExpanded(unit.key)
                unit is TranscriptUnit.Tool
            }
            is TuiAction.TypeChar -> when {
                action.char == ' ' && unit is TranscriptUnit.Tool -> { viewModel.toggleToolRowExpanded(unit.key); true }
                action.char == 'o' && unit is TranscriptUnit.Tool -> { viewModel.openToolOutput(unit.row); true }
                action.char == 'v' && unit is TranscriptUnit.Message -> { viewModel.viewFullMessage(unit.message); true }
                else -> { viewModel.clearMessageSelection(); false }
            }
            else -> false
        }
    }

    /** Keys of the output-limit notice while it is the last item and the prompt is empty. */
    private fun handleNoticeKey(char: Char, state: TuiState, viewModel: TuiViewModel): Boolean {
        when (char) {
            'c' -> viewModel.continueConversation()
            's' -> viewModel.splitIntoSteps()
            'l' -> if (OutputLimitNotice.windowIsTheCap(state)) return false else viewModel.raiseOutputLimitAndContinue()
            'm' -> viewModel.showModelSelector()
            else -> return false
        }
        return true
    }

    /**
     * Handle slash prompts (prompt templates only).
     * Returns true if the input was handled.
     *
     * All system operations (history, settings, export, etc.) are accessed
     * through GUI keybindings and screens, not slash prompts.
     * Slash prompts: /explain, /refactor, etc. — prompt templates from SlashPrompt.BUILTINS
     *
     * Control-style exceptions: `/goal …` sets/clears/inspects the per-task completion
     * condition consumed by `NextSpeakerJudgeGuardian`, and `/until …` / `/long …` set how the
     * task runs in rounds. Treated here rather than expanded as a prompt template because they
     * change persistent task state instead of producing a user message.
     */
    internal fun handleCommand(input: String, viewModel: TuiViewModel): Boolean {
        SlashActions.find(input, viewModel.slashTemplateNames())?.let { action ->
            viewModel.updateInputBuffer("")
            val keyAction = action.action
            if (keyAction == null) viewModel.switchMode("PLAN") else dispatchAction(keyAction, viewModel)
            return true
        }
        LongTaskCommand.parse(input)?.let { command ->
            viewModel.updateInputBuffer("")
            viewModel.applyLongTaskCommand(command)
            return true
        }
        if (input != "/goal" && !input.startsWith("/goal ")) return false
        viewModel.updateInputBuffer("")
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
