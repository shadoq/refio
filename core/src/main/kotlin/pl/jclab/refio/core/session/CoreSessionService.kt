package pl.jclab.refio.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallResult
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.api.UIAdapter
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = dualLogger("CoreSessionService")

/**
 * Platform-agnostic session execution service.
 *
 * Owns the three send-message orchestration paths (CHAT workflow, PLAN/AGENT turn loop,
 * legacy workflow entry) plus the shared auxiliaries (auto-naming, cost refresh,
 * tool-call display helpers). Constructed once per project-level
 * [CoreApiRouter]; IntelliJ's `SessionManager` and the CLI TUI both delegate
 * through this class so execution behavior stays in sync.
 *
 * StreamFilter, streaming-message bookkeeping, and the temp tool-call message map
 * live here. UI-only state (pending input, context-section tokens, StatusBar, EDT
 * dispatch) stays in the per-platform binding that constructs this service.
 */
class CoreSessionService(
    private val projectRouter: CoreApiRouter,
    private val stateManager: SessionStateManager,
    private val subtaskTracker: SubtaskTracker,
    private val messageDispatcher: MessageDispatcher,
    private val lifecycleService: SessionLifecycleService,
    private val uiAdapter: UIAdapter,
    private val scope: CoroutineScope,
    private val modeSwitchMutex: Mutex,
) {

    private val configService: ConfigService
        get() = projectRouter.configService

    suspend fun sendMessage(
        input: String,
        contextRefs: List<ContextReference> = emptyList(),
        model: String? = null,
        provider: String? = null,
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

    private suspend fun sendMessageUsingWorkflow(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?,
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
                createdAt = System.currentTimeMillis(),
            )
            stateManager.appendMessage(userMessage)

            when (session.mode) {
                TaskMode.CHAT -> sendMessageUsingChatWorkflow(session, input, contextRefs, model, provider, stream, executionMode)
                TaskMode.PLAN, TaskMode.AGENT -> sendMessageUsingTurnLoop(session, input, contextRefs, model, provider, stream, executionMode)
            }
        } catch (e: RefioError.MalformedResponse) {
            logger.error(e) {
                "[SESSION] Malformed response from provider=${e.provider}/${e.model}: reason=${e.reason}, " +
                        "bodyPreview=${e.bodyPreview.take(500)}"
            }
            val userFacing = "Provider ${e.provider} returned an invalid response — check logs for details."
            uiAdapter.showError(userFacing)
            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "system",
                content = userFacing,
                createdAt = System.currentTimeMillis(),
            )
            stateManager.appendMessage(errorMessage)
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[SESSION] Workflow failed: taskId=${session.id}, error=${e.message}" }
            uiAdapter.showError("Workflow failed: ${e.message}")
            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = session.id,
                role = "system",
                content = "Error: ${e.message}",
                createdAt = System.currentTimeMillis(),
            )
            stateManager.appendMessage(errorMessage)
            throw e
        } finally {
            stateManager.setIsGenerating(false)
        }
    }

    private suspend fun sendMessageUsingTurnLoop(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?,
        stream: Boolean,
        executionMode: ExecutionMode,
    ): Message {
        logger.info {
            "[TURN_LOOP] Starting turn: taskId=${session.id}, mode=${session.mode}, " +
                    "inputChars=${input.length}, contextRefs=${contextRefs.size}"
        }

        GlobalMetrics.setCurrentOperation(OperationInfo.ChatRequest(model ?: "auto"))

        // Transition the task to RUNNING up-front so HistoryPanel reflects the active
        // session correctly. Without this the task lingers as NEW for the entire turn,
        // and (since we now flip to SUCCESS at the end) the row stays at NEW forever
        // for AGENT/PLAN sessions. ChatService already does the same for CHAT mode.
        try {
            projectRouter.taskRepository.update(id = session.id, status = TaskStatus.RUNNING)
        } catch (e: Exception) {
            logger.warn(e) { "[TURN_LOOP] Failed to mark task RUNNING for ${session.id}" }
        }

        try {
            var streamingMessageId: String? = null
            val streamingClosed = AtomicBoolean(false)
            val pendingStreamContent = AtomicReference<String?>(null)
            val streamStateMutex = Mutex()
            var streamUiFlushJob: Job? = null
            val streamFilter = IncrementalToolCallStreamFilter()

            val streamCallback: StreamCallback? = if (stream) { chunk ->
                scope.launch {
                    if (streamingClosed.get()) return@launch

                    streamStateMutex.withLock {
                        val now = System.currentTimeMillis()
                        val filteredContent = streamFilter.filter(
                            delta = chunk.delta,
                            accumulated = chunk.accumulated,
                            isComplete = chunk.isComplete,
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
                                        createdAt = now,
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
                                                isStreaming = false,
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
                            streamUiFlushJob = scope.launch {
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
                                                            isStreaming = true,
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

            val turnListener = CoreMessageToolCallListener(
                scope = scope,
                stateManager = stateManager,
                onReloadSubtasks = { subtaskTracker.scheduleReload() },
                onReloadMessages = { messageDispatcher.loadMessages() },
                resolveToolDisplayType = ::resolveToolDisplayType,
                parseToolParameters = ::parseToolParameters,
            )

            val modeDb = pl.jclab.refio.core.db.TaskMode.valueOf(session.mode.name)
            val executionModeDb = pl.jclab.refio.core.db.ExecutionMode.valueOf(executionMode.name)
            val defaultTurnRequest = TurnRequest(
                taskId = session.id,
                userInput = input,
                mode = modeDb,
                executionMode = executionModeDb,
                model = model,
                provider = provider,
                userContextRefs = contextRefs,
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
                    createdAt = System.currentTimeMillis(),
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

                    TurnRequest(
                        taskId = session.id,
                        userInput = subagentPrompt,
                        mode = modeDb,
                        executionMode = executionModeDb,
                        model = resolvedModel,
                        provider = resolvedProvider,
                        userContextRefs = contextRefs,
                        runProfile = TurnRunProfile.SUBAGENT,
                        profileOverrides = TurnProfileOverrides(
                            subagentName = subagentName,
                            systemPromptOverride = definition.systemPrompt,
                            allowedTools = definition.allowedTools,
                            disallowedTools = definition.disallowedTools,
                            modelOverride = resolvedModel,
                            providerOverride = resolvedProvider,
                            maxIterationsOverride = definition.maxSteps,
                            depth = 0,
                            subagentChain = emptyList(),
                            contextProfile = definition.contextProfile,
                            reasoningEffort = definition.reasoningEffort,
                        ),
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

            // Live-refresh the UI when the turn spawns subagent or tool activity. Without this,
            // messages persisted mid-turn by AgentTurnLoop (tool calls, sub-LLM responses) stay
            // invisible until the outer runTurn completes and the final loadMessages() flush runs.
            // We subscribe to AgentEventBus events for this session and reload on TOOL / TURN-END
            // boundaries — so each subagent bubble materializes right after its tool call completes.
            val liveRefreshJob = scope.launch {
                projectRouter.agentEventBus.turnEvents(session.id).collect { event ->
                    val depth = when (event) {
                        is pl.jclab.refio.core.agents.events.AgentEvent.ToolCalled -> event.depth
                        is pl.jclab.refio.core.agents.events.AgentEvent.TurnEnded -> event.depth
                        else -> -1
                    }
                    // Only refresh for subagent activity (depth > 0); top-level turn messages
                    // are already handled by the streaming path in this function.
                    if (depth > 0) {
                        messageDispatcher.loadMessages()
                    }
                }
            }

            // Per-subagent token streaming. AgentTurnLoop emits AgentEvent.StreamChunk for every
            // subagent delta with runId/depth/agentName. We key streaming messages by runId and
            // update them live so the user sees tokens appear inside per-agent bubbles while the
            // subagent's LLM is still generating. The final DB-persisted ASSISTANT message (with
            // the same agentName) replaces this transient entry when loadMessages() flushes.
            // .collect serializes events so a plain map is safe.
            val subagentStreamingIds = HashMap<String, String>()
            val subagentStreamJob = scope.launch {
                projectRouter.agentEventBus.events
                    .collect { raw ->
                        val ev = raw as? pl.jclab.refio.core.agents.events.AgentEvent.StreamChunk
                            ?: return@collect
                        if (ev.sessionId != session.id || ev.agentName == null || ev.runId == null) return@collect
                        val now = System.currentTimeMillis()
                        val key = ev.runId
                        val existingId = subagentStreamingIds[key]
                        val messageId: String
                        if (existingId == null) {
                            messageId = UUID.randomUUID().toString()
                            subagentStreamingIds[key] = messageId
                            stateManager.appendMessage(
                                Message(
                                    id = messageId,
                                    taskId = session.id,
                                    role = "assistant",
                                    content = ev.accumulated,
                                    isStreaming = !ev.isComplete,
                                    streamStartedAt = now,
                                    lastChunkAt = now,
                                    createdAt = now,
                                    agentName = ev.agentName,
                                    agentDepth = ev.depth,
                                )
                            )
                        } else {
                            messageId = existingId
                            stateManager.updateMessages { messages ->
                                messages.map { msg ->
                                    if (msg.id == messageId) {
                                        msg.copy(
                                            content = ev.accumulated,
                                            lastChunkAt = now,
                                            isStreaming = !ev.isComplete,
                                        )
                                    } else msg
                                }
                            }
                        }
                        if (ev.isComplete) {
                            subagentStreamingIds.remove(key)
                        }
                    }
            }

            val result = try {
                projectRouter.agentRouter.runTurn(
                    request = turnRequest,
                    streamCallback = streamCallback,
                    listener = turnListener,
                )
            } catch (e: Exception) {
                runCatching {
                    projectRouter.taskRepository.update(id = session.id, status = TaskStatus.FAILED)
                }.onFailure { logger.warn(it) { "[TURN_LOOP] Failed to mark task FAILED for ${session.id}" } }
                throw e
            }

            logger.info {
                "[TURN_LOOP] Turn complete: taskId=${session.id}, success=${result.success}, " +
                        "iterations=${result.iterations}, responseChars=${result.response.length}"
            }

            // Mirror ChatService: turn finished without throwing → mark SUCCESS so
            // HistoryPanel / status filters recognise the session as completed. A turn that a
            // completion guardian flagged INCOMPLETE (request not delivered, no further re-entry
            // would help) is recorded as INCOMPLETE — distinct from FAILED — so an abandoned
            // multi-step task is never silently shown as SUCCESS. Either way the task is promoted
            // off NEW so it isn't left pending forever.
            runCatching {
                val finalStatus = when {
                    result.incomplete -> TaskStatus.INCOMPLETE
                    result.success -> TaskStatus.SUCCESS
                    else -> TaskStatus.FAILED
                }
                projectRouter.taskRepository.update(id = session.id, status = finalStatus)
            }.onFailure { logger.warn(it) { "[TURN_LOOP] Failed to update task status for ${session.id}" } }

            liveRefreshJob.cancel()
            subagentStreamJob.cancel()
            // The transient per-subagent streaming messages live only in UI state; the DB-backed
            // ASSISTANT rows that AgentTurnLoop persisted with agentName will replace them at the
            // next messageDispatcher.loadMessages() below. Drop them so we don't show duplicates.
            if (subagentStreamingIds.isNotEmpty()) {
                val transientIds = subagentStreamingIds.values.toSet()
                subagentStreamingIds.clear()
                stateManager.updateMessages { messages ->
                    messages.filterNot { it.id in transientIds }
                }
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

            messageDispatcher.loadMessages()
            turnListener.clearTracking()
            logger.debug { "[TURN_LOOP] Cleared tool call message tracking map after DB reload" }

            val freshTask = projectRouter.taskRepository.findById(session.id)
            if (freshTask != null) {
                val updatedSession = session.copy(
                    tokensIn = freshTask.tokensIn,
                    tokensOut = freshTask.tokensOut,
                    costUsd = freshTask.costUsd,
                )
                // Token-only refresh — UI settings did not change.
                lifecycleService.updateSession(updatedSession, persistSettings = false)
            }

            if (isDefaultSessionName(session.name) && stateManager.messages.value.size >= 2) {
                scheduleAutoNameSession(session, input)
            }

            return stateManager.messages.value.last()
        } finally {
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
            logger.info { "[TURN_LOOP] Operation state reset to Idle" }
        }
    }

    private suspend fun sendMessageUsingChatWorkflow(
        session: Session,
        input: String,
        contextRefs: List<ContextReference>,
        model: String?,
        provider: String?,
        stream: Boolean,
        executionMode: ExecutionMode,
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
            noEgressEnabled = stateManager.getNoEgressEnabled(),
        )

        val listener = DefaultWorkflowStreamingListener(
            taskId = session.id,
            stateManager = stateManager,
            scope = scope,
            streamingEnabled = stream,
        )

        val projectAnalysis = try {
            projectRouter.projectContextRouter.getProjectAnalysisSummary()
        } catch (e: Exception) {
            logger.warn(e) { "[SESSION] Failed to generate project analysis, using null" }
            null
        }

        val result = projectRouter.workflowOrchestrator.execute(
            request = WorkflowRequest(
                uiState = uiState,
                projectAnalysis = projectAnalysis,
            ),
            listener = listener,
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
            val streamingConfig = configService.get(
                key = ConfigKeys.STREAMING_ENABLED.key,
                scope = ConfigScope.APP,
            )
            streamingConfig?.toBoolean() ?: true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read streaming config, defaulting to true" }
            true
        }
    }

    private suspend fun updateSessionCosts(session: Session) {
        val freshTask = projectRouter.taskRepository.findById(session.id)
        if (freshTask != null) {
            lifecycleService.updateSession(
                session.copy(
                    tokensIn = freshTask.tokensIn,
                    tokensOut = freshTask.tokensOut,
                    costUsd = freshTask.costUsd,
                ),
                persistSettings = false,
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

        scope.launch {
            try {
                val rawTitle = projectRouter.chatRouter.generateSessionTitle(session.id, input)
                val generatedName = sanitizeSessionTitle(rawTitle)
                    .ifBlank { generateSessionNameFallback(input) }

                projectRouter.taskRouter.updateTask(session.id, UpdateTaskRequest(name = generatedName))
                pushSessionRefresh(name = generatedName) ?: return@launch
                logger.info { "Auto-named: '$generatedName'" }
            } catch (e: Exception) {
                val fallback = generateSessionNameFallback(input)
                try {
                    projectRouter.taskRouter.updateTask(session.id, UpdateTaskRequest(name = fallback))
                    pushSessionRefresh(name = fallback) ?: return@launch
                    logger.info { "Auto-named with fallback: '$fallback'" }
                } catch (inner: Exception) {
                    logger.warn(inner) { "Auto-name failed" }
                }
            }
        }
    }

    /**
     * Pull fresh task metrics from DB and push a session update to the UI. Used after
     * any LLM activity that touches the active task — generateSessionTitle adds tokens
     * to the task row through LLMClient centralization, but without this refresh the
     * UI keeps showing the snapshot taken before the auto-name call.
     * Returns null when there is no active session (caller should bail).
     */
    private fun pushSessionRefresh(name: String? = null): Session? {
        val active = stateManager.getActiveSession() ?: return null
        val freshTask = projectRouter.taskRepository.findById(active.id)
        val refreshed = if (freshTask != null) {
            active.copy(
                name = name ?: active.name,
                tokensIn = freshTask.tokensIn,
                tokensOut = freshTask.tokensOut,
                costUsd = freshTask.costUsd,
            )
        } else if (name != null) {
            active.copy(name = name)
        } else {
            active
        }
        // Token/cost deltas + (optionally) the auto-generated name. UI settings did not
        // change — skip the 5-key persistSessionSettings roundtrip that updateSession
        // performs by default.
        lifecycleService.updateSession(refreshed, persistSettings = false)
        return refreshed
    }

    private fun sanitizeSessionTitle(raw: String): String {
        return raw
            .trim()
            .trim('"', '\'', '\u201C', '\u201D')
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
}
