package pl.jclab.refio.core.db.repositories

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.jetbrains.exposed.sql.*
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
class AgentEventSqlRepository : AgentEventRepository {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    override suspend fun save(event: AgentEvent) {
        DatabaseFactory.dbQuery {
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

    override suspend fun findBySessionId(sessionId: String): List<AgentEvent> {
        return DatabaseFactory.dbQuery {
            AgentEventsTable.selectAll()
                .where { AgentEventsTable.sessionId eq sessionId }
                .orderBy(AgentEventsTable.timestamp, SortOrder.ASC)
                .mapNotNull { row -> deserializeEvent(row) }
        }
    }

    override suspend fun findByAgentId(agentId: String): List<AgentEvent> {
        return DatabaseFactory.dbQuery {
            AgentEventsTable.selectAll()
                .where { AgentEventsTable.sourceAgentId eq agentId }
                .orderBy(AgentEventsTable.timestamp, SortOrder.ASC)
                .mapNotNull { row -> deserializeEvent(row) }
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
            "ArtifactProduced" to AgentEvent.ArtifactProduced::class.java,
            "SpawnAgentRequest" to AgentEvent.SpawnAgentRequest::class.java,
            "AgentSpawned" to AgentEvent.AgentSpawned::class.java,
            "ApprovalRequired" to AgentEvent.ApprovalRequired::class.java,
            "ApprovalDecision" to AgentEvent.ApprovalDecision::class.java,
            "ProgressUpdate" to AgentEvent.ProgressUpdate::class.java,
            "StreamChunk" to AgentEvent.StreamChunk::class.java
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
    is AgentEvent.ArtifactProduced -> "ArtifactProduced"
    is AgentEvent.SpawnAgentRequest -> "SpawnAgentRequest"
    is AgentEvent.AgentSpawned -> "AgentSpawned"
    is AgentEvent.ApprovalRequired -> "ApprovalRequired"
    is AgentEvent.ApprovalDecision -> "ApprovalDecision"
    is AgentEvent.ProgressUpdate -> "ProgressUpdate"
    is AgentEvent.StreamChunk -> "StreamChunk"
}
