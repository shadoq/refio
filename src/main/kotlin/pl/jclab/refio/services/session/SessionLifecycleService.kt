package pl.jclab.refio.services.session

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.services.logging.dualLogger

class SessionLifecycleService(
    private val project: Project,
    private val projectRouter: CoreApiRouter,
    private val coreApiClient: CoreApiClient,
    private val configService: ConfigService,
    private val stateManager: SessionStateManager,
    private val modeSwitchMutex: Mutex,
    private val projectId: String,
    private val normalizedProjectPath: String,
    private val scope: CoroutineScope
) {

    private val logger = dualLogger("SessionLifecycleService")
    private var selectedMode: TaskMode = TaskMode.CHAT

    fun getSelectedMode(): TaskMode = selectedMode

    fun initialize(
        messageDispatcher: MessageDispatcher,
        subtaskTracker: SubtaskTracker,
        executionMonitor: ExecutionMonitor
    ) {
        loadUIState()

        scope.launchSafe {
            val lastSessionId = PropertiesComponent.getInstance(project)
                .getValue("refio.lastSession")
            logger.info { "Initializing SessionLifecycleService: lastSessionId=${lastSessionId ?: "none"}" }

            if (lastSessionId != null) {
                try {
                    val taskResponse = projectRouter.getTask(lastSessionId)
                    if (taskResponse != null) {
                        val executionModeStr = runBlocking(Dispatchers.IO) {
                            transaction {
                                configService.get(ConfigService.KEY_UI_EXECUTION_MODE)
                            }
                        }
                        val executionMode = try {
                            ExecutionMode.valueOf(executionModeStr ?: "INTERACTIVE")
                        } catch (e: Exception) {
                            ExecutionMode.INTERACTIVE
                        }

                        val session = Session(
                            id = taskResponse.id,
                            name = taskResponse.name,
                            mode = TaskMode.valueOf(taskResponse.mode),
                            status = TaskStatus.valueOf(taskResponse.status),
                            createdAt = taskResponse.createdAt,
                            updatedAt = taskResponse.updatedAt,
                            tokensIn = taskResponse.tokensIn,
                            tokensOut = taskResponse.tokensOut,
                            costUsd = taskResponse.costUsd,
                            executionMode = executionMode,
                            thinkingEnabled = stateManager.getThinkingEnabled(),
                            noEgressEnabled = stateManager.getNoEgressEnabled()
                        )

                        stateManager.setActiveSession(session)
                        selectedMode = TaskMode.valueOf(taskResponse.mode)

                        messageDispatcher.loadMessages()
                        subtaskTracker.loadSubtasks()

                        logger.info { "Restored last session: $lastSessionId" }
                    } else {
                        logger.info { "Last session not found, will create new session on first prompt" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to load last session, will create new session on first prompt" }
                }
            } else {
                logger.info { "No last session, will create new session on first prompt" }
            }

            refreshSelectedModelFromDB()
        }
    }

    suspend fun createSession(
        name: String,
        mode: TaskMode,
        executionMode: ExecutionMode? = null
    ): Session {
        val totalTimeStart = System.currentTimeMillis()
        logger.info { "[PERF] createSession START: name='$name', mode=$mode" }

        // Step 1: Resolve inherited settings
        val step1Start = System.currentTimeMillis()
        val inheritedSettings = resolveSettingsForNewSession()
        val effectiveExecutionMode = executionMode ?: inheritedSettings.executionMode
        logger.info { "[PERF] resolveSettingsForNewSession took ${System.currentTimeMillis() - step1Start}ms" }

        logger.info {
            "Creating session: name='$name', mode=$mode, executionMode=$effectiveExecutionMode, " +
                "selectedModel=${inheritedSettings.selectedModel ?: "auto"}"
        }

        // Step 2: Build request
        val readOnly = mode == TaskMode.PLAN || configService.isReadOnlyMode()
        val request = pl.jclab.refio.core.api.CreateTaskRequest(
            name = name,
            mode = pl.jclab.refio.core.db.TaskMode.valueOf(mode.name),
            projectId = projectId,
            projectPath = normalizedProjectPath,
            readOnly = readOnly
        )

        logger.info { "CreateTaskRequest: mode=$mode, readOnly=$readOnly, projectId=$projectId, projectPath=$normalizedProjectPath" }

        // Step 3: Create task in database (can be slow)
        val step3Start = System.currentTimeMillis()
        val taskResponse = try {
            projectRouter.createTask(request)
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task in database - cannot create session" }
            throw IllegalStateException("Failed to create session: ${e.message}", e)
        }
        logger.info { "[PERF] projectRouter.createTask took ${System.currentTimeMillis() - step3Start}ms" }

        // Step 4: Apply settings to state (should be fast)
        val step4Start = System.currentTimeMillis()
        applySettingsToState(inheritedSettings)
        logger.info { "[PERF] applySettingsToState took ${System.currentTimeMillis() - step4Start}ms" }

        // Step 5: Build session object (fast)
        val session = Session(
            id = taskResponse.id,
            name = taskResponse.name,
            mode = TaskMode.valueOf(taskResponse.mode),
            status = TaskStatus.valueOf(taskResponse.status),
            createdAt = taskResponse.createdAt,
            updatedAt = taskResponse.updatedAt,
            tokensIn = taskResponse.tokensIn,
            tokensOut = taskResponse.tokensOut,
            costUsd = taskResponse.costUsd,
            executionMode = effectiveExecutionMode,
            thinkingEnabled = inheritedSettings.thinkingEnabled,
            noEgressEnabled = inheritedSettings.noEgressEnabled
        )

        // Step 6: Update state manager (fast)
        stateManager.setSessions(stateManager.sessions.value + session)
        stateManager.setActiveSession(session)
        stateManager.clearMessages()
        stateManager.setSubtasks(emptyList())

        selectedMode = mode

        PropertiesComponent.getInstance(project)
            .setValue("refio.lastSession", session.id)

        // Step 7: Persist settings (optimized - only save uiState to task, not APP scope)
        val step7Start = System.currentTimeMillis()
        // Only save uiState to task (APP scope settings are already available in memory or config)
        persistSessionSettingsOptimized(session.id, inheritedSettings.copy(executionMode = effectiveExecutionMode))
        logger.info { "[PERF] persistSessionSettingsOptimized took ${System.currentTimeMillis() - step7Start}ms" }

        val totalTime = System.currentTimeMillis() - totalTimeStart
        logger.info { "[PERF] createSession TOTAL: ${totalTime}ms - session: ${session.id}, mode=${session.mode}, executionMode=${session.executionMode}" }

        return session
    }

    suspend fun switchSession(sessionId: String, messageDispatcher: MessageDispatcher, subtaskTracker: SubtaskTracker) {
        try {
            saveCurrentSessionState()

            val taskResponse = projectRouter.getTask(sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")

            val savedSettings = parseSessionSettings(taskResponse.uiState)
            savedSettings?.let { applySettingsToState(it) }

            val executionMode = savedSettings?.executionMode ?: loadExecutionModePreference()
            val thinkingEnabled = savedSettings?.thinkingEnabled ?: stateManager.getThinkingEnabled()
            val noEgressEnabled = savedSettings?.noEgressEnabled ?: stateManager.getNoEgressEnabled()

            val loadedSession = Session(
                id = taskResponse.id,
                name = taskResponse.name,
                mode = TaskMode.valueOf(taskResponse.mode),
                status = TaskStatus.valueOf(taskResponse.status),
                createdAt = taskResponse.createdAt,
                updatedAt = taskResponse.updatedAt,
                executionMode = executionMode,
                thinkingEnabled = thinkingEnabled,
                noEgressEnabled = noEgressEnabled,
                tokensIn = taskResponse.tokensIn,
                tokensOut = taskResponse.tokensOut,
                costUsd = taskResponse.costUsd,
                pinned = taskResponse.pinned,
                rate = taskResponse.rate
            )

            stateManager.setActiveSession(loadedSession)
            selectedMode = TaskMode.valueOf(taskResponse.mode)

            PropertiesComponent.getInstance(project)
                .setValue("refio.lastSession", sessionId)

            saveCurrentSessionState()

            messageDispatcher.loadMessages()
            subtaskTracker.loadSubtasks()

            logger.info {
                "Switched to session $sessionId, executionMode=$executionMode, " +
                    "thinking=${stateManager.getThinkingEnabled()}, noEgress=${stateManager.getNoEgressEnabled()}"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to switch session" }
        }
    }

    suspend fun loadSession(
        sessionId: String,
        messageDispatcher: MessageDispatcher,
        subtaskTracker: SubtaskTracker,
        executionMonitor: ExecutionMonitor
    ) {
        modeSwitchMutex.lock()
        try {
            logger.info { "Loading session: $sessionId" }

            saveCurrentSessionState()

            val taskResponse = projectRouter.getTask(sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")

            val savedSettings = parseSessionSettings(taskResponse.uiState)
            savedSettings?.let { applySettingsToState(it) }

            val executionMode = savedSettings?.executionMode ?: loadExecutionModePreference()
            val thinkingEnabled = savedSettings?.thinkingEnabled ?: stateManager.getThinkingEnabled()
            val noEgressEnabled = savedSettings?.noEgressEnabled ?: stateManager.getNoEgressEnabled()

            val session = Session(
                id = taskResponse.id,
                name = taskResponse.name,
                mode = TaskMode.valueOf(taskResponse.mode),
                status = TaskStatus.valueOf(taskResponse.status),
                createdAt = taskResponse.createdAt,
                updatedAt = taskResponse.updatedAt,
                executionMode = executionMode,
                thinkingEnabled = thinkingEnabled,
                noEgressEnabled = noEgressEnabled,
                tokensIn = taskResponse.tokensIn,
                tokensOut = taskResponse.tokensOut,
                costUsd = taskResponse.costUsd,
                pinned = taskResponse.pinned,
                rate = taskResponse.rate
            )

            stateManager.setActiveSession(session)
            selectedMode = TaskMode.valueOf(taskResponse.mode)

            messageDispatcher.loadMessages()
            subtaskTracker.loadSubtasks()

            logger.info {
                "Session loaded: ${session.name} (mode=${session.mode}, " +
                    "messages=${stateManager.messages.value.size}, subtasks=${stateManager.subtasks.value.size})"
            }

            if (session.mode in listOf(TaskMode.PLAN, TaskMode.AGENT)) {
                val pendingSubtasks = stateManager.subtasks.value.filter {
                    it.status == "PENDING" || it.status == "NEW" || it.status == "PLANNED"
                }
                if (pendingSubtasks.isNotEmpty()) {
                    logger.info { "Pending subtasks detected: ${pendingSubtasks.size} - awaiting AgentTurnLoop input" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load session" }
        } finally {
            modeSwitchMutex.unlock()
        }
    }

    fun updateSession(session: Session) {
        logger.info { "Updating session: id=${session.id}, mode=${session.mode}, executionMode=${session.executionMode}" }
        stateManager.updateSession(session)
        saveCurrentSessionState()
    }

    suspend fun switchModeSafely(newMode: TaskMode): Session? {
        modeSwitchMutex.lock()
        try {
            val currentSession = stateManager.getActiveSession() ?: run {
                logger.warn { "Cannot switch mode - no active session" }
                return null
            }

            if (!doesTaskExist(currentSession.id)) {
                logger.warn {
                    "Active session task not found in DB: ${currentSession.id}. " +
                        "Creating replacement session in mode=$newMode"
                }
                return createSession(
                    name = "New Session",
                    mode = newMode,
                    executionMode = currentSession.executionMode
                )
            }

            if (currentSession.mode == newMode) {
                logger.info { "Mode already set to $newMode, skipping" }
                return currentSession
            }

            val updatedSession = switchMode(currentSession, newMode)
            updateSession(updatedSession)

            if (newMode == TaskMode.CHAT) {
                stateManager.setSubtasks(emptyList())
            }

            return updatedSession
        } catch (e: Exception) {
            logger.error(e) { "Failed to switch mode" }
            throw e
        } finally {
            modeSwitchMutex.unlock()
        }
    }

    suspend fun switchMode(currentSession: Session, newMode: TaskMode): Session {
        logger.info { "Switching mode from ${currentSession.mode} to $newMode for task ${currentSession.id}" }
        val coreMode = pl.jclab.refio.core.db.TaskMode.valueOf(newMode.name)
        val updateRequest = UpdateTaskRequest(mode = coreMode)
        val response = projectRouter.updateTask(currentSession.id, updateRequest)

        val updatedSession = currentSession.copy(
            mode = TaskMode.valueOf(response.mode)
        )

        stateManager.setActiveSession(updatedSession)
        selectedMode = TaskMode.valueOf(response.mode)
        logger.info { "Successfully switched mode to $newMode" }

        return updatedSession
    }

    /**
     * Ensure active session exists in DB.
     * If missing or stale, creates a new task-based session and makes it active.
     */
    suspend fun ensureActiveSessionExists(
        preferredMode: TaskMode? = null,
        preferredExecutionMode: ExecutionMode? = null
    ): Session {
        val activeSession = stateManager.getActiveSession()
        if (activeSession == null) {
            val modeToUse = preferredMode ?: selectedMode
            return createSession(
                name = "New Session",
                mode = modeToUse,
                executionMode = preferredExecutionMode
            )
        }

        if (doesTaskExist(activeSession.id)) {
            return activeSession
        }

        val modeToUse = preferredMode ?: activeSession.mode
        val executionModeToUse = preferredExecutionMode ?: activeSession.executionMode
        logger.warn {
            "Active session points to missing task: ${activeSession.id}. " +
                "Creating replacement session (mode=$modeToUse, executionMode=$executionModeToUse)"
        }

        return createSession(
            name = "New Session",
            mode = modeToUse,
            executionMode = executionModeToUse
        )
    }

    suspend fun refreshSelectedModelFromDB() {
        val session = stateManager.getActiveSession()
        if (session == null) {
            logger.warn { "No active session, cannot refresh model" }
            return
        }

        try {
            val selectedModel = coreApiClient.getConfigValue("ui", "selected_model")
                ?.takeIf { it.isNotBlank() }
                ?: "auto"

            stateManager.setSelectedModel(selectedModel)
            logger.info { "Refreshed selected model from DB: $selectedModel (mode=${session.mode})" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh selected model from DB" }
        }
    }

    fun setSelectedModel(model: String) {
        stateManager.setSelectedModel(model)
        logger.info { "Selected model set to: $model" }
        saveCurrentSessionState()
    }

    /**
     * Set selected model in ConfigService (persistent storage).
     * Use "auto" to enable operation-specific model selection.
     */
    fun setSelectedModelConfig(model: String) {
        configService.setSelectedModel(model)
        logger.info { "Selected model config set to: $model" }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        logger.info { "Execution mode set to: $mode" }
        stateManager.getActiveSession()?.let { session ->
            stateManager.setActiveSession(session.copy(executionMode = mode))
        }
        saveCurrentSessionState()
    }

    fun setThinkingEnabled(enabled: Boolean) {
        stateManager.setThinkingEnabled(enabled)
        logger.info { "Thinking mode set to: $enabled" }
        val activeSession = stateManager.getActiveSession()
        if (activeSession != null) {
            val session = activeSession
            stateManager.setActiveSession(session.copy(thinkingEnabled = enabled))
        } else {
            setUiSettingDefaults(ConfigService.KEY_UI_THINKING_ENABLED, enabled.toString())
        }
        saveCurrentSessionState()
    }

    fun setNoEgressEnabled(enabled: Boolean) {
        stateManager.setNoEgressEnabled(enabled)
        logger.info { "No-egress mode set to: $enabled" }
        val activeSession = stateManager.getActiveSession()
        if (activeSession != null) {
            val session = activeSession
            stateManager.setActiveSession(session.copy(noEgressEnabled = enabled))
        } else {
            setUiSettingDefaults(ConfigService.KEY_UI_NO_EGRESS_ENABLED, enabled.toString())
        }
        saveCurrentSessionState()
    }

    suspend fun getAvailableModels(): List<String> {
        val models = projectRouter.getModelsWithVisibility()
        return models
            .filter { it.showInDropdown }
            .map { model ->
                val provider = model.provider.replaceFirstChar { it.uppercase() }
                "$provider/${model.id}"
            }
    }

    fun getDefaultModelForMode(): String {
        val sessionMode = stateManager.getActiveSession()?.mode ?: TaskMode.CHAT
        val operation = when (sessionMode) {
            TaskMode.PLAN -> pl.jclab.refio.core.api.ModelOperation.PLAN
            TaskMode.AGENT -> pl.jclab.refio.core.api.ModelOperation.CODING
            TaskMode.CHAT -> pl.jclab.refio.core.api.ModelOperation.DEFAULT
        }

        val response = projectRouter.getDefaultModel(operation)
        val provider = response.provider.replaceFirstChar { it.uppercase() }
        return "$provider/${response.modelId}"
    }

    fun setDefaultModelAllModes(modelId: String, provider: String) {
        projectRouter.setDefaultModelAllModes(
            pl.jclab.refio.core.api.SetDefaultModelAllModesRequest(
                modelId = modelId,
                provider = provider
            )
        )
    }

    fun getMaxContextWindow(): Int {
        val session = stateManager.getActiveSession()
        if (session == null) {
            logger.debug { "No active session, using default context window: 8192" }
            return DEFAULT_CONTEXT_SIZE
        }

        val modelString = stateManager.getSelectedModel()
        val modelId = if (modelString.contains("/")) {
            modelString.split("/").getOrNull(1) ?: "qwen2.5:7b"
        } else {
            modelString
        }

        return try {
            val modelConfig = pl.jclab.refio.core.llm.getModelConfigFromCache(modelId)

            val modelMaxContext = modelConfig?.maxContext ?: DEFAULT_CONTEXT_SIZE
            val configuredLimit = configService.getMaxContextSize(session.id)
            val effectiveContextWindow = minOf(modelMaxContext, configuredLimit)

            logger.debug {
                "Context window for model '$modelId': model=$modelMaxContext, " +
                    "limit=$configuredLimit, effective=$effectiveContextWindow tokens"
            }
            effectiveContextWindow

        } catch (e: Exception) {
            logger.warn(e) { "Failed to get context window for model '$modelId', using default: $DEFAULT_CONTEXT_SIZE" }
            DEFAULT_CONTEXT_SIZE
        }
    }

    fun saveCurrentSessionState() {
        val session = stateManager.getActiveSession() ?: return
        val settings = captureCurrentSettings(session.executionMode)
        logger.debug {
            "Saving session state: taskId=${session.id}, executionMode=${session.executionMode}, " +
                "thinking=${settings.thinkingEnabled}, noEgress=${settings.noEgressEnabled}"
        }

        // Launch in background coroutine for non-blocking save
        scope.launch(Dispatchers.IO) {
            persistSessionSettingsBlocking(session.id, settings)
        }
    }

    /**
     * Synchronous version of persistSessionSettings for use in non-suspend contexts.
     * Should be called within runBlocking or coroutine scope.
     */
    private fun persistSessionSettingsBlocking(taskId: String, settings: SessionSettings) {
        try {
            logger.debug { "Persisting session settings (blocking): taskId=$taskId" }
            runBlocking(Dispatchers.IO) {
                setUiSettingDefaults(
                    ConfigService.KEY_UI_SELECTED_MODEL,
                    settings.selectedModel ?: "auto",
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_THINKING_ENABLED,
                    settings.thinkingEnabled.toString(),
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_NO_EGRESS_ENABLED,
                    settings.noEgressEnabled.toString(),
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_EXECUTION_MODE,
                    settings.executionMode.name,
                )

                configService.set(
                    ConfigService.KEY_UI_SELECTED_MODE,
                    selectedMode.name,
                    ConfigScope.APP
                )
            }

            projectRouter.updateTask(
                taskId,
                pl.jclab.refio.core.api.UpdateTaskRequest(
                    uiState = settings.toJson()
                )
            )
        } catch (e: Exception) {
            if (isTaskNotFoundException(e)) {
                logger.warn { "Skipping session settings persistence for missing task: $taskId" }
                return
            }
            logger.error(e) { "Failed to persist session settings" }
        }
    }

    private fun loadUIState() {
        val modeStr = runBlocking(Dispatchers.IO) {
            transaction {
                configService.get(ConfigService.KEY_UI_SELECTED_MODE)
            }
        }
        selectedMode = runCatching { TaskMode.valueOf(modeStr ?: TaskMode.CHAT.name) }
            .getOrDefault(TaskMode.CHAT)

        val appDefaults = settingsFromScope(ConfigScope.APP)
        appDefaults?.let { applySettingsToState(it) }

        logger.info {
            "Loaded UI state from config: model=${stateManager.getSelectedModel()}, " +
                "thinking=${stateManager.getThinkingEnabled()}, noEgress=${stateManager.getNoEgressEnabled()}, " +
            "mode=$selectedMode"
        }
    }

    private fun resolveSettingsForNewSession(): SessionSettings {
        val activeSession = stateManager.getActiveSession()
        if (activeSession != null) {
            logger.debug {
                "Using in-memory settings as inheritance source for new session: sessionId=${activeSession.id}"
            }
            return captureCurrentSettings(activeSession.executionMode)
        }

        return loadInheritedSettings()
    }

    private fun loadInheritedSettings(): SessionSettings {
        val lastSession = runCatching {
            projectRouter.getLastSessionForProject(projectId)
        }.getOrNull()

        val lastSettings = lastSession?.uiState?.let { parseSessionSettings(it) }
        if (lastSettings != null) {
            return lastSettings
        }

        return settingsFromScope(ConfigScope.APP) ?: SessionSettings.default()
    }

    private fun settingsFromScope(
        scope: ConfigScope,
        projectId: String? = null,
        taskId: String? = null
    ): SessionSettings? {
        var hasAny = false

        val selectedModel = configService.get(
            ConfigService.KEY_UI_SELECTED_MODEL,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }

        val thinkingEnabled = configService.get(
            ConfigService.KEY_UI_THINKING_ENABLED,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.toBoolean()

        val noEgressEnabled = configService.get(
            ConfigService.KEY_UI_NO_EGRESS_ENABLED,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.toBoolean()
            ?: if (scope == ConfigScope.APP && configService.isNoEgressDefault()) {
                hasAny = true
                true
            } else {
                null
            }

        val executionModeValue = configService.get(
            ConfigService.KEY_UI_EXECUTION_MODE,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }

        val orchestrationEnabled = configService.get(
            ConfigService.KEY_UI_ORCHESTRATION_ENABLED,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.toBoolean()

        val intentClassificationEnabled = configService.get(
            ConfigService.KEY_UI_INTENT_CLASSIFICATION_ENABLED,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.toBoolean()

        if (!hasAny) {
            return null
        }

        return SessionSettings(
            selectedModel = selectedModel,
            thinkingEnabled = thinkingEnabled ?: false,
            noEgressEnabled = noEgressEnabled ?: false,
            executionMode = parseExecutionMode(executionModeValue)
        )
    }

    private fun parseExecutionMode(value: String?): ExecutionMode =
        parseExecutionModeValue(value)

    private fun applySettingsToState(settings: SessionSettings) {
        settings.selectedModel?.let { stateManager.setSelectedModel(it) }
        stateManager.setThinkingEnabled(settings.thinkingEnabled)
        stateManager.setNoEgressEnabled(settings.noEgressEnabled)
    }

    private fun captureCurrentSettings(currentExecutionMode: ExecutionMode): SessionSettings =
        SessionSettings(
            selectedModel = stateManager.getSelectedModel(),
            thinkingEnabled = stateManager.getThinkingEnabled(),
            noEgressEnabled = stateManager.getNoEgressEnabled(),
            executionMode = currentExecutionMode
        )

    private suspend fun persistSessionSettings(taskId: String, settings: SessionSettings) {
        try {
            logger.debug { "Persisting session settings: taskId=$taskId" }

            // Use withContext instead of runBlocking to avoid blocking the coroutine
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                setUiSettingDefaults(
                    ConfigService.KEY_UI_SELECTED_MODEL,
                    settings.selectedModel ?: "auto",
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_THINKING_ENABLED,
                    settings.thinkingEnabled.toString(),
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_NO_EGRESS_ENABLED,
                    settings.noEgressEnabled.toString(),
                )
                setUiSettingDefaults(
                    ConfigService.KEY_UI_EXECUTION_MODE,
                    settings.executionMode.name,
                )

                configService.set(
                    ConfigService.KEY_UI_SELECTED_MODE,
                    selectedMode.name,
                    ConfigScope.APP
                )
            }

            projectRouter.updateTask(
                taskId,
                pl.jclab.refio.core.api.UpdateTaskRequest(
                    uiState = settings.toJson()
                )
            )
        } catch (e: Exception) {
            if (isTaskNotFoundException(e)) {
                logger.warn { "Skipping session settings persistence for missing task: $taskId" }
                return
            }
            logger.error(e) { "Failed to persist session settings" }
        }
    }

    /**
     * Optimized version of persistSessionSettings that only saves uiState to task.
     * This avoids redundant APP scope writes when creating new sessions.
     */
    private suspend fun persistSessionSettingsOptimized(taskId: String, settings: SessionSettings) {
        try {
            logger.debug { "Persisting session settings (optimized): taskId=$taskId" }

            // Only save uiState to task (APP scope is already managed separately)
            projectRouter.updateTask(
                taskId,
                pl.jclab.refio.core.api.UpdateTaskRequest(
                    uiState = settings.toJson()
                )
            )

            logger.debug { "Session settings persisted (optimized): taskId=$taskId" }
        } catch (e: Exception) {
            if (isTaskNotFoundException(e)) {
                logger.warn { "Skipping optimized session settings persistence for missing task: $taskId" }
                return
            }
            logger.error(e) { "Failed to persist session settings (optimized)" }
        }
    }

    private fun doesTaskExist(taskId: String): Boolean {
        return try {
            projectRouter.getTask(taskId) != null
        } catch (e: Exception) {
            if (isTaskNotFoundException(e)) {
                false
            } else {
                throw e
            }
        }
    }

    private fun isTaskNotFoundException(e: Exception): Boolean {
        return e is IllegalArgumentException && e.message?.contains("Task not found:") == true
    }

    private fun setUiSettingDefaults(key: String, value: String) {
        configService.set(key, value, ConfigScope.APP)
    }

    private fun parseSessionSettings(json: String?): SessionSettings? {
        json ?: return null
        return try {
            val payload = pl.jclab.refio.core.utils.GsonInstance.gson.fromJson(
                json,
                SessionSettingsPayload::class.java
            )
            payload?.toSettings()
        } catch (e: Exception) {
            logger.warn { "Failed to parse uiState JSON: ${e.message}" }
            null
        }
    }

    private fun SessionSettings.toJson(): String {
        val payload = SessionSettingsPayload(
            selectedModel = selectedModel,
            thinkingEnabled = thinkingEnabled,
            noEgressEnabled = noEgressEnabled,
            executionMode = executionMode.name
        )
        return pl.jclab.refio.core.utils.GsonInstance.gson.toJson(payload)
    }

    private fun loadExecutionModePreference(): ExecutionMode {
        val executionModeStr = runBlocking(Dispatchers.IO) {
            transaction {
                configService.get(ConfigService.KEY_UI_EXECUTION_MODE)
            }
        }
        return parseExecutionMode(executionModeStr)
    }

    private data class SessionSettings(
        val selectedModel: String?,
        val thinkingEnabled: Boolean,
        val noEgressEnabled: Boolean,
        val executionMode: ExecutionMode
    ) {
        companion object {
            fun default() = SessionSettings(
                selectedModel = null,
                thinkingEnabled = false,
                noEgressEnabled = false,
                executionMode = ExecutionMode.INTERACTIVE
            )
        }
    }

    private data class SessionSettingsPayload(
        val selectedModel: String? = null,
        val thinkingEnabled: Boolean = false,
        val noEgressEnabled: Boolean = false,
        val executionMode: String = ExecutionMode.INTERACTIVE.name
    ) {
        fun toSettings(): SessionSettings = SessionSettings(
            selectedModel = selectedModel,
            thinkingEnabled = thinkingEnabled,
            noEgressEnabled = noEgressEnabled,
            executionMode = parseExecutionModeValue(executionMode)
        )
    }
}

private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    this.launch(Dispatchers.IO) { block() }
}

private fun parseExecutionModeValue(value: String?): ExecutionMode {
    if (value.isNullOrBlank()) {
        return ExecutionMode.INTERACTIVE
    }

    val normalized = value.trim()
    return runCatching { ExecutionMode.valueOf(normalized.uppercase()) }.getOrNull()
        ?: runCatching { ExecutionMode.fromApiValue(normalized.lowercase()) }.getOrNull()
        ?: ExecutionMode.INTERACTIVE
}
