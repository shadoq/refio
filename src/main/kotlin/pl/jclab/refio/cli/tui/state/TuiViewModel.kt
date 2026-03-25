package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.logging.LogSinkRegistry
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import mu.KotlinLogging
import pl.jclab.refio.cli.tui.input.TuiContextValidator
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
    private val projectPath: Path,
    private val initialMode: TaskMode,
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
    private val _autocompleteVisible = MutableStateFlow(false)
    private val _autocompleteCandidates = MutableStateFlow<List<String>>(emptyList())
    private val _autocompleteSelectedIndex = MutableStateFlow(0)

    private val _isInitialized = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Combined state flow for rendering — reacts to ANY flow change */
    val stateFlow: StateFlow<TuiState> = merge(
        _messages.map { Unit },
        _isStreaming.map { Unit },
        _agents.map { Unit },
        _pendingApprovals.map { Unit },
        _activeTab.map { Unit },
        _inputBuffer.map { Unit },
        _screen.map { Unit },
        _steps.map { Unit },
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
        _totalCost.map { Unit },
        _totalTokens.map { Unit },
        _settingsTab.map { Unit },
        _autocompleteVisible.map { Unit },
        _autocompleteCandidates.map { Unit },
        _autocompleteSelectedIndex.map { Unit },
    ).map {
        buildCurrentState()
    }.stateIn(scope, SharingStarted.Eagerly, TuiState(mode = initialMode.name, model = initialModel))

    private fun buildCurrentState() = TuiState(
        screen = _screen.value,
        activeTab = _activeTab.value,
        messages = _messages.value,
        isStreaming = _isStreaming.value,
        agents = _agents.value,
        steps = _steps.value,
        contextSections = _contextSections.value,
        logs = _logs.value,
        apiLogs = _apiLogs.value,
        debugInfo = _debugInfo.value,
        pendingApprovals = _pendingApprovals.value,
        sessions = _sessions.value,
        mode = _mode.value,
        model = _model.value,
        executionMode = _executionMode.value,
        thinkingEnabled = _thinkingEnabled.value,
        noEgressEnabled = _noEgressEnabled.value,
        inputBuffer = _inputBuffer.value,
        totalCostUsd = _totalCost.value,
        totalTokens = _totalTokens.value,
        settingsTab = _settingsTab.value,
        autocompleteVisible = _autocompleteVisible.value,
        autocompleteCandidates = _autocompleteCandidates.value,
        autocompleteSelectedIndex = _autocompleteSelectedIndex.value,
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
        scope = scope
    )

    suspend fun initialize() {
        try {
            // Register TUI log sink before core init so we capture init logs
            LogSinkRegistry.register(tuiLogSink)

            val boot = StandaloneCoreBootstrap(projectPath)
            val r = boot.initialize()
            bootstrap = boot
            router = r
            _isInitialized.value = true
            _debugInfo.update {
                it.copy(
                    connected = true,
                    dbPath = projectPath.resolve(".refio/database.sqlite").toString(),
                    mode = initialMode.name,
                    model = initialModel ?: "default"
                )
            }
            logger.info { "Core initialized for project: ${projectPath.toAbsolutePath()}" }

            bridgeBackendEventBus(r)
            subscribeToAgentEvents()
            startAutoRefresh(r)
            refreshRagStats(r)
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize core" }
            _error.value = "Initialization failed: ${e.message}"
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
        _activeTab.value = tab
    }

    fun setScreen(screen: TuiScreen) {
        _screen.value = screen
    }

    fun setSettingsTab(index: Int) {
        _settingsTab.value = index.coerceIn(0, 10)
    }

    fun updateInputBuffer(input: String) {
        _inputBuffer.value = input
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
        _mode.value = modes[(current + 1) % modes.size]
    }

    fun toggleThinking() {
        _thinkingEnabled.update { !it }
    }

    fun toggleNoEgress() {
        _noEgressEnabled.update { !it }
    }

    fun toggleExecutionMode() {
        _executionMode.update { if (it == "AUTO") "INTERACTIVE" else "AUTO" }
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
        val refRegex = Regex("""@(file|folder):(\S+)""")
        val root = projectPath.toAbsolutePath().toString()
        for (match in refRegex.findAll(input)) {
            val fullRef = match.value
            val result = TuiContextValidator.validate(fullRef, root)
            if (!result.isValid) return result.warning
            if (result.warning != null) {
                // Just a warning, not a block — show it but continue
                addSystemMessage("⚠ ${result.warning}")
            }
        }
        return null
    }

    fun sendMessage(input: String) {
        if (input.isBlank() || _isStreaming.value) return

        // Validate context references before sending
        val contextWarning = validateContextReferences(input)
        if (contextWarning != null) {
            addSystemMessage("⚠ $contextWarning")
            return
        }

        val userMsg = TuiChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "user",
            content = input
        )
        _messages.update { it + userMsg }
        _inputBuffer.value = ""

        scope.launch {
            val r = router ?: run {
                _error.value = "Core not initialized"
                return@launch
            }

            try {
                workflowListener.reset()
                _isStreaming.value = true

                val tid = taskId ?: run {
                    val newId = UUID.randomUUID().toString()
                    taskId = newId
                    newId
                }

                val taskMode = try {
                    TaskMode.valueOf(_mode.value)
                } catch (_: Exception) {
                    TaskMode.CHAT
                }

                val execMode = try {
                    ExecutionMode.valueOf(_executionMode.value)
                } catch (_: Exception) {
                    ExecutionMode.AUTO
                }

                val uiState = UIState(
                    taskId = tid,
                    mode = taskMode,
                    executionMode = execMode,
                    input = input,
                    model = _model.value,
                    provider = null,
                    streamingEnabled = true,
                    thinkingEnabled = _thinkingEnabled.value,
                    noEgressEnabled = _noEgressEnabled.value
                )

                val request = WorkflowRequest(uiState = uiState)
                r.workflowOrchestrator.execute(request, workflowListener)

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
        } catch (e: Exception) {
            logger.warn(e) { "Failed to reset settings" }
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
        _autocompleteCandidates.value = allContextPrefixes
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

    fun resendLastMessage() {
        val lastUserMsg = _messages.value.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            sendMessage(lastUserMsg.content)
        }
    }

    // --- Session history ---

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

    // --- Model selection ---

    fun setModel(model: String) {
        _model.value = model.ifBlank { null }
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
                    timestamp = apiLogTimeFormatter.format(Instant.ofEpochMilli(log.createdAt)),
                    provider = log.provider,
                    model = log.model,
                    tokensIn = log.inputTokens.toLong(),
                    tokensOut = log.outputTokens.toLong(),
                    costUsd = log.costUsd
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
            } catch (e: Exception) {
                logger.debug(e) { "RAG stats not available (indexing may not be configured)" }
            }
        }
    }
}
