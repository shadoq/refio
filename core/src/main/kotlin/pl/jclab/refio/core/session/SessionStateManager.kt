package pl.jclab.refio.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.api.PlanResponse
import pl.jclab.refio.core.api.PlanSpecStepResponse
import pl.jclab.refio.core.logging.dualLogger

/**
 * UI-agnostic session state holder.
 *
 * Trzyma 13 StateFlow reprezentujących **execution state** sesji (active session, history, mode,
 * pending tools, subtasks). Żadne pole nie jest UI-specific — Plugin i TUI obserwują tę samą
 * instancję. Przeniesione z `:intellij-plugin/services/session/`.
 */
class SessionStateManager {

    private val logger = dualLogger("SessionStateManager")
    private val messagesMutex = Mutex()

    private val _activeSession = MutableStateFlow<Session?>(null)
    val activeSession: StateFlow<Session?> = _activeSession.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _subtasks = MutableStateFlow<List<SubtaskResponse>>(emptyList())
    val subtasks: StateFlow<List<SubtaskResponse>> = _subtasks.asStateFlow()

    // Plan state (for PLAN mode sessions)
    private val _activePlan = MutableStateFlow<PlanResponse?>(null)
    val activePlan: StateFlow<PlanResponse?> = _activePlan.asStateFlow()

    private val _planSteps = MutableStateFlow<List<PlanSpecStepResponse>>(emptyList())
    val planSteps: StateFlow<List<PlanSpecStepResponse>> = _planSteps.asStateFlow()

    private val _selectedModel = MutableStateFlow("Ollama/qwen2.5-coder:14b")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private val _noEgressEnabled = MutableStateFlow(false)
    val noEgressEnabled: StateFlow<Boolean> = _noEgressEnabled.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _pendingContextRefs = MutableStateFlow<List<ContextReference>>(emptyList())
    val pendingContextRefs: StateFlow<List<ContextReference>> = _pendingContextRefs.asStateFlow()

    private val _pendingUserInput = MutableStateFlow("")
    val pendingUserInput: StateFlow<String> = _pendingUserInput.asStateFlow()

    private val _contextSectionTokens = MutableStateFlow<Map<String, ContextSectionTokenInfo>>(emptyMap())
    val contextSectionTokens: StateFlow<Map<String, ContextSectionTokenInfo>> =
        _contextSectionTokens.asStateFlow()

    private val _totalEstimatedTokens = MutableStateFlow(0)
    val totalEstimatedTokens: StateFlow<Int> = _totalEstimatedTokens.asStateFlow()

    // Transient snapshot of a native tool call being assembled during streaming.
    // Non-null only while the model streams a tool call's arguments; cleared when that LLM
    // turn's stream completes. UI renders a "⚙ building <tool>(<args>)" indicator.
    private val _toolCallProgress = MutableStateFlow<pl.jclab.refio.core.api.ToolCallProgress?>(null)
    val toolCallProgress: StateFlow<pl.jclab.refio.core.api.ToolCallProgress?> = _toolCallProgress.asStateFlow()

    fun setToolCallProgress(progress: pl.jclab.refio.core.api.ToolCallProgress?) {
        _toolCallProgress.value = progress
    }

    fun setActiveSession(session: Session?) {
        val previousId = _activeSession.value?.id
        _activeSession.value = session
        // A switch to a different (or no) session leaves behind any transient tool-call
        // progress from the previous turn — STOP/cancel never delivers the completion chunk
        // that normally clears it. Reset on id change only, so same-session metric refreshes
        // (auto-naming, token bumps) don't flicker the live "⚙ building" indicator off.
        if (session?.id != previousId) {
            _toolCallProgress.value = null
        }
    }

    fun setSessions(sessions: List<Session>) {
        _sessions.value = sessions
    }

    fun updateSession(session: Session) {
        _activeSession.value = session
        _sessions.value = _sessions.value.map { if (it.id == session.id) session else it }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun setSubtasks(subtasks: List<SubtaskResponse>) {
        _subtasks.value = subtasks
    }

    fun setActivePlan(plan: PlanResponse?) {
        _activePlan.value = plan
    }

    fun setPlanSteps(steps: List<PlanSpecStepResponse>) {
        _planSteps.value = steps
    }

    fun setSelectedModel(model: String) {
        _selectedModel.value = model
    }

    fun setThinkingEnabled(enabled: Boolean) {
        _thinkingEnabled.value = enabled
    }

    fun setNoEgressEnabled(enabled: Boolean) {
        _noEgressEnabled.value = enabled
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun setIsGenerating(isGenerating: Boolean) {
        _isGenerating.value = isGenerating
    }

    fun setPendingContextRefs(refs: List<ContextReference>) {
        _pendingContextRefs.value = refs
    }

    fun setPendingUserInput(input: String) {
        _pendingUserInput.value = input
    }

    fun setContextSectionTokens(tokens: Map<String, ContextSectionTokenInfo>) {
        _contextSectionTokens.value = tokens
    }

    fun setTotalEstimatedTokens(tokens: Int) {
        _totalEstimatedTokens.value = tokens
    }

    suspend fun appendMessage(message: Message) {
        messagesMutex.withLock {
            _messages.value = _messages.value + message
        }
    }

    suspend fun updateMessages(update: (List<Message>) -> List<Message>) {
        messagesMutex.withLock {
            _messages.value = update(_messages.value)
        }
    }

    fun getActiveSession(): Session? = _activeSession.value

    fun getSubtasks(): List<SubtaskResponse> = _subtasks.value

    fun getSelectedModel(): String = _selectedModel.value

    fun getPendingUserInput(): String = _pendingUserInput.value

    fun getTotalEstimatedTokens(): Int = _totalEstimatedTokens.value

    fun getContextSectionTokens(): Map<String, ContextSectionTokenInfo> = _contextSectionTokens.value

    fun getIsPaused(): Boolean = _isPaused.value

    fun getIsGenerating(): Boolean = _isGenerating.value

    fun getThinkingEnabled(): Boolean = _thinkingEnabled.value

    fun getNoEgressEnabled(): Boolean = _noEgressEnabled.value

    fun debugStateSnapshot() {
        logger.debug {
            "State snapshot: session=${_activeSession.value?.id}, " +
                "messages=${_messages.value.size}, subtasks=${_subtasks.value.size}"
        }
    }
}
