package pl.jclab.refio.services.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.ktor.util.reflect.*
import pl.jclab.refio.api.models.*
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.components.toolbar.StatusBar
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.ui.listeners.SwingWorkflowListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.reflect.typeOf

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val logger = dualLogger("SessionManager")

    // Use proper coroutine scope for Project-level service with error handler
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Unhandled exception in SessionManager coroutine" }

        // Show error message in chat
        val errorMessage = Message(
            id = UUID.randomUUID().toString(),
            taskId = (stateManager.getActiveSession()?.id ?: "unknown"),
            role = "system",
            content = "Error: ${exception.message}\n\nStack trace: ${exception.stackTraceToString()}",
            createdAt = System.currentTimeMillis()
        )
        CoroutineScope(Dispatchers.IO).launch {
            stateManager.appendMessage(errorMessage)
        }
    }

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val stateManager = SessionStateManager()
    val activeSession: StateFlow<Session?> = stateManager.activeSession
    val sessions: StateFlow<List<Session>> = stateManager.sessions
    val messages: StateFlow<List<Message>> = stateManager.messages
    val subtasks: StateFlow<List<SubtaskDto>> = stateManager.subtasks
    val activePlan: StateFlow<pl.jclab.refio.core.api.PlanResponse?> = stateManager.activePlan
    val planSteps: StateFlow<List<pl.jclab.refio.core.api.PlanSpecStepResponse>> = stateManager.planSteps
    val selectedModel: StateFlow<String> = stateManager.selectedModel
    val thinkingEnabled: StateFlow<Boolean> = stateManager.thinkingEnabled
    val noEgressEnabled: StateFlow<Boolean> = stateManager.noEgressEnabled
    val orchestrationEnabled: StateFlow<Boolean> = stateManager.orchestrationEnabled
    val intentClassificationEnabled: StateFlow<Boolean> = stateManager.intentClassificationEnabled
    val isPaused: StateFlow<Boolean> = stateManager.isPaused
    val pendingContextRefs: StateFlow<List<ContextReference>> = stateManager.pendingContextRefs
    val pendingUserInput: StateFlow<String> = stateManager.pendingUserInput
    val contextSectionTokens: StateFlow<Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>> =
        stateManager.contextSectionTokens
    val totalEstimatedTokens: StateFlow<Int> = stateManager.totalEstimatedTokens

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
        project.basePath ?: System.getProperty("user.dir")
    }
    private val normalizedProjectPath: String by lazy {
        java.nio.file.Paths.get(projectBasePath).toAbsolutePath().normalize().toString()
    }
    private val projectId: String by lazy {
        ProjectIdGenerator.generate(java.nio.file.Paths.get(projectBasePath))
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

    private val configService: pl.jclab.refio.core.services.ConfigService by lazy {
        projectRouter.getConfigService()
    }

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
    fun createSession(name: String, mode: TaskMode, executionMode: ExecutionMode? = null): Session {
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
        pl.jclab.refio.core.services.monitoring.GlobalMetrics.resetCancellation()

        if (stateManager.getActiveSession() == null) {
            lifecycleService.createSession("New Session", lifecycleService.getSelectedMode())
        }

        val currentSession = modeSwitchMutex.withLock {
            stateManager.getActiveSession() ?: throw IllegalStateException("Session should exist after creation")
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
        if (session.mode == pl.jclab.refio.api.models.TaskMode.PLAN) {
            projectRouter.planRouter.deleteAllPlansForSession(session.id)
            stateManager.setActivePlan(null)
            stateManager.setPlanSteps(emptyList())
        }

        // 4) Refresh UI state
        messageDispatcher.loadMessages()
        subtaskTracker.loadSubtasks()
        loadPlan()

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
        if (session.mode != pl.jclab.refio.api.models.TaskMode.CHAT) {
            subtaskTracker.loadSubtasks()
            loadPlan()
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
     * Set orchestration mode enabled/disabled (US-028).
     * Auto-saves UI state to database.
     */
    fun setOrchestrationEnabled(enabled: Boolean) {
        lifecycleService.setOrchestrationEnabled(enabled)
    }

    fun setIntentClassificationEnabled(enabled: Boolean) {
        lifecycleService.setIntentClassificationEnabled(enabled)
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
        try {
            logger.info { "Requesting conversation summary for task ${currentSession.id}" }
            projectRouter.summarizeConversation(currentSession.id)
            messageDispatcher.loadMessages()
            logger.info { "Conversation summary stored for task ${currentSession.id}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to summarize conversation for task ${currentSession.id}" }
            throw e
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

    suspend fun executeAutoMode() {
        executionMonitor.executeAutoMode()
    }

    suspend fun executeSubtaskById(subtaskId: String) {
        subtaskTracker.executeSubtaskById(subtaskId)
    }

    fun resumeExecution() {
        executionMonitor.resumeExecution()
    }

    private suspend fun sendMessageUsingWorkflow(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?
    ): Message {
        return try {
            val stream = isStreamingEnabled()
            val executionMode = session.executionMode
            logger.info {
                "[SESSION] Workflow start: taskId=${session.id}, mode=${session.mode}, " +
                "executionMode=$executionMode, stream=$stream, orchestration=${stateManager.getOrchestrationEnabled()}"
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
                noEgressEnabled = stateManager.getNoEgressEnabled(),
                orchestrationEnabled = stateManager.getOrchestrationEnabled(),
                intentClassificationEnabled = stateManager.getIntentClassificationEnabled()
            )

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "user",
                content = input,
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(userMessage)

            val listener = SwingWorkflowListener(
                taskId = session.id,
                stateManager = stateManager,
                scope = cs,
                streamingEnabled = stream
            )

            // Generate project analysis summary for intent classification (if enabled)
            val projectAnalysis = try {
                projectRouter.getProjectAnalysisSummary()
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

            logger.info { "[SESSION] Workflow result: taskId=${session.id}, type=${result::class.simpleName}" }

            when (result) {
                is IntentResult.ChatResult -> {
                    val response = result.response
                    logger.info {
                        "[SESSION] Chat response: taskId=${response.taskId}, outputChars=${response.output.length}"
                    }
                    if (response.taskId != session.id) {
                        logger.info { "[CHAT] Task ID changed: ${session.id} -> ${response.taskId}, syncing session" }
                        val newSession = session.copy(id = response.taskId)
                        stateManager.setActiveSession(newSession)
                        com.intellij.ide.util.PropertiesComponent.getInstance(project)
                            .setValue("refio.lastSession", response.taskId)
                    }
                    updateSessionCosts(stateManager.getActiveSession() ?: session)
                    autoNameSessionIfNeeded(stateManager.getActiveSession() ?: session, input)
                    messageDispatcher.loadMessages()
                }
                is IntentResult.PlanResult -> {
                    logger.info {
                        "[SESSION] Plan response: taskId=${session.id}, subtasks=${result.response.subtasks.size}"
                    }
                    messageDispatcher.loadMessages()
                    subtaskTracker.loadSubtasks()

                    val freshTask = pl.jclab.refio.core.db.repositories.TaskRepository().findById(session.id)
                    if (freshTask != null) {
                        val updatedSession = session.copy(
                            tokensIn = freshTask.tokensIn,
                            tokensOut = freshTask.tokensOut,
                            costUsd = freshTask.costUsd
                        )
                        updateSession(updatedSession)
                    } else {
                        logger.error { "Failed to load fresh task from DB!" }
                    }

                    if (stateManager.messages.value.size >= 2) {
                        try {
                            val generatedName = generateSessionName(input)
                            projectRouter.updateTask(
                                taskId = session.id,
                                request = pl.jclab.refio.core.api.UpdateTaskRequest(name = generatedName)
                            )
                            updateSession(
                                stateManager.getActiveSession()?.copy(name = generatedName)
                                    ?: return@sendMessageUsingWorkflow stateManager.messages.value.last()
                            )
                            logger.info { "Auto-named session: '$generatedName'" }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to auto-name session: ${e.message}" }
                        }
                    }

                    executionMonitor.startExecutionFromPlan(session, result.response.subtasks.size)
                }
                is IntentResult.StepResult -> {
                    val response = result.response
                    logger.info {
                        "[SESSION] Step response: taskId=${session.id}, status=${response.status}, " +
                        "durationMs=${response.durationMs}, error=${response.error ?: "none"}"
                    }
                    messageDispatcher.loadMessages()
                    subtaskTracker.loadSubtasks()
                }
                is IntentResult.SubagentResult -> {
                    logger.info { "[SESSION] Subagent response: taskId=${session.id}" }
                    messageDispatcher.loadMessages()
                }
                is IntentResult.AnswerResult -> {
                    logger.info { "[SESSION] Answer recorded: taskId=${result.taskId}" }
                    messageDispatcher.loadMessages()
                }
                is IntentResult.ClarificationResult -> {
                    logger.info {
                        "[SESSION] Clarification requested: taskId=${result.taskId}, " +
                        "questionId=${result.questionId}, question=${result.question.take(100)}"
                    }
                    messageDispatcher.loadMessages()
                }
                is IntentResult.ToolResult -> {
                    logger.info {
                        "[SESSION] Tool execution complete: taskId=${result.taskId}, " +
                        "tool=${result.toolName}, success=${result.success}"
                    }
                    messageDispatcher.loadMessages()
                }
            }

            userMessage
        } catch (e: Exception) {
            logger.error(e) { "[SESSION] Workflow failed: taskId=${session.id}, error=${e.message}" }
            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "system",
                content = "Error: ${e.message}",
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(errorMessage)
            throw e
        }
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

    private suspend fun autoNameSessionIfNeeded(session: Session, input: String) {
        if (session.name == "New Session" && stateManager.messages.value.size == 2) {
            try {
                val name = generateSessionName(input)
                projectRouter.updateTask(session.id, pl.jclab.refio.core.api.UpdateTaskRequest(name = name))
                updateSession(session.copy(name = name))
                logger.info { "Auto-named: '$name'" }
            } catch (e: Exception) {
                logger.warn(e) { "Auto-name failed" }
            }
        }
    }

    private fun generateSessionName(input: String): String {
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

    suspend fun addSteps(prompt: String) {
        subtaskTracker.addSteps(prompt)
    }

    suspend fun addStep(description: String) {
        val prompt = "Add step: $description"
        addSteps(prompt)
    }

    suspend fun replan(prompt: String) {
        subtaskTracker.replan(prompt)
    }

    suspend fun cancelAllPendingSteps() {
        subtaskTracker.cancelAllPendingSteps()
    }

    // ============================================================
    // Plan Management (US-001: Plan as Specification)
    // ============================================================

    /**
     * Load current plan for active session
     */
    suspend fun loadPlan() {
        val session = stateManager.getActiveSession() ?: return
        if (session.mode != pl.jclab.refio.api.models.TaskMode.PLAN) {
            stateManager.setActivePlan(null)
            stateManager.setPlanSteps(emptyList())
            return
        }

        val plan = projectRouter.planRouter.getPlan(session.id)
        stateManager.setActivePlan(plan)

        if (plan != null) {
            val steps = projectRouter.planRouter.getPlanSteps(plan.id)
            stateManager.setPlanSteps(steps)
        } else {
            stateManager.setPlanSteps(emptyList())
        }
    }

    /**
     * Add step to current plan
     */
    suspend fun addPlanStep(
        kind: String,
        description: String,
        paramsJson: String? = null,
        insertAfterIndex: Int? = null,
        isWriteOp: Boolean = false
    ) {
        val plan = stateManager.activePlan.value
            ?: throw IllegalStateException("No active plan")

        val request = pl.jclab.refio.core.api.AddPlanStepRequest(
            planId = plan.id,
            kind = kind,
            description = description,
            paramsJson = paramsJson,
            insertAfterIndex = insertAfterIndex,
            isWriteOp = isWriteOp
        )

        projectRouter.planRouter.addStep(request)
        loadPlan()  // Reload to reflect changes
    }

    /**
     * Update plan step
     */
    suspend fun updatePlanStep(
        stepId: String,
        kind: String? = null,
        description: String? = null,
        paramsJson: String? = null,
        isWriteOp: Boolean? = null
    ) {
        val request = pl.jclab.refio.core.api.UpdatePlanStepRequest(
            stepId = stepId,
            kind = kind,
            description = description,
            paramsJson = paramsJson,
            isWriteOp = isWriteOp
        )

        projectRouter.planRouter.updateStep(request)
        loadPlan()
    }

    /**
     * Delete plan step
     */
    suspend fun deletePlanStep(stepId: String) {
        projectRouter.planRouter.deleteStep(stepId)
        loadPlan()
    }

    /**
     * Reorder plan steps
     */
    suspend fun reorderPlanSteps(stepIds: List<String>) {
        val plan = stateManager.activePlan.value ?: return

        val request = pl.jclab.refio.core.api.ReorderPlanStepsRequest(
            planId = plan.id,
            stepIds = stepIds
        )

        projectRouter.planRouter.reorderSteps(request)
        loadPlan()
    }

    /**
     * Finalize plan (DRAFT → READY)
     */
    suspend fun finalizePlan() {
        val plan = stateManager.activePlan.value ?: return
        projectRouter.planRouter.finalizePlan(plan.id)
        loadPlan()
    }

    /**
     * Execute plan - creates new AGENT session and switches to it
     * @return ID of created AGENT session
     */
    suspend fun executePlan(orchestrationEnabled: Boolean = false): String {
        val plan = stateManager.activePlan.value
            ?: throw IllegalStateException("No active plan")

        val request = pl.jclab.refio.core.api.ExecutePlanRequest(
            planId = plan.id,
            sessionName = "${plan.name} - Execution",
            orchestrationEnabled = orchestrationEnabled
        )

        val response = projectRouter.planRouter.executePlan(request)

        // Switch to new AGENT session
        switchSession(response.executionSessionId)

        // Start execution
        if (orchestrationEnabled) {
            executeAutoMode()
        } else {
            // Show first step for approval
            prepareNextStep()
        }

        return response.executionSessionId
    }

    companion object {
        fun getInstance(project: Project): SessionManager {
            return project.getService(SessionManager::class.java)
        }
    }

}





