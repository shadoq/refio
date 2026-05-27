package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session, per-agent queue of incoming [AgentEvent.DataRequest] events.
 *
 * Spec: docs/0054-multiagent.md §3.1 / Step 1.
 *
 * Subscribes to [AgentEventBus] on construction and:
 *  - captures every DataRequest whose `targetAgentId` matches this inbox's agent name,
 *  - drops a request from `pending` once any matching DataResponse appears on the bus
 *    (so it is not re-injected into the next turn if the agent already answered in the
 *    same batch, or another subsystem replied).
 *
 * Subscriptions die with the supplied [scope].
 */
class AgentMessageInbox(
    val agentName: String,
    val sessionId: String,
    eventBus: AgentEventBus,
    scope: CoroutineScope,
) {
    private val pending = ConcurrentHashMap<String, AgentEvent.DataRequest>()

    private val incomingJob: Job = eventBus.events
        .filter {
            it is AgentEvent.DataRequest &&
                it.sessionId == sessionId &&
                it.targetAgentId == agentName
        }
        .onEach {
            val req = it as AgentEvent.DataRequest
            pending[req.id] = req
        }
        .launchIn(scope)

    private val responseJob: Job = eventBus.events
        .filter { it is AgentEvent.DataResponse && it.sessionId == sessionId }
        .onEach {
            val resp = it as AgentEvent.DataResponse
            pending.remove(resp.requestId)
        }
        .launchIn(scope)

    /** Current pending (unanswered) requests targeted at this agent. */
    fun snapshotPending(): List<AgentEvent.DataRequest> = pending.values.toList()

    /** Explicitly drop a request — called by [AnswerMessageTool] after emitting a response. */
    fun markAnswered(requestId: String) {
        pending.remove(requestId)
    }

    /**
     * Cancel both bus subscriptions. Required because the inbox attaches its collectors as
     * children of [scope]; the SharedFlow never completes, so the coroutine that owns [scope]
     * would otherwise wait forever for those children at the end of its body. Launchers must
     * call this in `finally` after the agent's main work completes.
     */
    fun close() {
        incomingJob.cancel()
        responseJob.cancel()
    }
}
