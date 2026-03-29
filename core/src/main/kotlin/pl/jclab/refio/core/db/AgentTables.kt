package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Multi-agent session — groups multiple agent instances working together.
 */
object AgentSessionsTable : Table("agent_sessions") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val projectId = varchar("project_id", 512)
    val name = varchar("name", 255)
    val status = varchar("status", 32).default("NEW") // NEW, RUNNING, COMPLETED, FAILED
    val definitionYaml = text("definition_yaml").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val completedAt = long("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Individual agent instance within a multi-agent session.
 */
object AgentInstancesTable : Table("agent_instances") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val sessionId = varchar("session_id", 36)
        .references(AgentSessionsTable.id, onDelete = ReferenceOption.CASCADE)
    val taskId = varchar("task_id", 36).nullable() // Linked task in tasks table
    val name = varchar("name", 255)
    val profile = varchar("profile", 255).nullable()
    val status = varchar("status", 32).default("PENDING")
    val model = varchar("model", 255).nullable()
    val taskDescription = text("task_description")
    val dependsOn = text("depends_on").nullable() // JSON array of agent names
    val result = text("result").nullable()
    val tokensIn = integer("tokens_in").default(0)
    val tokensOut = integer("tokens_out").default(0)
    val costUsd = double("cost_usd").default(0.0)
    val startedAt = long("started_at").nullable()
    val completedAt = long("completed_at").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_agent_instances_session", false, sessionId)
    }
}

/**
 * Persisted agent events for history and replay.
 */
object AgentEventsTable : Table("agent_events") {
    val id = varchar("id", 36)
    val sessionId = varchar("session_id", 36)
        .references(AgentSessionsTable.id, onDelete = ReferenceOption.CASCADE)
    val sourceAgentId = varchar("source_agent_id", 36)
    val eventType = varchar("event_type", 64) // Class simple name: "AgentStarted", "DataRequest", etc.
    val correlationId = varchar("correlation_id", 36)
    val payloadJson = text("payload_json") // Full event serialized as JSON
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_agent_events_session_ts", false, sessionId, timestamp)
        index("idx_agent_events_source", false, sourceAgentId)
        index("idx_agent_events_type", false, eventType)
    }
}

/**
 * Status enum for agent instances.
 */
enum class AgentInstanceStatus {
    PENDING,
    RUNNING,
    WAITING_DATA,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Data class for agent session.
 */
data class AgentSession(
    val id: String,
    val projectId: String,
    val name: String,
    val status: String,
    val definitionYaml: String?,
    val createdAt: Long,
    val completedAt: Long?
)

/**
 * Data class for agent instance.
 */
data class AgentInstance(
    val id: String,
    val sessionId: String,
    val taskId: String?,
    val name: String,
    val profile: String?,
    val status: String,
    val model: String?,
    val taskDescription: String,
    val dependsOn: String?,
    val result: String?,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val startedAt: Long?,
    val completedAt: Long?,
    val createdAt: Long
)
