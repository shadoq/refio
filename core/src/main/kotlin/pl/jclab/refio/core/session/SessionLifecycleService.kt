package pl.jclab.refio.core.session

import pl.jclab.refio.core.session.SessionStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.api.UpdateTaskRequest
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.ModelWindow
import pl.jclab.refio.core.llm.ReasoningEffort
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.logging.dualLogger

class SessionLifecycleService(
    private val projectRouter: CoreApiRouter,
    private val configService: ConfigService,
    private val stateManager: SessionStateManager,
    private val modeSwitchMutex: Mutex,
    private val projectId: String,
    private val normalizedProjectPath: String,
    private val scope: CoroutineScope
) {

    private val logger = dualLogger("SessionLifecycleService")
    private var selectedMode: TaskMode = TaskMode.CHAT

    // Tracks the startup UI-state load so that user-driven lifecycle ops
    // (createSession/switchSession/loadSession) wait for it before mutating
    // session state. Without this, a fast "New Session" click can create a
    // session before the persisted mode/settings are known.
    private var initializeJob: Job? = null

    suspend fun awaitInitialization() {
        initializeJob?.join()
    }

    fun getSelectedMode(): TaskMode = selectedMode

    /**
     * Startup only restores persisted UI settings (mode, model, toggles) - never a
     * previous conversation. Each IDE start begins with an empty chat; the session
     * itself is created on the first prompt. Earlier conversations stay reachable
     * from the history panel.
     */
    fun initialize() {
        initializeJob = scope.launchSafeJob {
            loadUIState()
            logger.info { "Startup UI state loaded, starting with a clean session (mode=$selectedMode)" }
        }
    }

    suspend fun createSession(
        name: String,
        mode: TaskMode,
        executionMode: ExecutionMode? = null
    ): Session {
        awaitInitialization()
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
        val readOnly = mode == TaskMode.PLAN || configService.getTyped(ConfigKeys.READ_ONLY_MODE)
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
            projectRouter.taskRouter.createTask(request)
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
            cachedTokens = taskResponse.cachedTokens,
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
        awaitInitialization()
        try {
            saveCurrentSessionState()

            val taskResponse = projectRouter.taskRouter.getTask(sessionId)
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
                cachedTokens = taskResponse.cachedTokens,
                costUsd = taskResponse.costUsd,
                pinned = taskResponse.pinned,
                rate = taskResponse.rate
            )

            stateManager.setActiveSession(loadedSession)
            selectedMode = TaskMode.valueOf(taskResponse.mode)

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

    @Suppress("UNUSED_PARAMETER")
    suspend fun loadSession(
        sessionId: String,
        messageDispatcher: MessageDispatcher,
        subtaskTracker: SubtaskTracker,
        _executionMonitor: ExecutionMonitor
    ) {
        awaitInitialization()
        modeSwitchMutex.lock()
        try {
            logger.info { "Loading session: $sessionId" }

            saveCurrentSessionState()

            val taskResponse = projectRouter.taskRouter.getTask(sessionId)
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
                cachedTokens = taskResponse.cachedTokens,
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

    /**
     * Push a session update to the StateFlow. When [persistSettings] is true (default),
     * the per-session UI/general settings (model, thinking, no-egress, exec mode, ui mode)
     * are also written to the config table — five DB roundtrips. Pass false for refreshes
     * that only carry token/cost deltas (e.g. after auto-naming) — UI settings haven't
     * changed and don't need to round-trip through the DB just to bump the token counter.
     */
    fun updateSession(session: Session, persistSettings: Boolean = true) {
        logger.info { "Updating session: id=${session.id}, mode=${session.mode}, executionMode=${session.executionMode}, persistSettings=$persistSettings" }
        stateManager.updateSession(session)
        if (persistSettings) {
            saveCurrentSessionState()
        }
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
        val response = projectRouter.taskRouter.updateTask(currentSession.id, updateRequest)

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
            val selectedModel = configService.get("ui.selected_model", ConfigScope.APP, null)
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
            stateManager.setActiveSession(activeSession.copy(thinkingEnabled = enabled))
        }
        // This coarse on/off toggle maps to MEDIUM/OFF. The fine-grained level (LOW/HIGH)
        // is chosen in Settings and owned by GENERAL_REASONING_EFFORT; session autosave does
        // not write it back (see persist paths) so it can't downgrade a HIGH selection.
        setUiSettingDefaults(
            ConfigKeys.GENERAL_REASONING_EFFORT.key,
            if (enabled) ReasoningEffort.MEDIUM.name else ReasoningEffort.OFF.name,
        )
        saveCurrentSessionState()
    }

    fun setNoEgressEnabled(enabled: Boolean) {
        stateManager.setNoEgressEnabled(enabled)
        logger.info { "No-egress mode set to: $enabled" }
        val activeSession = stateManager.getActiveSession()
        if (activeSession != null) {
            stateManager.setActiveSession(activeSession.copy(noEgressEnabled = enabled))
        } else {
            setUiSettingDefaults(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, enabled.toString())
        }
        saveCurrentSessionState()
    }

    /**
     * @param fetchIfMissing false = answer from the model cache only, never call providers.
     *        Callers that just redraw the dropdown (e.g. after closing Settings) pass false:
     *        a provider fetch can block for seconds behind a running turn, and the settings
     *        screens already refresh the provider they touched.
     */
    suspend fun getAvailableModels(fetchIfMissing: Boolean = true): List<String> {
        val models = projectRouter.configRouter.getModelsWithVisibility(fetchIfMissing = fetchIfMissing)
        val visibleModels = models.filter { it.showInDropdown }
        val modelsForDropdown = if (visibleModels.isNotEmpty() || models.isEmpty()) {
            visibleModels
        } else {
            logger.warn {
                "No models marked as visible in dropdown; falling back to all ${models.size} available models"
            }
            models
        }

        return modelsForDropdown
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

        val response = projectRouter.configRouter.getDefaultModel(operation)
        val provider = response.provider.replaceFirstChar { it.uppercase() }
        return "$provider/${response.modelId}"
    }

    fun setDefaultModelAllModes(modelId: String, provider: String) {
        projectRouter.configRouter.setDefaultModelAllModes(
            pl.jclab.refio.core.api.SetDefaultModelAllModesRequest(
                modelId = modelId,
                provider = provider
            )
        )
    }

    fun getMaxContextWindow(): Int {
        val session = stateManager.getActiveSession()
        if (session == null) {
            logger.debug { "No active session, using default context window: $DEFAULT_CONTEXT_SIZE" }
            return DEFAULT_CONTEXT_SIZE
        }

        return try {
            // Resolve (model, provider) and the window through the SAME path the engine's context
            // budget uses (ContextBudgetResolver.resolveContextSize): getModel() applies the canonical
            // selected-model parsing + provider fallback, and ModelWindow is the single resolver
            // (provider override → ModelDefinitions prefix match → MAX_CONTEXT_SIZE fallback). This
            // keeps the StatusBar window in lockstep with the context panel.
            //
            // The previous implementation parsed the id with split("/")[1], which for multi-segment
            // OpenRouter model strings ("openrouter/deepseek/deepseek-v4-flash") grabbed the vendor
            // segment ("deepseek"), missed every cache/definition lookup, and fell back to
            // DEFAULT_CONTEXT_SIZE (32768) — the StatusBar showed 32K while the engine used 128K.
            //
            // Operation is DEFAULT because the UI exposes a single mode-independent model selector and
            // a user-selected model wins for every operation; this matches the old behaviour (which
            // read getSelectedModel() regardless of mode).
            val (model, provider) = configService.getModel(ModelOperation.DEFAULT, session.id)
            val window = ModelWindow.resolve(provider, model, configService, session.id)
            logger.debug { "Context window for model '$model' (provider=$provider): $window tokens" }
            window
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get context window, using default: $DEFAULT_CONTEXT_SIZE" }
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
            persistSessionSettingsSuspending(session.id, settings)
        }
    }

    /**
     * Suspend version used from `scope.launch(Dispatchers.IO)`. The previous `runBlocking`
     * was redundant — the launch context already dispatches on IO.
     */
    private suspend fun persistSessionSettingsSuspending(taskId: String, settings: SessionSettings) {
        try {
            logger.debug { "Persisting session settings: taskId=$taskId" }
            // Caller already dispatches on Dispatchers.IO via scope.launch.
            setUiSettingDefaults(ConfigKeys.UI_SELECTED_MODEL.key, settings.selectedModel ?: "auto")
            // Reasoning effort is owned by GENERAL_REASONING_EFFORT (Settings / coarse toggle),
            // not autosaved here, so a HIGH selection is never downgraded to the mirror boolean.
            setUiSettingDefaults(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, settings.noEgressEnabled.toString())
            setUiSettingDefaults(ConfigKeys.GENERAL_EXECUTION_MODE.key, settings.executionMode.name)
            configService.set(ConfigKeys.UI_SELECTED_MODE.key, selectedMode.name, ConfigScope.APP)

            projectRouter.taskRouter.updateTask(
                taskId,
                pl.jclab.refio.core.api.UpdateTaskRequest(uiState = settings.toJson())
            )
        } catch (e: Exception) {
            if (isTaskNotFoundException(e)) {
                logger.warn { "Skipping session settings persistence for missing task: $taskId" }
                return
            }
            logger.error(e) { "Failed to persist session settings" }
        }
    }

    private suspend fun loadUIState() {
        val modeStr = withContext(Dispatchers.IO) {
            transaction {
                configService.get(ConfigKeys.UI_SELECTED_MODE.key)
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

        val inherited = loadInheritedSettings()

        // A concrete model chosen in the model dropdown lives only in stateManager (and as the
        // turn's per-request override), while the inheritance path above re-derives the model
        // from config / last session and drops that choice. Without this, the CODING slot used by
        // advance_code_editing keeps reading the stale ui.selected_model and generates file content
        // with the wrong model. When the user has an explicit selection - proven by the inheritance
        // path already resolving a model, i.e. the app is configured - let the live dropdown model
        // win so every slot (turn + CODING) uses it. Skip on a pristine install (no inherited model)
        // to preserve the auto / per-operation defaults.
        val liveModel = stateManager.getSelectedModel()
        if (inherited.selectedModel != null && inherited.selectedModel != liveModel) {
            logger.info { "Applying live model selection to new session: $liveModel (was ${inherited.selectedModel})" }
            return inherited.copy(selectedModel = liveModel)
        }

        return inherited
    }

    private fun loadInheritedSettings(): SessionSettings {
        val lastSession = runCatching {
            projectRouter.taskRouter.getLastSessionForProject(projectId)
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
            ConfigKeys.UI_SELECTED_MODEL.key,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }

        val thinkingEnabled = configService.get(
            ConfigKeys.GENERAL_REASONING_EFFORT.key,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.let { ReasoningEffort.parse(it)?.isOn ?: false }

        val noEgressEnabled = configService.get(
            ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }?.toBoolean()

        val executionModeValue = configService.get(
            ConfigKeys.GENERAL_EXECUTION_MODE.key,
            scope,
            taskId = taskId,
            projectId = projectId
        )?.also { hasAny = true }

        @Suppress("UNUSED_VARIABLE") val _intentClassificationEnabled = configService.get(
            ConfigKeys.UI_INTENT_CLASSIFICATION_ENABLED.key,
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
                    ConfigKeys.UI_SELECTED_MODEL.key,
                    settings.selectedModel ?: "auto",
                )
                // Reasoning effort is owned by GENERAL_REASONING_EFFORT (Settings / coarse
                // toggle), not autosaved here, so a HIGH selection is never downgraded.
                setUiSettingDefaults(
                    ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key,
                    settings.noEgressEnabled.toString(),
                )
                setUiSettingDefaults(
                    ConfigKeys.GENERAL_EXECUTION_MODE.key,
                    settings.executionMode.name,
                )

                configService.set(
                    ConfigKeys.UI_SELECTED_MODE.key,
                    selectedMode.name,
                    ConfigScope.APP
                )
            }

            projectRouter.taskRouter.updateTask(
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

            // The selected model must reach the APP-scope config, not just the task uiState blob:
            // model-slot resolution for the CODING slot (advance_code_editing) reads
            // ui.selected_model via ConfigService precedence (TASK > PROJECT > APP), which never
            // consults the task uiState JSON. When the dropdown model is changed before any session
            // exists, saveCurrentSessionState() early-returns (no active session) and never flushes
            // it, so without this write the CODING slot keeps resolving the stale APP value while the
            // turn LLM (per-request override) already uses the new one - the file gets generated by
            // the wrong provider.
            configService.set(
                ConfigKeys.UI_SELECTED_MODEL.key,
                settings.selectedModel ?: "auto",
                ConfigScope.APP
            )

            projectRouter.taskRouter.updateTask(
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
            projectRouter.taskRouter.getTask(taskId) != null
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

    private suspend fun loadExecutionModePreference(): ExecutionMode {
        val executionModeStr = withContext(Dispatchers.IO) {
            transaction {
                configService.get(ConfigKeys.GENERAL_EXECUTION_MODE.key)
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

private fun CoroutineScope.launchSafeJob(block: suspend () -> Unit): Job =
    this.launch(Dispatchers.IO) { block() }

private fun parseExecutionModeValue(value: String?): ExecutionMode {
    if (value.isNullOrBlank()) {
        return ExecutionMode.INTERACTIVE
    }

    val normalized = value.trim()
    return runCatching { ExecutionMode.valueOf(normalized.uppercase()) }.getOrNull()
        ?: runCatching { ExecutionMode.fromApiValue(normalized.lowercase()) }.getOrNull()
        ?: ExecutionMode.INTERACTIVE
}
