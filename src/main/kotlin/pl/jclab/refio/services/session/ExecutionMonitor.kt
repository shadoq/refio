package pl.jclab.refio.services.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.services.execution.unified.StepPlan
import pl.jclab.refio.core.services.execution.unified.StepResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.components.chat.PlanApprovalDialog
import java.util.UUID

class ExecutionMonitor(
    private val project: Project,
    private val projectRouter: CoreApiRouter,
    private val stateManager: SessionStateManager,
    private val stepExecutionService: StepExecutionService,
    private val scope: CoroutineScope,
    private val loadMessages: suspend () -> Unit,
    private val loadSubtasks: suspend () -> Unit,
    private val prepareNextStep: suspend () -> pl.jclab.refio.core.api.PlanStepResponse?
) {

    private val logger = dualLogger("ExecutionMonitor")
    private var streamingJob: Job? = null

    data class StreamingMessageState(
        val messageId: String,
        val callback: pl.jclab.refio.core.api.StreamCallback
    )

    fun cancelStreaming() {
        logger.info { "[US-027] Cancelling streaming..." }
        streamingJob?.cancel()
        streamingJob = null
    }

    fun cancelExecution() {
        logger.info { "Cancelling execution..." }
        stepExecutionService.stopExecution()
        GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
    }

    fun setPaused(paused: Boolean) {
        logger.info { "Setting paused state to: $paused" }
        stateManager.setPaused(paused)

        if (paused) {
            logger.info { "Execution paused - will wait after current step completes" }
        } else {
            logger.info { "Execution resumed - continuing with next step" }
            stateManager.getActiveSession()?.let { session ->
                scope.launchSafe {
                    val pendingSubtasks = stateManager.getSubtasks().filter {
                        it.status == "PENDING" || it.status == "PLANNED"
                    }
                    if (pendingSubtasks.isNotEmpty()) {
                        logger.info { "Resuming execution - ${pendingSubtasks.size} pending steps" }
                        when (session.executionMode) {
                            ExecutionMode.AUTO -> executeAutoMode()
                            ExecutionMode.INTERACTIVE -> showApprovalMessageForNextSubtask()
                        }
                    }
                }
            }
        }
    }

    fun startExecutionFromPlan(session: Session, subtaskCount: Int) {
        GlobalMetrics.setCurrentOperation(
            OperationInfo.ExecutingStep(0, subtaskCount, "Starting execution")
        )
        logger.info { "Starting execution workflow: mode=${session.executionMode}, subtasks=$subtaskCount" }

        when (session.executionMode) {
            ExecutionMode.AUTO -> {
                logger.info { "Starting AUTO mode execution" }
                stepExecutionService.startAutoExecution(session.id)
            }
            ExecutionMode.INTERACTIVE -> {
                logger.info { "Starting INTERACTIVE mode execution" }
                stepExecutionService.startInteractiveExecution(session.id)
                scope.launchSafe { showApprovalMessageForNextSubtask() }
            }
        }
    }

    suspend fun executeCurrentStep(subtaskId: String): pl.jclab.refio.core.api.ExecuteStepResponse? {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "Cannot execute step - no active session" }
            return null
        }

        logger.info { "[EXECUTION] executeCurrentStep: taskId=${currentSession.id}, subtaskId=$subtaskId" }

        val orchestrationEnabled = resolveOrchestrationEnabled(currentSession.id)

        if (isStreamingEnabled() && !orchestrationEnabled) {
            return executeCurrentStepStreaming(subtaskId)
        }

        return try {
            if (orchestrationEnabled) {
                logger.info { "[INTERACTIVE+ORCHESTRATION] Executing subtask with orchestration: $subtaskId" }

                val uiListener = UIProgressListener(currentSession.id)
                val orchestrationResponse = projectRouter.executeSubtaskStepWithOrchestration(
                    taskId = currentSession.id,
                    subtaskId = subtaskId,
                    externalListener = uiListener
                )

                logger.info {
                    "[INTERACTIVE+ORCHESTRATION] Step executed: status=${orchestrationResponse.status}, " +
                        "decision=${orchestrationResponse.reflectionDecision}, " +
                        "planModified=${orchestrationResponse.planModified}, " +
                        "duration=${orchestrationResponse.durationMs}ms"
                }

                loadMessages()
                loadSubtasks()

                pl.jclab.refio.core.api.ExecuteStepResponse(
                    status = orchestrationResponse.status,
                    summary = orchestrationResponse.summary,
                    durationMs = orchestrationResponse.durationMs,
                    error = orchestrationResponse.error
                )
            } else {
                logger.info { "[INTERACTIVE] Executing subtask without orchestration: $subtaskId" }

                val executeResponse = projectRouter.executeSubtaskStep(
                    taskId = currentSession.id,
                    subtaskId = subtaskId
                )

                logger.info {
                    "[INTERACTIVE] Step executed: status=${executeResponse.status}, " +
                        "duration=${executeResponse.durationMs}ms"
                }

                loadMessages()
                loadSubtasks()

                executeResponse
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute step" }
            null
        }
    }

    private suspend fun executeCurrentStepStreaming(subtaskId: String): pl.jclab.refio.core.api.ExecuteStepResponse? {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "Cannot execute step (streaming) - no active session" }
            return null
        }

        logger.info { "[ADR-0031] Executing subtask with streaming listener: taskId=${currentSession.id}, subtaskId=$subtaskId" }

        val uiListener = UIProgressListener(currentSession.id)

        return try {
            val response = projectRouter.executeSubtaskStepWithListener(
                taskId = currentSession.id,
                subtaskId = subtaskId,
                externalListener = uiListener
            )
            logger.info {
                "[ADR-0031] Streaming execution complete: taskId=${currentSession.id}, subtaskId=$subtaskId, " +
                    "status=${response.status}, durationMs=${response.durationMs}ms"
            }
            loadMessages()
            loadSubtasks()
            response
        } catch (e: Exception) {
            logger.error(e) { "[ADR-0031] Step execution with listener failed: ${e.message}" }
            null
        }
    }

    suspend fun executeAutoMode() {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "Cannot execute auto mode - no active session" }
            return
        }

        GlobalMetrics.setCurrentOperation(
            OperationInfo.AutoExecution(taskId = currentSession.id)
        )

        try {
            if (!ensurePlanApproved(currentSession.id)) {
                logger.info { "Auto execution aborted - plan not approved" }
                return
            }

            val orchestrationEnabled = resolveOrchestrationEnabled(currentSession.id)

            if (orchestrationEnabled) {
                logger.info { "Starting orchestrated execution for task ${currentSession.id}" }
                val uiListener = UIProgressListener(currentSession.id)
                try {
                    val response = projectRouter.executeWithOrchestration(currentSession.id, uiListener)
                    logger.info {
                        "Orchestrated execution completed: stepsExecuted=${response.stepsExecuted}, " +
                            "stepsFailed=${response.stepsFailed}, reflections=${response.reflectionsCount}, " +
                            "planModifications=${response.planModificationsCount}, " +
                            "userQuestions=${response.userQuestionsCount}, " +
                            "durationMs=${response.durationMs}ms, success=${response.success}"
                    }
                    if (!response.success) {
                        logger.error { "Orchestrated execution failed: ${response.error}" }
                    }
                } finally {
                    loadMessages()
                    loadSubtasks()
                    project.baseDir?.refresh(true, true)
                }
            } else {
                logger.info { "Starting standard auto mode execution for task ${currentSession.id}" }
                val uiListener = UIProgressListener(currentSession.id)
                try {
                    val response = projectRouter.executeAutoMode(currentSession.id, uiListener)
                    logger.info {
                        "Auto mode completed: totalSteps=${response.totalSteps}, " +
                            "completedSteps=${response.completedSteps}, failedSteps=${response.failedSteps}, " +
                            "durationMs=${response.durationMs}ms, success=${response.success}"
                    }
                    if (!response.success) {
                        logger.error { "Auto mode failed: ${response.error}" }
                    }
                } finally {
                    loadMessages()
                    loadSubtasks()
                    project.baseDir?.refresh(true, true)
                }
            }

            generateExecutionSummary(currentSession.id)
        } catch (e: CancellationException) {
            logger.info { "Auto execution cancelled" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute auto mode" }
        } finally {
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
            logger.info { "Auto execution finished - cleared operation state" }
        }
    }

    private suspend fun ensurePlanApproved(taskId: String): Boolean {
        val summary = projectRouter.getPlanSummary(taskId)
        if (!summary.requiresApproval || summary.isApproved) {
            return true
        }

        val approved = showPlanApprovalDialog(summary)
        if (!approved) {
            addSystemMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    role = "system",
                    content = "Plan approval required. Execution cancelled.",
                    createdAt = System.currentTimeMillis()
                )
            )
            return false
        }

        projectRouter.approvePlan(taskId)
        return true
    }

    private fun resolveOrchestrationEnabled(taskId: String): Boolean {
        if (!stateManager.getOrchestrationEnabled()) {
            logger.info { "Orchestration disabled in UI state for task $taskId" }
            return false
        }

        return try {
            val result = projectRouter.isOrchestrationEnabled(taskId)
            logger.info { "Orchestration check for task $taskId: enabled=$result" }
            result
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check orchestration status, defaulting to standard execution" }
            false
        }
    }

    private fun showPlanApprovalDialog(summary: pl.jclab.refio.core.api.PlanSummaryResponse): Boolean {
        val approved = booleanArrayOf(false)
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = PlanApprovalDialog(project, summary)
            approved[0] = dialog.showAndGet()
        }
        return approved[0]
    }

    fun resumeExecution() {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "No active session for resumeExecution" }
            return
        }

        logger.info { "Resuming execution for session: ${currentSession.id}" }

        when (currentSession.executionMode) {
            ExecutionMode.INTERACTIVE -> {
                stepExecutionService.startInteractiveExecution(currentSession.id)
                scope.launchSafe { showApprovalMessageForNextSubtask() }
            }

            ExecutionMode.AUTO -> {
                stepExecutionService.startAutoExecution(currentSession.id)
            }

            null -> {
                logger.warn { "No execution mode set for session ${currentSession.id}" }
            }
        }

        logger.info { "Execution resumed successfully" }
    }

    suspend fun showApprovalMessageForNextSubtask() {
        logger.info { "[INTERACTIVE] showApprovalMessageForNextSubtask() called" }

        if (stateManager.getActiveSession() == null) {
            logger.warn { "[INTERACTIVE] No active session" }
            return
        }

        try {
            val pendingCount = stateManager.getSubtasks().count { it.status == "PENDING" }
            logger.info {
                "[INTERACTIVE] Current subtasks count: ${stateManager.getSubtasks().size}, " +
                    "pending count: $pendingCount"
            }

            val prepareResponse = prepareNextStep() ?: run {
                logger.warn { "[INTERACTIVE] prepareNextStep() returned null - no subtasks to prepare" }

                val completionMessage = Message(
                    id = UUID.randomUUID().toString(),
                    taskId = (stateManager.getActiveSession()?.id ?: "unknown"),
                    role = "system",
                    content = "All steps completed or no pending steps found.",
                    createdAt = System.currentTimeMillis()
                )
                addSystemMessage(completionMessage)

                val currentSession = stateManager.getActiveSession()
                if (currentSession != null && currentSession.mode in listOf(TaskMode.PLAN, TaskMode.AGENT)) {
                    logger.info { "[INTERACTIVE] Generating execution summary" }
                    generateExecutionSummary(currentSession.id)
                }
                stepExecutionService.markComplete()
                return
            }

            // AgentRouter persists the approval request as a chat message in DB.
            // Refresh state so ChatView can render the approval bubble + buttons.
            loadMessages()

            val toolsList = prepareResponse.tools.joinToString(", ") { it.name }
            val plannedSubtask = stateManager.getSubtasks()
                .firstOrNull { it.status == "PLANNED" }
                ?: stateManager.getSubtasks().firstOrNull { it.status == "PENDING" }
            val stepIndex = plannedSubtask?.orderIndex?.toString() ?: "?"

            val message = Message(
                id = UUID.randomUUID().toString(),
                taskId = (stateManager.getActiveSession()?.id ?: "unknown"),
                role = "system",
                content = "Step $stepIndex planned; awaiting approval (tools: $toolsList)",
                createdAt = System.currentTimeMillis()
            )
            addSystemMessage(message)
            logger.debug { "[INTERACTIVE] Showed step executing message for: ${plannedSubtask?.id}" }
        } catch (e: Exception) {
            logger.error(e) { "[INTERACTIVE] Error preparing next step" }
            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = (stateManager.getActiveSession()?.id ?: "unknown"),
                role = "system",
                content = "Error preparing next step: ${e.message}",
                createdAt = System.currentTimeMillis()
            )
            addSystemMessage(errorMessage)
        }
    }

    private suspend fun generateExecutionSummary(taskId: String) {
        try {
            logger.info { "Generating execution summary for task: $taskId" }
            projectRouter.generateExecutionSummary(taskId)
            loadMessages()
            logger.info { "Execution summary generated successfully" }
        } catch (e: CancellationException) {
            logger.info { "Execution summary generation cancelled" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate execution summary" }
        }
    }

    private fun addSystemMessage(message: Message) {
        scope.launchSafe {
            stateManager.updateMessages { messages -> messages + message }
        }
        logger.info { "Added system message: ${message.content}" }
    }

    private fun updateOrAddSystemMessage(message: Message) {
        scope.launchSafe {
            stateManager.updateMessages { currentMessages ->
                val existingIndex = currentMessages.indexOfFirst { it.id == message.id }
                if (existingIndex >= 0) {
                    currentMessages.toMutableList().apply { set(existingIndex, message) }
                } else {
                    currentMessages + message
                }
            }
        }
    }

    private fun removeSystemMessage(messageId: String) {
        scope.launchSafe {
            stateManager.updateMessages { messages -> messages.filter { it.id != messageId } }
        }
    }

    fun createStreamingMessage(
        sessionId: String,
        initialContent: String = "",
        role: String = "assistant",
        formatContent: (String) -> String = { it }
    ): StreamingMessageState {
        val messageId = UUID.randomUUID().toString()

        val streamingMessage = Message(
            id = messageId,
            taskId = sessionId,
            role = role,
            content = initialContent,
            isStreaming = true,
            streamStartedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        scope.launchSafe {
            stateManager.updateMessages { messages -> messages + streamingMessage }
        }

        var lastUiUpdateTime = 0L
        val callback: pl.jclab.refio.core.api.StreamCallback = { chunk ->
            val now = System.currentTimeMillis()
            if (now - lastUiUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                lastUiUpdateTime = now
                scope.launchSafe {
                    stateManager.updateMessages { messages ->
                        messages.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(content = formatContent(chunk.accumulated), lastChunkAt = now)
                            } else {
                                msg
                            }
                        }
                    }
                }
            }
        }

        return StreamingMessageState(messageId, callback)
    }

    suspend fun finalizeStreamingMessage(
        messageId: String,
        content: String,
        usage: pl.jclab.refio.core.llm.LLMUsage? = null,
        cost: Double? = null,
        formatContent: (String) -> String = { it }
    ) {
        stateManager.updateMessages { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(
                        content = formatContent(content),
                        isStreaming = false,
                        tokensIn = usage?.inputTokens,
                        tokensOut = usage?.outputTokens,
                        costUsd = cost,
                        lastChunkAt = System.currentTimeMillis()
                    )
                } else {
                    msg
                }
            }
        }
    }

    suspend fun removeStreamingMessage(messageId: String) {
        stateManager.updateMessages { messages -> messages.filter { it.id != messageId } }
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

    private inner class UIProgressListener(
        private val taskId: String
    ) : ExecutionEventListener {

        private var streamingMessageId: String? = null
        private var reflectionMessageId: String? = null
        private var codeGenMessageId: String? = null

        private var reloadJob: Job? = null
        private val reloadDebounceMs = 500L

        private fun scheduleReload(includeMessages: Boolean = true, includeSubtasks: Boolean = true) {
            reloadJob?.cancel()
            reloadJob = scope.launchSafeJob {
                delay(reloadDebounceMs)
                try {
                    if (includeSubtasks) {
                        loadSubtasks()
                    }
                    if (includeMessages) {
                        loadMessages()
                    }
                    logger.debug {
                        "[UI_LISTENER] Debounced reload complete (messages=$includeMessages, " +
                            "subtasks=$includeSubtasks)"
                    }
                } catch (e: CancellationException) {
                    // Normal - newer reload scheduled
                } catch (e: Exception) {
                    logger.warn(e) { "[UI_LISTENER] Debounced reload failed" }
                }
            }
        }

        override fun onStepPreparing(step: pl.jclab.refio.core.db.Subtask) {
            logger.debug { "[UI_LISTENER] Step preparing: ${step.id}" }
            scheduleReload(includeMessages = false, includeSubtasks = true)
        }

        override fun onStepPlanningStream(
            step: pl.jclab.refio.core.db.Subtask,
            streamContent: String,
            isComplete: Boolean
        ) {
            if (streamingMessageId == null) {
                val state = createStreamingMessage(
                    taskId,
                    "Planning...\n```json\n$streamContent\n```",
                    "system"
                ) { accumulated ->
                        "Planning...\n```json\n$accumulated\n```"
                    }
                streamingMessageId = state.messageId
            } else {
                val msgId = streamingMessageId ?: return
                scope.launchSafe {
                    stateManager.updateMessages { messages ->
                        messages.map { msg ->
                            if (msg.id == msgId) {
                                msg.copy(content = "Planning...\n```json\n$streamContent\n```")
                            } else {
                                msg
                            }
                        }
                    }
                }
            }

            if (isComplete) {
                val msgId = streamingMessageId
                if (msgId != null) {
                    // Update message to final state instead of removing
                    scope.launchSafe {
                        stateManager.updateMessages { messages ->
                            messages.map { msg ->
                                if (msg.id == msgId) {
                                    msg.copy(content = "**Step Planning Complete**\n```json\n$streamContent\n```")
                                } else {
                                    msg
                                }
                            }
                        }
                    }
                    streamingMessageId = null
                    logger.debug { "[UI_LISTENER] Finalized planning stream for step: ${step.id}" }
                }
            }
        }

        override fun onToolCodeGenerationStream(
            step: pl.jclab.refio.core.db.Subtask,
            toolName: String,
            filePath: String,
            streamContent: String,
            isComplete: Boolean
        ) {
            if (codeGenMessageId == null) {
                logger.info { "[UI_LISTENER] Creating streaming message for code generation: $filePath" }
                val header = "Generating code for `$filePath`... ($toolName)\n\n"
                val state = createStreamingMessage(
                    taskId,
                    header + streamContent + "\n",
                    "system"
                ) { accumulated ->
                    header + accumulated + "\n"
                }
                codeGenMessageId = state.messageId
            } else {
                val msgId = codeGenMessageId ?: return
                scope.launchSafe {
                    stateManager.updateMessages { messages ->
                        messages.map { msg ->
                            if (msg.id == msgId) {
                                msg.copy(
                                    content = "Generating code for `$filePath`... ($toolName)\n\n$streamContent\n"
                                )
                            } else {
                                msg
                            }
                        }
                    }
                }
            }

            if (isComplete) {
                val msgId = codeGenMessageId
                if (msgId != null) {
                    scope.launchSafe { removeStreamingMessage(msgId) }
                    codeGenMessageId = null
                    logger.debug { "[UI_LISTENER] Removed code generation stream for: $filePath" }
                }
            }
        }

        override fun onReflectionStream(
            step: pl.jclab.refio.core.db.Subtask,
            streamContent: String,
            isFinal: Boolean
        ) {
            if (reflectionMessageId == null) {
                val state = createStreamingMessage(
                    sessionId = taskId,
                    initialContent = "Reflecting...\n\n$streamContent\n",
                    role = "system",
                    formatContent = { accumulated -> "Reflecting...\n\n$accumulated\n" }
                )
                reflectionMessageId = state.messageId
            } else {
                val msgId = reflectionMessageId ?: return
                scope.launchSafe {
                    stateManager.updateMessages { messages ->
                        messages.map { msg ->
                            if (msg.id == msgId) {
                                msg.copy(content = "Reflecting...\n\n$streamContent\n")
                            } else {
                                msg
                            }
                        }
                    }
                }
            }

            if (isFinal) {
                val msgId = reflectionMessageId
                if (msgId != null) {
                    // Update message to final state instead of removing
                    scope.launchSafe {
                        stateManager.updateMessages { messages ->
                            messages.map { msg ->
                                if (msg.id == msgId) {
                                    msg.copy(content = "**Reflection Complete**\n\n$streamContent\n")
                                } else {
                                    msg
                                }
                            }
                        }
                    }
                    reflectionMessageId = null
                    logger.debug { "[UI_LISTENER] Finalized reflection stream for step: ${step.id}" }
                }
            }
        }

        override fun onStepExecuting(step: pl.jclab.refio.core.db.Subtask, plan: StepPlan) {
            logger.debug { "[UI_LISTENER] Step executing: ${step.id}" }
            val toolsList = plan.tools.joinToString(", ") { it.name }

            val decisionInfo = plan.planDecision?.let { decision ->
                buildString {
                    append("### Tool Decision\n")
                    append("- Intent: ${decision.intent}\n")
                    append("- Suggested: ${decision.suggestedTool} ${decision.suggestedParams}\n")
                    append("- Selected: ${decision.selectedTool} ${decision.selectedParams}\n")
                    decision.reasoning?.let { append("- Reasoning: $it\n") }
                    append("\n### Tools\n")
                    append("- ${decision.selectedTool}: ${decision.selectedParams}\n")
                }
            } ?: ""

            val message = Message(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                role = "system",
                content = "Executing step ${step.orderIndex} with tools: $toolsList\n\n$decisionInfo",
                createdAt = System.currentTimeMillis()
            )
            addSystemMessage(message)
            logger.debug { "[UI_LISTENER] Showed step executing message for: ${step.id}" }

            scheduleReload(includeMessages = false, includeSubtasks = true)
        }

        override fun onStepCompleted(step: pl.jclab.refio.core.db.Subtask, result: StepResult) {
            logger.debug { "[UI_LISTENER] Step completed: ${step.id}, status: ${result.status}" }
            val currentSubtasks = stateManager.getSubtasks()
            val completedCount = currentSubtasks.count {
                it.status in listOf("SUCCESS", "FAILED", "SKIPPED", "CANCELED")
            } + 1
            val totalCount = currentSubtasks.size.coerceAtLeast(1)
            GlobalMetrics.setCurrentOperation(
                OperationInfo.ExecutingStep(completedCount, totalCount, "Executing steps")
            )
            scheduleReload(includeMessages = true, includeSubtasks = true)
        }

        override fun onStepFailed(step: pl.jclab.refio.core.db.Subtask, error: Throwable) {
            logger.error(error) { "[UI_LISTENER] Step failed: ${step.id}" }
            val currentSubtasks = stateManager.getSubtasks()
            val completedCount = currentSubtasks.count {
                it.status in listOf("SUCCESS", "FAILED", "SKIPPED", "CANCELED")
            } + 1
            val totalCount = currentSubtasks.size.coerceAtLeast(1)
            GlobalMetrics.setCurrentOperation(
                OperationInfo.ExecutingStep(completedCount, totalCount, "Executing steps")
            )
            scheduleReload(includeMessages = true, includeSubtasks = true)
        }

        override fun onExecutionComplete(stats: pl.jclab.refio.core.services.execution.unified.ExecutionStats) {
            logger.info {
                "[UI_LISTENER] Execution complete: ${stats.stepsExecuted} executed, " +
                    "${stats.stepsFailed} failed"
            }

            reloadJob?.cancel()
            stepExecutionService.markComplete()

            val message = Message(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                role = "system",
                content = "Execution completed: ${stats.stepsExecuted}/${stats.stepsExecuted + stats.stepsFailed} steps successful",
                createdAt = System.currentTimeMillis()
            )
            addSystemMessage(message)

            scope.launchSafe {
                try {
                    loadSubtasks()
                    loadMessages()
                    logger.info { "[UI_LISTENER] Final reload complete" }
                } catch (e: Exception) {
                    logger.warn(e) { "[UI_LISTENER] Failed final reload" }
                }
            }
        }

        override fun onExecutionError(error: Throwable) {
            logger.error(error) { "[UI_LISTENER] Execution error" }

            reloadJob?.cancel()
            stepExecutionService.markComplete()

            val message = Message(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                role = "system",
                content = "Execution error: ${error.message}",
                createdAt = System.currentTimeMillis()
            )
            addSystemMessage(message)

            scope.launchSafe {
                try {
                    loadSubtasks()
                    loadMessages()
                } catch (e: Exception) {
                    logger.warn(e) { "[UI_LISTENER] Failed reload after error" }
                }
            }
        }
    }

    companion object {
        private const val UI_UPDATE_INTERVAL_MS = 500L
    }
}

private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    this.launch(Dispatchers.IO) { block() }
}

private fun CoroutineScope.launchSafeJob(block: suspend () -> Unit): Job {
    return this.launch(Dispatchers.IO) { block() }
}
