package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Per-agent event handler managing DataRequest/Response and Approval flows.
 *
 * Each running agent gets its own handler that:
 * - Listens for events directed at this agent
 * - Manages pending DataRequests with CompletableDeferred
 * - Manages pending Approvals with CompletableDeferred
 */
class AgentEventHandler(
    val agentId: String,
    val sessionId: String,
    private val correlationId: String,
    private val eventBus: AgentEventBus,
    private val scope: CoroutineScope
) {
    private val pendingDataRequests = ConcurrentHashMap<String, CompletableDeferred<AgentEvent.DataResponse>>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<AgentEvent.ApprovalDecision>>()

    private val listenerJob: Job

    init {
        listenerJob = scope.launch {
            eventBus.events
                .filter { shouldHandle(it) }
                .collect { event ->
                    when (event) {
                        is AgentEvent.DataResponse -> {
                            pendingDataRequests[event.requestId]?.complete(event)
                        }
                        is AgentEvent.ApprovalDecision -> {
                            pendingApprovals[event.approvalId]?.complete(event)
                        }
                        else -> { /* no-op for other events */ }
                    }
                }
        }
    }

    private fun shouldHandle(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.DataResponse -> event.targetAgentId == agentId
        is AgentEvent.ApprovalDecision -> pendingApprovals.containsKey(event.approvalId)
        is AgentEvent.DataRequest -> event.targetAgentId == agentId || event.targetAgentId == null
        else -> false
    }

    /**
     * Send a DataRequest and wait for a response.
     */
    suspend fun requestData(
        targetAgentId: String?,
        query: String,
        context: Map<String, String> = emptyMap(),
        timeout: Duration = 60.seconds
    ): AgentEvent.DataResponse? {
        val request = AgentEvent.DataRequest(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sourceAgentId = agentId,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            targetAgentId = targetAgentId,
            query = query,
            context = context
        )

        val deferred = CompletableDeferred<AgentEvent.DataResponse>()
        pendingDataRequests[request.id] = deferred
        eventBus.emit(request)

        return try {
            withTimeout(timeout.inWholeMilliseconds) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingDataRequests.remove(request.id)
            null
        }
    }

    /**
     * Send an ApprovalRequired and wait for user decision.
     */
    suspend fun requestApproval(
        action: String,
        actionType: String,
        risk: String,
        details: Map<String, String>,
        autoApproveAfterMs: Long? = null
    ): Boolean {
        val approval = AgentEvent.ApprovalRequired(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sourceAgentId = agentId,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            action = action,
            actionType = actionType,
            risk = risk,
            details = details
        )

        val deferred = CompletableDeferred<AgentEvent.ApprovalDecision>()
        pendingApprovals[approval.id] = deferred
        eventBus.emit(approval)

        return try {
            val decision = if (autoApproveAfterMs != null) {
                withTimeout(autoApproveAfterMs) { deferred.await() }
            } else {
                deferred.await()
            }
            decision.approved
        } catch (e: TimeoutCancellationException) {
            pendingApprovals.remove(approval.id)
            true // Auto-approve on timeout
        }
    }

    /**
     * Cancel listener and clean up pending requests.
     */
    fun shutdown() {
        listenerJob.cancel()
        pendingDataRequests.values.forEach { it.cancel() }
        pendingApprovals.values.forEach { it.cancel() }
        pendingDataRequests.clear()
        pendingApprovals.clear()
    }
}
