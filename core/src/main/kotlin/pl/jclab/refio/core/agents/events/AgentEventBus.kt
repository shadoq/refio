package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("AgentEventBus")

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
     *
     * CRITICAL: live emission must NEVER be blocked by a repository failure.
     * Previously a DB save exception would propagate and prevent `_events.emit`
     * from running, which silently killed the GUI Trace/Graph tracking whenever
     * the `agent_events` table was not yet migrated or the DB was momentarily
     * unavailable. Persistence is now best-effort and logged on failure.
     */
    suspend fun emit(event: AgentEvent) {
        try {
            eventRepository?.save(event)
        } catch (e: Exception) {
            logger.warn { "Failed to persist agent event (${event::class.simpleName}): ${e.message}" }
        }
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

    /** Turn lifecycle events (for Session Trace panel) */
    fun turnEvents(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.TurnStarted ||
                it is AgentEvent.TurnEnded ||
                it is AgentEvent.LLMCallCompleted ||
                it is AgentEvent.ToolCalled ||
                it is AgentEvent.StreamAborted
            )
        }

    /**
     * Non-suspend emit for callers that cannot suspend (e.g. Swing listeners).
     * Skips repository persistence. Returns false if the event buffer is full.
     */
    fun tryEmit(event: AgentEvent): Boolean = _events.tryEmit(event)

    /**
     * Load persisted events for a session from the backing repository (if any).
     *
     * Used by the GUI to replay the full Trace/Timeline/Graph state after the user
     * reloads a conversation from history. Returns an empty list when no repository
     * is wired or when the session has no persisted events.
     */
    suspend fun loadPersistedEvents(sessionId: String): List<AgentEvent> =
        eventRepository?.findBySessionId(sessionId) ?: emptyList()

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
