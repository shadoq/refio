package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.SetDefaultModelRequest
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.context.mcp.MCPAuthConfig
import pl.jclab.refio.core.context.mcp.MCPAuthType
import pl.jclab.refio.core.context.mcp.MCPEnvVariable
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.db.TaskMode as CoreTaskMode
import pl.jclab.refio.core.logging.LogSinkRegistry
import pl.jclab.refio.core.models.api.SetToolPermissionRequest
import pl.jclab.refio.core.models.api.ToolPermissionDto
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import java.io.File
import java.nio.file.Path
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * TUI ViewModel coordinator -- delegates to three sub-ViewModels:
 *   chat     -> TuiChatViewModel (messages, input, autocomplete, approvals)
 *   session  -> TuiSessionViewModel (session lifecycle, execution, steps, model selector)
 *   obs      -> TuiObservabilityViewModel (RAG, context, logs, API logs, debug, file browser, viewer)
 *
 * Owns: screen/tab navigation, settings editing, initialization/shutdown, stateFlow merge.
 */
class TuiViewModel(
    internal val projectPath: Path,
    private val initialMode: pl.jclab.refio.api.models.TaskMode?,
    private val initialModel: String?,
    private val noEgress: Boolean,
    /**
     * Run-scope config overrides (`--config` / `--config-file`) forwarded to the bootstrap so an
     * interactive session can retarget a provider endpoint (Ollama / LM Studio) or any config key
     * without editing config.yaml. Highest priority, read-only, never persisted.
     */
    private val runConfigOverrides: Map<String, String> = emptyMap()
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Coordinator-owned state ---
    private val _activeTab = MutableStateFlow(TuiTab.CHAT)
    private val _screen = MutableStateFlow(TuiScreen.MAIN)
    private val _settingsTab = MutableStateFlow(0)
    private val _mode = MutableStateFlow(initialMode?.name ?: "CHAT")
    private val _model = MutableStateFlow(initialModel)
    private val _settingsSelectedField = MutableStateFlow(0)
    private val _settingsEditingField = MutableStateFlow<String?>(null)
    private val _settingsEditBuffer = MutableStateFlow("")
    private val _settingsResetArmed = MutableStateFlow(false)
    private val _isInitialized = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _contextMaxTokens = MutableStateFlow(128_000)

    // Settings render/input threads read only this cached snapshot; router/DB
    // reads run on Dispatchers.IO. The cache is refreshed on Settings screen
    // entry, on tab switch, and after every write, which keeps the documented
    // "fresh values when Settings is shown" behavior without blocking a frame.
    private val configSectionCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    @Volatile
    private var toolPermissionsCache: List<ToolPermissionDto> = emptyList()
    internal val _settingsCacheVersion = MutableStateFlow(0)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    // --- Infrastructure ---
    private var bootstrap: StandaloneCoreBootstrap? = null
    private var router: CoreApiRouter? = null
    /**
     * Core session facade — created after router is up in [initialize]. Sub-VMs read it through
     * [getCoreSession] so they can observe [pl.jclab.refio.core.session.SessionStateManager] flows
     * instead of keeping their own copies of execution state.
     */
    /**
     * Shared session execution state. Created eagerly so sub-VMs can bind their observers
     * before [CoreSessionService] is wired up in [initialize].
     */
    internal val sessionStateManager = pl.jclab.refio.core.session.SessionStateManager()
    private var coreSession: pl.jclab.refio.core.session.CoreSessionService? = null
    fun getCoreSession(): pl.jclab.refio.core.session.CoreSessionService? = coreSession
    var taskId: String? = null
        private set
    val agentEventBus = AgentEventBus()

    private val projectId: String by lazy {
        ProjectIdGenerator.generate(projectPath.toAbsolutePath().normalize())
    }

    // --- Sub-ViewModels ---
    // Created eagerly; wired to router/taskId lazily in initialize().
    // Order: session -> chat -> workflowListener -> obs (no circular deps).

    val session = TuiSessionViewModel(
        scope = scope,
        getRouter = { router },
        getTaskId = { taskId },
        setTaskId = { taskId = it },
        mode = _mode,
        model = _model,
        projectPath = projectPath,
        projectId = projectId,
        stateManager = sessionStateManager
    )

    // chat needs workflowListener but workflowListener needs chat._messages.
    // Break the cycle: create chat first, then workflowListener, then wire.
    val chat = TuiChatViewModel(
        scope = scope,
        getRouter = { router },
        getTaskId = { taskId },
        mode = _mode.asStateFlow(),
        model = _model.asStateFlow(),
        executionMode = session._executionMode.asStateFlow(),
        agentEventBus = agentEventBus
    )

    private val workflowListener = TuiWorkflowListener(
        agentId = "main",
        agentName = "Refio",
        agentColorIndex = 0,
        messagesState = chat._messages,
        streamingState = chat._isStreaming,
        scope = scope,
        viewModel = this
    )

    init {
        // Wire the workflowListener into chat (breaks the constructor cycle)
        chat.workflowListener = workflowListener
    }

    val obs = TuiObservabilityViewModel(
        scope = scope,
        getRouter = { router },
        getTaskId = { taskId },
        projectPath = projectPath,
        addSystemMessageFn = { chat.addSystemMessage(it) },
        insertContextFn = { chat.insertStringAtCursor(it) }
    )

    // ========================================================================
    // stateFlow — merges all sub-VM and coordinator flows into TuiState
    // ========================================================================

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val stateFlow: StateFlow<TuiState> = merge(
        // Coordinator flows
        _activeTab.map { Unit },
        _screen.map { Unit },
        _settingsTab.map { Unit },
        _mode.map { Unit },
        _model.map { Unit },
        _settingsSelectedField.map { Unit },
        _settingsEditingField.map { Unit },
        _settingsEditBuffer.map { Unit },
        _settingsResetArmed.map { Unit },
        _settingsCacheVersion.map { Unit },
        _contextMaxTokens.map { Unit },
        // Session execution flows
        sessionStateManager.toolCallProgress.map { Unit },
        // Chat sub-VM flows
        chat._messages.map { Unit },
        chat._isStreaming.map { Unit },
        chat._inputBuffer.map { Unit },
        chat._cursorPosition.map { Unit },
        chat._scrollOffset.map { Unit },
        chat._selectedMessageIndex.map { Unit },
        chat._pastedContent.map { Unit },
        chat._pendingQuestionId.map { Unit },
        chat._pendingQuestionOptions.map { Unit },
        chat._pendingApprovals.map { Unit },
        chat._pendingToolApproval.map { Unit },
        chat._agentFilter.map { Unit },
        chat._agents.map { Unit },
        chat._panelFocused.map { Unit },
        chat._autocompleteVisible.map { Unit },
        chat._autocompleteCandidates.map { Unit },
        chat._autocompleteSelectedIndex.map { Unit },
        // Session sub-VM flows
        session.subtasks.map { Unit },
        session.activePlan.map { Unit },
        session.isPaused.map { Unit },
        session.pendingPlanApproval.map { Unit },
        session.selectedStepIndex.map { Unit },
        session.executionStatus.map { Unit },
        session.executionMode.map { Unit },
        session.sessions.map { Unit },
        session.selectedHistoryIndex.map { Unit },
        session.historyFilter.map { Unit },
        session.modelSelectorVisible.map { Unit },
        session.modelSelectorCandidates.map { Unit },
        session.modelSelectorIndex.map { Unit },
        session.totalCost.map { Unit },
        session.totalTokens.map { Unit },
        session.thinkingEnabled.map { Unit },
        session.noEgressEnabled.map { Unit },
        // Observability sub-VM flows
        obs._ragIndexingProgress.map { Unit },
        obs._ragIndexingStatus.map { Unit },
        obs._ragIndexedFiles.map { Unit },
        obs._ragSelectedFileIndex.map { Unit },
        obs._ragSearchQuery.map { Unit },
        obs._ragSearchResults.map { Unit },
        obs._contextSections.map { Unit },
        obs._contextMaxTokens.map { Unit },
        obs._selectedContextIndex.map { Unit },
        obs._contextDetailVisible.map { Unit },
        obs._contextDetailScrollOffset.map { Unit },
        obs._logs.map { Unit },
        obs._logsPaused.map { Unit },
        obs._selectedLogIndex.map { Unit },
        obs._logDetailVisible.map { Unit },
        obs._logsFilter.map { Unit },
        obs._apiLogs.map { Unit },
        obs._apiLogsFilter.map { Unit },
        obs._selectedApiLogIndex.map { Unit },
        obs._apiLogDetailVisible.map { Unit },
        obs._apiLogDetailScrollOffset.map { Unit },
        obs._debugInfo.map { Unit },
        obs._debugScrollOffset.map { Unit },
        obs._fileBrowserPath.map { Unit },
        obs._fileBrowserEntries.map { Unit },
        obs._fileBrowserSelectedIndex.map { Unit },
        obs._fileBrowserShowHidden.map { Unit },
        obs._fileViewerVisible.map { Unit },
        obs._fileViewerPath.map { Unit },
        obs._fileViewerContent.map { Unit },
        obs._fileViewerScrollOffset.map { Unit },
        obs._fileViewerShowLineNumbers.map { Unit },
        obs._fileViewerAllowAddContext.map { Unit },
        obs._fileViewerHintVisible.map { Unit },
        obs._helpScrollOffset.map { Unit },
    ).debounce(16)
    .map { buildCurrentState() }
    .stateIn(scope, SharingStarted.Eagerly, TuiState(mode = _mode.value, model = initialModel))

    // Token sums are cached per messages-list instance: the list is immutable
    // and replaced wholesale on every change, so reference identity is a valid
    // cache key and the O(n) sum runs once per list change, not once per frame.
    private var tokenSumsFor: List<TuiChatMessage>? = null
    private var tokenSums: Pair<Long, Long> = 0L to 0L

    private fun sessionTokenSums(messages: List<TuiChatMessage>): Pair<Long, Long> {
        if (messages !== tokenSumsFor) {
            tokenSums = messages.sumOf { it.tokensIn.toLong() } to messages.sumOf { it.tokensOut.toLong() }
            tokenSumsFor = messages
        }
        return tokenSums
    }

    private fun buildCurrentState() = TuiState(
        // Coordinator
        screen = _screen.value,
        activeTab = _activeTab.value,
        mode = _mode.value,
        model = _model.value,
        settingsTab = _settingsTab.value,
        settingsSelectedField = _settingsSelectedField.value,
        settingsEditingField = _settingsEditingField.value,
        settingsEditBuffer = _settingsEditBuffer.value,
        settingsResetArmed = _settingsResetArmed.value,
        settingsCacheVersion = _settingsCacheVersion.value,
        contextMaxTokens = _contextMaxTokens.value,
        activeSessionId = taskId,
        // Chat sub-VM
        messages = chat._messages.value,
        isStreaming = chat._isStreaming.value,
        toolCallProgress = sessionStateManager.toolCallProgress.value,
        inputBuffer = chat._inputBuffer.value,
        cursorPosition = chat._cursorPosition.value,
        scrollOffset = chat._scrollOffset.value,
        selectedMessageIndex = chat._selectedMessageIndex.value,
        pastedContent = chat._pastedContent.value,
        pendingQuestionId = chat._pendingQuestionId.value,
        pendingQuestionOptions = chat._pendingQuestionOptions.value,
        pendingApprovals = chat._pendingApprovals.value,
        pendingToolApproval = chat._pendingToolApproval.value,
        agentFilter = chat._agentFilter.value,
        agents = chat._agents.value,
        panelFocused = chat._panelFocused.value,
        autocompleteVisible = chat._autocompleteVisible.value,
        autocompleteCandidates = chat._autocompleteCandidates.value,
        autocompleteSelectedIndex = chat._autocompleteSelectedIndex.value,
        sessionTokensIn = sessionTokenSums(chat._messages.value).first,
        sessionTokensOut = sessionTokenSums(chat._messages.value).second,
        // Session sub-VM
        subtasks = session.subtasks.value,
        activePlan = session.activePlan.value,
        isPaused = session.isPaused.value,
        pendingPlanApproval = session.pendingPlanApproval.value,
        selectedStepIndex = session.selectedStepIndex.value,
        executionStatus = session.executionStatus.value,
        executionMode = session.executionMode.value,
        sessions = session.sessions.value,
        selectedHistoryIndex = session.selectedHistoryIndex.value,
        historyFilter = session.historyFilter.value,
        modelSelectorVisible = session.modelSelectorVisible.value,
        modelSelectorCandidates = session.modelSelectorCandidates.value,
        modelSelectorIndex = session.modelSelectorIndex.value,
        totalCostUsd = session.totalCost.value,
        totalTokens = session.totalTokens.value,
        thinkingEnabled = session.thinkingEnabled.value,
        noEgressEnabled = session.noEgressEnabled.value,
        // Observability sub-VM
        ragIndexingProgress = obs._ragIndexingProgress.value,
        ragIndexingStatus = obs._ragIndexingStatus.value,
        ragIndexedFiles = obs._ragIndexedFiles.value,
        ragSelectedFileIndex = obs._ragSelectedFileIndex.value,
        ragSearchQuery = obs._ragSearchQuery.value,
        ragSearchResults = obs._ragSearchResults.value,
        contextSections = obs._contextSections.value,
        contextUsedTokens = obs._contextSections.value.sumOf { it.tokensUsed },
        selectedContextIndex = obs._selectedContextIndex.value,
        contextDetailVisible = obs._contextDetailVisible.value,
        contextDetailScrollOffset = obs._contextDetailScrollOffset.value,
        logs = obs._logs.value,
        logsPaused = obs._logsPaused.value,
        selectedLogIndex = obs._selectedLogIndex.value,
        logDetailVisible = obs._logDetailVisible.value,
        logsFilter = obs._logsFilter.value,
        apiLogs = obs._apiLogs.value,
        apiLogsFilter = obs._apiLogsFilter.value,
        selectedApiLogIndex = obs._selectedApiLogIndex.value,
        apiLogDetailVisible = obs._apiLogDetailVisible.value,
        apiLogDetailScrollOffset = obs._apiLogDetailScrollOffset.value,
        debugInfo = obs._debugInfo.value,
        debugScrollOffset = obs._debugScrollOffset.value,
        fileBrowserPath = obs._fileBrowserPath.value,
        fileBrowserEntries = obs._fileBrowserEntries.value,
        fileBrowserSelectedIndex = obs._fileBrowserSelectedIndex.value,
        fileBrowserShowHidden = obs._fileBrowserShowHidden.value,
        fileViewerVisible = obs._fileViewerVisible.value,
        fileViewerPath = obs._fileViewerPath.value,
        fileViewerContent = obs._fileViewerContent.value,
        fileViewerScrollOffset = obs._fileViewerScrollOffset.value,
        fileViewerShowLineNumbers = obs._fileViewerShowLineNumbers.value,
        fileViewerAllowAddContext = obs._fileViewerAllowAddContext.value,
        fileViewerHintVisible = obs._fileViewerHintVisible.value,
        helpScrollOffset = obs._helpScrollOffset.value,
    )

    // ========================================================================
    // Initialization & shutdown
    // ========================================================================

    suspend fun initialize() {
        try {
            LogSinkRegistry.register(obs.tuiLogSink)

            val boot = StandaloneCoreBootstrap(projectPath, runConfigOverrides)
            val r = boot.initialize()
            bootstrap = boot
            router = r
            coreSession = pl.jclab.refio.core.session.CoreSessionServiceFactory.create(
                projectRouter = r,
                projectId = projectId,
                projectPath = projectPath,
                scope = scope,
                stateManager = sessionStateManager,
            )
            _isInitialized.value = true

            // Wire sub-VM callbacks
            wireSubViewModelCallbacks()

            // Wire chat's executionMode to session's (replace the initial dummy)
            // chat reads from session's executionMode via the passed StateFlow constructor param
            // Since TuiChatViewModel.executionMode is a val, we re-assign during wire phase:
            // Actually, we need a proper bridging approach. Let's bridge it:
            chat.projectPath = projectPath

            val restoredTaskId = restoreOrCreateSession(r)
            taskId = restoredTaskId

            // Load persisted UI state from config (same keys as IntelliJ plugin)
            try {
                val uiConfig = r.configRouter.getConfig("ui", "app")
                val settings = uiConfig.settings

                // Mode: CLI --mode flag overrides DB value
                if (initialMode == null) {
                    settings["selected_mode"]?.toString()?.let { dbMode ->
                        val resolved = runCatching {
                            pl.jclab.refio.api.models.TaskMode.valueOf(dbMode)
                        }.getOrNull()
                        if (resolved != null) {
                            _mode.value = resolved.name
                            logger.info { "Loaded mode from config: ${resolved.name}" }
                        }
                    }
                }

                // Thinking enabled
                settings["thinking_enabled"]?.toString()?.toBooleanStrictOrNull()?.let {
                    session.setThinkingEnabled(it)
                }

                // No-egress enabled
                settings["no_egress_enabled"]?.toString()?.toBooleanStrictOrNull()?.let {
                    session.setNoEgressEnabled(it)
                }

                // Execution mode
                settings["execution_mode"]?.toString()?.let { dbExecMode ->
                    if (dbExecMode == "AUTO" || dbExecMode == "INTERACTIVE") {
                        session._executionMode.value = dbExecMode
                        logger.info { "Loaded execution mode from config: $dbExecMode" }
                    }
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load UI state from config" }
            }

            // CLI --no-egress flag always overrides
            if (noEgress) {
                session.setNoEgressEnabled(true)
            }

            // Resolve model if none specified via CLI
            // Uses getModel() which checks ui.selected_model first (same as IntelliJ),
            // then falls back to operation-specific defaults.
            if (initialModel == null) {
                try {
                    val operation = ModelOperation.fromTaskMode(CoreTaskMode.valueOf(_mode.value))
                    val resolvedModel = r.configRouter.getModel(operation)
                    if (resolvedModel.modelId != null && resolvedModel.provider != null) {
                        _model.value = "${resolvedModel.provider}/${resolvedModel.modelId}"
                        resolveContextWindow(_model.value)
                        logger.info { "Resolved model for ${_mode.value}: ${_model.value}" }
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to resolve model for mode" }
                }
            } else {
                resolveContextWindow(initialModel)
            }

            obs._debugInfo.update {
                it.copy(
                    connected = true,
                    sessionId = restoredTaskId,
                    dbPath = File(System.getProperty("user.home"), ".refio/data/database.sqlite").toString(),
                    mode = _mode.value,
                    model = _model.value ?: "default"
                )
            }
            logger.info { "Core initialized for project: ${projectPath.toAbsolutePath()}, session: $restoredTaskId" }

            loadMessagesFromDb(r, restoredTaskId)
            loadSubtasksFromDb(r, restoredTaskId)

            bridgeBackendEventBus(r)
            subscribeToAgentEvents()
            subscribeToUserInteraction(r)
            subscribeToToolApprovals(r)
            obs.startAutoRefresh(r) {
                // Refresh only when the data is visible or changing: a turn in
                // progress, an observability tab open, or a non-main screen.
                chat._isStreaming.value ||
                    _screen.value != TuiScreen.MAIN ||
                    _activeTab.value != TuiTab.CHAT
            }
            obs.refreshRagStats(r)

            loadModelsInBackground(r)
            obs.initFileBrowser()
            // Pre-warm the settings snapshot so the Settings screen has data on first entry.
            refreshSettingsCache()
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize core" }
            _error.value = "Initialization failed: ${e.message}"
        }
    }

    private fun wireSubViewModelCallbacks() {
        // Chat -> coordinator/session callbacks
        chat.onUpdateTotalTokens = { count -> session.addTokens(count) }
        chat.onUpdateTotalCost = { amount -> session.addCost(amount) }
        chat.onUpdateDebugInfo = { messageCount ->
            obs._debugInfo.update { it.copy(messageCount = messageCount) }
        }
        chat.onUpdateExecutionStatus = { status -> session.updateExecutionStatus(status) }
        chat.onLoadMessagesFromDb = { r, tid -> loadMessagesFromDb(r, tid) }
        chat.onLoadSubtasksFromDb = { r, tid -> loadSubtasksFromDb(r, tid) }
        chat.onRefreshApiLogs = { r -> obs.refreshApiLogs(r) }
        chat.onCreateNewTaskInDb = { r -> createNewTaskInDb(r) }
        chat.onSetTaskId = { id -> taskId = id }

        // Session -> coordinator/chat/obs callbacks
        session.addSystemMessage = { chat.addSystemMessage(it) }
        session.setActiveTab = { setActiveTab(it) }
        session.loadMessagesFromDb = { r, tid -> loadMessagesFromDb(r, tid) }
        session.loadSubtasksFromDb = { r, tid -> loadSubtasksFromDb(r, tid) }
        session.createNewTaskInDb = { r -> createNewTaskInDb(r) }
        session.refreshApiLogs = { r -> obs.refreshApiLogs(r) }
        session.onStreamChunk = { delta -> workflowListener.onStreamChunk(delta) }
        session.onStreamComplete = { response -> workflowListener.onStreamComplete(response) }
        session.clearMessages = { chat._messages.value = emptyList() }
        session.clearSteps = { /* no-op: steps removed, subtasks flow owns UI state */ }
        session.clearContextSections = { obs._contextSections.value = emptyList() }
        session.clearInputBuffer = { chat._inputBuffer.value = "" }
        session.setStreaming = { chat._isStreaming.value = it }
        session.updateDebugInfo = { sessionId, mode ->
            obs._debugInfo.update { info ->
                var updated = info
                if (sessionId != null) updated = updated.copy(sessionId = sessionId)
                if (mode != null) updated = updated.copy(mode = mode)
                updated.copy(selectedModel = _model.value ?: "auto")
            }
        }
        session.setScreen = { screen -> _screen.value = screen }
        session.resolveContextWindow = { modelStr -> resolveContextWindow(modelStr) }
    }

    fun shutdown() {
        LogSinkRegistry.clear()
        TuiChatMessageMapper.reset()
        bootstrap?.shutdown()
        bootstrap = null
        router = null
    }

    // ========================================================================
    // Tab / screen navigation (coordinator-owned)
    // ========================================================================

    fun setActiveTab(tab: TuiTab) {
        _activeTab.value = if (_activeTab.value == tab && tab != TuiTab.CHAT) TuiTab.CHAT else tab
    }

    fun setScreen(screen: TuiScreen) {
        _screen.value = if (_screen.value == screen && screen != TuiScreen.MAIN) TuiScreen.MAIN else screen
        if (_screen.value == TuiScreen.SETTINGS) {
            refreshSettingsCache()
        }
    }

    fun setSettingsTab(index: Int) {
        _settingsTab.value = index.coerceIn(0, 10)
        refreshSettingsCache()
        // Field indices are per-tab; keep the cursor on the first field of the new tab.
        _settingsSelectedField.value = 0
        _settingsResetArmed.value = false
    }

    // ========================================================================
    // Session / task helpers (coordinator-owned, used by sub-VMs via callbacks)
    // ========================================================================

    private fun restoreOrCreateSession(r: CoreApiRouter): String {
        try {
            val lastTask = r.taskRouter.getLastSessionForProject(projectId)
            if (lastTask != null) {
                _mode.value = lastTask.mode
                logger.info { "Restored last project session: ${lastTask.id} (mode=${lastTask.mode})" }
                return lastTask.id
            }
        } catch (e: Exception) {
            logger.debug(e) { "No previous session found" }
        }

        return createNewTaskInDb(r)
    }

    internal fun createNewTaskInDb(r: CoreApiRouter): String {
        val taskMode = try { CoreTaskMode.valueOf(_mode.value) } catch (_: Exception) { CoreTaskMode.CHAT }
        val task = r.taskRouter.createTask(CreateTaskRequest(
            name = "New Session",
            mode = taskMode,
            projectId = projectId,
            projectPath = projectPath.toAbsolutePath().toString()
        ))
        logger.info { "Created new session: ${task.id} (mode=${taskMode})" }
        return task.id
    }

    internal fun loadMessagesFromDb(r: CoreApiRouter, tid: String) {
        try {
            val response = r.chatRouter.getMessages(tid)
            if (response.messages.isNotEmpty()) {
                chat._messages.value = response.messages.map { msg ->
                    val msgType = detectMessageType(msg)
                    val toolName = extractToolName(msg)
                    TuiChatMessage(
                        id = msg.id,
                        timestamp = msg.createdAt,
                        role = if (msg.role == "tool") "assistant" else msg.role,
                        content = msg.content,
                        tokensIn = msg.tokensIn ?: 0,
                        tokensOut = msg.tokensOut ?: 0,
                        costUsd = msg.cost ?: 0.0,
                        messageType = msgType,
                        toolName = toolName
                    )
                }
                logger.info { "Loaded ${response.messages.size} messages from DB" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to load messages from DB" }
        }
    }

    private fun detectMessageType(msg: pl.jclab.refio.core.api.MessageResponse): TuiMessageType {
        if (msg.role == "tool") return TuiMessageType.TOOL_CALL
        val tcJson = msg.toolCallsJson
        if (tcJson != null && tcJson.isNotBlank()) return TuiMessageType.TOOL_CALL
        val meta = msg.metadata
        if (meta != null) {
            if (meta.contains("\"orchestrator_question\"")) return TuiMessageType.ORCHESTRATOR_QUESTION
            if (meta.contains("\"execution_summary\"")) return TuiMessageType.EXECUTION_SUMMARY
            if (meta.contains("\"plan\"")) return TuiMessageType.PLAN
        }
        return TuiMessageType.TEXT
    }

    private fun extractToolName(msg: pl.jclab.refio.core.api.MessageResponse): String? {
        val tcJson = msg.toolCallsJson ?: return null
        return try {
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
            nameRegex.find(tcJson)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    internal fun loadSubtasksFromDb(r: CoreApiRouter, tid: String) {
        try {
            val response = r.subtaskRouter.getSubtasks(tid)
            if (response.subtasks.isNotEmpty()) {
                session.setSubtasks(response.subtasks)
                logger.info { "Loaded ${response.subtasks.size} subtasks from DB" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to load subtasks from DB" }
        }
    }

    // ========================================================================
    // Event bus bridging
    // ========================================================================

    private fun bridgeBackendEventBus(router: CoreApiRouter) {
        scope.launch {
            router.agentEventBus.events.collect { event ->
                agentEventBus.emit(event)
            }
        }
    }

    private fun subscribeToAgentEvents() {
        scope.launch {
            agentEventBus.events.collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    private fun subscribeToUserInteraction(r: CoreApiRouter) {
        scope.launch {
            r.userInteraction.currentQuestionId.collect { questionId ->
                chat._pendingQuestionId.value = questionId
                if (questionId == null) {
                    chat._pendingQuestionOptions.value = emptyList()
                }
            }
        }
    }

    private fun subscribeToToolApprovals(r: CoreApiRouter) {
        scope.launch {
            r.toolApprovalService.pendingRequests.collect { requests ->
                val first = requests.firstOrNull()
                chat._pendingToolApproval.value = if (first != null) {
                    TuiToolApprovalRequest(
                        requestId = first.requestId,
                        toolName = first.toolName,
                        description = first.description,
                        arguments = first.arguments,
                        proposedChange = first.proposedChange
                    )
                } else null
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        val chatMsg = TuiChatMessageMapper.mapEvent(event)
        if (chatMsg != null) {
            chat._messages.update { it + chatMsg }
        }

        when (event) {
            is AgentEvent.AgentStarted -> {
                val colorIdx = TuiChatMessageMapper.getAgentColorIndex(event.sourceAgentId)
                chat._agents.update { agents ->
                    agents + TuiAgentState(
                        id = event.sourceAgentId,
                        name = event.agentName,
                        status = "RUNNING",
                        colorIndex = colorIdx,
                        dependsOn = event.dependsOn
                    )
                }
            }
            is AgentEvent.AgentCompleted -> {
                chat._agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(
                            status = "COMPLETED",
                            tokensUsed = event.tokensUsed,
                            costUsd = event.costUsd
                        ) else it
                    }
                }
                session.addTokens(event.tokensUsed)
                session.addCost(event.costUsd)
            }
            is AgentEvent.AgentFailed -> {
                chat._agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "FAILED") else it
                    }
                }
            }
            is AgentEvent.ProgressUpdate -> {
                chat._agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(currentPhase = event.phase) else it
                    }
                }
            }
            is AgentEvent.ApprovalRequired -> {
                if (chat.isAgentTrusted(event.sourceAgentId)) {
                    chat.approve(event.id)
                    return
                }
                val agentState = chat._agents.value.find { it.id == event.sourceAgentId }
                chat._pendingApprovals.update { approvals ->
                    approvals + TuiPendingApproval(
                        id = event.id,
                        agentId = event.sourceAgentId,
                        agentName = agentState?.name ?: event.sourceAgentId,
                        action = event.action,
                        risk = event.risk,
                        details = event.details
                    )
                }
                chat._agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "WAITING_APPROVAL") else it
                    }
                }
            }
            is AgentEvent.ApprovalDecision -> {
                chat._pendingApprovals.update { approvals ->
                    approvals.filter { it.id != event.approvalId }
                }
                if (event.approved) {
                    chat._agents.update { agents ->
                        agents.map {
                            if (it.id == event.sourceAgentId) it.copy(status = "RUNNING") else it
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // ========================================================================
    // Model / context window resolution
    // ========================================================================

    private fun resolveContextWindow(modelString: String?) {
        if (modelString.isNullOrBlank()) {
            _contextMaxTokens.value = 128_000
            return
        }
        try {
            val parts = modelString.split("/", limit = 2)
            if (parts.size == 2) {
                val provider = parts[0]
                val modelId = parts[1]
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition(provider, modelId)
                if (definition != null) {
                    _contextMaxTokens.value = definition.maxContext
                    return
                }
            }
            _contextMaxTokens.value = 128_000
        } catch (_: Exception) {
            _contextMaxTokens.value = 128_000
        }
    }

    private fun loadModelsInBackground(r: CoreApiRouter) {
        scope.launch {
            try {
                fetchAndCacheModels(r)
                logger.info { "Pre-loaded ${TuiSettingsScreen.getCachedModelCount()} models from providers" }
            } catch (e: Exception) {
                logger.debug(e) { "Background model loading failed (non-critical)" }
            }
        }
    }

    private suspend fun fetchAndCacheModels(r: CoreApiRouter) {
        val models = r.configRouter.getModelsWithVisibility()
        val entries = models.map { model ->
            TuiSettingsScreen.CachedModelEntry(
                id = model.id,
                provider = model.provider,
                name = model.name,
                contextSize = model.contextSize,
                pricing = model.pricing?.let {
                    TuiSettingsScreen.CachedPricing(it.inputPer1MTokens, it.outputPer1MTokens)
                },
                showInDropdown = model.showInDropdown
            )
        }
        TuiSettingsScreen.refreshModels(entries)
    }

    // ========================================================================
    // Settings methods (coordinator-owned)
    // ========================================================================

    private val settingsSections = listOf(
        "general", "providers", "default_model", "models", "prompts", "rag",
        "mcp", "docs", "tools", "subagents", "advanced", "theme", "limits"
    )

    /** Cached config section snapshot. Safe to call from render/input threads. */
    fun getConfigSection(section: String): Map<String, String> =
        configSectionCache[section] ?: emptyMap()

    /** Reload the settings snapshot (config sections + tool permissions) off the render thread. */
    fun refreshSettingsCache() {
        val r = router ?: return
        scope.launch(Dispatchers.IO) {
            refreshSettingsCacheBlocking(r)
        }
    }

    private fun refreshSettingsCacheBlocking(r: CoreApiRouter) {
        for (section in settingsSections) {
            configSectionCache[section] = try {
                r.configRouter.getConfig(section, "app").settings.mapValues { it.value.toString() }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get config section: $section" }
                emptyMap()
            }
        }
        toolPermissionsCache = try {
            r.toolRouter.getToolPermissions().tools
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load tool permissions" }
            emptyList()
        }
        // Bump the version so the merged state flow re-renders with the new snapshot.
        _settingsCacheVersion.update { it + 1 }
    }

    fun updateConfig(section: String, key: String, value: String) {
        val r = router ?: return
        scope.launch(Dispatchers.IO) {
            try {
                r.configRouter.updateConfig(section, "app", null, mapOf(key to value))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to update config: $section.$key" }
            }
            refreshSettingsCacheBlocking(r)
        }
    }

    fun resetAllSettings() {
        val r = router ?: return
        scope.launch(Dispatchers.IO) {
            try {
                r.configRouter.resetAllSettingsToDefaults()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to reset settings" }
            }
            refreshSettingsCacheBlocking(r)
        }
    }

    fun refreshSettingsModels() {
        val r = router ?: return
        scope.launch {
            try {
                chat.addSystemMessage("Refreshing models from providers...")
                fetchAndCacheModels(r)
                chat.addSystemMessage("Loaded ${TuiSettingsScreen.getCachedModelCount()} models from providers.")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh models" }
                chat.addSystemMessage("Failed to refresh models: ${e.message}")
            }
        }
    }

    fun settingsFieldUp() {
        _settingsSelectedField.update { (it - 1).coerceAtLeast(0) }
    }

    fun settingsFieldDown() {
        val lastIndex = (TuiSettingsScreen.fieldCount(_settingsTab.value) - 1).coerceAtLeast(0)
        _settingsSelectedField.update { (it + 1).coerceAtMost(lastIndex) }
    }

    /**
     * Two-step reset guard: first R arms, second R within the armed state resets.
     * Any other settings action disarms via [disarmSettingsReset].
     */
    fun armSettingsReset() {
        _settingsResetArmed.value = true
    }

    fun disarmSettingsReset() {
        if (_settingsResetArmed.value) {
            _settingsResetArmed.value = false
        }
    }

    fun isSettingsResetArmed(): Boolean = _settingsResetArmed.value

    /** Cached tool permissions snapshot (refreshed by [refreshSettingsCache]). */
    fun getToolPermissions(): List<ToolPermissionDto> = toolPermissionsCache

    /** Cycle ON -> ASK -> OFF for one tool/mode; persists through the tool-permissions API. */
    fun cycleToolPermission(toolName: String, agentMode: Boolean) {
        val r = router ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val current = r.toolRouter.getToolPermissions().tools.firstOrNull { it.toolName == toolName }
                    ?: return@launch
                val cycle = listOf("ON", "ASK", "OFF")
                fun next(value: String): String {
                    val idx = cycle.indexOf(value.uppercase())
                    return cycle[(idx + 1).mod(cycle.size)]
                }
                val request = if (agentMode) {
                    SetToolPermissionRequest(planMode = current.planMode, agentMode = next(current.agentMode))
                } else {
                    SetToolPermissionRequest(planMode = next(current.planMode), agentMode = current.agentMode)
                }
                r.toolRouter.setToolPermission(toolName, request)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cycle permission for $toolName" }
            }
            refreshSettingsCacheBlocking(r)
        }
    }

    fun settingsStartEdit(fieldKey: String, currentValue: String) {
        _settingsEditingField.value = fieldKey
        _settingsEditBuffer.value = currentValue
    }

    fun settingsUpdateEditBuffer(text: String) {
        _settingsEditBuffer.value = text
    }

    fun settingsCancelEdit() {
        _settingsEditingField.value = null
        _settingsEditBuffer.value = ""
    }

    fun settingsCommitEdit() {
        val field = _settingsEditingField.value ?: return
        val value = _settingsEditBuffer.value
        val modelOperation = when (field) {
            "default_model.chat" -> ModelOperation.DEFAULT
            "default_model.plan" -> ModelOperation.PLAN
            "default_model.agent" -> ModelOperation.CODING
            "default_model.weak" -> ModelOperation.WEAK
            "default_model.strong" -> ModelOperation.STRONG
            else -> null
        }
        if (modelOperation != null) {
            // Model slots persist as JSON {"modelId","provider"} via setDefaultModel — the shape
            // ModelSelectionService reads back. The generic raw-string updateConfig path below
            // would write an unparseable "provider/model" string (the bug this routing fixes).
            commitModelSlot(modelOperation, value)
        } else {
            val parts = field.split(".", limit = 2)
            if (parts.size == 2) {
                updateConfig(parts[0], parts[1], value)
            }
        }
        _settingsEditingField.value = null
        _settingsEditBuffer.value = ""
    }

    /**
     * Persist a `default_model.*` slot through [setDefaultModel] (JSON shape). Accepts the friendly
     * `provider/modelId` form, the already-stored JSON, or blank/`inherit` (clears to the parent slot).
     */
    private fun commitModelSlot(operation: ModelOperation, value: String) {
        val r = router ?: return
        val trimmed = value.trim()
        val (provider, modelId) = when {
            trimmed.isEmpty() || trimmed.equals(ConfigService.INHERIT_MODEL_VALUE, ignoreCase = true) ->
                ConfigService.INHERIT_MODEL_VALUE to ConfigService.INHERIT_MODEL_VALUE
            trimmed.startsWith("{") -> {
                val id = Regex(""""modelId"\s*:\s*"([^"]*)"""").find(trimmed)?.groupValues?.get(1)
                val prov = Regex(""""provider"\s*:\s*"([^"]*)"""").find(trimmed)?.groupValues?.get(1)
                if (id == null || prov == null) {
                    chat.addSystemMessage("Invalid model '$value' — expected provider/model (e.g. ollama/qwen3.5:9b).")
                    return
                }
                prov to id
            }
            else -> {
                // Split on the FIRST slash only — a modelId may itself contain slashes.
                val slash = trimmed.indexOf('/')
                if (slash <= 0 || slash == trimmed.length - 1) {
                    chat.addSystemMessage("Invalid model '$value' — expected provider/model (e.g. ollama/qwen3.5:9b).")
                    return
                }
                trimmed.substring(0, slash) to trimmed.substring(slash + 1)
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                r.configRouter.setDefaultModel(SetDefaultModelRequest(operation, modelId, provider), taskId = null)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to set model slot $operation = $provider/$modelId" }
            }
            refreshSettingsCacheBlocking(r)
        }
    }

    fun settingsToggleBool(section: String, key: String, currentValue: Boolean) {
        updateConfig(section, key, (!currentValue).toString())
    }

    fun exportUserConfig() {
        scope.launch {
            try {
                val r = router ?: return@launch
                val configFile = File(System.getProperty("user.home"), ".refio/config.yaml")
                configFile.parentFile?.mkdirs()
                val sections = listOf("general", "providers", "models", "limits", "advanced", "tools", "rag", "ui")
                val yaml = buildString {
                    appendLine("# Refio configuration — exported from TUI")
                    for (section in sections) {
                        val resp = r.configRouter.getConfig(section, "APP")
                        val settings = resp.settings
                        if (settings.isNotEmpty()) {
                            appendLine("$section:")
                            for ((key, value) in settings.entries.sortedBy { it.key }) {
                                val safe = if (key.contains("api_key") || key.contains("token")) "****" else value.toString()
                                appendLine("  $key: $safe")
                            }
                        }
                    }
                }
                configFile.writeText(yaml)
                chat.addSystemMessage("Config exported to: ${configFile.absolutePath}")
            } catch (e: Exception) {
                chat.addSystemMessage("Export failed: ${e.message}")
            }
        }
    }

    fun exportProjectConfig() {
        scope.launch {
            try {
                val r = router ?: return@launch
                val configFile = File(projectPath.toFile(), ".refio/config.yaml")
                configFile.parentFile?.mkdirs()
                val sections = listOf("general", "limits", "tools", "rag", "ui")
                val yaml = buildString {
                    appendLine("# Refio project configuration — exported from TUI")
                    for (section in sections) {
                        val resp = r.configRouter.getConfig(section, "PROJECT")
                        val settings = resp.settings
                        if (settings.isNotEmpty()) {
                            appendLine("$section:")
                            for ((key, value) in settings.entries.sortedBy { it.key }) {
                                appendLine("  $key: $value")
                            }
                        }
                    }
                }
                configFile.writeText(yaml)
                chat.addSystemMessage("Project config exported to: ${configFile.absolutePath}")
            } catch (e: Exception) {
                chat.addSystemMessage("Export failed: ${e.message}")
            }
        }
    }

    fun reloadConfig() {
        refreshSettingsCache()
        chat.addSystemMessage("Config reloaded from the config store.")
    }

    fun testProviderConnection(provider: String) {
        scope.launch {
            val r = router ?: return@launch
            try {
                chat.addSystemMessage("Testing connection to $provider...")
                val config = getConfigSection("providers")
                val result = r.configRouter.testProviderConnection(provider, config)
                if (result.success) {
                    chat.addSystemMessage("$provider: Connected (${result.latencyMs}ms)")
                } else {
                    chat.addSystemMessage("$provider: ${result.message}")
                }
            } catch (e: Exception) {
                chat.addSystemMessage("$provider test failed: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Delegation methods — forward to sub-VMs so callers (TuiInputHandler) work unchanged
    // ========================================================================

    // --- Chat delegations ---
    fun sendMessage(input: String) = chat.sendMessage(input)
    fun getSlashPrompts() = chat.getSlashPrompts()
    fun processSlashPrompts(text: String) = chat.processSlashPrompts(text)
    fun updateInputBuffer(input: String) = chat.updateInputBuffer(input)
    fun moveCursorLeft() = chat.moveCursorLeft()
    fun moveCursorRight() = chat.moveCursorRight()
    fun insertAtCursor(char: Char) = chat.insertAtCursor(char)
    fun insertStringAtCursor(text: String) = chat.insertStringAtCursor(text)
    fun clearPasteMarker() = chat.clearPasteMarker()
    fun deleteAtCursor() = chat.deleteAtCursor()
    fun chatScrollUp() = chat.chatScrollUp()
    fun chatScrollDown() = chat.chatScrollDown()
    fun chatScrollReset() = chat.chatScrollReset()
    fun addSystemMessage(content: String) = chat.addSystemMessage(content)
    fun messageSelectionUp() = chat.messageSelectionUp()
    fun messageSelectionDown() = chat.messageSelectionDown()
    fun clearMessageSelection() = chat.clearMessageSelection()
    fun answerQuestion(answer: String) = chat.answerQuestion(answer)
    fun approve(approvalId: String) = chat.approve(approvalId)
    fun reject(approvalId: String) = chat.reject(approvalId)
    fun trustAgent(agentId: String) = chat.trustAgent(agentId)
    fun isAgentTrusted(agentId: String) = chat.isAgentTrusted(agentId)

    // Tool approval (PermissionLevel.ASK)
    fun approveToolExecution(requestId: String) {
        router?.toolApprovalService?.resolveApproval(
            requestId,
            pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision.Approved
        )
    }
    fun trustToolExecution(requestId: String, toolName: String) {
        router?.toolApprovalService?.resolveApproval(
            requestId,
            pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision.Trusted(toolName)
        )
    }
    fun rejectToolExecution(requestId: String) {
        router?.toolApprovalService?.resolveApproval(
            requestId,
            pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision.Rejected()
        )
    }
    fun cycleAgentFilter() = chat.cycleAgentFilter()
    fun togglePanelFocus() = chat.togglePanelFocus()
    fun copyLastMessageToClipboard() = chat.copyLastMessageToClipboard()
    fun copyAllConversation() = chat.copyAllConversation()
    fun showCurrentPrompt() = chat.showCurrentPrompt()
    fun rewindToMessage(messageIndex: Int) = chat.rewindToMessage(messageIndex)
    fun resendLastMessage() = chat.resendLastMessage()
    fun editMessage(messageIndex: Int?) = chat.editMessage(messageIndex)
    fun continueConversation() = chat.continueConversation()
    fun summarizeConversation() = chat.summarizeConversation()
    fun exportConversation(path: String) = chat.exportConversation(path)
    fun cancelCurrentOperation() = chat.cancelCurrentOperation()
    fun triggerAutocomplete() = chat.triggerAutocomplete()
    fun triggerSubagentAutocomplete() = chat.triggerSubagentAutocomplete()
    fun triggerCommandAutocomplete() = chat.triggerCommandAutocomplete()
    fun updateAutocompleteFilter() = chat.updateAutocompleteFilter()
    fun autocompleteNext() = chat.autocompleteNext()
    fun autocompletePrev() = chat.autocompletePrev()
    fun autocompleteAccept() = chat.autocompleteAccept()
    fun autocompleteDismiss() = chat.autocompleteDismiss()

    // --- Session delegations ---
    fun updateExecutionStatus(status: String) = session.updateExecutionStatus(status)
    fun setSubtasks(subtasks: List<pl.jclab.refio.core.api.SubtaskResponse>) = session.setSubtasks(subtasks)
    fun updateSubtaskStatus(subtaskId: String, status: String, error: String? = null) = session.updateSubtaskStatus(subtaskId, status, error)
    fun cycleMode() = session.cycleMode()
    fun toggleThinking() = session.toggleThinking()
    fun toggleNoEgress() = session.toggleNoEgress()
    fun toggleExecutionMode() = session.toggleExecutionMode()
    fun showModelSelector() = session.showModelSelector()
    fun isModelSelectorVisible() = session.isModelSelectorVisible()
    fun modelSelectorNext() = session.modelSelectorNext()
    fun modelSelectorPrev() = session.modelSelectorPrev()
    fun modelSelectorAccept() = session.modelSelectorAccept()
    fun dismissModelSelector() = session.dismissModelSelector()
    fun getModelSelectorState() = session.getModelSelectorState()
    fun showNewSessionDialog() = session.showNewSessionDialog()
    fun switchSession(sessionId: String) = session.switchSession(sessionId)
    fun loadSessions() = session.loadSessions()
    fun refreshSessions() = session.refreshSessions()
    fun selectHistoryUp() = session.selectHistoryUp()
    fun selectHistoryDown() = session.selectHistoryDown()
    fun loadSelectedSession() = session.loadSelectedSession()
    fun deleteSelectedSession() = session.deleteSelectedSession()
    fun togglePinSession() = session.togglePinSession()
    fun setHistoryFilter(filter: String) = session.setHistoryFilter(filter)
    fun approvePlan() = session.approvePlan()
    fun rejectPlan() = session.rejectPlan()
    fun approveSubtask(subtaskId: String) = session.approveSubtask(subtaskId)
    fun skipSubtask(subtaskId: String) = session.skipSubtask(subtaskId)
    fun deleteSubtask(subtaskId: String) = session.deleteSubtask(subtaskId)
    fun moveStepUp(index: Int) = session.moveStepUp(index)
    fun moveStepDown(index: Int) = session.moveStepDown(index)
    fun cancelAllPending() = session.cancelAllPending()
    fun replanSteps() = session.replanSteps()
    fun addStep(description: String) = session.addStep(description)
    fun togglePause() = session.togglePause()
    fun selectStep(index: Int) = session.selectStep(index)
    fun selectStepUp() = session.selectStepUp()
    fun selectStepDown() = session.selectStepDown()

    // --- Observability delegations ---
    fun ragReindex() = obs.ragReindex()
    fun ragGenerateEmbeddings() = obs.ragGenerateEmbeddings()
    fun ragSearch(query: String) = obs.ragSearch(query)
    fun ragFileUp() = obs.ragFileUp()
    fun ragFileDown() = obs.ragFileDown()
    fun ragOpenSelectedFile() = obs.ragOpenSelectedFile()
    fun ragViewSelectedChunks() = obs.ragViewSelectedChunks()
    fun ragViewChunks(filePath: String) = obs.ragViewChunks(filePath)
    fun ragStopIndexing() = obs.ragStopIndexing()
    fun ragClearIndex() = obs.ragClearIndex()
    fun docsAdd(url: String, depth: Int = 2) = obs.docsAdd(url, depth)
    fun docsDelete(docId: Int) = obs.docsDelete(docId)
    fun docsReindex(docId: Int) = obs.docsReindex(docId)
    fun contextSectionUp() = obs.contextSectionUp()
    fun contextSectionDown() = obs.contextSectionDown()
    fun toggleContextDetail() = obs.toggleContextDetail()
    fun contextDetailScrollUp() = obs.contextDetailScrollUp()
    fun contextDetailScrollDown() = obs.contextDetailScrollDown()
    fun toggleLogPause() = obs.toggleLogPause()
    fun logUp() = obs.logUp()
    fun logDown() = obs.logDown()
    fun toggleLogDetail() = obs.toggleLogDetail()
    fun cycleLogFilter() = obs.cycleLogFilter()
    fun openLogDetailViewer() = obs.openLogDetailViewer()
    fun cycleApiLogsFilter() = obs.cycleApiLogsFilter()
    fun apiLogUp() = obs.apiLogUp()
    fun apiLogDown() = obs.apiLogDown()
    fun toggleApiLogDetail() = obs.toggleApiLogDetail()
    fun openApiLogDetailViewer() = obs.openApiLogDetailViewer()
    fun apiLogDetailScrollUp() = obs.apiLogDetailScrollUp()
    fun apiLogDetailScrollDown() = obs.apiLogDetailScrollDown()
    fun resetApiLogDetailScroll() = obs.resetApiLogDetailScroll()
    fun debugScrollUp() = obs.debugScrollUp()
    fun debugScrollDown() = obs.debugScrollDown()
    fun fileBrowserUp() = obs.fileBrowserUp()
    fun fileBrowserDown() = obs.fileBrowserDown()
    fun fileBrowserEnter() = obs.fileBrowserEnter()
    fun fileBrowserGoUp() = obs.fileBrowserGoUp()
    fun fileBrowserToggleHidden() = obs.fileBrowserToggleHidden()
    fun fileBrowserAddAsContext() = obs.fileBrowserAddAsContext()
    fun fileBrowserOpenExternal() = obs.fileBrowserOpenExternal()
    fun fileBrowserShowInfo() = obs.fileBrowserShowInfo()
    fun fileBrowserRefresh() = obs.fileBrowserRefresh()
    fun openFileViewer(path: String, content: String) = obs.openFileViewer(path, content)
    fun openContentViewer(title: String, content: String, showLineNumbers: Boolean = false, allowAddContext: Boolean = false) =
        obs.openContentViewer(title, content, showLineNumbers, allowAddContext)
    fun closeFileViewer() = obs.closeFileViewer()
    fun showFileViewerHint() = obs.showFileViewerHint()
    fun fileViewerScrollUp() = obs.fileViewerScrollUp()
    fun fileViewerScrollDown() = obs.fileViewerScrollDown()
    fun fileViewerPageUp() = obs.fileViewerPageUp()
    fun fileViewerPageDown() = obs.fileViewerPageDown()
    fun fileViewerAddAsContext() = obs.fileViewerAddAsContext()
    fun fileViewerCopyToClipboard() = obs.fileViewerCopyToClipboard()
    fun helpScrollUp() = obs.helpScrollUp()
    fun helpScrollDown() = obs.helpScrollDown()
    fun helpPageUp() = obs.helpPageUp()
    fun helpPageDown() = obs.helpPageDown()

    // ========================================================================
    // Methods kept on coordinator (called from main.kt, not sub-VM territory)
    // ========================================================================

    fun rateConversation(rating: Int) {
        val r = router ?: return
        val tid = taskId ?: run {
            chat.addSystemMessage("No active session to rate.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                r.taskRouter.updateTask(tid, UpdateTaskRequest(rate = rating))
                val label = if (rating > 0) "positive" else "negative"
                chat.addSystemMessage("Conversation rated: $label")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to rate: ${e.message}")
            }
        }
    }

    /**
     * `/goal <condition>` — set a completion condition for the active task. When set, the
     * next-speaker judge (in AGENT mode) switches to strict goal-aware evaluation: it
     * keeps pushing the loop back until the transcript demonstrates that the condition
     * holds, instead of accepting the agent's first "Done." reply.
     *
     * The condition persists across session restarts (stored on the task in the DB).
     * Pass an empty/blank string from the caller side to surface a usage hint instead.
     */
    fun setGoal(condition: String) {
        val r = router ?: return
        val tid = taskId ?: run {
            chat.addSystemMessage("No active session — start a conversation first, then set a goal.")
            return
        }
        if (condition.isBlank()) {
            chat.addSystemMessage("Usage: /goal <completion condition>  (e.g. \"all tests in src/test pass\")")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                r.taskRouter.setGoal(tid, condition)
                chat.addSystemMessage("◎ goal set: ${condition.take(120)}${if (condition.length > 120) "…" else ""}")
            } catch (e: IllegalArgumentException) {
                chat.addSystemMessage("Failed to set goal: ${e.message}")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to set goal: ${e.message}")
            }
        }
    }

    /**
     * `/goal clear` — remove the active completion condition. The judge falls back to
     * generic "is the turn finished?" evaluation.
     */
    fun clearGoal() {
        val r = router ?: return
        val tid = taskId ?: run {
            chat.addSystemMessage("No active session.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val had = r.taskRouter.getGoal(tid) != null
                r.taskRouter.clearGoal(tid)
                chat.addSystemMessage(if (had) "goal cleared" else "no goal was set")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to clear goal: ${e.message}")
            }
        }
    }

    /**
     * `/goal` (no args) — print the currently active condition or report none.
     */
    fun showGoalStatus() {
        val r = router ?: return
        val tid = taskId ?: run {
            chat.addSystemMessage("No active session.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val goal = r.taskRouter.getGoal(tid)
                chat.addSystemMessage(if (goal != null) "◎ goal: $goal" else "(no goal set — use /goal <condition> to set one)")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to get goal: ${e.message}")
            }
        }
    }

    fun addSnippetContext(filePath: String, startLine: Int?, endLine: Int?) {
        if (filePath.isBlank()) {
            chat.addSystemMessage("Usage: /snippet <file> [startLine] [endLine]")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val resolved = projectPath.resolve(filePath).toRealPath()
                if (!java.nio.file.Files.exists(resolved)) {
                    chat.addSystemMessage("File not found: $filePath")
                    return@launch
                }
                val allLines = java.nio.file.Files.readAllLines(resolved)
                val start = ((startLine ?: 1) - 1).coerceIn(0, allLines.size)
                val end = (endLine ?: allLines.size).coerceIn(start, allLines.size)
                val snippet = allLines.subList(start, end).joinToString("\n")

                if (snippet.length > 100_000) {
                    chat.addSystemMessage("Snippet too large (${snippet.length} chars). Max 100K.")
                    return@launch
                }

                val lineInfo = if (startLine != null || endLine != null) ":${start + 1}-$end" else ""
                val currentInput = chat._inputBuffer.value
                val ref = "@file:${filePath}$lineInfo"
                chat._inputBuffer.value = if (currentInput.isBlank()) ref else "$currentInput $ref"
                chat.addSystemMessage("Added snippet: $filePath lines ${start + 1}-$end (${end - start} lines)")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to read snippet: ${e.message}")
            }
        }
    }

    fun openFileInEditor(filePath: String) {
        if (filePath.isBlank()) {
            chat.addSystemMessage("Usage: /open <file-path>")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val resolved = projectPath.resolve(filePath).toRealPath()
                if (!java.nio.file.Files.exists(resolved)) {
                    chat.addSystemMessage("File not found: $filePath")
                    return@launch
                }
                val editor = System.getenv("EDITOR") ?: System.getenv("VISUAL") ?: "vi"
                val pb = ProcessBuilder(editor, resolved.toString())
                pb.inheritIO()
                val proc = pb.start()
                proc.waitFor()
                chat.addSystemMessage("Closed editor for: $filePath")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to open editor: ${e.message}")
            }
        }
    }

    fun mcpAddServer(args: String) {
        val parts = args.split("\\s+".toRegex(), limit = 4)
        if (parts.size < 3) {
            chat.addSystemMessage("""Usage:
  /mcp-add stdio <name> <command> [args...]
  /mcp-add http <name> <url>
Example:
  /mcp-add stdio my-server npx -y @modelcontextprotocol/server-filesystem /tmp
  /mcp-add http docs-server https://docs.example.com/mcp""")
            return
        }
        val type = parts[0].lowercase()
        val name = parts[1]
        val rest = parts.getOrElse(2) { "" } + if (parts.size > 3) " ${parts[3]}" else ""

        val config = when (type) {
            "stdio" -> {
                val cmdParts = rest.split("\\s+".toRegex())
                MCPServerConfig(
                    id = name, displayName = name, type = MCPServerType.STDIO,
                    command = cmdParts.first(), args = cmdParts.drop(1),
                    workingDirectory = projectPath.toString(), enabled = true
                )
            }
            "http", "sse" -> {
                MCPServerConfig(
                    id = name, displayName = name, type = MCPServerType.HTTP_SSE,
                    url = rest.trim(), enabled = true
                )
            }
            else -> {
                chat.addSystemMessage("Unknown type '$type'. Use 'stdio' or 'http'.")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.addOrUpdateServer(projectId, config)
                chat.addSystemMessage("MCP server '$name' added (${config.type}). Connecting...")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to add MCP server: ${e.message}")
            }
        }
    }

    fun mcpEditServer(args: String) {
        val parts = args.split("\\s+".toRegex(), limit = 3)
        if (parts.size < 3) {
            chat.addSystemMessage("""Usage: /mcp-edit <id> <field> <value>
Fields: enabled (true/false), url, command, auth (bearer-token), env (KEY=VALUE)
Example:
  /mcp-edit my-server enabled false
  /mcp-edit my-server auth sk-my-api-key
  /mcp-edit my-server env OPENAI_API_KEY=sk-xxx""")
            return
        }
        val serverId = parts[0]
        val field = parts[1].lowercase()
        val value = parts[2]

        val servers = MCPManager.getAllServers(projectId)
        val existing = servers.find { it.id == serverId }
        if (existing == null) {
            chat.addSystemMessage("MCP server '$serverId' not found. Use /mcp-list to see available servers.")
            return
        }

        val updated = when (field) {
            "enabled" -> existing.copy(enabled = value.lowercase() in listOf("true", "1", "yes"))
            "url" -> existing.copy(url = value)
            "command" -> existing.copy(command = value)
            "auth" -> existing.copy(auth = MCPAuthConfig(type = MCPAuthType.BEARER, apiKey = value))
            "env" -> {
                val eqIdx = value.indexOf('=')
                if (eqIdx < 0) { chat.addSystemMessage("Env format: KEY=VALUE"); return }
                val envVar = MCPEnvVariable(value.substring(0, eqIdx), value.substring(eqIdx + 1))
                existing.copy(env = existing.env.filter { it.name != envVar.name } + envVar)
            }
            "name" -> existing.copy(displayName = value)
            else -> {
                chat.addSystemMessage("Unknown field '$field'. Use: enabled, url, command, auth, env, name")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.addOrUpdateServer(projectId, updated)
                chat.addSystemMessage("MCP server '$serverId' updated: $field = $value")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to update MCP server: ${e.message}")
            }
        }
    }

    fun mcpRemoveServer(serverId: String) {
        if (serverId.isBlank()) {
            chat.addSystemMessage("Usage: /mcp-remove <server-id>")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.removeServer(projectId, serverId)
                chat.addSystemMessage("MCP server '$serverId' removed.")
            } catch (e: Exception) {
                chat.addSystemMessage("Failed to remove MCP server: ${e.message}")
            }
        }
    }

    fun mcpListServers() {
        val servers = MCPManager.getAllServers(projectId)
        if (servers.isEmpty()) {
            chat.addSystemMessage("No MCP servers configured. Use /mcp-add to add one.")
            return
        }
        val sb = StringBuilder("MCP Servers:\n")
        for (server in servers) {
            val status = MCPManager.getServerStatus(projectId, server.id)
            val enabled = if (server.enabled) "Y" else "N"
            val statusLabel = status.name
            sb.appendLine("  [$enabled] [$statusLabel] ${server.id} (${server.type}) -- ${server.displayName ?: server.id}")
            server.command?.let { sb.appendLine("      cmd: $it ${server.args.joinToString(" ")}") }
            server.url?.let { sb.appendLine("      url: $it") }
        }
        chat.addSystemMessage(sb.toString().trimEnd())
    }
}
