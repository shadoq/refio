package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("AgentEventBus")

/**
 * Central event bus for multi-agent communication.
 *
 * SharedFlow-based with replay for late GUI subscribers.
 * Optionally persists events via AgentEventRepository.
 */
class AgentEventBus : AutoCloseable {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 200,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile
    private var eventRepository: AgentEventRepository? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceQueue = Channel<PersistenceItem>(PERSISTENCE_QUEUE_CAPACITY)

    init {
        persistenceScope.launch {
            for (item in persistenceQueue) {
                when (item) {
                    is PersistenceItem.Event -> persist(item.value)
                    is PersistenceItem.Flush -> item.done.complete(Unit)
                }
            }
        }
    }

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
        _events.emit(event)
        if (eventRepository != null && persistenceQueue.trySend(PersistenceItem.Event(event)).isFailure) {
            logger.warn { "Agent event persistence queue is full; dropping persistence for ${event::class.simpleName}" }
        }
    }

    private suspend fun persist(event: AgentEvent) {
        try {
            eventRepository?.save(event)
        } catch (e: Exception) {
            logger.warn { "Failed to persist agent event (${event::class.simpleName}): ${e.message}" }
        }
    }

    internal suspend fun flushPersistence() {
        val done = CompletableDeferred<Unit>()
        persistenceQueue.send(PersistenceItem.Flush(done))
        done.await()
    }

    override fun close() {
        persistenceQueue.close()
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
                it is AgentEvent.AgentFailed
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

    private sealed interface PersistenceItem {
        data class Event(val value: AgentEvent) : PersistenceItem
        data class Flush(val done: CompletableDeferred<Unit>) : PersistenceItem
    }

    companion object {
        private const val PERSISTENCE_QUEUE_CAPACITY = 1000
    }
}

/**
 * Repository interface for event persistence.
 */
interface AgentEventRepository {

    companion object {
        /**
         * Default cap for event history queries. Sessions can accumulate an unbounded
         * number of events (StreamChunk especially); replay loads the newest
         * [DEFAULT_EVENT_LIMIT] rows in ascending order instead of the whole table.
         */
        const val DEFAULT_EVENT_LIMIT: Int = 5000
    }

    suspend fun save(event: AgentEvent)

    /** Returns up to [limit] NEWEST events for the session, sorted ascending for replay. */
    suspend fun findBySessionId(sessionId: String, limit: Int = DEFAULT_EVENT_LIMIT): List<AgentEvent>

    /** Returns up to [limit] NEWEST events for the agent, sorted ascending. */
    suspend fun findByAgentId(agentId: String, limit: Int = DEFAULT_EVENT_LIMIT): List<AgentEvent>
}
