package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.SlashCommand
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.UpdateSubtaskRequest
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.TaskMode as CoreTaskMode
import pl.jclab.refio.core.db.ExecutionMode as CoreExecutionMode
import pl.jclab.refio.core.logging.LogSinkRegistry
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.services.TurnResult
import mu.KotlinLogging
import pl.jclab.refio.cli.tui.input.TuiContextValidator
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.context.mcp.MCPAuthConfig
import pl.jclab.refio.core.context.mcp.MCPAuthType
import pl.jclab.refio.core.context.mcp.MCPEnvVariable
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.utils.ProjectIdGenerator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * TUI ViewModel — connects core to TUI state via StateFlows.
 * No Compose dependencies. Uses ANSI color indices instead of Color objects.
 */
class TuiViewModel(
    internal val projectPath: Path,
    private val initialMode: pl.jclab.refio.api.models.TaskMode,
    private val initialModel: String?,
    private val noEgress: Boolean
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableStateFlow<List<TuiChatMessage>>(emptyList())
    private val _isStreaming = MutableStateFlow(false)
    private val _agents = MutableStateFlow<List<TuiAgentState>>(emptyList())
    private val _pendingApprovals = MutableStateFlow<List<TuiPendingApproval>>(emptyList())
    private val _activeTab = MutableStateFlow(TuiTab.CHAT)
    private val _screen = MutableStateFlow(TuiScreen.MAIN)
    private val _inputBuffer = MutableStateFlow("")
    private val _cursorPosition = MutableStateFlow(0)
    private val _scrollOffset = MutableStateFlow(0)
    private val _totalCost = MutableStateFlow(0.0)
    private val _totalTokens = MutableStateFlow(0L)
    private val _mode = MutableStateFlow(initialMode.name)
    private val _model = MutableStateFlow(initialModel)
    private val _steps = MutableStateFlow<List<TuiStep>>(emptyList())
    private val _contextSections = MutableStateFlow<List<TuiContextSection>>(emptyList())
    private val _logs = MutableStateFlow<List<TuiLogEntry>>(emptyList())
    private val _apiLogs = MutableStateFlow<List<TuiApiLogEntry>>(emptyList())
    private val _debugInfo = MutableStateFlow(TuiDebugInfo())
    private val _sessions = MutableStateFlow<List<TuiSessionEntry>>(emptyList())
    private val _settingsTab = MutableStateFlow(0)
    private val _executionMode = MutableStateFlow("AUTO")
    private val _thinkingEnabled = MutableStateFlow(false)
    private val _noEgressEnabled = MutableStateFlow(noEgress)
    private val _subtasks = MutableStateFlow<List<TuiSubtask>>(emptyList())
    private val _activePlan = MutableStateFlow<TuiPlan?>(null)
    private val _isPaused = MutableStateFlow(false)
    private val _pendingPlanApproval = MutableStateFlow<TuiPlanApproval?>(null)
    private val _selectedStepIndex = MutableStateFlow(0)
    private val _executionStatus = MutableStateFlow("Idle")
    private val _selectedHistoryIndex = MutableStateFlow(0)
    private val _historyFilter = MutableStateFlow("*")
    private val _ragIndexingProgress = MutableStateFlow(-1.0)
    private val _ragIndexingStatus = MutableStateFlow("")
    private val _agentFilter = MutableStateFlow<String?>(null)
    private val _modelSelectorVisible = MutableStateFlow(false)
    private val _modelSelectorCandidates = MutableStateFlow<List<String>>(emptyList())
    private val _modelSelectorIndex = MutableStateFlow(0)
    private val _autocompleteVisible = MutableStateFlow(false)
    private val _autocompleteCandidates = MutableStateFlow<List<String>>(emptyList())
    private val _autocompleteSelectedIndex = MutableStateFlow(0)
    private val _pendingQuestionId = MutableStateFlow<String?>(null)
    private val _pendingQuestionOptions = MutableStateFlow<List<String>>(emptyList())
    private val _contextMaxTokens = MutableStateFlow(128_000)
    private val _settingsSelectedField = MutableStateFlow(0)
    private val _settingsEditingField = MutableStateFlow<String?>(null)
    private val _settingsEditBuffer = MutableStateFlow("")
    private val _ragIndexedFiles = MutableStateFlow<List<TuiRagFile>>(emptyList())
    private val _apiLogsFilter = MutableStateFlow<String?>(null)
    private val _selectedApiLogIndex = MutableStateFlow(0)
    private val _apiLogDetailVisible = MutableStateFlow(false)
    private val _ragSelectedFileIndex = MutableStateFlow(0)
    private val _ragSearchQuery = MutableStateFlow("")
    private val _ragSearchResults = MutableStateFlow<List<String>>(emptyList())
    private val _selectedMessageIndex = MutableStateFlow(-1)
    private val _selectedContextIndex = MutableStateFlow(0)
    private val _pastedContent = MutableStateFlow<String?>(null)
    private val _helpScrollOffset = MutableStateFlow(0)
    private val _fileBrowserPath = MutableStateFlow("")
    private val _fileBrowserEntries = MutableStateFlow<List<TuiFileEntry>>(emptyList())
    private val _fileBrowserSelectedIndex = MutableStateFlow(0)
    private val _fileBrowserShowHidden = MutableStateFlow(false)

    private val _isInitialized = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Combined state flow for rendering — reacts to ANY flow change */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val stateFlow: StateFlow<TuiState> = merge(
        _messages.map { Unit },
        _isStreaming.map { Unit },
        _agents.map { Unit },
        _pendingApprovals.map { Unit },
        _activeTab.map { Unit },
        _inputBuffer.map { Unit },
        _cursorPosition.map { Unit },
        _scrollOffset.map { Unit },
        _screen.map { Unit },
        _steps.map { Unit },
        _subtasks.map { Unit },
        _activePlan.map { Unit },
        _isPaused.map { Unit },
        _pendingPlanApproval.map { Unit },
        _selectedStepIndex.map { Unit },
        _executionStatus.map { Unit },
        _contextSections.map { Unit },
        _logs.map { Unit },
        _apiLogs.map { Unit },
        _debugInfo.map { Unit },
        _sessions.map { Unit },
        _mode.map { Unit },
        _model.map { Unit },
        _executionMode.map { Unit },
        _thinkingEnabled.map { Unit },
        _noEgressEnabled.map { Unit },
        _selectedHistoryIndex.map { Unit },
        _historyFilter.map { Unit },
        _ragIndexingProgress.map { Unit },
        _ragIndexingStatus.map { Unit },
        _agentFilter.map { Unit },
        _totalCost.map { Unit },
        _totalTokens.map { Unit },
        _settingsTab.map { Unit },
        _autocompleteVisible.map { Unit },
        _autocompleteCandidates.map { Unit },
        _autocompleteSelectedIndex.map { Unit },
        _modelSelectorVisible.map { Unit },
        _modelSelectorCandidates.map { Unit },
        _modelSelectorIndex.map { Unit },
        _pendingQuestionId.map { Unit },
        _pendingQuestionOptions.map { Unit },
        _settingsSelectedField.map { Unit },
        _settingsEditingField.map { Unit },
        _settingsEditBuffer.map { Unit },
        _ragIndexedFiles.map { Unit },
        _apiLogsFilter.map { Unit },
        _selectedApiLogIndex.map { Unit },
        _apiLogDetailVisible.map { Unit },
        _ragSelectedFileIndex.map { Unit },
        _ragSearchQuery.map { Unit },
        _ragSearchResults.map { Unit },
        _selectedMessageIndex.map { Unit },
        _selectedContextIndex.map { Unit },
        _pastedContent.map { Unit },
        _helpScrollOffset.map { Unit },
        _fileBrowserPath.map { Unit },
        _fileBrowserEntries.map { Unit },
        _fileBrowserSelectedIndex.map { Unit },
        _fileBrowserShowHidden.map { Unit },
    ).debounce(16) // ~60fps cap — prevents excessive rebuilds during streaming
    .map {
        buildCurrentState()
    }.stateIn(scope, SharingStarted.Eagerly, TuiState(mode = initialMode.name, model = initialModel))

    private fun buildCurrentState() = TuiState(
        screen = _screen.value,
        activeTab = _activeTab.value,
        messages = _messages.value,
        isStreaming = _isStreaming.value,
        agents = _agents.value,
        steps = _steps.value,
        subtasks = _subtasks.value,
        activePlan = _activePlan.value,
        isPaused = _isPaused.value,
        pendingPlanApproval = _pendingPlanApproval.value,
        selectedStepIndex = _selectedStepIndex.value,
        contextSections = _contextSections.value,
        logs = _logs.value,
        apiLogs = _apiLogs.value,
        debugInfo = _debugInfo.value,
        pendingApprovals = _pendingApprovals.value,
        sessions = _sessions.value,
        activeSessionId = taskId,
        selectedHistoryIndex = _selectedHistoryIndex.value,
        historyFilter = _historyFilter.value,
        mode = _mode.value,
        model = _model.value,
        executionMode = _executionMode.value,
        thinkingEnabled = _thinkingEnabled.value,
        noEgressEnabled = _noEgressEnabled.value,
        inputBuffer = _inputBuffer.value,
        cursorPosition = _cursorPosition.value,
        scrollOffset = _scrollOffset.value,
        totalCostUsd = _totalCost.value,
        totalTokens = _totalTokens.value,
        settingsTab = _settingsTab.value,
        autocompleteVisible = _autocompleteVisible.value,
        autocompleteCandidates = _autocompleteCandidates.value,
        autocompleteSelectedIndex = _autocompleteSelectedIndex.value,
        executionStatus = _executionStatus.value,
        contextUsedTokens = _contextSections.value.sumOf { it.tokensUsed },
        contextMaxTokens = _contextMaxTokens.value,
        sessionTokensIn = _messages.value.sumOf { it.tokensIn.toLong() },
        sessionTokensOut = _messages.value.sumOf { it.tokensOut.toLong() },
        ragIndexingProgress = _ragIndexingProgress.value,
        ragIndexingStatus = _ragIndexingStatus.value,
        agentFilter = _agentFilter.value,
        modelSelectorVisible = _modelSelectorVisible.value,
        modelSelectorCandidates = _modelSelectorCandidates.value,
        modelSelectorIndex = _modelSelectorIndex.value,
        pendingQuestionId = _pendingQuestionId.value,
        pendingQuestionOptions = _pendingQuestionOptions.value,
        settingsSelectedField = _settingsSelectedField.value,
        settingsEditingField = _settingsEditingField.value,
        settingsEditBuffer = _settingsEditBuffer.value,
        ragIndexedFiles = _ragIndexedFiles.value,
        apiLogsFilter = _apiLogsFilter.value,
        selectedApiLogIndex = _selectedApiLogIndex.value,
        apiLogDetailVisible = _apiLogDetailVisible.value,
        ragSelectedFileIndex = _ragSelectedFileIndex.value,
        ragSearchQuery = _ragSearchQuery.value,
        ragSearchResults = _ragSearchResults.value,
        selectedMessageIndex = _selectedMessageIndex.value,
        selectedContextIndex = _selectedContextIndex.value,
        pastedContent = _pastedContent.value,
        helpScrollOffset = _helpScrollOffset.value,
        fileBrowserPath = _fileBrowserPath.value,
        fileBrowserEntries = _fileBrowserEntries.value,
        fileBrowserSelectedIndex = _fileBrowserSelectedIndex.value,
        fileBrowserShowHidden = _fileBrowserShowHidden.value,
    )

    private var bootstrap: StandaloneCoreBootstrap? = null
    private var router: CoreApiRouter? = null
    private var taskId: String? = null
    val agentEventBus = AgentEventBus()

    /** LogSink that feeds core log messages into the Logs tab. */
    private val tuiLogSink = TuiLogSink(_logs)

    private val apiLogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val workflowListener = TuiWorkflowListener(
        agentId = "main",
        agentName = "Refio",
        agentColorIndex = 0,
        messagesState = _messages,
        streamingState = _isStreaming,
        stepsState = _steps,
        scope = scope,
        viewModel = this
    )

    private val projectId: String by lazy {
        ProjectIdGenerator.generate(projectPath.toAbsolutePath().normalize())
    }

    /** File to persist last session ID for restore on restart */
    private val lastSessionFile: java.io.File by lazy {
        projectPath.resolve(".refio/last-session").toFile()
    }

    suspend fun initialize() {
        try {
            // Register TUI log sink before core init so we capture init logs
            LogSinkRegistry.register(tuiLogSink)

            val boot = StandaloneCoreBootstrap(projectPath)
            val r = boot.initialize()
            bootstrap = boot
            router = r
            _isInitialized.value = true

            // Restore or create session via router API
            val restoredTaskId = restoreOrCreateSession(r)
            taskId = restoredTaskId

            // Load persisted UI toggle states from config
            try {
                val uiConfig = r.configRouter.getConfig("ui", "app")
                uiConfig.settings["thinking_enabled"]?.toString()?.toBooleanStrictOrNull()?.let {
                    _thinkingEnabled.value = it
                }
                uiConfig.settings["no_egress_enabled"]?.toString()?.toBooleanStrictOrNull()?.let {
                    _noEgressEnabled.value = it
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load UI toggle states from config" }
            }

            // If no model specified via CLI, resolve default for current mode
            if (initialModel == null) {
                try {
                    val operation = ModelOperation.fromTaskMode(CoreTaskMode.valueOf(_mode.value))
                    val defaultModel = r.configRouter.getDefaultModel(operation)
                    if (defaultModel.modelId != null && defaultModel.provider != null) {
                        _model.value = "${defaultModel.provider}/${defaultModel.modelId}"
                        resolveContextWindow(_model.value)
                        logger.info { "Default model for ${_mode.value}: ${_model.value}" }
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to resolve default model for mode" }
                }
            } else {
                resolveContextWindow(initialModel)
            }

            _debugInfo.update {
                it.copy(
                    connected = true,
                    sessionId = restoredTaskId,
                    dbPath = projectPath.resolve(".refio/database.sqlite").toString(),
                    mode = initialMode.name,
                    model = _model.value ?: "default"
                )
            }
            logger.info { "Core initialized for project: ${projectPath.toAbsolutePath()}, session: $restoredTaskId" }

            // Load messages and subtasks from DB for restored session
            loadMessagesFromDb(r, restoredTaskId)
            loadSubtasksFromDb(r, restoredTaskId)

            bridgeBackendEventBus(r)
            subscribeToAgentEvents()
            subscribeToUserInteraction(r)
            startAutoRefresh(r)
            refreshRagStats(r)

            // Pre-load available models from providers (like plugin's ModelsSettingsPanel.loadModels())
            loadModelsInBackground(r)

            // Initialize file browser to project root
            initFileBrowser()
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize core" }
            _error.value = "Initialization failed: ${e.message}"
        }
    }

    /**
     * Restore last session from file, or create a new task via router.
     */
    private fun restoreOrCreateSession(r: CoreApiRouter): String {
        // Try to restore from last-session file
        try {
            if (lastSessionFile.exists()) {
                val savedId = lastSessionFile.readText().trim()
                if (savedId.isNotBlank()) {
                    val task = r.getTask(savedId)
                    if (task != null) {
                        _mode.value = task.mode
                        logger.info { "Restored session: $savedId (mode=${task.mode})" }
                        return savedId
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to restore last session" }
        }

        // Try to find last session for this project
        try {
            val lastTask = r.getLastSessionForProject(projectId)
            if (lastTask != null) {
                persistLastSessionId(lastTask.id)
                _mode.value = lastTask.mode
                logger.info { "Found last project session: ${lastTask.id}" }
                return lastTask.id
            }
        } catch (e: Exception) {
            logger.debug(e) { "No previous session found" }
        }

        // Create new session
        return createNewTaskInDb(r)
    }

    private fun createNewTaskInDb(r: CoreApiRouter): String {
        val taskMode = try { CoreTaskMode.valueOf(_mode.value) } catch (_: Exception) { CoreTaskMode.valueOf(initialMode.name) }
        val task = r.createTask(CreateTaskRequest(
            name = "TUI Session",
            mode = taskMode,
            projectId = projectId,
            projectPath = projectPath.toAbsolutePath().toString()
        ))
        persistLastSessionId(task.id)
        logger.info { "Created new session: ${task.id} (mode=${taskMode})" }
        return task.id
    }

    private fun persistLastSessionId(id: String) {
        try {
            lastSessionFile.parentFile?.mkdirs()
            lastSessionFile.writeText(id)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist session ID" }
        }
    }

    private fun loadMessagesFromDb(r: CoreApiRouter, tid: String) {
        try {
            val response = r.getMessages(tid)
            if (response.messages.isNotEmpty()) {
                _messages.value = response.messages.map { msg ->
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
        // Tool result messages
        if (msg.role == "tool") return TuiMessageType.TOOL_CALL
        // Assistant messages with tool calls
        val tcJson = msg.toolCallsJson
        if (tcJson != null && tcJson.isNotBlank()) return TuiMessageType.TOOL_CALL
        // Check metadata for special types
        val meta = msg.metadata
        if (meta != null) {
            try {
                if (meta.contains("\"orchestrator_question\"")) return TuiMessageType.ORCHESTRATOR_QUESTION
                if (meta.contains("\"execution_summary\"")) return TuiMessageType.EXECUTION_SUMMARY
                if (meta.contains("\"plan\"")) return TuiMessageType.PLAN
            } catch (_: Exception) {}
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

    private fun loadSubtasksFromDb(r: CoreApiRouter, tid: String) {
        try {
            val response = r.getSubtasks(tid)
            if (response.subtasks.isNotEmpty()) {
                _subtasks.value = response.subtasks.map { st ->
                    TuiSubtask(
                        id = st.id,
                        name = st.description,
                        description = st.description,
                        status = st.status,
                        toolName = st.kind,
                        tokensIn = st.tokensIn.toLong(),
                        tokensOut = st.tokensOut.toLong(),
                        costUsd = st.costUsd,
                        order = st.orderIndex,
                        model = st.model,
                        provider = st.provider,
                        startedAt = st.startedAt,
                        finishedAt = st.finishedAt,
                        resultSummary = st.resultSummary,
                        error = st.errorMessage
                    )
                }
                logger.info { "Loaded ${response.subtasks.size} subtasks from DB" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to load subtasks from DB" }
        }
    }

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
                _pendingQuestionId.value = questionId
                if (questionId == null) {
                    _pendingQuestionOptions.value = emptyList()
                }
                // Options are displayed from message metadata; the question itself
                // is already in the chat as an assistant message.
            }
        }
    }

    fun answerQuestion(answer: String) {
        val questionId = _pendingQuestionId.value ?: return
        val r = router ?: return
        scope.launch {
            try {
                r.userInteraction.provideResponse(questionId, answer)
                _pendingQuestionId.value = null
                _pendingQuestionOptions.value = emptyList()
                // Add user answer to chat for display
                _messages.update { it + TuiChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "user",
                    content = answer
                )}
            } catch (e: Exception) {
                logger.error(e) { "Failed to answer question" }
                addSystemMessage("Error answering question: ${e.message}")
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        val chatMsg = TuiChatMessageMapper.mapEvent(event)
        if (chatMsg != null) {
            _messages.update { it + chatMsg }
        }

        when (event) {
            is AgentEvent.AgentStarted -> {
                val colorIdx = TuiChatMessageMapper.getAgentColorIndex(event.sourceAgentId)
                _agents.update { agents ->
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
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(
                            status = "COMPLETED",
                            tokensUsed = event.tokensUsed,
                            costUsd = event.costUsd
                        ) else it
                    }
                }
                _totalTokens.update { it + event.tokensUsed }
                _totalCost.update { it + event.costUsd }
            }

            is AgentEvent.AgentFailed -> {
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "FAILED") else it
                    }
                }
            }

            is AgentEvent.ProgressUpdate -> {
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(currentPhase = event.phase) else it
                    }
                }
            }

            is AgentEvent.ApprovalRequired -> {
                // Auto-approve if agent is trusted
                if (isAgentTrusted(event.sourceAgentId)) {
                    approve(event.id)
                    return
                }
                val agentState = _agents.value.find { it.id == event.sourceAgentId }
                _pendingApprovals.update { approvals ->
                    approvals + TuiPendingApproval(
                        id = event.id,
                        agentId = event.sourceAgentId,
                        agentName = agentState?.name ?: event.sourceAgentId,
                        action = event.action,
                        risk = event.risk,
                        details = event.details
                    )
                }
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "WAITING_APPROVAL") else it
                    }
                }
            }

            is AgentEvent.ApprovalDecision -> {
                _pendingApprovals.update { approvals ->
                    approvals.filter { it.id != event.approvalId }
                }
                if (event.approved) {
                    _agents.update { agents ->
                        agents.map {
                            if (it.id == event.sourceAgentId) it.copy(status = "RUNNING") else it
                        }
                    }
                }
            }

            else -> {}
        }
    }

    fun setActiveTab(tab: TuiTab) {
        // Toggle: pressing the same F-key again switches back to CHAT (closes the panel)
        _activeTab.value = if (_activeTab.value == tab && tab != TuiTab.CHAT) TuiTab.CHAT else tab
    }

    fun setScreen(screen: TuiScreen) {
        // Toggle: pressing F8 again while in Settings goes back to MAIN
        _screen.value = if (_screen.value == screen && screen != TuiScreen.MAIN) TuiScreen.MAIN else screen
    }

    fun setSettingsTab(index: Int) {
        _settingsTab.value = index.coerceIn(0, 10)
    }

    fun updateInputBuffer(input: String) {
        _inputBuffer.value = input
        _cursorPosition.value = input.length
    }

    fun moveCursorLeft() {
        _cursorPosition.value = (_cursorPosition.value - 1).coerceAtLeast(0)
    }

    fun moveCursorRight() {
        _cursorPosition.value = (_cursorPosition.value + 1).coerceAtMost(_inputBuffer.value.length)
    }

    // Paste detection: track rapid character insertions
    @Volatile private var lastInsertTime = 0L
    @Volatile private var rapidInsertCount = 0
    private val PASTE_THRESHOLD_CHARS = 20 // chars in rapid succession to detect paste
    private val PASTE_THRESHOLD_MS = 100L // time window for rapid insertion

    fun insertAtCursor(char: Char) {
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        _inputBuffer.value = buf.substring(0, pos) + char + buf.substring(pos)
        _cursorPosition.value = pos + 1

        // Detect paste: many chars in quick succession
        val now = System.currentTimeMillis()
        if (now - lastInsertTime < PASTE_THRESHOLD_MS) {
            rapidInsertCount++
        } else {
            rapidInsertCount = 1
        }
        lastInsertTime = now
    }

    /**
     * Insert a string at cursor (used for paste operations).
     * For large pastes (>100 chars), stores as pastedContent for marker display.
     */
    fun insertStringAtCursor(text: String) {
        if (text.isEmpty()) return
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        _inputBuffer.value = buf.substring(0, pos) + text + buf.substring(pos)
        _cursorPosition.value = pos + text.length

        // Large paste marker
        if (text.length > 200) {
            _pastedContent.value = text
        }
    }

    /** Clear paste marker (called before sending message) */
    fun clearPasteMarker() {
        _pastedContent.value = null
    }

    fun deleteAtCursor() {
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        if (pos > 0) {
            _inputBuffer.value = buf.substring(0, pos - 1) + buf.substring(pos)
            _cursorPosition.value = pos - 1
        }
    }

    fun chatScrollUp() {
        _scrollOffset.value = (_scrollOffset.value + 3).coerceAtLeast(0)
    }

    fun chatScrollDown() {
        _scrollOffset.value = (_scrollOffset.value - 3).coerceAtLeast(0)
    }

    fun chatScrollReset() {
        _scrollOffset.value = 0
    }

    fun addSystemMessage(content: String) {
        _messages.update { it + TuiChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "system",
            content = content
        )}
    }

    fun cycleMode() {
        val modes = listOf("CHAT", "PLAN", "AGENT")
        val current = modes.indexOf(_mode.value)
        val newMode = modes[(current + 1) % modes.size]

        val r = router ?: return
        val currentTaskId = taskId ?: run {
            // No active session — create one
            val newId = createNewTaskInDb(r)
            taskId = newId
            _mode.value = newMode
            _debugInfo.update { it.copy(sessionId = newId, mode = newMode) }
            addSystemMessage("Mode switched to $newMode (new session)")
            return
        }

        // Update existing task mode in DB (preserves conversation, like the plugin does)
        try {
            val coreMode = CoreTaskMode.valueOf(newMode)
            r.updateTask(currentTaskId, UpdateTaskRequest(mode = coreMode))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update task mode, falling back to new session" }
            // Fallback: create new session if update fails (e.g. task not found)
            val newId = createNewTaskInDb(r)
            taskId = newId
            _messages.value = emptyList()
            _subtasks.value = emptyList()
            _steps.value = emptyList()
            _activePlan.value = null
            _pendingPlanApproval.value = null
            _debugInfo.update { it.copy(sessionId = newId, mode = newMode) }
        }

        _mode.value = newMode

        // Clear plan/step state when switching to CHAT (no tools in chat mode)
        if (newMode == "CHAT") {
            _subtasks.value = emptyList()
            _activePlan.value = null
            _pendingPlanApproval.value = null
        }

        _debugInfo.update { it.copy(mode = newMode) }
        addSystemMessage("Mode switched to $newMode")
    }

    fun toggleThinking() {
        _thinkingEnabled.update { !it }
        // Persist to config — ConfigKeys.UI_THINKING_ENABLED uses key "ui.thinking_enabled"
        try {
            router?.configRouter?.updateConfig("ui", "app", null, mapOf("thinking_enabled" to _thinkingEnabled.value.toString()))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist thinking state" }
        }
    }

    fun toggleNoEgress() {
        _noEgressEnabled.update { !it }
        // Persist to config — ConfigKeys.UI_NO_EGRESS_ENABLED uses key "ui.no_egress_enabled"
        try {
            router?.configRouter?.updateConfig("ui", "app", null, mapOf("no_egress_enabled" to _noEgressEnabled.value.toString()))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist no-egress state" }
        }
    }

    fun toggleExecutionMode() {
        _executionMode.update { if (it == "AUTO") "INTERACTIVE" else "AUTO" }
    }

    private var currentJob: kotlinx.coroutines.Job? = null

    fun cancelCurrentOperation() {
        // Set global cancellation flag FIRST — checked by LLM adapters, executors, tools
        GlobalMetrics.requestCancellation()
        currentJob?.cancel()
        currentJob = null
        _isStreaming.value = false
        _executionStatus.value = "Cancelled"
        workflowListener.reset()
        addSystemMessage("Operation cancelled.")
        scope.launch {
            delay(2000)
            if (_executionStatus.value == "Cancelled") _executionStatus.value = "Idle"
        }
    }

    // --- Slash commands (prompt templates) ---

    fun getSlashCommands(): List<SlashCommand> {
        return SlashCommand.BUILTINS
    }

    /**
     * Process slash commands inline (same as plugin's PromptInputPanel.processSlashCommand).
     * Replaces each "/command" with its template, supporting multiple commands anywhere in text.
     * Only matches /command after whitespace or at start (not in URLs like https://example.com).
     */
    fun processSlashCommands(text: String): String {
        val commandRegex = Regex("""(?<=\s|^)/([\w-]+)""")
        val matches = commandRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val commands = getSlashCommands()
        var result = text
        var offset = 0

        for (match in matches) {
            val commandName = match.groupValues[1]
            val cmd = commands.find { it.name.equals(commandName, ignoreCase = true) } ?: continue

            var template = cmd.template

            // Substitute template variables
            template = template
                .replace("{{MODEL_ID}}", _model.value ?: "default")
                .replace("{{PROJECT_NAME}}", projectPath.fileName?.toString() ?: "project")
                .replace("{{MODE}}", _mode.value)
                .replace("{{EXECUTION_MODE}}", _executionMode.value)

            val originalStart = match.range.first + offset
            val originalEnd = match.range.last + 1 + offset

            result = result.substring(0, originalStart) + template + result.substring(originalEnd)
            offset += template.length - match.value.length
        }

        return result
    }

    // --- Model selector ---

    fun showModelSelector() {
        scope.launch {
            val models = getAvailableModels()
            if (models.isNotEmpty()) {
                _modelSelectorCandidates.value = models
                _modelSelectorIndex.value = models.indexOf(_model.value).coerceAtLeast(0)
                _modelSelectorVisible.value = true
            } else {
                addSystemMessage("No models available. Configure providers in Settings (F8).")
            }
        }
    }

    fun isModelSelectorVisible(): Boolean = _modelSelectorVisible.value

    fun modelSelectorNext() {
        val max = _modelSelectorCandidates.value.size
        if (max > 0) _modelSelectorIndex.value = (_modelSelectorIndex.value + 1) % max
    }

    fun modelSelectorPrev() {
        val max = _modelSelectorCandidates.value.size
        if (max > 0) _modelSelectorIndex.value = (_modelSelectorIndex.value - 1 + max) % max
    }

    fun modelSelectorAccept() {
        val candidates = _modelSelectorCandidates.value
        val idx = _modelSelectorIndex.value
        if (idx in candidates.indices) {
            setModel(candidates[idx])
            addSystemMessage("Model set to: ${candidates[idx]}")
        }
        dismissModelSelector()
    }

    fun dismissModelSelector() {
        _modelSelectorVisible.value = false
    }

    fun getModelSelectorState(): Triple<List<String>, Int, Boolean> {
        return Triple(_modelSelectorCandidates.value, _modelSelectorIndex.value, _modelSelectorVisible.value)
    }

    private suspend fun getAvailableModels(): List<String> {
        val r = router ?: return getStaticModelList()
        return try {
            val dynamic = r.configRouter.getModelsWithVisibility()
                .filter { it.showInDropdown }
                .map { "${it.provider}/${it.id}" }
            if (dynamic.isNotEmpty()) dynamic else getStaticModelList()
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get available models, using static list" }
            getStaticModelList()
        }
    }

    /**
     * Fallback model list from static ModelDefinitions when dynamic fetch fails
     * (e.g. no providers configured, endpoints unreachable).
     */
    private fun getStaticModelList(): List<String> {
        val providers = listOf("openai", "anthropic", "openrouter", "gemini", "ollama", "lmstudio", "custom_openai", "zai")
        val result = mutableListOf<String>()
        for (provider in providers) {
            val definitions = pl.jclab.refio.core.llm.ModelDefinitions.getProviderDefinitions(provider)
            for ((modelId, _) in definitions) {
                if (pl.jclab.refio.core.llm.SupportedModels.isSupported(provider, modelId)) {
                    result.add("$provider/$modelId")
                }
            }
        }
        // Also allow manually typing — add current model if not in list
        val current = _model.value
        if (current != null && current !in result) {
            result.add(0, current)
        }
        return result
    }

    // --- New session ---

    fun showNewSessionDialog() {
        val r = router ?: return
        // Create a new session in DB
        val newId = createNewTaskInDb(r)
        taskId = newId
        _messages.value = emptyList()
        _steps.value = emptyList()
        _subtasks.value = emptyList()
        _activePlan.value = null
        _pendingPlanApproval.value = null
        _contextSections.value = emptyList()
        _inputBuffer.value = ""
        _isStreaming.value = false
        _executionStatus.value = "Idle"
        _debugInfo.update { it.copy(sessionId = newId) }
        addSystemMessage("New session created (${_mode.value}). ID: ${newId.take(8)}...")
    }

    fun switchSession(sessionId: String) {
        val r = router ?: return
        scope.launch {
            try {
                val task = r.getTask(sessionId) ?: run {
                    addSystemMessage("Session not found: $sessionId")
                    return@launch
                }
                taskId = task.id
                _mode.value = task.mode
                persistLastSessionId(task.id)

                // Load messages and subtasks from DB
                loadMessagesFromDb(r, task.id)
                loadSubtasksFromDb(r, task.id)

                _debugInfo.update { it.copy(sessionId = task.id, mode = task.mode) }
                _screen.value = TuiScreen.MAIN
                addSystemMessage("Switched to session: ${task.name} (${task.mode})")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to switch session: $sessionId" }
                addSystemMessage("Failed to switch session: ${e.message}")
            }
        }
    }

    // --- Conversation actions ---

    fun continueConversation() {
        sendMessage("Continue from where you left off")
    }

    fun summarizeConversation() {
        if (_messages.value.isEmpty()) return
        addSystemMessage("Summarizing conversation...")
        // Trigger summarization via chat router if available
        scope.launch {
            try {
                val r = router ?: return@launch
                val tid = taskId ?: return@launch
                r.chatRouter.summarizeConversation(tid)
                addSystemMessage("Conversation summarized.")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to summarize conversation" }
                addSystemMessage("Failed to summarize: ${e.message}")
            }
        }
    }

    fun showCurrentPrompt() {
        scope.launch {
            val r = router ?: return@launch
            try {
                val prompts = r.promptsRouter.getSystemPrompts()
                val text = buildString {
                    appendLine("=== System Prompts (${prompts.count} total) ===")
                    for (p in prompts.prompts.take(5)) {
                        appendLine()
                        appendLine("--- ${p.type} ${if (p.isEnabled) "✓" else "✗"} ---")
                        val content = p.content ?: "(empty)"
                        appendLine(content.take(500))
                        if (content.length > 500) appendLine("... (${content.length} chars)")
                    }
                }
                addSystemMessage(text)
            } catch (e: Exception) {
                addSystemMessage("Cannot load prompts: ${e.message}")
            }
        }
    }

    fun copyAllConversation() {
        val messages = _messages.value
        if (messages.isEmpty()) {
            addSystemMessage("No messages to copy")
            return
        }
        val text = messages.joinToString("\n\n") { msg ->
            "[${msg.role.uppercase()}] ${msg.content}"
        }
        copyToClipboard(text)
        addSystemMessage("Copied ${messages.size} messages to clipboard")
    }

    fun copyLastMessageToClipboard() {
        val msgs = _messages.value
        val idx = _selectedMessageIndex.value
        val msg = if (idx in msgs.indices) {
            msgs[idx]
        } else {
            msgs.lastOrNull { it.role == "assistant" } ?: msgs.lastOrNull()
        }
        if (msg == null) return
        copyToClipboard(msg.content)
        if (idx in msgs.indices) {
            addSystemMessage("Copied message ${idx + 1}/${msgs.size} to clipboard.")
        }
    }

    fun messageSelectionUp() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val current = _selectedMessageIndex.value
        _selectedMessageIndex.value = if (current <= 0) msgs.lastIndex else current - 1
    }

    fun messageSelectionDown() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val current = _selectedMessageIndex.value
        _selectedMessageIndex.value = if (current < 0 || current >= msgs.lastIndex) 0 else current + 1
    }

    fun clearMessageSelection() {
        _selectedMessageIndex.value = -1
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            addSystemMessage("Copied to clipboard.")
        } catch (_: Exception) {
            try {
                val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                print("\u001b]52;c;$encoded\u0007")
                addSystemMessage("Copied to clipboard (OSC 52).")
            } catch (e: Exception) {
                addSystemMessage("Clipboard not available: ${e.message}")
            }
        }
    }

    // --- Subtask / Plan management ---

    fun updateExecutionStatus(status: String) {
        _executionStatus.value = status
    }

    fun setSubtasks(subtasks: List<TuiSubtask>) {
        _subtasks.value = subtasks
    }

    fun updateSubtaskStatus(subtaskId: String, status: String, error: String? = null) {
        _subtasks.update { list ->
            list.map {
                if (it.id == subtaskId) it.copy(status = status, error = error) else it
            }
        }
    }

    fun setPendingPlanApproval(plan: TuiPlan) {
        _pendingPlanApproval.value = TuiPlanApproval(
            taskId = plan.taskId,
            plan = plan
        )
    }

    fun approvePlan() {
        val approval = _pendingPlanApproval.value ?: return
        _activePlan.value = approval.plan
        _subtasks.value = approval.plan.steps
        _pendingPlanApproval.value = null
    }

    fun rejectPlan() {
        _pendingPlanApproval.value = null
        addSystemMessage("Plan rejected.")
    }

    fun approveSubtask(subtaskId: String) {
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.updateSubtask(tid, subtaskId, UpdateSubtaskRequest(approvalStatus = ApprovalStatus.APPROVED))
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to approve subtask: $subtaskId" }
                updateSubtaskStatus(subtaskId, "APPROVED") // fallback to local
            }
        }
    }

    fun skipSubtask(subtaskId: String) {
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.updateSubtask(tid, subtaskId, UpdateSubtaskRequest(approvalStatus = ApprovalStatus.SKIPPED))
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to skip subtask: $subtaskId" }
                updateSubtaskStatus(subtaskId, "SKIPPED") // fallback to local
            }
        }
    }

    fun deleteSubtask(subtaskId: String) {
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.deleteSubtask(tid, subtaskId)
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete subtask: $subtaskId" }
                _subtasks.update { it.filter { s -> s.id != subtaskId } } // fallback
            }
        }
    }

    fun moveStepUp(index: Int) {
        if (index <= 0) return
        val list = _subtasks.value
        val current = list.getOrNull(index) ?: return
        val above = list.getOrNull(index - 1) ?: return
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.swapSubtaskOrder(tid, current.id, above.id)
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to swap subtask order" }
                // Fallback to local swap
                _subtasks.update { l ->
                    l.toMutableList().apply {
                        val item = removeAt(index)
                        add(index - 1, item)
                    }
                }
            }
        }
        _selectedStepIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun moveStepDown(index: Int) {
        val list = _subtasks.value
        if (index >= list.size - 1) return
        val current = list.getOrNull(index) ?: return
        val below = list.getOrNull(index + 1) ?: return
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.swapSubtaskOrder(tid, current.id, below.id)
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to swap subtask order" }
                _subtasks.update { l ->
                    l.toMutableList().apply {
                        val item = removeAt(index)
                        add(index + 1, item)
                    }
                }
            }
        }
        _selectedStepIndex.update { (it + 1).coerceAtMost(list.size - 1) }
    }

    fun cancelAllPending() {
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                r.deletePendingSubtasks(tid)
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cancel all pending" }
                _subtasks.update { list ->
                    list.map {
                        if (it.status in listOf("NEW", "PENDING", "APPROVED")) it.copy(status = "SKIPPED") else it
                    }
                }
            }
        }
    }

    fun executeStep(index: Int) {
        val subtask = _subtasks.value.getOrNull(index) ?: return
        if (subtask.status !in listOf("NEW", "PENDING", "APPROVED")) {
            addSystemMessage("Step '${subtask.name}' is ${subtask.status}, cannot execute")
            return
        }
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            try {
                _executionStatus.value = "Executing: ${subtask.name}"
                _subtasks.update { list ->
                    list.map { if (it.id == subtask.id) it.copy(status = "RUNNING") else it }
                }
                val result = r.executeSubtaskStep(tid, subtask.id)
                loadSubtasksFromDb(r, tid)
                loadMessagesFromDb(r, tid)
                refreshApiLogs(r)
                _executionStatus.value = "Idle"
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute step: ${subtask.id}" }
                _subtasks.update { list ->
                    list.map { if (it.id == subtask.id) it.copy(status = "FAILED", error = e.message) else it }
                }
                _executionStatus.value = "Error"
                addSystemMessage("Step execution failed: ${e.message}")
            }
        }
    }

    fun replanSteps() {
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            val taskMode = try { CoreTaskMode.valueOf(_mode.value) } catch (_: Exception) { CoreTaskMode.AGENT }
            if (taskMode == CoreTaskMode.CHAT) {
                addSystemMessage("Re-plan is only available in PLAN or AGENT mode")
                return@launch
            }
            try {
                _executionStatus.value = "Re-planning..."
                // Delete existing pending subtasks and run a new turn to re-plan
                r.deletePendingSubtasks(tid)
                val turnRequest = TurnRequest(
                    taskId = tid,
                    userInput = "Re-plan the remaining steps based on current progress",
                    mode = taskMode,
                    executionMode = CoreExecutionMode.INTERACTIVE,
                    model = _model.value
                )
                val result = r.runTurn(turnRequest, streamCallback = { chunk ->
                    workflowListener.onStreamChunk(chunk.delta)
                })
                workflowListener.onStreamComplete(result.response)
                loadSubtasksFromDb(r, tid)
                loadMessagesFromDb(r, tid)
                _executionStatus.value = "Idle"
            } catch (e: Exception) {
                logger.error(e) { "Re-plan failed" }
                _executionStatus.value = "Error"
                addSystemMessage("Re-plan failed: ${e.message}")
            }
        }
    }

    fun addStep(description: String) {
        if (description.isBlank()) {
            addSystemMessage("Step description required. Usage: press '+' then type description.")
            return
        }
        scope.launch {
            val r = router ?: return@launch
            val tid = taskId ?: return@launch
            val taskMode = try { CoreTaskMode.valueOf(_mode.value) } catch (_: Exception) { CoreTaskMode.AGENT }
            if (taskMode == CoreTaskMode.CHAT) {
                addSystemMessage("Add step is only available in PLAN or AGENT mode")
                return@launch
            }
            try {
                _executionStatus.value = "Adding step..."
                val turnRequest = TurnRequest(
                    taskId = tid,
                    userInput = "Add one new step to the plan: $description. Only add this one step, do not modify existing steps.",
                    mode = taskMode,
                    executionMode = CoreExecutionMode.INTERACTIVE,
                    model = _model.value
                )
                val result = r.runTurn(turnRequest, streamCallback = { chunk ->
                    workflowListener.onStreamChunk(chunk.delta)
                })
                workflowListener.onStreamComplete(result.response)
                loadSubtasksFromDb(r, tid)
                _executionStatus.value = "Idle"
            } catch (e: Exception) {
                _executionStatus.value = "Error"
                addSystemMessage("Failed to add step: ${e.message}")
            }
        }
    }

    fun togglePause() {
        val wasPaused = _isPaused.value
        _isPaused.update { !it }
        // On resume, check for pending subtasks needing approval
        if (wasPaused) {
            val subtasks = _subtasks.value
            val nextPending = subtasks.indexOfFirst { it.status in listOf("NEW", "PENDING") }
            if (nextPending >= 0) {
                setActiveTab(TuiTab.STEPS)
                selectStep(nextPending)
                addSystemMessage("Resumed. Next step awaiting approval: ${subtasks[nextPending].name}")
            } else {
                addSystemMessage("Resumed. No pending steps.")
            }
        }
    }

    fun selectStep(index: Int) {
        _selectedStepIndex.value = index.coerceIn(0, (_subtasks.value.size - 1).coerceAtLeast(0))
    }

    fun selectStepUp() {
        _selectedStepIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun selectStepDown() {
        _selectedStepIndex.update { (it + 1).coerceAtMost((_subtasks.value.size - 1).coerceAtLeast(0)) }
    }

    fun approve(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = true,
                    reason = null
                )
            )
        }
    }

    fun reject(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = false,
                    reason = "Rejected by user"
                )
            )
        }
    }

    /**
     * Validate @file: and @folder: references in user input.
     * Returns warning message if invalid, null if OK.
     */
    private fun validateContextReferences(input: String): String? {
        val allRefRegex = Regex("""@\w+[:\S]*""")
        val allRefs = allRefRegex.findAll(input).toList()

        // P12: Context ref count limit
        if (allRefs.size > 50) {
            return "Too many context references (${allRefs.size}/50). Remove some to continue."
        }

        val fileRefRegex = Regex("""@(file|folder):(\S+)""")
        val root = projectPath.toAbsolutePath().toString()
        for (match in fileRefRegex.findAll(input)) {
            val fullRef = match.value
            val result = TuiContextValidator.validate(fullRef, root)
            if (!result.isValid) return result.warning
            if (result.warning != null) {
                addSystemMessage("⚠ ${result.warning}")
            }
        }
        return null
    }

    fun sendMessage(input: String) {
        if (input.isBlank() || _isStreaming.value) return
        _scrollOffset.value = 0 // auto-scroll to bottom on new message

        // If orchestrator is waiting for a question response, route as answer
        if (_pendingQuestionId.value != null) {
            _inputBuffer.value = ""
            answerQuestion(input)
            return
        }

        // Process slash commands inline (like the plugin does)
        val processedInput = processSlashCommands(input)

        // Validate context references before sending
        val contextWarning = validateContextReferences(processedInput)
        if (contextWarning != null) {
            addSystemMessage("⚠ $contextWarning")
            return
        }

        val userMsg = TuiChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "user",
            content = processedInput
        )
        _messages.update { it + userMsg }
        _inputBuffer.value = ""

        currentJob = scope.launch {
            val r = router ?: run {
                _error.value = "Core not initialized"
                return@launch
            }

            try {
                workflowListener.reset()
                GlobalMetrics.resetCancellation()
                _isStreaming.value = true

                // Ensure we have a valid DB-persisted task
                val tid = taskId ?: createNewTaskInDb(r).also { taskId = it }

                val taskMode = try {
                    CoreTaskMode.valueOf(_mode.value)
                } catch (_: Exception) {
                    CoreTaskMode.CHAT
                }

                val execMode = try {
                    CoreExecutionMode.valueOf(_executionMode.value)
                } catch (_: Exception) {
                    CoreExecutionMode.AUTO
                }

                // Split "provider/model" string into separate provider and model
                // e.g. "ollama/qwen2.5-coder:7b" → provider="ollama", model="qwen2.5-coder:7b"
                val (selectedProvider, selectedModel) = splitProviderModel(_model.value)

                // Route through proper router API based on mode
                when (taskMode) {
                    CoreTaskMode.CHAT -> {
                        _executionStatus.value = "Chatting..."
                        val chatRequest = ChatRequest(
                            taskId = tid,
                            mode = taskMode,
                            input = processedInput,
                            params = LLMParams(
                                model = selectedModel,
                                provider = selectedProvider
                            )
                        )
                        val response = r.chat(chatRequest, stream = true) { chunk ->
                            workflowListener.onStreamChunk(chunk.delta)
                        }
                        // Finalize stream with the full response
                        workflowListener.onStreamComplete(response.output)
                        // Update metrics
                        response.costs?.let { costs ->
                            _totalTokens.update { it + costs.tokensIn + costs.tokensOut }
                            _totalCost.update { it + costs.usdEst }
                        }
                    }

                    CoreTaskMode.PLAN, CoreTaskMode.AGENT -> {
                        _executionStatus.value = if (taskMode == CoreTaskMode.PLAN) "Planning..." else "Agent executing..."
                        val turnRequest = TurnRequest(
                            taskId = tid,
                            userInput = processedInput,
                            mode = taskMode,
                            executionMode = execMode,
                            model = selectedModel,
                            provider = selectedProvider
                        )
                        val result: TurnResult = r.runTurn(turnRequest, streamCallback = { chunk ->
                            workflowListener.onStreamChunk(chunk.delta)
                        })
                        // Finalize stream
                        workflowListener.onStreamComplete(result.response)
                        // Update metrics
                        _totalTokens.update { it + result.tokensIn + result.tokensOut }
                        _totalCost.update { it + result.cost }
                        // Reload subtasks from DB — plan/agent may have created new ones
                        loadSubtasksFromDb(r, tid)
                    }
                }

                _isStreaming.value = false
                _executionStatus.value = "Idle"

                // Reload messages from DB (authoritative source)
                loadMessagesFromDb(r, tid)

                _debugInfo.update {
                    it.copy(
                        messageCount = _messages.value.size,
                        tokensIn = _totalTokens.value,
                        costUsd = _totalCost.value
                    )
                }

                // Refresh API logs from database
                refreshApiLogs(r)
            } catch (e: Exception) {
                logger.error(e) { "Workflow error" }
                _isStreaming.value = false
                _executionStatus.value = "Error"
                _messages.update { messages ->
                    messages + TuiChatMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        role = "system",
                        content = "Error: ${e.message}",
                        messageType = TuiMessageType.AGENT_FAILED
                    )
                }
            }
        }
    }

    // --- Settings (config read/write via ConfigRouter) ---

    fun getConfigSection(section: String): Map<String, String> {
        val r = router ?: return emptyMap()
        return try {
            r.configRouter.getConfig(section, "app").settings.mapValues { it.value.toString() }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get config section: $section" }
            emptyMap()
        }
    }

    fun updateConfig(section: String, key: String, value: String) {
        val r = router ?: return
        try {
            r.configRouter.updateConfig(section, "app", null, mapOf(key to value))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update config: $section.$key" }
        }
    }

    fun resetAllSettings() {
        val r = router ?: return
        try {
            r.configRouter.resetAllSettingsToDefaults()
            TuiSettingsScreen.invalidateCache()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to reset settings" }
        }
    }

    fun refreshSettingsModels() {
        val r = router ?: return
        scope.launch {
            try {
                addSystemMessage("Refreshing models from providers...")
                fetchAndCacheModels(r)
                addSystemMessage("Loaded ${TuiSettingsScreen.getCachedModelCount()} models from providers.")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh models" }
                addSystemMessage("Failed to refresh models: ${e.message}")
            }
        }
    }

    /**
     * Pre-load models on startup (background, no chat messages).
     * Like plugin's ModelsSettingsPanel.loadModels() called in init{}.
     */
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
        val models = r.getModelsWithVisibility()
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

    // --- Settings interactive editing ---

    fun settingsFieldUp() {
        _settingsSelectedField.update { (it - 1).coerceAtLeast(0) }
    }

    fun settingsFieldDown() {
        _settingsSelectedField.update { it + 1 }
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
        // Parse section.key from field identifier
        val parts = field.split(".", limit = 2)
        if (parts.size == 2) {
            updateConfig(parts[0], parts[1], value)
        }
        _settingsEditingField.value = null
        _settingsEditBuffer.value = ""
        TuiSettingsScreen.invalidateCache()
    }

    fun settingsToggleBool(section: String, key: String, currentValue: Boolean) {
        updateConfig(section, key, (!currentValue).toString())
        TuiSettingsScreen.invalidateCache()
    }

    fun exportUserConfig() {
        scope.launch {
            try {
                val r = router ?: return@launch
                val configFile = java.io.File(System.getProperty("user.home"), ".refio/config.yaml")
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
                addSystemMessage("Config exported to: ${configFile.absolutePath}")
            } catch (e: Exception) {
                addSystemMessage("Export failed: ${e.message}")
            }
        }
    }

    fun exportProjectConfig() {
        scope.launch {
            try {
                val r = router ?: return@launch
                val configFile = java.io.File(projectPath.toFile(), ".refio/config.yaml")
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
                addSystemMessage("Project config exported to: ${configFile.absolutePath}")
            } catch (e: Exception) {
                addSystemMessage("Export failed: ${e.message}")
            }
        }
    }

    fun reloadConfig() {
        // Reload by invalidating the settings cache — next getConfigSection call will re-read
        TuiSettingsScreen.invalidateCache()
        addSystemMessage("Settings cache cleared. Config will be re-read on next access.")
    }

    fun testProviderConnection(provider: String) {
        scope.launch {
            val r = router ?: return@launch
            try {
                addSystemMessage("Testing connection to $provider...")
                val config = getConfigSection("providers")
                val result = r.testProviderConnection(provider, config)
                if (result.success) {
                    addSystemMessage("✓ $provider: Connected (${result.latencyMs}ms)")
                } else {
                    addSystemMessage("✗ $provider: ${result.message}")
                }
            } catch (e: Exception) {
                addSystemMessage("✗ $provider test failed: ${e.message}")
            }
        }
    }

    // --- Autocomplete (@context, !subagent, /command) ---

    private val allContextPrefixes = listOf(
        "@file:", "@folder:", "@selection", "@current", "@open_files",
        "@recent", "@problems", "@terminal", "@git_diff", "@git_commit:",
        "@grep:", "@codebase:", "@docs:", "@url:"
    )

    /** Which trigger char started the autocomplete: '@', '!', '/' */
    private var autocompleteTrigger: Char = '@'

    /** Cache for subagent names (refreshed on trigger) */
    private var cachedSubagentNames: List<String> = emptyList()
    private var subagentCacheTime: Long = 0

    /** Built-in slash commands for autocomplete */
    private val builtinCommandNames: List<String> by lazy {
        try {
            pl.jclab.refio.api.models.SlashCommand.BUILTINS.map { "/${it.name}" }
        } catch (_: Exception) {
            listOf("/explain", "/fix", "/test", "/refactor", "/optimize", "/simplify",
                "/document", "/security-review", "/translate", "/implement")
        }
    }

    fun triggerAutocomplete() {
        autocompleteTrigger = '@'
        // Include agent names when multi-agent session is active
        val agentNames = _agents.value.map { "@${it.name}" }
        _autocompleteCandidates.value = allContextPrefixes + agentNames
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    fun triggerSubagentAutocomplete() {
        autocompleteTrigger = '!'
        refreshSubagentCache()
        _autocompleteCandidates.value = cachedSubagentNames.ifEmpty { listOf("(no subagents available)") }
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    fun triggerCommandAutocomplete() {
        autocompleteTrigger = '/'
        _autocompleteCandidates.value = builtinCommandNames
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    private fun refreshSubagentCache() {
        val now = System.currentTimeMillis()
        if (now - subagentCacheTime < 5000 && cachedSubagentNames.isNotEmpty()) return
        try {
            val r = router ?: return
            val subagents = r.subagentRouter?.listSubagents() ?: emptyList()
            cachedSubagentNames = subagents.map { "!${it.name}" }
            subagentCacheTime = now
        } catch (e: Exception) {
            logger.debug(e) { "Failed to list subagents" }
        }
    }

    /**
     * Provide file/folder listing for @file: or @folder: autocomplete.
     */
    private fun listFilesForAutocomplete(prefix: String, isFolder: Boolean): List<String> {
        return try {
            val root = projectPath.toFile()
            val partial = prefix.substringAfter(if (isFolder) "@folder:" else "@file:")
            val searchDir = if (partial.contains('/') || partial.contains('\\')) {
                val parentPath = partial.substringBeforeLast('/')
                java.io.File(root, parentPath)
            } else {
                root
            }
            if (!searchDir.isDirectory) return emptyList()

            val parentPrefix = if (partial.contains('/')) partial.substringBeforeLast('/') + "/" else ""
            val nameFilter = if (partial.contains('/')) partial.substringAfterLast('/') else partial

            searchDir.listFiles()
                ?.filter { file ->
                    if (isFolder) file.isDirectory else true
                }
                ?.filter { it.name.startsWith(nameFilter, ignoreCase = true) }
                ?.filter { !it.name.startsWith(".") } // hide hidden files
                ?.sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name })
                ?.take(15)
                ?.map { file ->
                    val tag = if (isFolder) "@folder:" else "@file:"
                    val suffix = if (file.isDirectory) "/" else ""
                    "$tag$parentPrefix${file.name}$suffix"
                } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updateAutocompleteFilter() {
        val input = _inputBuffer.value
        when (autocompleteTrigger) {
            '@' -> {
                val atIdx = input.lastIndexOf('@')
                if (atIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(atIdx).lowercase()

                // If user is typing @file:path or @folder:path, show file listing
                if (filter.startsWith("@file:") && filter.length > 6) {
                    val files = listFilesForAutocomplete(filter, isFolder = false)
                    if (files.isNotEmpty()) {
                        _autocompleteCandidates.value = files
                        _autocompleteSelectedIndex.value = 0
                        return
                    }
                }
                if (filter.startsWith("@folder:") && filter.length > 8) {
                    val folders = listFilesForAutocomplete(filter, isFolder = true)
                    if (folders.isNotEmpty()) {
                        _autocompleteCandidates.value = folders
                        _autocompleteSelectedIndex.value = 0
                        return
                    }
                }

                val filtered = allContextPrefixes.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
            '!' -> {
                val bangIdx = input.lastIndexOf('!')
                if (bangIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(bangIdx).lowercase()
                val filtered = cachedSubagentNames.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
            '/' -> {
                val slashIdx = input.lastIndexOf('/')
                if (slashIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(slashIdx).lowercase()
                val filtered = builtinCommandNames.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
        }
    }

    fun autocompleteNext() {
        val max = _autocompleteCandidates.value.size
        if (max > 0) _autocompleteSelectedIndex.value = (_autocompleteSelectedIndex.value + 1) % max
    }

    fun autocompletePrev() {
        val max = _autocompleteCandidates.value.size
        if (max > 0) _autocompleteSelectedIndex.value = (_autocompleteSelectedIndex.value - 1 + max) % max
    }

    fun autocompleteAccept() {
        val candidates = _autocompleteCandidates.value
        val idx = _autocompleteSelectedIndex.value
        if (idx in candidates.indices) {
            val selected = candidates[idx]
            if (selected.startsWith("(")) return // placeholder like "(no subagents available)"
            val input = _inputBuffer.value
            val triggerIdx = when (autocompleteTrigger) {
                '@' -> input.lastIndexOf('@')
                '!' -> input.lastIndexOf('!')
                '/' -> input.lastIndexOf('/')
                else -> -1
            }
            if (triggerIdx >= 0) {
                _inputBuffer.value = input.substring(0, triggerIdx) + selected + " "
            }
        }
        autocompleteDismiss()
    }

    fun autocompleteDismiss() {
        _autocompleteVisible.value = false
        _autocompleteCandidates.value = emptyList()
        _autocompleteSelectedIndex.value = 0
    }

    // --- Message resend ---

    fun rewindToMessage(messageIndex: Int) {
        val messages = _messages.value
        if (messageIndex < 0 || messageIndex >= messages.size) return
        val targetMsg = messages[messageIndex]
        if (targetMsg.role != "user") {
            addSystemMessage("Can only rewind to user messages")
            return
        }
        // Keep messages up to (not including) the target, then resend it
        _messages.value = messages.take(messageIndex)
        sendMessage(targetMsg.content)
    }

    fun resendLastMessage() {
        val lastUserMsg = _messages.value.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            sendMessage(lastUserMsg.content)
        }
    }

    // --- Rate conversation ---

    fun rateConversation(rating: Int) {
        val r = router ?: return
        val tid = taskId ?: run {
            addSystemMessage("No active session to rate.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                r.updateTask(tid, pl.jclab.refio.core.api.UpdateTaskRequest(rate = rating))
                val label = if (rating > 0) "👍 positive" else "👎 negative"
                addSystemMessage("Conversation rated: $label")
            } catch (e: Exception) {
                addSystemMessage("Failed to rate: ${e.message}")
            }
        }
    }

    // --- Edit message (rewind + pre-fill input buffer) ---

    fun editMessage(messageIndex: Int?) {
        val messages = _messages.value
        if (messages.isEmpty()) {
            addSystemMessage("No messages to edit.")
            return
        }
        // Find the target user message
        val idx = if (messageIndex != null) {
            (messageIndex - 1).coerceIn(0, messages.lastIndex) // 1-based to 0-based
        } else {
            // Find last user message
            messages.indexOfLast { it.role == "user" }
        }
        if (idx < 0) {
            addSystemMessage("No user message found to edit.")
            return
        }
        val targetMsg = messages[idx]
        if (targetMsg.role != "user") {
            addSystemMessage("Can only edit user messages. Message $messageIndex is a ${targetMsg.role} message.")
            return
        }
        // Truncate conversation to before this message and pre-fill input
        _messages.value = messages.take(idx)
        _inputBuffer.value = targetMsg.content
        addSystemMessage("Editing message ${idx + 1}. Modify and press Enter to resend.")
    }

    // --- Add file snippet as context ---

    fun addSnippetContext(filePath: String, startLine: Int?, endLine: Int?) {
        if (filePath.isBlank()) {
            addSystemMessage("Usage: /snippet <file> [startLine] [endLine]")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val resolved = projectPath.resolve(filePath).toRealPath()
                if (!java.nio.file.Files.exists(resolved)) {
                    addSystemMessage("File not found: $filePath")
                    return@launch
                }
                val allLines = java.nio.file.Files.readAllLines(resolved)
                val start = ((startLine ?: 1) - 1).coerceIn(0, allLines.size)
                val end = (endLine ?: allLines.size).coerceIn(start, allLines.size)
                val snippet = allLines.subList(start, end).joinToString("\n")

                if (snippet.length > 100_000) {
                    addSystemMessage("Snippet too large (${snippet.length} chars). Max 100K.")
                    return@launch
                }

                val lineInfo = if (startLine != null || endLine != null) ":${start + 1}-$end" else ""
                val currentInput = _inputBuffer.value
                val ref = "@file:${filePath}$lineInfo"
                _inputBuffer.value = if (currentInput.isBlank()) ref else "$currentInput $ref"
                addSystemMessage("Added snippet: $filePath lines ${start + 1}-$end (${end - start} lines)")
            } catch (e: Exception) {
                addSystemMessage("Failed to read snippet: ${e.message}")
            }
        }
    }

    // --- Open file in external editor ---

    fun openFileInEditor(filePath: String) {
        if (filePath.isBlank()) {
            addSystemMessage("Usage: /open <file-path>")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val resolved = projectPath.resolve(filePath).toRealPath()
                if (!java.nio.file.Files.exists(resolved)) {
                    addSystemMessage("File not found: $filePath")
                    return@launch
                }
                val editor = System.getenv("EDITOR") ?: System.getenv("VISUAL") ?: "vi"
                val pb = ProcessBuilder(editor, resolved.toString())
                pb.inheritIO()
                val proc = pb.start()
                proc.waitFor()
                addSystemMessage("Closed editor for: $filePath")
            } catch (e: Exception) {
                addSystemMessage("Failed to open editor: ${e.message}")
            }
        }
    }

    // --- MCP server management ---

    fun mcpAddServer(args: String) {
        // Syntax: /mcp-add stdio <name> <command> [args...]
        // Syntax: /mcp-add http <name> <url>
        val parts = args.split("\\s+".toRegex(), limit = 4)
        if (parts.size < 3) {
            addSystemMessage("""Usage:
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
                    id = name,
                    displayName = name,
                    type = MCPServerType.STDIO,
                    command = cmdParts.first(),
                    args = cmdParts.drop(1),
                    workingDirectory = projectPath.toString(),
                    enabled = true
                )
            }
            "http", "sse" -> {
                MCPServerConfig(
                    id = name,
                    displayName = name,
                    type = MCPServerType.HTTP_SSE,
                    url = rest.trim(),
                    enabled = true
                )
            }
            else -> {
                addSystemMessage("Unknown type '$type'. Use 'stdio' or 'http'.")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.addOrUpdateServer(projectId, config)
                addSystemMessage("MCP server '$name' added (${config.type}). Connecting...")
            } catch (e: Exception) {
                addSystemMessage("Failed to add MCP server: ${e.message}")
            }
        }
    }

    fun mcpEditServer(args: String) {
        // Syntax: /mcp-edit <id> <field> <value>
        // Fields: enabled, url, command, auth, env
        val parts = args.split("\\s+".toRegex(), limit = 3)
        if (parts.size < 3) {
            addSystemMessage("""Usage: /mcp-edit <id> <field> <value>
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
            addSystemMessage("MCP server '$serverId' not found. Use /mcp-list to see available servers.")
            return
        }

        val updated = when (field) {
            "enabled" -> existing.copy(enabled = value.lowercase() in listOf("true", "1", "yes"))
            "url" -> existing.copy(url = value)
            "command" -> existing.copy(command = value)
            "auth" -> existing.copy(auth = MCPAuthConfig(type = MCPAuthType.BEARER, apiKey = value))
            "env" -> {
                val eqIdx = value.indexOf('=')
                if (eqIdx < 0) {
                    addSystemMessage("Env format: KEY=VALUE")
                    return
                }
                val envVar = MCPEnvVariable(value.substring(0, eqIdx), value.substring(eqIdx + 1))
                existing.copy(env = existing.env.filter { it.name != envVar.name } + envVar)
            }
            "name" -> existing.copy(displayName = value)
            else -> {
                addSystemMessage("Unknown field '$field'. Use: enabled, url, command, auth, env, name")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.addOrUpdateServer(projectId, updated)
                addSystemMessage("MCP server '$serverId' updated: $field = $value")
            } catch (e: Exception) {
                addSystemMessage("Failed to update MCP server: ${e.message}")
            }
        }
    }

    fun mcpRemoveServer(serverId: String) {
        if (serverId.isBlank()) {
            addSystemMessage("Usage: /mcp-remove <server-id>")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                MCPManager.removeServer(projectId, serverId)
                addSystemMessage("MCP server '$serverId' removed.")
            } catch (e: Exception) {
                addSystemMessage("Failed to remove MCP server: ${e.message}")
            }
        }
    }

    fun mcpListServers() {
        val servers = MCPManager.getAllServers(projectId)
        if (servers.isEmpty()) {
            addSystemMessage("No MCP servers configured. Use /mcp-add to add one.")
            return
        }
        val sb = StringBuilder("MCP Servers:\n")
        for (server in servers) {
            val status = MCPManager.getServerStatus(projectId, server.id)
            val enabled = if (server.enabled) "✓" else "○"
            val statusIcon = when (status.name) {
                "CONNECTED" -> "🟢"
                "CONNECTING" -> "⟳"
                "ERROR" -> "🔴"
                else -> "⚪"
            }
            sb.appendLine("  $enabled $statusIcon ${server.id} (${server.type}) — ${server.displayName ?: server.id}")
            server.command?.let { sb.appendLine("      cmd: $it ${server.args.joinToString(" ")}") }
            server.url?.let { sb.appendLine("      url: $it") }
        }
        addSystemMessage(sb.toString().trimEnd())
    }

    // --- Session history ---

    fun refreshSessions() {
        loadSessions()
        addSystemMessage("Sessions refreshed.")
    }

    fun loadSessions() {
        val r = router ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val tasks = r.listTasks().tasks
                _sessions.value = tasks.map { task ->
                    TuiSessionEntry(
                        id = task.id,
                        name = task.name,
                        mode = task.mode,
                        status = task.status,
                        tokensIn = task.tokensIn,
                        tokensOut = task.tokensOut,
                        costUsd = task.costUsd,
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                        pinned = task.pinned
                    )
                }.sortedByDescending { it.updatedAt }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load sessions" }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        val r = router ?: return
        try {
            r.deleteTask(sessionId)
            _sessions.update { it.filter { s -> s.id != sessionId } }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete session: $sessionId" }
        }
    }

    /**
     * Split a combined "provider/model" string into (provider, model).
     * e.g. "ollama/qwen2.5-coder:7b" → ("ollama", "qwen2.5-coder:7b")
     * If no "/" is present, infers provider via LLMClient.
     */
    private fun splitProviderModel(combined: String?): Pair<String?, String?> {
        if (combined.isNullOrBlank()) return Pair(null, null)
        val slashIdx = combined.indexOf('/')
        return if (slashIdx > 0) {
            val provider = combined.substring(0, slashIdx)
            val model = combined.substring(slashIdx + 1)
            Pair(provider, model)
        } else {
            // No provider prefix — try to infer
            Pair(null, combined)
        }
    }

    // --- Model selection ---

    fun setModel(model: String) {
        _model.value = model.ifBlank { null }
        resolveContextWindow(model)
    }

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

    // --- Conversation export ---

    fun exportConversation(path: String): Boolean {
        val messages = _messages.value
        if (messages.isEmpty()) return false

        return try {
            val sb = StringBuilder()
            sb.appendLine("# Refio Conversation Export")
            sb.appendLine("# Mode: ${_mode.value}, Model: ${_model.value ?: "default"}")
            sb.appendLine("# Messages: ${messages.size}")
            sb.appendLine()

            for (msg in messages) {
                val role = msg.agentName ?: msg.role.replaceFirstChar { it.uppercase() }
                val time = apiLogTimeFormatter.format(Instant.ofEpochMilli(msg.timestamp))
                sb.appendLine("## [$role] $time")
                sb.appendLine()
                sb.appendLine(msg.content)
                sb.appendLine()
            }

            java.io.File(path).writeText(sb.toString())
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to export conversation to: $path" }
            false
        }
    }

    // --- History navigation (interactive) ---

    fun selectHistoryUp() {
        val sessions = filteredSessions()
        if (sessions.isEmpty()) return
        _selectedHistoryIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun selectHistoryDown() {
        val sessions = filteredSessions()
        if (sessions.isEmpty()) return
        _selectedHistoryIndex.update { (it + 1).coerceAtMost(sessions.size - 1) }
    }

    fun loadSelectedSession() {
        val sessions = filteredSessions()
        val idx = _selectedHistoryIndex.value
        val session = sessions.getOrNull(idx) ?: return
        switchSession(session.id)
    }

    fun deleteSelectedSession() {
        val sessions = filteredSessions()
        val idx = _selectedHistoryIndex.value
        val session = sessions.getOrNull(idx) ?: return
        if (session.id == taskId) {
            addSystemMessage("Cannot delete active session.")
            return
        }
        deleteSession(session.id)
        _selectedHistoryIndex.update { it.coerceAtMost((sessions.size - 2).coerceAtLeast(0)) }
    }

    fun togglePinSession() {
        val sessions = filteredSessions()
        val idx = _selectedHistoryIndex.value
        val session = sessions.getOrNull(idx) ?: return
        val r = router ?: return
        scope.launch {
            try {
                r.updateTask(session.id, pl.jclab.refio.core.api.UpdateTaskRequest(pinned = !session.pinned))
                loadSessions()
            } catch (e: Exception) {
                logger.debug(e) { "Failed to toggle pin" }
            }
        }
    }

    fun setHistoryFilter(filter: String) {
        _historyFilter.value = filter
        _selectedHistoryIndex.value = 0
    }

    private fun filteredSessions(): List<TuiSessionEntry> {
        val filter = _historyFilter.value
        return if (filter == "*") _sessions.value
        else _sessions.value.filter { it.mode == filter }
    }

    // --- Agent trust (auto-approve) ---

    private val trustedAgents = mutableSetOf<String>()

    fun trustAgent(agentId: String) {
        trustedAgents.add(agentId)
        val agentName = _agents.value.find { it.id == agentId }?.name ?: agentId
        addSystemMessage("Trusted agent: $agentName (future approvals auto-approved)")
    }

    fun isAgentTrusted(agentId: String): Boolean = agentId in trustedAgents

    // --- Agent filter ---

    fun cycleAgentFilter() {
        val agents = _agents.value
        if (agents.isEmpty()) {
            _agentFilter.value = null
            return
        }
        val currentFilter = _agentFilter.value
        val agentNames = agents.map { it.name }
        val currentIdx = if (currentFilter == null) -1 else agentNames.indexOf(currentFilter)
        _agentFilter.value = if (currentIdx >= agentNames.size - 1) null
            else agentNames.getOrNull(currentIdx + 1)
        val label = _agentFilter.value ?: "All"
        addSystemMessage("Agent filter: $label")
    }

    // --- RAG operations (interactive) ---

    fun ragReindex() {
        ragJob = scope.launch {
            val r = router ?: return@launch
            try {
                _ragIndexingStatus.value = "Indexing..."
                _ragIndexingProgress.value = 0.0
                r.indexProjectForRag(onProgress = { progress ->
                    val pct = if (progress.totalFiles > 0) progress.processedFiles.toDouble() / progress.totalFiles else 0.0
                    _ragIndexingProgress.value = pct
                    _ragIndexingStatus.value = progress.message
                })
                _ragIndexingStatus.value = "Indexing complete"
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                logger.warn(e) { "RAG reindex failed" }
                _ragIndexingStatus.value = "Error: ${e.message}"
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    fun ragGenerateEmbeddings() {
        ragJob = scope.launch {
            val r = router ?: return@launch
            try {
                _ragIndexingStatus.value = "Generating embeddings..."
                _ragIndexingProgress.value = 0.0
                r.generateEmbeddings(onProgress = { progress ->
                    _ragIndexingProgress.value = progress.progressPercent / 100.0
                    _ragIndexingStatus.value = progress.statusMessage
                })
                _ragIndexingStatus.value = "Embeddings complete"
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                logger.warn(e) { "Embedding generation failed" }
                _ragIndexingStatus.value = "Error: ${e.message}"
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    fun ragSearch(query: String) {
        _ragSearchQuery.value = query
        scope.launch {
            val r = router ?: return@launch
            try {
                val results = r.searchRag(query, topK = 5)
                // Populate RAG tab state
                _ragSearchResults.value = results.map { result ->
                    val score = String.format("%.2f", result.similarity)
                    val lines = if (result.startLine != null) ":${result.startLine}-${result.endLine}" else ""
                    val preview = result.content.take(120).replace("\n", " ")
                    "[$score] ${result.filePath}$lines: $preview"
                }
                // Also show in chat
                val resultText = if (results.isEmpty()) {
                    "No results for: $query"
                } else {
                    buildString {
                        appendLine("RAG search results for: $query")
                        appendLine()
                        for ((i, result) in results.withIndex()) {
                            appendLine("${i + 1}. ${result.filePath}:${result.startLine ?: ""}")
                            appendLine("   Score: ${String.format("%.3f", result.similarity)}")
                            appendLine("   ${result.content.take(120)}...")
                            appendLine()
                        }
                    }
                }
                addSystemMessage(resultText)
            } catch (e: Exception) {
                logger.warn(e) { "RAG search failed" }
                _ragSearchResults.value = listOf("Search error: ${e.message}")
                addSystemMessage("RAG search error: ${e.message}")
            }
        }
    }

    fun cycleApiLogsFilter() {
        val providers = _apiLogs.value.map { it.provider }.distinct().sorted()
        val current = _apiLogsFilter.value
        _apiLogsFilter.value = if (current == null && providers.isNotEmpty()) {
            providers.first()
        } else if (current != null) {
            val idx = providers.indexOf(current)
            if (idx >= 0 && idx < providers.size - 1) providers[idx + 1] else null
        } else null
    }

    fun apiLogUp() {
        val max = _apiLogs.value.size
        if (max > 0) _selectedApiLogIndex.value = (_selectedApiLogIndex.value - 1).coerceIn(0, max - 1)
        _apiLogDetailVisible.value = false
    }

    fun apiLogDown() {
        val max = _apiLogs.value.size
        if (max > 0) _selectedApiLogIndex.value = (_selectedApiLogIndex.value + 1).coerceIn(0, max - 1)
        _apiLogDetailVisible.value = false
    }

    fun toggleApiLogDetail() {
        _apiLogDetailVisible.value = !_apiLogDetailVisible.value
    }

    private var ragJob: kotlinx.coroutines.Job? = null

    // --- Documentation management ---

    fun docsAdd(url: String, depth: Int = 2) {
        scope.launch {
            val r = router ?: return@launch
            try {
                val source = r.addDocumentationSource(url, depth)
                addSystemMessage("Added documentation source: $url (ID: ${source.id})")
                TuiSettingsScreen.invalidateCache()
            } catch (e: Exception) {
                addSystemMessage("Failed to add docs: ${e.message}")
            }
        }
    }

    fun docsDelete(docId: Int) {
        scope.launch {
            val r = router ?: return@launch
            try {
                r.deleteDocumentationSource(docId)
                addSystemMessage("Deleted documentation source #$docId")
                TuiSettingsScreen.invalidateCache()
            } catch (e: Exception) {
                addSystemMessage("Failed to delete docs: ${e.message}")
            }
        }
    }

    fun docsReindex(docId: Int) {
        scope.launch {
            val r = router ?: return@launch
            try {
                addSystemMessage("Indexing documentation #$docId...")
                r.indexDocumentation(docId).collect { progress ->
                    _ragIndexingStatus.value = progress.statusMessage
                    _ragIndexingProgress.value = progress.progressPercent / 100.0
                }
                addSystemMessage("Documentation #$docId indexed.")
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                addSystemMessage("Indexing failed: ${e.message}")
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    fun ragFileUp() { _ragSelectedFileIndex.update { (it - 1).coerceAtLeast(0) } }
    fun ragFileDown() { _ragSelectedFileIndex.update { it + 1 } }

    // --- Context section navigation ---
    fun contextSectionUp() { _selectedContextIndex.update { (it - 1).coerceAtLeast(0) } }
    fun contextSectionDown() { _selectedContextIndex.update { (it + 1).coerceAtMost((_contextSections.value.size - 1).coerceAtLeast(0)) } }

    fun ragViewSelectedChunks() {
        val file = _ragIndexedFiles.value.getOrNull(_ragSelectedFileIndex.value) ?: return
        ragViewChunks(file.filePath)
    }

    fun ragViewChunks(filePath: String) {
        scope.launch {
            val r = router ?: return@launch
            try {
                val chunks = r.getRagChunksForFile(filePath)
                if (chunks.isEmpty()) {
                    addSystemMessage("No chunks for: $filePath")
                    return@launch
                }
                val text = buildString {
                    appendLine("Chunks for: $filePath (${chunks.size} chunks)")
                    appendLine()
                    for ((i, chunk) in chunks.withIndex()) {
                        appendLine("--- Chunk ${i + 1} (lines ${chunk.startLine ?: "?"}–${chunk.endLine ?: "?"}) ---")
                        appendLine(chunk.content.take(300))
                        if (chunk.content.length > 300) appendLine("... (${chunk.content.length} chars)")
                        appendLine()
                    }
                }
                addSystemMessage(text)
            } catch (e: Exception) {
                addSystemMessage("Failed to load chunks: ${e.message}")
            }
        }
    }

    fun ragStopIndexing() {
        ragJob?.cancel()
        ragJob = null
        _ragIndexingStatus.value = "Stopped"
        _ragIndexingProgress.value = -1.0
    }

    fun ragClearIndex() {
        scope.launch {
            val r = router ?: return@launch
            try {
                r.clearRagIndex()
                _ragIndexingStatus.value = "Index cleared"
                refreshRagStats(r)
                addSystemMessage("RAG index cleared.")
            } catch (e: Exception) {
                logger.warn(e) { "RAG clear index failed" }
                addSystemMessage("Error clearing RAG index: ${e.message}")
            }
        }
    }

    fun shutdown() {
        LogSinkRegistry.clear()
        TuiChatMessageMapper.reset()
        bootstrap?.shutdown()
        bootstrap = null
        router = null
    }

    // --- Auto-refresh (mirrors DebugPanel's 30s timer from the IntelliJ plugin) ---

    private fun startAutoRefresh(r: CoreApiRouter) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000) // 30s like plugin's DebugPanel
                try {
                    refreshApiLogs(r)
                    refreshDebugState(r)
                    refreshRagStats(r)
                } catch (e: Exception) {
                    logger.warn(e) { "Auto-refresh failed" }
                }
            }
        }
    }

    private fun refreshDebugState(r: CoreApiRouter) {
        try {
            val stats = r.apiLogsRouter.getApiLogStatistics()
            _debugInfo.update {
                it.copy(
                    messageCount = _messages.value.size,
                    tokensIn = stats.totalInputTokens,
                    tokensOut = stats.totalOutputTokens,
                    costUsd = stats.totalCost,
                    status = if (_isStreaming.value) "STREAMING" else "IDLE"
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh debug state" }
        }
    }

    // --- API Logs (from database via ApiLogsRouter) ---

    private fun refreshApiLogs(r: CoreApiRouter) {
        try {
            val logs = r.apiLogsRouter.getRecentApiLogs(100)
            _apiLogs.value = logs.map { log ->
                TuiApiLogEntry(
                    id = log.id,
                    timestamp = apiLogTimeFormatter.format(Instant.ofEpochMilli(log.createdAt)),
                    provider = log.provider,
                    model = log.model,
                    tokensIn = log.inputTokens.toLong(),
                    tokensOut = log.outputTokens.toLong(),
                    costUsd = log.costUsd,
                    latencyMs = log.latencyMs,
                    httpStatus = log.httpStatus,
                    source = log.requestSource,
                    errorType = log.errorType,
                    errorMessage = log.errorMessage,
                    endpoint = log.endpoint,
                    requestPayload = log.requestPayload.take(2000),
                    responsePayload = (log.responsePayload ?: "").take(2000),
                    taskId = log.taskId,
                    subtaskId = log.subtaskId
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh API logs" }
        }
    }

    // --- Context & RAG data (from router, mirrors ContextPanel from plugin) ---

    /** Section key → color index mapping (matches plugin's ContextSectionColorPalette) */
    private val sectionColorMap = mapOf(
        "project_overview" to 0, "semantic_summary" to 0,
        "project_instructions" to 1, "project_structure" to 1,
        "technologies" to 2, "dependencies" to 2,
        "code_analysis" to 3, "framework_analysis" to 3,
        "current_task" to 4, "subtasks" to 4,
        "conversation_history" to 5, "recent_work" to 5,
        "rag_fragments" to 6, "rag_index" to 6,
        "user_context" to 7, "user_requirements" to 7,
        "key_components" to 8, "domain_analysis" to 8,
        "working_memory" to 9, "mcp_resources" to 9,
    )

    private fun categorizeSection(key: String): String {
        return when {
            key.startsWith("project") || key == "semantic_summary" -> "project"
            key.startsWith("user") -> "user"
            key.startsWith("rag") -> "rag"
            key.startsWith("conversation") || key == "recent_work" -> "conversation"
            key.startsWith("mcp") || key.startsWith("tool") -> "tools"
            else -> "project"
        }
    }

    private fun refreshRagStats(r: CoreApiRouter) {
        scope.launch(Dispatchers.IO) {
            try {
                // Try to get full project context (has token breakdown per section)
                val tid = taskId
                if (tid != null) {
                    try {
                        val ctx = r.getProjectContext(tid)
                        val totalTokens = ctx.totalEstimatedTokens.coerceAtLeast(1)
                        val contextLimit = 128_000 // default context window

                        val sections = ctx.contextSectionTokens.entries
                            .sortedByDescending { it.value.tokens }
                            .mapIndexed { _, (key, info) ->
                                TuiContextSection(
                                    name = info.name,
                                    category = categorizeSection(key),
                                    tokensUsed = info.tokens,
                                    tokensMax = contextLimit,
                                    percentage = info.percentage,
                                    colorIndex = sectionColorMap[key] ?: (key.hashCode() and 0x7FFFFFFF) % 10,
                                )
                            }

                        if (sections.isNotEmpty()) {
                            _contextSections.value = sections
                            return@launch
                        }
                    } catch (e: Exception) {
                        logger.debug(e) { "Project context not available, falling back to RAG stats" }
                    }
                }

                // Fallback: just RAG stats
                val stats = r.getRagStatistics()
                _contextSections.value = listOf(
                    TuiContextSection(
                        name = "RAG Index",
                        category = "rag",
                        tokensUsed = stats.embeddingsCount,
                        tokensMax = stats.chunksCount,
                        colorIndex = 6
                    ),
                    TuiContextSection(
                        name = "Indexed Files",
                        category = "rag",
                        tokensUsed = stats.filesCount,
                        tokensMax = 0,
                        colorIndex = 6
                    )
                )
                // Load indexed files for the RAG files table
                try {
                    val files = r.getRagIndexedFiles()
                    _ragIndexedFiles.value = files.map { f ->
                        TuiRagFile(
                            filePath = f.filePath,
                            chunks = f.chunksCount,
                            embeddings = f.embeddingsCount,
                            sizeBytes = f.fileSize
                        )
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                logger.debug(e) { "RAG stats not available (indexing may not be configured)" }
            }
        }
    }

    // === Help Screen ===

    fun helpScrollUp() { _helpScrollOffset.update { (it - 1).coerceAtLeast(0) } }
    fun helpScrollDown() { _helpScrollOffset.update { it + 1 } }
    fun helpPageUp() { _helpScrollOffset.update { (it - 10).coerceAtLeast(0) } }
    fun helpPageDown() { _helpScrollOffset.update { it + 10 } }

    // === File Browser ===

    private fun initFileBrowser() {
        _fileBrowserPath.value = projectPath.toAbsolutePath().toString()
        refreshFileBrowser()
    }

    private fun refreshFileBrowser() {
        val dir = File(_fileBrowserPath.value)
        if (!dir.isDirectory) return
        val showHidden = _fileBrowserShowHidden.value
        val entries = mutableListOf<TuiFileEntry>()

        // Parent directory entry (unless at filesystem root)
        if (dir.parentFile != null) {
            entries.add(TuiFileEntry(name = "..", isDirectory = true))
        }

        try {
            val children = dir.listFiles()?.toList() ?: emptyList()
            val filtered = if (showHidden) children else children.filter { !it.name.startsWith(".") }
            val sorted = filtered.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })

            for (file in sorted) {
                entries.add(TuiFileEntry(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    lastModified = file.lastModified(),
                    isSymlink = Files.isSymbolicLink(file.toPath())
                ))
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to list directory: ${_fileBrowserPath.value}" }
        }

        _fileBrowserEntries.value = entries
        _fileBrowserSelectedIndex.value = 0
    }

    fun fileBrowserUp() {
        _fileBrowserSelectedIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun fileBrowserDown() {
        _fileBrowserSelectedIndex.update { (it + 1).coerceAtMost((_fileBrowserEntries.value.size - 1).coerceAtLeast(0)) }
    }

    fun fileBrowserEnter() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return

        if (entry.isDirectory) {
            val currentPath = _fileBrowserPath.value
            val newPath = if (entry.name == "..") {
                File(currentPath).parent ?: currentPath
            } else {
                File(currentPath, entry.name).absolutePath
            }
            _fileBrowserPath.value = newPath
            refreshFileBrowser()
        } else {
            // Open file content as a system message (preview)
            fileBrowserPreviewFile(entry)
        }
    }

    fun fileBrowserGoUp() {
        val parent = File(_fileBrowserPath.value).parent
        if (parent != null) {
            _fileBrowserPath.value = parent
            refreshFileBrowser()
        }
    }

    fun fileBrowserToggleHidden() {
        _fileBrowserShowHidden.update { !it }
        refreshFileBrowser()
    }

    fun fileBrowserAddAsContext() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val relativePath = try {
            projectPath.toAbsolutePath().relativize(java.nio.file.Paths.get(fullPath)).toString()
        } catch (_: Exception) { fullPath }

        val ref = if (entry.isDirectory) "@folder:$relativePath" else "@file:$relativePath"
        val current = _inputBuffer.value
        val newBuffer = if (current.isBlank()) ref else "$current $ref"
        updateInputBuffer(newBuffer)
        _cursorPosition.value = newBuffer.length
        addSystemMessage("Added context: $ref")
    }

    fun fileBrowserOpenExternal() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        openFileInEditor(fullPath)
    }

    fun fileBrowserShowInfo() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val file = File(fullPath)
        val info = buildString {
            appendLine("File Info: ${entry.name}")
            appendLine("  Path: $fullPath")
            appendLine("  Type: ${if (entry.isDirectory) "Directory" else "File"}")
            if (!entry.isDirectory) {
                appendLine("  Size: ${formatFileSize(entry.size)}")
            }
            if (entry.isSymlink) appendLine("  Symlink: yes")
            if (entry.lastModified > 0) {
                val instant = java.time.Instant.ofEpochMilli(entry.lastModified)
                val dt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                appendLine("  Modified: $dt")
            }
            if (file.isDirectory) {
                val count = file.listFiles()?.size ?: 0
                appendLine("  Contents: $count items")
            }
        }
        addSystemMessage(info)
    }

    private fun fileBrowserPreviewFile(entry: TuiFileEntry) {
        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val file = File(fullPath)

        if (!file.isFile) return
        if (file.length() > 100_000) {
            addSystemMessage("File too large to preview: ${entry.name} (${formatFileSize(file.length())}). Use [a] to add as context or [o] to open externally.")
            return
        }

        try {
            val content = file.readText()
            val ext = entry.name.substringAfterLast('.', "")
            val preview = if (content.length > 2000) content.take(2000) + "\n... (truncated, ${content.length} chars total)" else content
            addSystemMessage("```$ext\n$preview\n```\n📄 ${entry.name} (${formatFileSize(file.length())})")
        } catch (e: Exception) {
            addSystemMessage("Cannot read file: ${e.message}")
        }
    }

    fun fileBrowserRefresh() {
        refreshFileBrowser()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1fM", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.1fK", bytes / 1_024.0)
        else -> "${bytes}B"
    }
}
