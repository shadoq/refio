package pl.jclab.refio.services.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.core.api.UIAdapter
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.session.ExecutionMonitor
import pl.jclab.refio.core.session.MessageDispatcher
import pl.jclab.refio.core.session.PromptStateTracker
import pl.jclab.refio.core.session.SessionLifecycleService
import pl.jclab.refio.core.session.SessionStateManager
import pl.jclab.refio.core.session.SubtaskTracker
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.ui.components.toolbar.StatusBar
import java.util.*

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val logger = dualLogger("SessionManager")

    /**
     * Platform-agnostic UI adapter for notifications, logging, and user interaction.
     * Initialized lazily to avoid circular dependency during service construction.
     */
    val uiAdapter: UIAdapter by lazy { IntelliJUIAdapter(project) }

    // Use proper coroutine scope for Project-level service with error handler
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Unhandled exception in SessionManager coroutine" }
        uiAdapter.showError("Unhandled error: ${exception.message}")

        // Show error message in chat
        val errorMessage = Message(
            id = UUID.randomUUID().toString(),
            taskId = (stateManager.getActiveSession()?.id ?: "unknown"),
            role = "system",
            content = "Error: ${exception.message}\n\nStack trace: ${exception.stackTraceToString()}",
            createdAt = System.currentTimeMillis()
        )
        // We can't use `cs` here because it's not yet initialized (exceptionHandler is
        // defined before cs). GlobalScope is acceptable here as a last-resort error reporter.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                stateManager.appendMessage(errorMessage)
            } catch (e: Exception) {
                logger.error(e) { "Failed to append error message to state" }
            }
        }
    }

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val stateManager = SessionStateManager()
    val activeSession: StateFlow<Session?> = stateManager.activeSession
    val sessions: StateFlow<List<Session>> = stateManager.sessions
    val messages: StateFlow<List<Message>> = stateManager.messages
    val subtasks: StateFlow<List<SubtaskResponse>> = stateManager.subtasks
    val activePlan: StateFlow<pl.jclab.refio.core.api.PlanResponse?> = stateManager.activePlan
    val planSteps: StateFlow<List<pl.jclab.refio.core.api.PlanSpecStepResponse>> = stateManager.planSteps
    val selectedModel: StateFlow<String> = stateManager.selectedModel
    val thinkingEnabled: StateFlow<Boolean> = stateManager.thinkingEnabled
    val noEgressEnabled: StateFlow<Boolean> = stateManager.noEgressEnabled

    val isPaused: StateFlow<Boolean> = stateManager.isPaused
    val isGenerating: StateFlow<Boolean> = stateManager.isGenerating
    val pendingContextRefs: StateFlow<List<ContextReference>> = stateManager.pendingContextRefs
    val pendingUserInput: StateFlow<String> = stateManager.pendingUserInput
    val contextSectionTokens: StateFlow<Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>> =
        stateManager.contextSectionTokens
    val totalEstimatedTokens: StateFlow<Int> = stateManager.totalEstimatedTokens

    // Cached max context window. Refreshed off-EDT on session/model change and on explicit
    // refreshMaxContextWindow() calls (e.g. after MAX_CONTEXT_SIZE config save). Reading
    // .value is instant and EDT-safe — never hits SQLite.
    private val _maxContextWindow = MutableStateFlow(8192)
    val maxContextWindow: StateFlow<Int> = _maxContextWindow.asStateFlow()

    /**
     * Turn execution state (phase, iteration, tokens, active tool).
     * Null if AgentTurnLoop is not initialized.
     */
    val turnState: StateFlow<pl.jclab.refio.core.services.turn.TurnStateSnapshot>?
        get() = projectRouter.turnState

    /**
     * Last prompt snapshot with context decision trace.
     * Null if no prompt has been built yet.
     */
    val lastPromptSnapshot: StateFlow<pl.jclab.refio.core.services.turn.PromptSnapshot?>?
        get() = projectRouter.lastPromptSnapshot

    /**
     * Tool approval service for ASK permission level.
     * UI observes pendingRequests and calls resolveApproval.
     */
    val toolApprovalService: pl.jclab.refio.core.services.turn.ToolApprovalService
        get() = projectRouter.toolApprovalService

    val pendingUserMessageQueue: pl.jclab.refio.core.services.PendingUserMessageQueue
        get() = projectRouter.pendingUserMessageQueue

    /**
     * Append a mid-execution user message to the in-memory message list for immediate UI display.
     * Does NOT call loadMessages() — that would wipe in-flight streaming/tool messages.
     * The next loadMessages() (after turn completes) will reconcile with the DB version.
     */
    fun notifyMidExecutionMessage(taskId: String, content: String) {
        cs.launch {
            stateManager.appendMessage(
                pl.jclab.refio.api.models.Message(
                    id = "mid-exec-${System.currentTimeMillis()}",
                    taskId = taskId,
                    role = "user",
                    content = content,
                    metadata = """{"type":"mid_execution_input"}""",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private val statusBarIntegration = StatusBarIntegration()
    private lateinit var lifecycleService: SessionLifecycleService
    private lateinit var messageDispatcher: MessageDispatcher
    private lateinit var subtaskTracker: SubtaskTracker
    private lateinit var executionMonitor: ExecutionMonitor
    private lateinit var promptStateTracker: PromptStateTracker
    private lateinit var coreSessionService: pl.jclab.refio.core.session.CoreSessionService

    // Selected mode (persisted in config, used for creating default session)
    private val modeSwitchMutex = Mutex()

    // Services
    private val coreManager = CoreConnectionManager.getInstance()

    private val projectBasePath: String by lazy {
        val basePath = project.basePath ?: System.getProperty("user.dir")
        logger.info { "SessionManager: projectBasePath='$basePath'" }
        basePath
    }
    private val normalizedProjectPath: String by lazy {
        val normalized = java.nio.file.Paths.get(projectBasePath).toAbsolutePath().normalize().toString()
        logger.info { "SessionManager: normalizedProjectPath='$normalized'" }
        normalized
    }
    private val projectId: String by lazy {
        val id = ProjectIdGenerator.generate(java.nio.file.Paths.get(projectBasePath))
        logger.info { "SessionManager: generated projectId='$id' for path='$projectBasePath'" }
        id
    }

    // Project-specific API router with correct projectRoot from IntelliJ Project
    private val projectRouter: pl.jclab.refio.core.api.CoreApiRouter by lazy {
        if (project.basePath == null) {
            logger.warn { "Project basePath is null, falling back to user.dir" }
        }
        logger.info { "SessionManager: project.basePath='${project.basePath}', project.name='${project.name}'" }
        val projectRoot = java.nio.file.Paths.get(projectBasePath)
        logger.info { "SessionManager: Initializing project-specific router with projectRoot=$projectRoot (absolute=${projectRoot.toAbsolutePath()})" }
        coreManager.getOrCreateProjectRouter(projectRoot, project)
    }

    private val configService: pl.jclab.refio.core.services.ConfigService
        get() = projectRouter.configService

    /**
     * Expose UserInteraction for UI components (PromptInputPanel).
     * This ensures UI uses the same router instance as the orchestrator.
     */
    val userInteraction: pl.jclab.refio.core.services.orchestration.UserInteraction by lazy {
        projectRouter.userInteraction
    }

    /**
     * Expose the project-specific API router for UI components.
     * All UI components should use this router instead of coreManager.getApiRouter()
     * to ensure consistent projectRoot, toolRegistry, and stateful services.
     */
    val apiRouter: pl.jclab.refio.core.api.CoreApiRouter
        get() = projectRouter

    fun currentProjectId(): String = projectId

    private val stepExecutionService: StepExecutionService by lazy {
        StepExecutionService.getInstance(project)
    }

    init {
        initializeServices()

        cs.launch {
            userInteraction.isWaitingForResponse.collect { isWaiting ->
                if (isWaiting) {
                    logger.info { "UserInteraction is waiting for response - refreshing messages" }
                    messageDispatcher.loadMessages()
                }
            }
        }

        // Observe lastPromptSnapshot to propagate context section tokens to UI.
        // lastPromptSnapshot is published by AgentTurnLoop after each prompt build,
        // so this bridges core-layer context info to SessionStateManager → StatusBar/ContextPanel.
        cs.launch {
            var snapshotJob: Job? = null
            activeSession.collect { _ ->
                snapshotJob?.cancel()
                val flow = lastPromptSnapshot
                if (flow != null) {
                    snapshotJob = cs.launch {
                        flow.collect { snapshot ->
                            if (snapshot != null && snapshot.sectionTokens.isNotEmpty()) {
                                updateContextSectionTokens(snapshot.sectionTokens, snapshot.totalTokens)
                            }
                        }
                    }
                }
            }
        }

        // Refresh max context window off-EDT on session or model change.
        cs.launch {
            combine(activeSession, selectedModel) { session, model -> session?.id to model }
                .collect { refreshMaxContextWindow() }
        }
    }

    private fun initializeServices() {
        executionMonitor = ExecutionMonitor(
            projectRouter = projectRouter,
            stateManager = stateManager,
            stepExecutionService = stepExecutionService,
            scope = cs,
            loadMessages = { messageDispatcher.loadMessages() },
            loadSubtasks = { subtaskTracker.loadSubtasks() },
            prepareNextStep = { subtaskTracker.prepareNextStep() }
        )

        subtaskTracker = SubtaskTracker(
            projectRouter = projectRouter,
            stateManager = stateManager,
            vfsRefresher = pl.jclab.refio.services.project.IntelliJVfsRefresher(project),
            loadMessages = { messageDispatcher.loadMessages() },
            executeCurrentStep = { subtaskId -> executionMonitor.executeCurrentStep(subtaskId) },
            showApprovalMessageForNextSubtask = { executionMonitor.showApprovalMessageForNextSubtask() },
            scope = cs,
        )

        messageDispatcher = MessageDispatcher(
            projectRouter = projectRouter,
            stateManager = stateManager,
            scope = cs,
        )

        promptStateTracker = PromptStateTracker(stateManager)

        lifecycleService = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = modeSwitchMutex,
            projectId = projectId,
            normalizedProjectPath = normalizedProjectPath,
            scope = cs
        )

        lifecycleService.initialize(messageDispatcher, subtaskTracker, executionMonitor)

        coreSessionService = pl.jclab.refio.core.session.CoreSessionService(
            projectRouter = projectRouter,
            stateManager = stateManager,
            subtaskTracker = subtaskTracker,
            messageDispatcher = messageDispatcher,
            lifecycleService = lifecycleService,
            uiAdapter = uiAdapter,
            scope = cs,
            modeSwitchMutex = modeSwitchMutex,
        )
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Set reference to StatusBar for progress updates.
     */
    fun setStatusBar(statusBar: StatusBar) {
        statusBarIntegration.setStatusBar(statusBar)
    }

    /**
     * Create a new session.
     */
    suspend fun createSession(name: String, mode: TaskMode, executionMode: ExecutionMode? = null): Session {
        return lifecycleService.createSession(name, mode, executionMode)
    }

    /**
     * Switch to a different session.
     */
    suspend fun switchSession(sessionId: String) {
        lifecycleService.switchSession(sessionId, messageDispatcher, subtaskTracker)
    }

    /**
     * Update current session.
     */
    fun updateSession(session: Session) {
        lifecycleService.updateSession(session)
    }

    /**
     * Load existing session by ID (US-204: History Panel)
     *
     * Flow:
     * 1. Fetch task from database
     * 2. Parse uiState JSON to restore UI toggles
     * 3. Load messages and subtasks
     * 4. Update StateFlows for UI reactivity
     * 5. Resume execution if mode==PLAN/AGENT and has pending subtasks
     */
    suspend fun loadSession(sessionId: String) {
        lifecycleService.loadSession(sessionId, messageDispatcher, subtaskTracker, executionMonitor)
    }

    /**
     * Switch mode for active session (US-100: does NOT create new session).
     *
     * Flow:
     * 1. Lock mutex to prevent race with sendMessage()
     * 2. Update mode via API
     * 3. Update local state
     * 4. Clear subtasks if switching to CHAT
     * 5. Reload messages from backend
     */
    suspend fun switchMode(newMode: TaskMode) {
        lifecycleService.switchModeSafely(newMode)
    }

    suspend fun sendMessage(
        input: String,
        contextRefs: List<ContextReference> = emptyList(),
        model: String? = null,
        provider: String? = null
    ): Message = coreSessionService.sendMessage(input, contextRefs, model, provider)

    /**
     * Rewind conversation to the given message (inclusive), delete all related execution/planning data,
     * and re-send as a fresh message using the standard flow of the current mode.
     *
     * No confirmation dialog is shown by design (per UX spec).
     */
    suspend fun rewindAndResendFromMessage(fromMessageId: String, newContent: String): Message {
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Message content cannot be blank")
        }

        val session = modeSwitchMutex.withLock {
            stateManager.getActiveSession() ?: throw IllegalStateException("No active session")
        }

        // Avoid races with active streaming/execution.
        cancelStreaming()
        cancelExecution()

        // 1) Truncate chat history from pivot (inclusive)
        projectRouter.chatRouter.truncateHistoryFromMessage(
            taskId = session.id,
            fromMessageId = fromMessageId
        )

        // 2) Clear related execution data (subtasks/logs/snapshots)
        projectRouter.subtaskRouter.deleteAllSubtasks(session.id)
        projectRouter.apiLogsRouter.deleteApiLogsByTaskId(session.id)
        projectRouter.snapshotRouter.deleteSnapshotsByTaskId(session.id)

        // 3) Clear planning state if in PLAN mode (plans are tied to session)
        if (session.mode == TaskMode.PLAN) {
            stateManager.setActivePlan(null)
            stateManager.setPlanSteps(emptyList())
        }

        // 4) Refresh UI state
        messageDispatcher.loadMessages()
        subtaskTracker.loadSubtasks()

        // 5) Re-send using standard path for the active mode
        return sendMessage(trimmed, contextRefs = emptyList())
    }

    /**
     * Manually re-load the subtask list for the active session from the backend.
     * Wired to the refresh button in the Execution panel — useful when the
     * UI state has drifted from the database (e.g. after a backend hiccup
     * or when an event was missed).
     */
    suspend fun refreshSubtasks() {
        subtaskTracker.loadSubtasks()
    }

    suspend fun deleteChatMessage(messageId: String) {
        val session = stateManager.getActiveSession() ?: throw IllegalStateException("No active session")
        val deleted = projectRouter.chatRouter.deleteMessage(messageId)
        if (!deleted) {
            throw IllegalArgumentException("Message not found: $messageId")
        }
        messageDispatcher.loadMessages()

        // Deleting a message can leave stale execution UI; keep subtasks in sync.
        if (session.mode != TaskMode.CHAT) {
            subtaskTracker.loadSubtasks()
        }
    }

    /**
     * Answer orchestrator question.
     *
     * Called when user provides response to orchestrator question during execution.
     * This will resume the suspended orchestration workflow.
     *
     * @param questionId Question ID from UserInteraction
     * @param answer User's response
     */
    suspend fun answerQuestion(questionId: String, answer: String) {
        messageDispatcher.answerQuestion(questionId, answer)
    }


    /**
     * Set selected model (UI state only).
     */
    fun setSelectedModel(model: String) {
        lifecycleService.setSelectedModel(model)
    }

    /**
     * Set selected model in ConfigService (persistent).
     * Use "auto" to enable operation-specific model selection (DEFAULT/PLAN/CODING/WEAK).
     * Use specific model ID (e.g., "ollama/qwen2.5:7b") to override all operations.
     */
    fun setSelectedModelConfig(model: String) {
        lifecycleService.setSelectedModelConfig(model)
    }

    /**
     * Refresh selected model from DB based on current mode.
     * Should be called after Settings changes or mode switches.
     */
    suspend fun refreshSelectedModelFromDB() {
        lifecycleService.refreshSelectedModelFromDB()
    }

    /**
     * Set execution mode (INTERACTIVE/AUTO).
     * Auto-saves UI state to database.
     */
    fun setExecutionMode(mode: ExecutionMode) {
        lifecycleService.setExecutionMode(mode)
    }

    /**
     * Set thinking enabled/disabled (US-010).
     * Auto-saves UI state to database.
     */
    fun setThinkingEnabled(enabled: Boolean) {
        lifecycleService.setThinkingEnabled(enabled)
    }

    /**
     * Set no-egress enabled/disabled (US-006).
     * Auto-saves UI state to database.
     */
    fun setNoEgressEnabled(enabled: Boolean) {
        lifecycleService.setNoEgressEnabled(enabled)
    }

    suspend fun getAvailableModels(): List<String> {
        return lifecycleService.getAvailableModels()
    }

    fun getDefaultModelForMode(): String {
        return lifecycleService.getDefaultModelForMode()
    }

    fun setDefaultModelAllModes(modelId: String, provider: String) {
        lifecycleService.setDefaultModelAllModes(modelId, provider)
    }

    /**
     * Update pending context refs from PromptInputPanel.
     * Called when user adds/removes context via @mentions in prompt.
     * Used by ContextPanel for live preview of attached context.
     */
    fun updatePendingContextRefs(refs: List<ContextReference>) {
        promptStateTracker.updatePendingContextRefs(refs)
    }

    /**
     * Update pending user input text (live prompt preview).
     * Called by PromptInputPanel whenever text changes.
     */
    fun updatePendingUserInput(text: String) {
        promptStateTracker.updatePendingUserInput(text)
    }

    /**
     * Update context section tokens (called by ContextPanel after refresh).
     * StatusBar observes this StateFlow to display section colors.
     */
    fun updateContextSectionTokens(
        sections: Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>,
        totalTokens: Int = 0
    ) {
        promptStateTracker.updateContextSectionTokens(sections, totalTokens)
    }

    /**
     * Clear pending context refs (called after message is sent).
     */
    fun clearPendingContextRefs() {
        promptStateTracker.clearPendingContextRefs()

        // Note: pending_context_json is deprecated (see docs/0042-chat-context.md)
        // Context is now stored in chat_messages.metadata, not in tasks table
    }

    fun clearPendingUserInput() {
        promptStateTracker.clearPendingUserInput()
    }

    /**
     * Cached max context window for current session's model. Reads StateFlow value —
     * instant, EDT-safe. Refreshed in background on session/model change and via
     * [refreshMaxContextWindow]. Use [maxContextWindow] flow to react to changes.
     */
    fun getMaxContextWindow(): Int = _maxContextWindow.value

    /**
     * Force a background refresh of [maxContextWindow]. Call after MAX_CONTEXT_SIZE
     * config is mutated (settings save, yaml reload, reset) so the cached value
     * reflects the new limit. Safe to call from EDT.
     */
    fun refreshMaxContextWindow() {
        cs.launch {
            try {
                _maxContextWindow.value = lifecycleService.getMaxContextWindow()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh max context window" }
            }
        }
    }

    /**
     * Set paused state for execution.
     * When paused, step execution will wait after completing current step.
     */
    fun setPaused(paused: Boolean) {
        executionMonitor.setPaused(paused)
    }

    /**
     * Dispose resources.
     */
    fun dispose() {
        cs.cancel()
    }

    /**
     * Cancel active execution (orchestration or auto execution).
     *
     * Delegates to StepExecutionService which manages the execution job.
     */
    fun cancelExecution() {
        executionMonitor.cancelExecution()
    }

    fun cancelStreaming() {
        executionMonitor.cancelStreaming()
    }

    /**
     * Request conversation summary generation for the active session.
     */
    suspend fun generateSummary() {
        val currentSession = stateManager.getActiveSession()
            ?: throw IllegalStateException("No active session to summarize")
        var streamingMessageId: String? = null
        val streamCallback: pl.jclab.refio.core.api.StreamCallback = { chunk ->
            cs.launch {
                val summaryText = chunk.accumulated.trim()
                if (summaryText.isNotBlank()) {
                    if (streamingMessageId == null) {
                        streamingMessageId = UUID.randomUUID().toString()
                        val message = Message(
                            id = streamingMessageId!!,
                            taskId = currentSession.id,
                            role = "assistant",
                            content = summaryText,
                            isStreaming = true,
                            createdAt = System.currentTimeMillis()
                        )
                        stateManager.appendMessage(message)
                    } else {
                        stateManager.updateMessages { messages ->
                            messages.map { msg ->
                                if (msg.id == streamingMessageId) {
                                    msg.copy(
                                        content = summaryText,
                                        lastChunkAt = System.currentTimeMillis(),
                                        isStreaming = !chunk.isComplete
                                    )
                                } else msg
                            }
                        }
                    }
                }

                if (chunk.isComplete && streamingMessageId != null) {
                    val completedId = streamingMessageId
                    streamingMessageId = null
                    stateManager.updateMessages { messages ->
                        messages.map { msg ->
                            if (msg.id == completedId) {
                                msg.copy(isStreaming = false, lastChunkAt = System.currentTimeMillis())
                            } else msg
                        }
                    }
                }
            }
        }

        try {
            logger.info { "Requesting conversation summary for task ${currentSession.id}" }
            GlobalMetrics.setCurrentOperation(OperationInfo.SummarizingConversation(currentSession.id))
            stateManager.setIsGenerating(true)
            projectRouter.chatRouter.summarizeConversation(currentSession.id, streamCallback)
            messageDispatcher.loadMessages()
            logger.info { "Conversation summary stored for task ${currentSession.id}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to summarize conversation for task ${currentSession.id}" }
            throw e
        } finally {
            if (streamingMessageId != null) {
                val cleanupId = streamingMessageId
                streamingMessageId = null
                stateManager.updateMessages { messages ->
                    messages.filterNot { it.id == cleanupId }
                }
            }
            stateManager.setIsGenerating(false)
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
        }
    }

    suspend fun approveSubtask(subtaskId: String) {
        subtaskTracker.approveSubtask(subtaskId)
    }

    suspend fun skipSubtask(subtaskId: String) {
        subtaskTracker.skipSubtask(subtaskId)
    }

    suspend fun moveStepUp(subtaskId: String) {
        subtaskTracker.moveStepUp(subtaskId)
    }

    suspend fun moveStepDown(subtaskId: String) {
        subtaskTracker.moveStepDown(subtaskId)
    }

    suspend fun deleteStep(subtaskId: String) {
        subtaskTracker.deleteStep(subtaskId)
    }

    suspend fun prepareNextStep(): pl.jclab.refio.core.api.PlanStepResponse? {
        return subtaskTracker.prepareNextStep()
    }

    suspend fun executeCurrentStep(subtaskId: String): pl.jclab.refio.core.api.ExecuteStepResponse? {
        return executionMonitor.executeCurrentStep(subtaskId)
    }

    suspend fun executeSubtaskById(subtaskId: String) {
        subtaskTracker.executeSubtaskById(subtaskId)
    }


    suspend fun cancelAllPendingSteps() {
        subtaskTracker.cancelAllPendingSteps()
    }

    companion object {
        fun getInstance(project: Project): SessionManager {
            return project.getService(SessionManager::class.java)
        }
    }
}





