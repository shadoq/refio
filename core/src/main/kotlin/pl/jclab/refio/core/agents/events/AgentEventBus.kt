package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * Central event bus for multi-agent communication.
 *
 * SharedFlow-based with replay for late GUI subscribers.
 * Optionally persists events via AgentEventRepository.
 */
class AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 200,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private var eventRepository: AgentEventRepository? = null

    fun setRepository(repo: AgentEventRepository) {
        eventRepository = repo
    }

    /**
     * Emit an event to all subscribers and optionally persist.
     */
    suspend fun emit(event: AgentEvent) {
        eventRepository?.save(event)
        _events.emit(event)
    }

    // ── Filtered subscriptions ──

    /** All events for a given multi-agent session */
    fun sessionEvents(sessionId: String): Flow<AgentEvent> =
        events.filter { it.sessionId == sessionId }

    /** All events emitted by a specific agent */
    fun agentEvents(agentId: String): Flow<AgentEvent> =
        events.filter { it.sourceAgentId == agentId }

    /** Lifecycle events only (for DAG visualization) */
    fun lifecycleEvents(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.AgentStarted ||
                it is AgentEvent.AgentCompleted ||
                it is AgentEvent.AgentFailed ||
                it is AgentEvent.AgentSpawned
            )
        }

    /** Stream chunks + lifecycle (for interleaved chat) */
    fun chatStream(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.StreamChunk ||
                it is AgentEvent.AgentStarted ||
                it is AgentEvent.AgentCompleted ||
                it is AgentEvent.AgentFailed ||
                it is AgentEvent.ArtifactProduced ||
                it is AgentEvent.ApprovalRequired ||
                it is AgentEvent.DataRequest ||
                it is AgentEvent.DataResponse
            )
        }

    /** Pending approval events */
    fun approvalEvents(sessionId: String): Flow<AgentEvent.ApprovalRequired> =
        events.filterIsInstance<AgentEvent.ApprovalRequired>()
            .filter { it.sessionId == sessionId }

    /** Events of a specific type */
    inline fun <reified T : AgentEvent> eventsOfType(): Flow<T> =
        events.filterIsInstance<T>()
}

/**
 * Repository interface for event persistence.
 */
interface AgentEventRepository {
    suspend fun save(event: AgentEvent)
    suspend fun findBySessionId(sessionId: String): List<AgentEvent>
    suspend fun findByAgentId(agentId: String): List<AgentEvent>
}
