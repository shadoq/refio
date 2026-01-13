package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Plan status enum
 * Represents the lifecycle state of a plan
 */
enum class PlanStatus {
    DRAFT,      // Editable, being created/modified
    READY,      // Finalized, ready to execute
    EXECUTING,  // Currently executing (locked from edits)
    EXECUTED    // Completed execution
}

/**
 * Plans table definition using Exposed ORM DSL
 * Plans are specifications created in PLAN mode sessions
 * Separate from execution (Subtasks) for clean separation of concerns
 */
object PlansTable : Table("plans") {
    val id = varchar("id", 128).clientDefault { UUID.randomUUID().toString() }
    val sessionId = varchar("session_id", 128)
        .references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 500)
    val description = text("description").nullable()
    val status = enumerationByName<PlanStatus>("status", 20).default(PlanStatus.DRAFT)
    val version = integer("version").default(1)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    val finalizedAt = long("finalized_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_plans_session", false, sessionId)
        index("idx_plans_status", false, status)
        index("idx_plans_session_status", false, sessionId, status)
    }
}

/**
 * Plan data class for results
 * Represents a specification/blueprint for task execution
 */
data class Plan(
    val id: String,
    val sessionId: String,
    val name: String,
    val description: String?,
    val status: PlanStatus,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val finalizedAt: Long?
)
