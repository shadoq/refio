package pl.jclab.refio.cli.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import androidx.compose.ui.graphics.Color
import mu.KotlinLogging
import java.nio.file.Path
import java.util.UUID

private val logger = KotlinLogging.logger {}

data class AgentState(
    val id: String,
    val name: String,
    val status: String,
    val color: Color,
    val currentPhase: String? = null,
    val dependsOn: List<String> = emptyList(),
    val tokensUsed: Long = 0,
    val costUsd: Double = 0.0
)

data class MetricsInfo(
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val costUsd: Double = 0.0,
    val totalAgents: Int = 0,
    val completedAgents: Int = 0,
    val totalDurationMs: Long = 0
)

data class PendingApproval(
    val id: String,
    val agentId: String,
    val agentName: String,
    val action: String,
    val risk: String,
    val details: Map<String, String>
)

class RefioViewModel(
    private val projectPath: Path,
    private val mode: TaskMode,
    private val model: String?,
    private val noEgress: Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableStateFlow<List<UIChatMessage>>(emptyList())
    val messages: StateFlow<List<UIChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentState>>(emptyList())
    val agents: StateFlow<List<AgentState>> = _agents.asStateFlow()

    private val _metrics = MutableStateFlow(MetricsInfo())
    val metrics: StateFlow<MetricsInfo> = _metrics.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _agentFilter = MutableStateFlow<String?>(null)
    val agentFilter: StateFlow<String?> = _agentFilter.asStateFlow()

    private var bootstrap: StandaloneCoreBootstrap? = null
    private var router: CoreApiRouter? = null
    private var taskId: String? = null
    val agentEventBus = AgentEventBus()

    private val workflowListener = ComposeWorkflowListener(
        agentId = "main",
        agentName = "Refio",
        agentColor = Color(0xFF6C63FF),
        messagesState = _messages,
        streamingState = _isStreaming,
        scope = scope
    )

    suspend fun initialize() {
        try {
            val boot = StandaloneCoreBootstrap(projectPath)
            val r = boot.initialize()
            bootstrap = boot
            router = r
            _isInitialized.value = true
            logger.info { "Core initialized for project: ${projectPath.toAbsolutePath()}" }

            // Bridge backend event bus to local event bus for UI updates
            bridgeBackendEventBus(r)

            // Subscribe to agent events for multi-agent sessions
            subscribeToAgentEvents()
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize core" }
            _error.value = "Initialization failed: ${e.message}"
        }
    }

    private fun bridgeBackendEventBus(router: pl.jclab.refio.core.api.CoreApiRouter) {
        scope.launch {
            router.agentEventBus.events.collect { event ->
                agentEventBus.emit(event)
            }
        }
    }

    private fun subscribeToAgentEvents() {
        scope.launch {
            agentEventBus.events.collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        // Map to chat message
        val chatMsg = ChatMessageMapper.mapEvent(event)
        if (chatMsg != null) {
            _messages.update { it + chatMsg }
        }

        // Update agent state
        when (event) {
            is AgentEvent.AgentStarted -> {
                val color = ChatMessageMapper.getAgentColor(event.sourceAgentId)
                _agents.update { agents ->
                    agents + AgentState(
                        id = event.sourceAgentId,
                        name = event.agentName,
                        status = "RUNNING",
                        color = color,
                        dependsOn = event.dependsOn
                    )
                }
                _metrics.update { it.copy(totalAgents = it.totalAgents + 1) }
            }

            is AgentEvent.AgentCompleted -> {
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(
                            status = "COMPLETED",
                            tokensUsed = event.tokensUsed,
                            costUsd = event.costUsd
                        ) else it
                    }
                }
                _metrics.update {
                    it.copy(
                        completedAgents = it.completedAgents + 1,
                        tokensIn = it.tokensIn + event.tokensUsed,
                        costUsd = it.costUsd + event.costUsd,
                        totalDurationMs = it.totalDurationMs + event.durationMs
                    )
                }
            }

            is AgentEvent.AgentFailed -> {
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "FAILED") else it
                    }
                }
                _metrics.update { it.copy(completedAgents = it.completedAgents + 1) }
            }

            is AgentEvent.ProgressUpdate -> {
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(currentPhase = event.phase) else it
                    }
                }
            }

            is AgentEvent.ApprovalRequired -> {
                val agentState = _agents.value.find { it.id == event.sourceAgentId }
                _pendingApprovals.update { approvals ->
                    approvals + PendingApproval(
                        id = event.id,
                        agentId = event.sourceAgentId,
                        agentName = agentState?.name ?: event.sourceAgentId,
                        action = event.action,
                        risk = event.risk,
                        details = event.details
                    )
                }
                _agents.update { agents ->
                    agents.map {
                        if (it.id == event.sourceAgentId) it.copy(status = "WAITING_APPROVAL") else it
                    }
                }
            }

            is AgentEvent.ApprovalDecision -> {
                _pendingApprovals.update { approvals ->
                    approvals.filter { it.id != event.approvalId }
                }
                if (event.approved) {
                    _agents.update { agents ->
                        agents.map {
                            if (it.id == event.sourceAgentId) it.copy(status = "RUNNING") else it
                        }
                    }
                }
            }

            else -> { /* Other events handled by chat message mapping */ }
        }
    }

    fun approve(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = true,
                    reason = null
                )
            )
        }
    }

    fun reject(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = false,
                    reason = "Rejected by user"
                )
            )
        }
    }

    fun setAgentFilter(agentId: String?) {
        _agentFilter.value = agentId
    }

    fun sendMessage(input: String) {
        if (input.isBlank() || _isStreaming.value) return

        val userMsg = UIChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "user",
            content = input
        )
        _messages.value = _messages.value + userMsg

        scope.launch {
            val r = router ?: run {
                _error.value = "Core not initialized"
                return@launch
            }

            try {
                _isStreaming.value = true

                val tid = taskId ?: run {
                    val newId = UUID.randomUUID().toString()
                    taskId = newId
                    newId
                }

                val uiState = UIState(
                    taskId = tid,
                    mode = mode,
                    executionMode = ExecutionMode.AUTO,
                    input = input,
                    model = model,
                    provider = null,
                    streamingEnabled = true,
                    thinkingEnabled = false,
                    noEgressEnabled = noEgress
                )

                val request = WorkflowRequest(uiState = uiState)
                r.workflowOrchestrator.execute(request, workflowListener)
            } catch (e: Exception) {
                logger.error(e) { "Workflow error" }
                _isStreaming.value = false
                _messages.value = _messages.value + UIChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "system",
                    content = "Error: ${e.message}",
                    messageType = MessageType.AGENT_FAILED
                )
            }
        }
    }

    fun shutdown() {
        ChatMessageMapper.reset()
        bootstrap?.shutdown()
        bootstrap = null
        router = null
    }
}
