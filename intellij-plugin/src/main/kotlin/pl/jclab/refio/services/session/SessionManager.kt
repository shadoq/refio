package pl.jclab.refio.services.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.ktor.util.reflect.*
import pl.jclab.refio.api.models.*
import pl.jclab.refio.core.api.UIAdapter
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.ui.components.toolbar.StatusBar
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.ui.listeners.SwingWorkflowListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.reflect.typeOf

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
    private val chatMessageRepository = ChatMessageRepository()

    // PHASE 2 FIX: Track tool call message IDs for real-time streaming updates
    // Maps toolCallId -> temporary message ID in stateManager
    private val toolCallMessageIds = ConcurrentHashMap<String, String>()
    val activeSession: StateFlow<Session?> = stateManager.activeSession
    val sessions: StateFlow<List<Session>> = stateManager.sessions
    val messages: StateFlow<List<Message>> = stateManager.messages
    val subtasks: StateFlow<List<SubtaskDto>> = stateManager.subtasks
    val activePlan: StateFlow<pl.jclab.refio.core.api.PlanResponse?> = stateManager.activePlan
    val planSteps: StateFlow<List<pl.jclab.refio.core.api.PlanSpecStepResponse>> = stateManager.planSteps
    val selectedModel: StateFlow<String> = stateManager.selectedModel
    val thinkingEnabled: StateFlow<Boolean> = stateManager.thinkingEnabled
    val noEgressEnabled: StateFlow<Boolean> = stateManager.noEgressEnabled
    val multiAgentEnabled: StateFlow<Boolean> = stateManager.multiAgentEnabled
    val multiAgentStrategy: StateFlow<pl.jclab.refio.api.models.MultiAgentStrategy> = stateManager.multiAgentStrategy

    val isPaused: StateFlow<Boolean> = stateManager.isPaused
    val isGenerating: StateFlow<Boolean> = stateManager.isGenerating
    val pendingContextRefs: StateFlow<List<ContextReference>> = stateManager.pendingContextRefs
    val pendingUserInput: StateFlow<String> = stateManager.pendingUserInput
    val contextSectionTokens: StateFlow<Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>> =
        stateManager.contextSectionTokens
    val totalEstimatedTokens: StateFlow<Int> = stateManager.totalEstimatedTokens

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

    private val coreApiClient: pl.jclab.refio.api.CoreApiClient by lazy {
        pl.jclab.refio.api.CoreApiClient(projectRouter)
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
    }

    private fun initializeServices() {
        executionMonitor = ExecutionMonitor(
            project = project,
            projectRouter = projectRouter,
            stateManager = stateManager,
            stepExecutionService = stepExecutionService,
            scope = cs,
            loadMessages = { messageDispatcher.loadMessages() },
            loadSubtasks = { subtaskTracker.loadSubtasks() },
            prepareNextStep = { subtaskTracker.prepareNextStep() }
        )

        subtaskTracker = SubtaskTracker(
            project = project,
            projectRouter = projectRouter,
            coreApiClient = coreApiClient,
            stateManager = stateManager,
            loadMessages = { messageDispatcher.loadMessages() },
            executeCurrentStep = { subtaskId -> executionMonitor.executeCurrentStep(subtaskId) },
            showApprovalMessageForNextSubtask = { executionMonitor.showApprovalMessageForNextSubtask() }
        )

        messageDispatcher = MessageDispatcher(
            projectRouter = projectRouter,
            stateManager = stateManager
        )

        promptStateTracker = PromptStateTracker(stateManager)

        lifecycleService = SessionLifecycleService(
            project = project,
            projectRouter = projectRouter,
            coreApiClient = coreApiClient,
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = modeSwitchMutex,
            projectId = projectId,
            normalizedProjectPath = normalizedProjectPath,
            scope = cs
        )

        lifecycleService.initialize(messageDispatcher, subtaskTracker, executionMonitor)

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
    ): Message {
        GlobalMetrics.resetCancellation()

        val currentSession = modeSwitchMutex.withLock {
            lifecycleService.ensureActiveSessionExists()
        }

        logger.info {
            "[SESSION] sendMessage: taskId=${currentSession.id}, mode=${currentSession.mode}, " +
                    "executionMode=${currentSession.executionMode}, inputChars=${input.length}, " +
                    "contextRefs=${contextRefs.size}, model=${model ?: "auto"}, provider=${provider ?: "auto"}"
        }
        return sendMessageUsingWorkflow(currentSession, input, contextRefs, model, provider)
    }

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
        projectRouter.deleteSnapshotsByTaskId(session.id)

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

    /**
     * Set multi-agent mode enabled/disabled.
     * Auto-saves UI state to database.
     */
    fun setMultiAgentEnabled(enabled: Boolean) {
        lifecycleService.setMultiAgentEnabled(enabled)
    }

    fun setMultiAgentStrategy(strategy: pl.jclab.refio.api.models.MultiAgentStrategy) {
        lifecycleService.setMultiAgentStrategy(strategy)
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
     * Get max context window for current session's model.
     * Returns MINIMUM of model's maxContext and configured max_context_size limit.
     *
     * @return Max context window in tokens (fallback: 8192)
     */
    fun getMaxContextWindow(): Int {
        return lifecycleService.getMaxContextWindow()
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

    private suspend fun sendMessageUsingWorkflow(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?
    ): Message {
        stateManager.setIsGenerating(true)
        return try {
            val stream = isStreamingEnabled()
            val executionMode = session.executionMode
            logger.info {
                "[SESSION] Workflow start: taskId=${session.id}, mode=${session.mode}, " +
                        "executionMode=$executionMode, stream=$stream"
            }

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "user",
                content = input,
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(userMessage)

            // Use AgentTurnLoop for PLAN/AGENT modes instead of WorkflowOrchestrator
            when (session.mode) {
                TaskMode.CHAT -> {
                    sendMessageUsingChatWorkflow(session, input, contextRefs, model, provider, stream, executionMode)
                }

                TaskMode.PLAN,
                TaskMode.AGENT -> {
                    sendMessageUsingTurnLoop(session, input, contextRefs, model, provider, stream, executionMode)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[SESSION] Workflow failed: taskId=${session.id}, error=${e.message}" }
            uiAdapter.showError("Workflow failed: ${e.message}")
            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "system",
                content = "Error: ${e.message}",
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(errorMessage)
            throw e
        } finally {
            stateManager.setIsGenerating(false)
        }
    }

    /**
     * Send message using new AgentTurnLoop for PLAN/AGENT modes.
     * Implements Codex CLI-style turn loop where model self-directs tool usage.
     */
    private suspend fun sendMessageUsingTurnLoop(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?,
        stream: Boolean,
        executionMode: pl.jclab.refio.api.models.ExecutionMode
    ): Message {
        logger.info {
            "[TURN_LOOP] Starting turn: taskId=${session.id}, mode=${session.mode}, " +
                    "inputChars=${input.length}, contextRefs=${contextRefs.size}"
        }

        GlobalMetrics.setCurrentOperation(
            OperationInfo.ChatRequest(model ?: "auto")
        )

        try {
            // Create streaming message for UI updates
            var streamingMessageId: String? = null
            val streamingClosed = AtomicBoolean(false)
            val pendingStreamContent = AtomicReference<String?>(null)
            val streamStateMutex = Mutex()
            var streamUiFlushJob: Job? = null
            val streamFilter = IncrementalToolCallStreamFilter()

            // Create stream callback for UI updates
            // Filter TOOL_CALL blocks from streaming content for cleaner display
            val streamCallback: pl.jclab.refio.core.api.StreamCallback? = if (stream) { chunk ->
                cs.launch {
                    if (streamingClosed.get()) return@launch

                    streamStateMutex.withLock {
                        val now = System.currentTimeMillis()
                        val filteredContent = streamFilter.filter(
                            delta = chunk.delta,
                            accumulated = chunk.accumulated,
                            isComplete = chunk.isComplete
                        )

                        if (filteredContent.isNotBlank()) {
                            if (streamingMessageId == null) {
                                streamingMessageId = UUID.randomUUID().toString()
                                stateManager.appendMessage(
                                    Message(
                                        id = streamingMessageId!!,
                                        taskId = session.id,
                                        role = "assistant",
                                        content = "",
                                        isStreaming = true,
                                        streamStartedAt = now,
                                        createdAt = now
                                    )
                                )
                            }
                            pendingStreamContent.set(filteredContent)
                        }

                        if (chunk.isComplete) {
                            val completedId = streamingMessageId
                            val finalContent = pendingStreamContent.getAndSet(null)
                            if (completedId != null) {
                                stateManager.updateMessages { messages ->
                                    messages.map { msg ->
                                        if (msg.id == completedId) {
                                            msg.copy(
                                                content = finalContent ?: msg.content,
                                                lastChunkAt = now,
                                                isStreaming = false
                                            )
                                        } else msg
                                    }
                                }
                            }
                            streamingMessageId = null
                            streamUiFlushJob?.cancel()
                            streamUiFlushJob = null
                            return@withLock
                        }

                        if (streamUiFlushJob?.isActive != true) {
                            streamUiFlushJob = cs.launch {
                                while (!streamingClosed.get()) {
                                    val contentToFlush = streamStateMutex.withLock {
                                        pendingStreamContent.getAndSet(null)
                                    }

                                    if (!contentToFlush.isNullOrBlank()) {
                                        val activeMessageId = streamingMessageId
                                        if (activeMessageId != null) {
                                            stateManager.updateMessages { messages ->
                                                messages.map { msg ->
                                                    if (msg.id == activeMessageId) {
                                                        msg.copy(
                                                            content = contentToFlush,
                                                            lastChunkAt = System.currentTimeMillis(),
                                                            isStreaming = true
                                                        )
                                                    } else msg
                                                }
                                            }
                                        }
                                    }

                                    delay(500)
                                }
                            }
                        }
                    }
                }
            } else null

            // Create turn event listener for UI updates
            val turnListener = object : pl.jclab.refio.core.services.AgentTurnLoop.TurnEventListener {
                override fun onTurnStarted(
                    taskId: String,
                    mode: pl.jclab.refio.core.db.TaskMode,
                    runId: String,
                    parentRunId: String?,
                    depth: Int
                ) {
                    logger.info {
                        "[TURN_LOOP] Turn started: taskId=$taskId, mode=$mode, runId=$runId, " +
                            "parentRunId=${parentRunId ?: "-"}, depth=$depth"
                    }
                }

                override fun onToolExecutionStarted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData) {
                    logger.info { "[TURN_LOOP] Tool started: ${toolCall.name}" }
                    cs.launch {
                        subtaskTracker.loadSubtasks()

                        // PHASE 2 FIX: Create temporary message for real-time UI updates
                        // This message will be replaced by loadMessages() at turn end with the DB version
                        val toolInfo = ToolCallDisplayInfo(
                            toolName = toolCall.name,
                            toolCallId = toolCall.id,
                            displayType = resolveToolDisplayType(toolCall.name),
                            parameters = parseToolParameters(toolCall.arguments),
                            status = ToolCallStatus.EXECUTING
                        )

                        val tempMessage = Message(
                            id = "temp-${toolCall.id}", // Temporary ID - will be replaced by DB version
                            taskId = taskId,
                            role = "assistant",
                            content = "",
                            toolCallInfo = toolInfo,
                            createdAt = System.currentTimeMillis()
                        )

                        stateManager.appendMessage(tempMessage)
                        toolCallMessageIds[toolCall.id] = tempMessage.id
                        logger.debug { "[TURN_LOOP] Created temp message for tool ${toolCall.name}: tempId=${tempMessage.id}" }
                    }
                }

                override fun onToolStreamChunk(
                    taskId: String,
                    toolCallId: String,
                    delta: String,
                    accumulated: String
                ) {
                    // PHASE 2 FIX: Update temporary message with streaming content
                    val messageId = toolCallMessageIds[toolCallId]
                    if (messageId == null) {
                        logger.warn { "[TURN_LOOP] Tool stream chunk for unknown tool call: $toolCallId" }
                        return
                    }

                    cs.launch {
                        stateManager.updateMessages { messages ->
                            messages.map { msg ->
                                if (msg.id == messageId) {
                                    msg.copy(
                                        content = accumulated,
                                        isStreaming = true,
                                        isToolStreaming = true,
                                        lastChunkAt = System.currentTimeMillis()
                                    )
                                } else msg
                            }
                        }
                    }
                }

                override fun onToolExecutionCompleted(
                    taskId: String,
                    toolCall: pl.jclab.refio.core.db.ToolCallData,
                    result: String,
                    success: Boolean
                ) {
                    logger.info { "[TURN_LOOP] Tool completed: ${toolCall.name}, success=$success" }
                    cs.launch {
                        subtaskTracker.loadSubtasks()

                        // PHASE 2 FIX: Update temporary message status and result
                        val messageId = toolCallMessageIds[toolCall.id]
                        if (messageId != null) {
                            val resultSummary = if (result.isNotBlank()) {
                                val trimmed = result.trim()
                                if (trimmed.length <= 120) trimmed
                                else "${trimmed.take(120)}..."
                            } else null
                            stateManager.updateMessages { messages ->
                                messages.map { msg ->
                                    if (msg.id == messageId) {
                                        val updatedToolInfo = msg.toolCallInfo?.copy(
                                            status = if (success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                                            result = if (resultSummary != null) ToolCallResult(
                                                success = success,
                                                summary = resultSummary
                                            ) else null
                                        )
                                        msg.copy(
                                            toolCallInfo = updatedToolInfo,
                                            isStreaming = false,
                                            isToolStreaming = false,
                                            lastChunkAt = System.currentTimeMillis()
                                        )
                                    } else msg
                                }
                            }
                            // Remove from tracking map
                            toolCallMessageIds.remove(toolCall.id)
                            logger.debug { "[TURN_LOOP] Finalized temp message for tool ${toolCall.name}: tempId=$messageId" }
                        }

                        // NOTE: AgentTurnLoop creates TOOL message with result in DB
                        // MessageDispatcher.loadMessages() at turn end will replace temp messages with DB versions
                    }
                }

                override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
                    // Handled by streamCallback
                }

                override fun onTurnCompleted(
                    taskId: String,
                    result: pl.jclab.refio.core.services.TurnResult,
                    runId: String,
                    parentRunId: String?,
                    depth: Int
                ) {
                    logger.info {
                        "[TURN_LOOP] Turn completed: taskId=$taskId, success=${result.success}, " +
                                "iterations=${result.iterations}, runId=$runId, " +
                                "parentRunId=${parentRunId ?: "-"}, depth=$depth"
                    }
                }
            }

            val modeDb = pl.jclab.refio.core.db.TaskMode.valueOf(session.mode.name)
            val executionModeDb = pl.jclab.refio.core.db.ExecutionMode.valueOf(executionMode.name)
            val defaultTurnRequest = pl.jclab.refio.core.api.TurnRequest(
                taskId = session.id,
                userInput = input,
                mode = modeDb,
                executionMode = executionModeDb,
                model = model,
                provider = provider,
                userContextRefs = contextRefs
            )

            val subagentRouter = projectRouter.subagentRouter
            val subagentCommand = subagentRouter?.parseSubagentCommand(input)
            val subagentInvocation = subagentRouter?.parseSubagentInvocation(input)

            if (subagentCommand != null && subagentInvocation == null) {
                val (requestedName, _) = subagentCommand
                val allSubagents = subagentRouter.listSubagents(includeDisabled = true)
                val matched = allSubagents.firstOrNull { it.name.equals(requestedName, ignoreCase = true) }
                val enabledSubagentNames = allSubagents
                    .filter { it.enabled }
                    .map { it.name }
                    .sorted()

                val errorContent = when {
                    matched == null -> buildString {
                        append("Subagent '")
                        append(requestedName)
                        append("' not found.")
                        if (enabledSubagentNames.isNotEmpty()) {
                            append(" Available subagents: ")
                            append(enabledSubagentNames.joinToString(", "))
                            append(".")
                        }
                    }
                    !matched.enabled -> "Subagent '${matched.name}' is disabled. Enable it in Settings > Subagents."
                    else -> "Subagent '$requestedName' is not available."
                }

                logger.warn {
                    "[TURN_LOOP] Invalid subagent invocation: name=$requestedName, reason='${errorContent.replace('\n', ' ')}'"
                }

                val assistantMessage = Message(
                    id = UUID.randomUUID().toString(),
                    taskId = session.id,
                    role = "assistant",
                    content = errorContent,
                    createdAt = System.currentTimeMillis()
                )
                stateManager.appendMessage(assistantMessage)
                return assistantMessage
            }

            val turnRequest = if (subagentInvocation != null) {
                val (subagentName, subagentPrompt) = subagentInvocation
                val definition = subagentRouter.getSubagent(subagentName)

                if (definition != null) {
                    val parentModel = if (model != null && provider != null) "$provider/$model" else model
                    val (resolvedModel, resolvedProvider) = definition.resolveModel(configService, parentModel)

                    logger.info {
                        "[TURN_LOOP] subagentDetected=true, subagentName=$subagentName, " +
                            "runProfile=SUBAGENT, model=$resolvedModel, provider=$resolvedProvider"
                    }

                    pl.jclab.refio.core.api.TurnRequest(
                        taskId = session.id,
                        userInput = subagentPrompt,
                        mode = modeDb,
                        executionMode = executionModeDb,
                        model = resolvedModel,
                        provider = resolvedProvider,
                        userContextRefs = contextRefs,
                        runProfile = pl.jclab.refio.core.api.TurnRunProfile.SUBAGENT,
                        profileOverrides = pl.jclab.refio.core.api.TurnProfileOverrides(
                            subagentName = subagentName,
                            systemPromptOverride = definition.systemPrompt,
                            allowedTools = definition.allowedTools,
                            disallowedTools = definition.disallowedTools,
                            modelOverride = resolvedModel,
                            providerOverride = resolvedProvider,
                            maxIterationsOverride = definition.maxSteps,
                            depth = 0,
                            subagentChain = emptyList(),
                            contextProfile = definition.contextProfile
                        )
                    )
                } else {
                    logger.warn {
                        "[TURN_LOOP] subagentDetected=true but definition not found: name=$subagentName, falling back"
                    }
                    defaultTurnRequest
                }
            } else {
                defaultTurnRequest
            }

            // Execute turn using AgentTurnLoop
            val result = projectRouter.agentRouter.runTurn(
                request = turnRequest,
                streamCallback = streamCallback,
                listener = turnListener
            )

            logger.info {
                "[TURN_LOOP] Turn complete: taskId=${session.id}, success=${result.success}, " +
                        "iterations=${result.iterations}, responseChars=${result.response.length}"
            }

            streamingClosed.set(true)
            streamUiFlushJob?.cancel()
            val completedStreamingMessageId = streamingMessageId
            streamingMessageId = null
            if (completedStreamingMessageId != null) {
                stateManager.updateMessages { messages ->
                    messages.filterNot { it.id == completedStreamingMessageId }
                }
            }

            // Reload messages from database (includes all tool calls and results)
            messageDispatcher.loadMessages()

            // PHASE 2 FIX: Clear temporary message IDs after DB reload
            toolCallMessageIds.clear()
            logger.debug { "[TURN_LOOP] Cleared tool call message tracking map after DB reload" }

            // Update session costs
            val freshTask = pl.jclab.refio.core.db.repositories.TaskRepository().findById(session.id)
            if (freshTask != null) {
                val updatedSession = session.copy(
                    tokensIn = freshTask.tokensIn,
                    tokensOut = freshTask.tokensOut,
                    costUsd = freshTask.costUsd
                )
                updateSession(updatedSession)
            }

            // Auto-name session if needed
            if (isDefaultSessionName(session.name) && stateManager.messages.value.size >= 2) {
                scheduleAutoNameSession(session, input)
            }

        return stateManager.messages.value.last()
        } finally {
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
            logger.info { "[TURN_LOOP] Operation state reset to Idle" }
        }
    }

    /**
     * Send message using existing WorkflowOrchestrator for CHAT mode.
     * CHAT mode has no tools - direct LLM conversation.
     */
    private suspend fun sendMessageUsingChatWorkflow(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?,
        stream: Boolean,
        executionMode: pl.jclab.refio.api.models.ExecutionMode
    ): Message {
        logger.info {
            "[CHAT_WORKFLOW] Starting chat: taskId=${session.id}, inputChars=${input.length}"
        }

        val uiState = UIState(
            taskId = session.id,
            mode = session.mode,
            executionMode = executionMode,
            input = input,
            contextRefs = contextRefs,
            model = model,
            provider = provider,
            streamingEnabled = stream,
            thinkingEnabled = stateManager.getThinkingEnabled(),
            noEgressEnabled = stateManager.getNoEgressEnabled()
        )

        val listener = SwingWorkflowListener(
            taskId = session.id,
            stateManager = stateManager,
            scope = cs,
            streamingEnabled = stream
        )

        // Generate project analysis summary for intent classification (if enabled)
        val projectAnalysis = try {
            projectRouter.projectContextRouter.getProjectAnalysisSummary()
        } catch (e: Exception) {
            logger.warn(e) { "[SESSION] Failed to generate project analysis, using null" }
            null
        }

        val result = projectRouter.workflowOrchestrator.execute(
            request = WorkflowRequest(
                uiState = uiState,
                projectAnalysis = projectAnalysis
            ),
            listener = listener
        )

        logger.info { "[CHAT_WORKFLOW] Workflow result: taskId=${session.id}, type=${result::class.simpleName}" }

        when (result) {
            is IntentResult.ChatResult -> {
                val response = result.response
                logger.info {
                    "[CHAT_WORKFLOW] Chat response: taskId=${response.taskId}, outputChars=${response.output.length}"
                }
                if (response.taskId != session.id) {
                    logger.info { "[CHAT] Task ID changed: ${session.id} -> ${response.taskId}, syncing session" }
                    uiAdapter.log("INFO", "Session ID changed: ${session.id} -> ${response.taskId}")
                    val newSession = session.copy(id = response.taskId)
                    stateManager.setActiveSession(newSession)
                }
                updateSessionCosts(stateManager.getActiveSession() ?: session)
                autoNameSessionIfNeeded(stateManager.getActiveSession() ?: session, input)
                messageDispatcher.loadMessages()
            }

            is IntentResult.SubagentResult -> {
                logger.info { "[CHAT_WORKFLOW] Subagent response: taskId=${session.id}" }
                messageDispatcher.loadMessages()
            }

            else -> {
                logger.warn { "[CHAT_WORKFLOW] Unexpected result type in CHAT mode: ${result::class.simpleName}" }
            }
        }

        return stateManager.messages.value.last()
    }

    private fun isStreamingEnabled(): Boolean {
        return try {
            val streamingConfig = projectRouter.configService.get(
                key = pl.jclab.refio.core.services.ConfigService.KEY_STREAMING_ENABLED,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
            streamingConfig?.toBoolean() ?: true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read streaming config, defaulting to true" }
            true
        }
    }

    private suspend fun updateSessionCosts(session: Session) {
        val freshTask = pl.jclab.refio.core.db.repositories.TaskRepository().findById(session.id)

        if (freshTask != null) {
            updateSession(
                session.copy(
                    tokensIn = freshTask.tokensIn,
                    tokensOut = freshTask.tokensOut,
                    costUsd = freshTask.costUsd
                )
            )
        }
    }

    private fun isDefaultSessionName(name: String): Boolean {
        return name == "New Session" || name.matches(Regex("^Session \\(.+\\)$"))
    }

    private suspend fun autoNameSessionIfNeeded(session: Session, input: String) {
        if (isDefaultSessionName(session.name) && stateManager.messages.value.size == 2) {
            scheduleAutoNameSession(session, input)
        }
    }

    private fun scheduleAutoNameSession(session: Session, input: String) {
        if (!isDefaultSessionName(session.name)) return

        cs.launch {
            try {
                val rawTitle = projectRouter.chatRouter.generateSessionTitle(session.id, input)
                val generatedName = sanitizeSessionTitle(rawTitle)
                    .ifBlank { generateSessionNameFallback(input) }

                projectRouter.taskRouter.updateTask(session.id, pl.jclab.refio.core.api.UpdateTaskRequest(name = generatedName))
                updateSession(
                    stateManager.getActiveSession()?.copy(name = generatedName)
                        ?: return@launch
                )
                logger.info { "Auto-named: '$generatedName'" }
            } catch (e: Exception) {
                val fallback = generateSessionNameFallback(input)
                try {
                    projectRouter.taskRouter.updateTask(session.id, pl.jclab.refio.core.api.UpdateTaskRequest(name = fallback))
                    updateSession(
                        stateManager.getActiveSession()?.copy(name = fallback)
                            ?: return@launch
                    )
                    logger.info { "Auto-named with fallback: '$fallback'" }
                } catch (inner: Exception) {
                    logger.warn(inner) { "Auto-name failed" }
                }
            }
        }
    }

    private fun sanitizeSessionTitle(raw: String): String {
        return raw
            .trim()
            .trim('"', '\'', '“', '”')
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[.!?:;]+$"), "")
            .take(60)
    }

    private fun generateSessionNameFallback(input: String): String {
        val cleaned = input
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\r\\n]+"), " ")

        val firstSentence = cleaned.split(Regex("[.!?]\\s+")).firstOrNull() ?: cleaned
        val truncated = if (firstSentence.length > 50) {
            firstSentence.substring(0, 47) + "..."
        } else {
            firstSentence
        }

        return truncated.ifBlank { "Chat" }
    }
    private fun resolveToolDisplayType(toolName: String): ToolDisplayType {
        return when (toolName) {
            "advance_code_editing", "multi_line_editor" -> ToolDisplayType.LLM_EDIT
            "code_editing", "create_new_file", "multi_edit" -> ToolDisplayType.CODE_EDIT
            "run_terminal_command" -> ToolDisplayType.TERMINAL
            else -> ToolDisplayType.SIMPLE
        }
    }

    private fun parseToolParameters(argumentsJson: String): Map<String, String> {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val args = json.parseToJsonElement(argumentsJson)
            val argsObj = args as? kotlinx.serialization.json.JsonObject ?: return emptyMap()

            argsObj.entries.associate { (key, value) ->
                key to when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool arguments" }
            emptyMap()
        }
    }


    /**
     * Build display text for tool call bubble (assistant role).
     * Shows what tool is being called with key parameters.
     */
    private fun buildToolCallDisplay(toolName: String, argumentsJson: String): String {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val args = json.parseToJsonElement(argumentsJson)
            val argsObj = args as? kotlinx.serialization.json.JsonObject ?: return "📤 **$toolName**"

            when (toolName) {
                "advance_code_editing", "multi_line_editor" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val description = argsObj["edit_description"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    val shortDesc = description?.take(80)?.let { if (description.length > 80) "$it..." else it }
                    buildString {
                        append("📤 **$toolName**\n")
                        append("```\npath: $path")
                        if (shortDesc != null) {
                            append("\nedit_description: $shortDesc")
                        }
                        append("\n```")
                    }
                }

                "code_editing" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val oldString = argsObj["old_string"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }?.take(50)?.let { if (it.length >= 50) "$it..." else it }
                    buildString {
                        append("📤 **$toolName**\n")
                        append("```\npath: $path")
                        if (oldString != null) {
                            append("\nold_string: $oldString")
                        }
                        append("\n```")
                    }
                }

                "create_new_file" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    "📤 **$toolName**\n```\npath: $path\n```"
                }

                "read_file" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    "📤 **$toolName**\n```\npath: $path\n```"
                }

                "read_directory" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "."
                    "📤 **$toolName**\n```\npath: $path\n```"
                }

                "file_search" -> {
                    val pattern = argsObj["pattern"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "*"
                    "📤 **$toolName**\n```\npattern: $pattern\n```"
                }

                "grep_search" -> {
                    val pattern = argsObj["pattern"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: ""
                    val shortPattern = pattern.take(60).let { if (pattern.length > 60) "$it..." else it }
                    "📤 **$toolName**\n```\npattern: $shortPattern\n```"
                }

                else -> "📤 **$toolName**"
            }
        } catch (e: Exception) {
            logger.warn(e) { "[TURN_LOOP] Failed to parse tool arguments for display" }
            "📤 **$toolName**"
        }
    }

    /**
     * Build a user-friendly summary for tool execution display.
     * Parses tool arguments to extract meaningful info (path, description, etc.)
     */
    private fun buildToolExecutionSummary(toolName: String, argumentsJson: String): String {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val args = json.parseToJsonElement(argumentsJson)
            val argsObj = args as? kotlinx.serialization.json.JsonObject ?: return "🔧 Executing: $toolName"

            when (toolName) {
                "advance_code_editing", "multi_line_editor" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val description = argsObj["edit_description"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    val shortPath = path.substringAfterLast("/").substringAfterLast("\\")
                    val shortDesc = description?.take(100)?.let { if (description.length > 100) "$it..." else it }
                    buildString {
                        append("🔧 Executing: **$toolName**\n")
                        append("📄 File: `$shortPath`\n")
                        if (shortDesc != null) {
                            append("📝 $shortDesc")
                        }
                    }
                }

                "code_editing" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val shortPath = path.substringAfterLast("/").substringAfterLast("\\")
                    "🔧 Executing: **$toolName**\n📄 File: `$shortPath`"
                }

                "create_new_file" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val shortPath = path.substringAfterLast("/").substringAfterLast("\\")
                    "🔧 Executing: **$toolName**\n📄 Creating: `$shortPath`"
                }

                "read_file" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "unknown"
                    val shortPath = path.substringAfterLast("/").substringAfterLast("\\")
                    "🔧 Executing: **$toolName**\n📄 Reading: `$shortPath`"
                }

                "read_directory" -> {
                    val path = argsObj["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "."
                    "🔧 Executing: **$toolName**\n📁 Directory: `$path`"
                }

                "file_search" -> {
                    val pattern = argsObj["pattern"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "*"
                    "🔧 Executing: **$toolName**\n🔍 Pattern: `$pattern`"
                }

                "grep_search" -> {
                    val pattern = argsObj["pattern"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: ""
                    val shortPattern = pattern.take(50).let { if (pattern.length > 50) "$it..." else it }
                    "🔧 Executing: **$toolName**\n🔍 Pattern: `$shortPattern`"
                }

                else -> "🔧 Executing: **$toolName**"
            }
        } catch (e: Exception) {
            logger.warn(e) { "[TURN_LOOP] Failed to parse tool arguments for summary" }
            "🔧 Executing: $toolName"
        }
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





