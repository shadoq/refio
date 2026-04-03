package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mu.KotlinLogging
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.UpdateSubtaskRequest
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.TaskMode as CoreTaskMode
import pl.jclab.refio.core.db.ExecutionMode as CoreExecutionMode
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Sub-ViewModel for session management, execution/planning, and model selection.
 * Extracted from TuiViewModel to reduce its size.
 */
class TuiSessionViewModel(
    val scope: CoroutineScope,
    internal val getRouter: () -> CoreApiRouter?,
    internal val getTaskId: () -> String?,
    internal val setTaskId: (String?) -> Unit,
    internal val mode: MutableStateFlow<String>,
    internal val model: MutableStateFlow<String?>,
    internal val projectPath: Path,
    internal val projectId: String
) {
    // --- StateFlows owned by this sub-VM ---

    /** Exposed as internal for coordinator wiring (workflowListener, clearSteps callback). */
    internal val _stepsInternal = MutableStateFlow<List<TuiStep>>(emptyList())
    val steps: StateFlow<List<TuiStep>> = _stepsInternal.asStateFlow()

    private val _subtasks = MutableStateFlow<List<TuiSubtask>>(emptyList())
    val subtasks: StateFlow<List<TuiSubtask>> = _subtasks.asStateFlow()

    private val _activePlan = MutableStateFlow<TuiPlan?>(null)
    val activePlan: StateFlow<TuiPlan?> = _activePlan.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pendingPlanApproval = MutableStateFlow<TuiPlanApproval?>(null)
    val pendingPlanApproval: StateFlow<TuiPlanApproval?> = _pendingPlanApproval.asStateFlow()

    private val _selectedStepIndex = MutableStateFlow(0)
    val selectedStepIndex: StateFlow<Int> = _selectedStepIndex.asStateFlow()

    private val _executionStatus = MutableStateFlow("Idle")
    val executionStatus: StateFlow<String> = _executionStatus.asStateFlow()

    internal val _executionMode = MutableStateFlow("AUTO")
    val executionMode: StateFlow<String> = _executionMode.asStateFlow()

    private val _sessions = MutableStateFlow<List<TuiSessionEntry>>(emptyList())
    val sessions: StateFlow<List<TuiSessionEntry>> = _sessions.asStateFlow()

    private val _selectedHistoryIndex = MutableStateFlow(0)
    val selectedHistoryIndex: StateFlow<Int> = _selectedHistoryIndex.asStateFlow()

    private val _historyFilter = MutableStateFlow("*")
    val historyFilter: StateFlow<String> = _historyFilter.asStateFlow()

    private val _modelSelectorVisible = MutableStateFlow(false)
    val modelSelectorVisible: StateFlow<Boolean> = _modelSelectorVisible.asStateFlow()

    private val _modelSelectorCandidates = MutableStateFlow<List<String>>(emptyList())
    val modelSelectorCandidates: StateFlow<List<String>> = _modelSelectorCandidates.asStateFlow()

    private val _modelSelectorIndex = MutableStateFlow(0)
    val modelSelectorIndex: StateFlow<Int> = _modelSelectorIndex.asStateFlow()

    private val _totalCost = MutableStateFlow(0.0)
    val totalCost: StateFlow<Double> = _totalCost.asStateFlow()

    private val _totalTokens = MutableStateFlow(0L)
    val totalTokens: StateFlow<Long> = _totalTokens.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private val _noEgressEnabled = MutableStateFlow(false)
    val noEgressEnabled: StateFlow<Boolean> = _noEgressEnabled.asStateFlow()

    // --- Callbacks to parent TuiViewModel ---

    /** Callback to add a system message to the chat */
    var addSystemMessage: (String) -> Unit = {}

    /** Callback to set the active tab */
    var setActiveTab: (TuiTab) -> Unit = {}

    /** Callback to load messages from DB for a given task */
    var loadMessagesFromDb: (CoreApiRouter, String) -> Unit = { _, _ -> }

    /** Callback to load subtasks from DB for a given task */
    var loadSubtasksFromDb: (CoreApiRouter, String) -> Unit = { _, _ -> }

    /** Callback to create a new task in DB, returns the new task ID */
    var createNewTaskInDb: (CoreApiRouter) -> String = { "" }

    /** Callback to refresh API logs */
    var refreshApiLogs: (CoreApiRouter) -> Unit = {}

    /** Callback for streaming (used in replanSteps, addStep, executeStep) */
    var onStreamChunk: (String) -> Unit = {}

    /** Callback for stream completion (used in replanSteps, addStep) */
    var onStreamComplete: (String) -> Unit = {}

    /** Callback to clear messages list */
    var clearMessages: () -> Unit = {}

    /** Callback to clear steps list */
    var clearSteps: () -> Unit = {}

    /** Callback to clear context sections */
    var clearContextSections: () -> Unit = {}

    /** Callback to clear input buffer */
    var clearInputBuffer: () -> Unit = {}

    /** Callback to update streaming state */
    var setStreaming: (Boolean) -> Unit = {}

    /** Callback to update debug info */
    var updateDebugInfo: (sessionId: String?, mode: String?) -> Unit = { _, _ -> }

    /** Callback to set screen */
    var setScreen: (TuiScreen) -> Unit = {}

    /** Callback to resolve context window from model string */
    var resolveContextWindow: (String?) -> Unit = {}

    fun setNoEgressEnabled(enabled: Boolean) {
        _noEgressEnabled.value = enabled
    }

    fun setThinkingEnabled(enabled: Boolean) {
        _thinkingEnabled.value = enabled
    }

    // =============================================
    // Session history
    // =============================================

    fun loadSessions() {
        val r = getRouter() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val tasks = r.taskRouter.listTasks().tasks
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

    fun refreshSessions() {
        loadSessions()
        addSystemMessage("Sessions refreshed.")
    }

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
        if (session.id == getTaskId()) {
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
        val r = getRouter() ?: return
        scope.launch {
            try {
                r.taskRouter.updateTask(session.id, UpdateTaskRequest(pinned = !session.pinned))
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

    fun showNewSessionDialog() {
        val r = getRouter() ?: return
        // Create a new session in DB
        val newId = createNewTaskInDb(r)
        setTaskId(newId)
        clearMessages()
        clearSteps()
        _subtasks.value = emptyList()
        _activePlan.value = null
        _pendingPlanApproval.value = null
        clearContextSections()
        clearInputBuffer()
        setStreaming(false)
        _executionStatus.value = "Idle"
        updateDebugInfo(newId, null)
        addSystemMessage("New session created (${mode.value}). ID: ${newId.take(8)}...")
    }

    fun switchSession(sessionId: String) {
        val r = getRouter() ?: return
        scope.launch {
            try {
                val task = r.taskRouter.getTask(sessionId) ?: run {
                    addSystemMessage("Session not found: $sessionId")
                    return@launch
                }
                setTaskId(task.id)
                mode.value = task.mode

                // Load messages and subtasks from DB
                loadMessagesFromDb(r, task.id)
                loadSubtasksFromDb(r, task.id)

                updateDebugInfo(task.id, task.mode)
                setScreen(TuiScreen.MAIN)
                addSystemMessage("Switched to session: ${task.name} (${task.mode})")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to switch session: $sessionId" }
                addSystemMessage("Failed to switch session: ${e.message}")
            }
        }
    }

    private fun deleteSession(sessionId: String) {
        val r = getRouter() ?: return
        try {
            r.taskRouter.deleteTask(sessionId)
            _sessions.update { it.filter { s -> s.id != sessionId } }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete session: $sessionId" }
        }
    }

    // =============================================
    // Execution / Plan management
    // =============================================

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

    // =============================================
    // Subtask operations
    // =============================================

    fun approveSubtask(subtaskId: String) {
        scope.launch {
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.updateSubtask(tid, subtaskId, UpdateSubtaskRequest(approvalStatus = ApprovalStatus.APPROVED))
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to approve subtask: $subtaskId" }
                updateSubtaskStatus(subtaskId, "APPROVED") // fallback to local
            }
        }
    }

    fun skipSubtask(subtaskId: String) {
        scope.launch {
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.updateSubtask(tid, subtaskId, UpdateSubtaskRequest(approvalStatus = ApprovalStatus.SKIPPED))
                loadSubtasksFromDb(r, tid)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to skip subtask: $subtaskId" }
                updateSubtaskStatus(subtaskId, "SKIPPED") // fallback to local
            }
        }
    }

    fun deleteSubtask(subtaskId: String) {
        scope.launch {
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.deleteSubtask(tid, subtaskId)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.swapSubtaskOrder(tid, current.id, above.id)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.swapSubtaskOrder(tid, current.id, below.id)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                r.subtaskRouter.deletePendingSubtasks(tid)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            try {
                _executionStatus.value = "Executing: ${subtask.name}"
                _subtasks.update { list ->
                    list.map { if (it.id == subtask.id) it.copy(status = "RUNNING") else it }
                }
                val result = r.agentRouter.executeSubtaskStep(tid, subtask.id)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            val taskMode = try { CoreTaskMode.valueOf(mode.value) } catch (_: Exception) { CoreTaskMode.AGENT }
            if (taskMode == CoreTaskMode.CHAT) {
                addSystemMessage("Re-plan is only available in PLAN or AGENT mode")
                return@launch
            }
            try {
                _executionStatus.value = "Re-planning..."
                // Delete existing pending subtasks and run a new turn to re-plan
                r.subtaskRouter.deletePendingSubtasks(tid)
                val turnRequest = TurnRequest(
                    taskId = tid,
                    userInput = "Re-plan the remaining steps based on current progress",
                    mode = taskMode,
                    executionMode = CoreExecutionMode.INTERACTIVE,
                    model = model.value
                )
                val result = r.agentRouter.runTurn(turnRequest, streamCallback = { chunk ->
                    onStreamChunk(chunk.delta)
                })
                onStreamComplete(result.response)
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
            val r = getRouter() ?: return@launch
            val tid = getTaskId() ?: return@launch
            val taskMode = try { CoreTaskMode.valueOf(mode.value) } catch (_: Exception) { CoreTaskMode.AGENT }
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
                    model = model.value
                )
                val result = r.agentRouter.runTurn(turnRequest, streamCallback = { chunk ->
                    onStreamChunk(chunk.delta)
                })
                onStreamComplete(result.response)
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

    // =============================================
    // Model selector
    // =============================================

    fun showModelSelector() {
        scope.launch {
            val models = getAvailableModels()
            if (models.isNotEmpty()) {
                _modelSelectorCandidates.value = models
                _modelSelectorIndex.value = models.indexOf(model.value).coerceAtLeast(0)
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
            val selected = candidates[idx]
            model.value = selected.ifBlank { null }
            resolveContextWindow(selected)
            addSystemMessage("Model set to: $selected")
            // Persist to config (same key as IntelliJ: ui.selected_model)
            try {
                getRouter()?.configRouter?.updateConfig("ui", "app", null, mapOf("selected_model" to selected))
            } catch (e: Exception) {
                logger.debug(e) { "Failed to persist selected model" }
            }
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
        val r = getRouter() ?: return getStaticModelList()
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
        // Also allow manually typing -- add current model if not in list
        val current = model.value
        if (current != null && current !in result) {
            result.add(0, current)
        }
        return result
    }

    // =============================================
    // Mode / toggle
    // =============================================

    fun cycleMode() {
        val modes = listOf("CHAT", "PLAN", "AGENT")
        val current = modes.indexOf(mode.value)
        val newMode = modes[(current + 1) % modes.size]

        val r = getRouter() ?: return
        val currentTaskId = getTaskId() ?: run {
            // No active session -- create one
            val newId = createNewTaskInDb(r)
            setTaskId(newId)
            mode.value = newMode
            try {
                r.configRouter.updateConfig("ui", "app", null, mapOf("selected_mode" to newMode))
            } catch (_: Exception) {}
            updateDebugInfo(newId, newMode)
            addSystemMessage("Mode switched to $newMode (new session)")
            return
        }

        // Update existing task mode in DB (preserves conversation, like the plugin does)
        try {
            val coreMode = CoreTaskMode.valueOf(newMode)
            r.taskRouter.updateTask(currentTaskId, UpdateTaskRequest(mode = coreMode))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update task mode, falling back to new session" }
            // Fallback: create new session if update fails (e.g. task not found)
            val newId = createNewTaskInDb(r)
            setTaskId(newId)
            clearMessages()
            _subtasks.value = emptyList()
            clearSteps()
            _activePlan.value = null
            _pendingPlanApproval.value = null
            updateDebugInfo(newId, newMode)
        }

        mode.value = newMode

        // Persist to config (same key as IntelliJ: ui.selected_mode)
        try {
            getRouter()?.configRouter?.updateConfig("ui", "app", null, mapOf("selected_mode" to newMode))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist selected mode" }
        }

        // Clear plan/step state when switching to CHAT (no tools in chat mode)
        if (newMode == "CHAT") {
            _subtasks.value = emptyList()
            _activePlan.value = null
            _pendingPlanApproval.value = null
        }

        updateDebugInfo(null, newMode)
        addSystemMessage("Mode switched to $newMode")
    }

    fun toggleThinking() {
        _thinkingEnabled.update { !it }
        // Persist to config
        try {
            getRouter()?.configRouter?.updateConfig("ui", "app", null, mapOf("thinking_enabled" to _thinkingEnabled.value.toString()))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist thinking state" }
        }
    }

    fun toggleNoEgress() {
        _noEgressEnabled.update { !it }
        // Persist to config
        try {
            getRouter()?.configRouter?.updateConfig("ui", "app", null, mapOf("no_egress_enabled" to _noEgressEnabled.value.toString()))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist no-egress state" }
        }
    }

    fun toggleExecutionMode() {
        _executionMode.update { if (it == "AUTO") "INTERACTIVE" else "AUTO" }
        // Persist to config (same key as IntelliJ: ui.execution_mode)
        try {
            getRouter()?.configRouter?.updateConfig("ui", "app", null, mapOf("execution_mode" to _executionMode.value))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist execution mode" }
        }
    }

    // =============================================
    // Cost/token tracking
    // =============================================

    fun addTokens(count: Long) {
        _totalTokens.update { it + count }
    }

    fun addCost(amount: Double) {
        _totalCost.update { it + amount }
    }
}
