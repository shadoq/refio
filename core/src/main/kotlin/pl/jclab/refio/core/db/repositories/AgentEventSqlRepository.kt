package pl.jclab.refio.core.db.repositories

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventRepository
import pl.jclab.refio.core.agents.events.Artifact
import pl.jclab.refio.core.db.AgentEventsTable
import pl.jclab.refio.core.db.DatabaseFactory

/**
 * SQL-backed implementation of [AgentEventRepository].
 *
 * Persists agent events to the agent_events table for history replay
 * and debugging. Uses Gson for JSON serialization of event payloads.
 */
class AgentEventSqlRepository(
    /**
     * Database this repository writes to, captured when it is wired up.
     *
     * Event saves are queued by [pl.jclab.refio.core.agents.events.AgentEventBus] and run later on
     * Dispatchers.IO, so resolving the database lazily meant a queued insert could execute against
     * whatever default happened to be registered by then - observed as a lost AgentStarted with
     * "no such table: agent_events". Pinning it here keeps every save on the database the bus was
     * wired to. Null falls back to Exposed's default, which is correct for a single-database run.
     */
    private val database: Database? = TransactionManager.defaultDatabase
) : AgentEventRepository {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    override suspend fun save(event: AgentEvent) {
        DatabaseFactory.suspendDbQuery(database) {
            AgentEventsTable.insert {
                it[id] = event.id
                it[sessionId] = event.sessionId
                it[sourceAgentId] = event.sourceAgentId
                it[eventType] = event.eventTypeName()
                it[correlationId] = event.correlationId
                it[payloadJson] = gson.toJson(event)
                it[timestamp] = event.timestamp
            }
        }
    }

    override suspend fun findBySessionId(sessionId: String, limit: Int): List<AgentEvent> {
        // Fetch the NEWEST `limit` rows, then restore ascending order for replay.
        return DatabaseFactory.suspendDbQuery(database) {
            AgentEventsTable.selectAll()
                .where { AgentEventsTable.sessionId eq sessionId }
                .orderBy(AgentEventsTable.timestamp, SortOrder.DESC)
                .limit(limit)
                .mapNotNull { row -> deserializeEvent(row) }
                .asReversed()
        }
    }

    override suspend fun findByAgentId(agentId: String, limit: Int): List<AgentEvent> {
        return DatabaseFactory.suspendDbQuery(database) {
            AgentEventsTable.selectAll()
                .where { AgentEventsTable.sourceAgentId eq agentId }
                .orderBy(AgentEventsTable.timestamp, SortOrder.DESC)
                .limit(limit)
                .mapNotNull { row -> deserializeEvent(row) }
                .asReversed()
        }
    }

    private fun deserializeEvent(row: ResultRow): AgentEvent? {
        val eventType = row[AgentEventsTable.eventType]
        val json = row[AgentEventsTable.payloadJson]

        return try {
            val clazz = EVENT_TYPE_MAP[eventType] ?: return null
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val EVENT_TYPE_MAP: Map<String, Class<out AgentEvent>> = mapOf(
            "AgentStarted" to AgentEvent.AgentStarted::class.java,
            "AgentCompleted" to AgentEvent.AgentCompleted::class.java,
            "AgentFailed" to AgentEvent.AgentFailed::class.java,
            "DataRequest" to AgentEvent.DataRequest::class.java,
            "DataResponse" to AgentEvent.DataResponse::class.java,
            "ApprovalRequired" to AgentEvent.ApprovalRequired::class.java,
            "ApprovalDecision" to AgentEvent.ApprovalDecision::class.java,
            "ProgressUpdate" to AgentEvent.ProgressUpdate::class.java,
            "StreamChunk" to AgentEvent.StreamChunk::class.java,
            "TurnStarted" to AgentEvent.TurnStarted::class.java,
            "TurnEnded" to AgentEvent.TurnEnded::class.java,
            "LLMCallCompleted" to AgentEvent.LLMCallCompleted::class.java,
            "ToolCalled" to AgentEvent.ToolCalled::class.java,
            "StreamAborted" to AgentEvent.StreamAborted::class.java
        )
    }
}

/**
 * Returns the simple event type name for persistence.
 */
private fun AgentEvent.eventTypeName(): String = when (this) {
    is AgentEvent.AgentStarted -> "AgentStarted"
    is AgentEvent.AgentCompleted -> "AgentCompleted"
    is AgentEvent.AgentFailed -> "AgentFailed"
    is AgentEvent.DataRequest -> "DataRequest"
    is AgentEvent.DataResponse -> "DataResponse"
    is AgentEvent.ApprovalRequired -> "ApprovalRequired"
    is AgentEvent.ApprovalDecision -> "ApprovalDecision"
    is AgentEvent.ProgressUpdate -> "ProgressUpdate"
    is AgentEvent.StreamChunk -> "StreamChunk"
    is AgentEvent.TurnStarted -> "TurnStarted"
    is AgentEvent.TurnEnded -> "TurnEnded"
    is AgentEvent.LLMCallCompleted -> "LLMCallCompleted"
    is AgentEvent.ToolCalled -> "ToolCalled"
    is AgentEvent.StreamAborted -> "StreamAborted"
}
