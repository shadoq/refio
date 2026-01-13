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

    fun createSession(
        name: String,
        mode: TaskMode,
        executionMode: ExecutionMode? = null
    ): Session {
        val inheritedSettings = loadInheritedSettings()
        val effectiveExecutionMode = executionMode ?: inheritedSettings.executionMode

        logger.info {
            "Creating session: name='$name', mode=$mode, executionMode=$effectiveExecutionMode, " +
                "selectedModel=${inheritedSettings.selectedModel ?: "auto"}"
        }
        val readOnly = mode == TaskMode.PLAN || configService.isReadOnlyMode()
        val request = pl.jclab.refio.core.api.CreateTaskRequest(
            name = name,
            mode = pl.jclab.refio.core.db.TaskMode.valueOf(mode.name),
            projectId = projectId,
            projectPath = normalizedProjectPath,
            readOnly = readOnly
        )

        logger.info { "CreateTaskRequest: mode=$mode, readOnly=$readOnly, projectId=$projectId" }

        val taskResponse = try {
            projectRouter.createTask(request)
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task in database - cannot create session" }
            throw IllegalStateException("Failed to create session: ${e.message}", e)
        }

        applySettingsToState(inheritedSettings)

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
            noEgressEnabled = inheritedSettings.noEgressEnabled,
            orchestrationEnabled = inheritedSettings.orchestrationEnabled,
            intentClassificationEnabled = inheritedSettings.intentClassificationEnabled
        )

        stateManager.setSessions(stateManager.sessions.value + session)
        stateManager.setActiveSession(session)
        stateManager.clearMessages()
        stateManager.setSubtasks(emptyList())

        selectedMode = mode

        PropertiesComponent.getInstance(project)
            .setValue("refio.lastSession", session.id)

        persistSessionSettings(session.id, inheritedSettings.copy(executionMode = effectiveExecutionMode))

        logger.info { "Created session: ${session.id}, mode=${session.mode}, executionMode=${session.executionMode}" }

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
            val orchestrationEnabled = savedSettings?.orchestrationEnabled ?: stateManager.getOrchestrationEnabled()
            val intentClassificationEnabled =
                savedSettings?.intentClassificationEnabled ?: stateManager.getIntentClassificationEnabled()

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
                rate = taskResponse.rate,
                orchestrationEnabled = orchestrationEnabled,
                intentClassificationEnabled = intentClassificationEnabled
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
            val orchestrationEnabled = savedSettings?.orchestrationEnabled ?: stateManager.getOrchestrationEnabled()
            val intentClassificationEnabled =
                savedSettings?.intentClassificationEnabled ?: stateManager.getIntentClassificationEnabled()

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
                orchestrationEnabled = orchestrationEnabled,
                intentClassificationEnabled = intentClassificationEnabled,
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
                    logger.info { "Resuming execution: ${pendingSubtasks.size} pending subtasks" }
                    if (session.executionMode == ExecutionMode.INTERACTIVE) {
                        executionMonitor.showApprovalMessageForNextSubtask()
                    } else {
                        executionMonitor.executeAutoMode()
                    }
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

    suspend fun refreshSelectedModelFromDB() {
        val session = stateManager.getActiveSession()
        if (session == null) {
            logger.warn { "No active session, cannot refresh model" }
            return
        }

        try {
            val response = coreApiClient.getDefaultModel(pl.jclab.refio.core.api.ModelOperation.DEFAULT, taskId = null)
            val modelString = "${response.provider}/${response.modelId}"

            stateManager.setSelectedModel(modelString)
            logger.info { "Refreshed selected model from DB: $modelString (mode=${session.mode})" }

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

    fun setThinkingEnabled(enabled: Boolean) {
        stateManager.setThinkingEnabled(enabled)
        logger.info { "Thinking mode set to: $enabled" }
        stateManager.getActiveSession()?.let { session ->
            stateManager.setActiveSession(session.copy(thinkingEnabled = enabled))
        }
        saveCurrentSessionState()
    }

    fun setNoEgressEnabled(enabled: Boolean) {
        stateManager.setNoEgressEnabled(enabled)
        logger.info { "No-egress mode set to: $enabled" }
        stateManager.getActiveSession()?.let { session ->
            stateManager.setActiveSession(session.copy(noEgressEnabled = enabled))
        }
        saveCurrentSessionState()
    }

    fun setOrchestrationEnabled(enabled: Boolean) {
        stateManager.setOrchestrationEnabled(enabled)
        logger.info { "Orchestration mode set to: $enabled" }
        stateManager.getActiveSession()?.let { session ->
            stateManager.setActiveSession(session.copy(orchestrationEnabled = enabled))
        }

        saveCurrentSessionState()
    }

    fun setIntentClassificationEnabled(enabled: Boolean) {
        stateManager.setIntentClassificationEnabled(enabled)
        logger.info { "Intent classification set to: $enabled" }
        stateManager.getActiveSession()?.let { session ->
            stateManager.setActiveSession(session.copy(intentClassificationEnabled = enabled))
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
            val modelConfig = runBlocking {
                pl.jclab.refio.core.llm.getModelConfig(modelId, configService)
            }

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
                "thinking=${settings.thinkingEnabled}, noEgress=${settings.noEgressEnabled}, " +
                "orchestration=${settings.orchestrationEnabled}, intentClassification=${settings.intentClassificationEnabled}"
        }
        persistSessionSettings(session.id, settings)
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

        val projectDefaults = settingsFromScope(ConfigScope.PROJECT, projectId = projectId)
        projectDefaults?.let { applySettingsToState(it) }

        logger.info {
            "Loaded UI state from config: model=${stateManager.getSelectedModel()}, " +
                "thinking=${stateManager.getThinkingEnabled()}, noEgress=${stateManager.getNoEgressEnabled()}, " +
                "mode=$selectedMode"
        }
    }

    private fun loadInheritedSettings(): SessionSettings {
        val lastSession = runCatching {
            projectRouter.getLastSessionForProject(projectId)
        }.getOrNull()

        val lastSettings = lastSession?.uiState?.let { parseSessionSettings(it) }
        if (lastSettings != null) {
            return lastSettings
        }

        val projectDefaults = settingsFromScope(
            ConfigScope.PROJECT,
            projectId = projectId
        )
        if (projectDefaults != null) {
            return projectDefaults
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
            executionMode = parseExecutionMode(executionModeValue),
            orchestrationEnabled = orchestrationEnabled ?: false,
            intentClassificationEnabled = intentClassificationEnabled ?: false
        )
    }

    private fun parseExecutionMode(value: String?): ExecutionMode =
        parseExecutionModeValue(value)

    private fun applySettingsToState(settings: SessionSettings) {
        settings.selectedModel?.let { stateManager.setSelectedModel(it) }
        stateManager.setThinkingEnabled(settings.thinkingEnabled)
        stateManager.setNoEgressEnabled(settings.noEgressEnabled)
        stateManager.setOrchestrationEnabled(settings.orchestrationEnabled)
        stateManager.setIntentClassificationEnabled(settings.intentClassificationEnabled)
    }

    private fun captureCurrentSettings(currentExecutionMode: ExecutionMode): SessionSettings =
        SessionSettings(
            selectedModel = stateManager.getSelectedModel(),
            thinkingEnabled = stateManager.getThinkingEnabled(),
            noEgressEnabled = stateManager.getNoEgressEnabled(),
            executionMode = currentExecutionMode,
            orchestrationEnabled = stateManager.getOrchestrationEnabled(),
            intentClassificationEnabled = stateManager.getIntentClassificationEnabled()
        )

    private fun persistSessionSettings(taskId: String, settings: SessionSettings) {
        try {
            logger.debug { "Persisting session settings: taskId=$taskId" }
            runBlocking(Dispatchers.IO) {
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_SELECTED_MODEL,
                    settings.selectedModel ?: "auto",
                    taskId
                )
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_THINKING_ENABLED,
                    settings.thinkingEnabled.toString(),
                    taskId
                )
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_NO_EGRESS_ENABLED,
                    settings.noEgressEnabled.toString(),
                    taskId
                )
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_EXECUTION_MODE,
                    settings.executionMode.name,
                    taskId
                )
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_ORCHESTRATION_ENABLED,
                    settings.orchestrationEnabled.toString(),
                    taskId
                )
                setSettingAcrossScopes(
                    ConfigService.KEY_UI_INTENT_CLASSIFICATION_ENABLED,
                    settings.intentClassificationEnabled.toString(),
                    taskId
                )
                configService.set(
                    ConfigService.KEY_UI_SELECTED_MODE,
                    selectedMode.name,
                    ConfigScope.PROJECT,
                    projectId = projectId
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
            logger.error(e) { "Failed to persist session settings" }
        }
    }

    private fun setSettingAcrossScopes(key: String, value: String, taskId: String) {
        configService.set(key, value, ConfigScope.TASK, taskId = taskId)
        configService.set(key, value, ConfigScope.PROJECT, projectId = projectId)
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
            executionMode = executionMode.name,
            orchestrationEnabled = orchestrationEnabled,
            intentClassificationEnabled = intentClassificationEnabled
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
        val executionMode: ExecutionMode,
        val orchestrationEnabled: Boolean,
        val intentClassificationEnabled: Boolean
    ) {
        companion object {
            fun default() = SessionSettings(
                selectedModel = null,
                thinkingEnabled = false,
                noEgressEnabled = false,
                executionMode = ExecutionMode.INTERACTIVE,
                orchestrationEnabled = false,
                intentClassificationEnabled = false
            )
        }
    }

    private data class SessionSettingsPayload(
        val selectedModel: String? = null,
        val thinkingEnabled: Boolean = false,
        val noEgressEnabled: Boolean = false,
        val executionMode: String = ExecutionMode.INTERACTIVE.name,
        val orchestrationEnabled: Boolean = false,
        val intentClassificationEnabled: Boolean = false
    ) {
        fun toSettings(): SessionSettings = SessionSettings(
            selectedModel = selectedModel,
            thinkingEnabled = thinkingEnabled,
            noEgressEnabled = noEgressEnabled,
            executionMode = parseExecutionModeValue(executionMode),
            orchestrationEnabled = orchestrationEnabled,
            intentClassificationEnabled = intentClassificationEnabled
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
