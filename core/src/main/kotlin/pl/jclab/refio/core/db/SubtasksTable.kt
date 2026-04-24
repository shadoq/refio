package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * Subtasks table definition using Exposed ORM DSL
 * Represents individual steps within a task execution plan
 */
object SubtasksTable : Table("subtasks") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val orderIndex = integer("order_index")  // Position in task's step sequence
    val kind = enumerationByName<SubtaskKind>("kind", 32)
    val status = enumerationByName<TaskStatus>("status", 16).default(TaskStatus.NEW)

    // Core step data
    val description = text("description")
    val paramsJson = text("params_json").nullable()  // Tool-specific parameters as JSON
    val stepPlanJson = text("step_plan_json").nullable()  // Planning agent output
    val summary = text("summary").nullable()  // LLM-generated 5-10 sentence summary of step execution

    // Approval workflow
    val requiresApproval = bool("requires_approval").default(false)
    val approvalStatus = enumerationByName<ApprovalStatus>("approval_status", 32).default(ApprovalStatus.NOT_REQUIRED)
    val approvedAt = long("approved_at").nullable()

    // Execution results
    val result = text("result").nullable()
    val errorMessage = text("error_message").nullable()
    val errorStacktrace = text("error_stacktrace").nullable()

    // LLM metrics
    val llmModel = varchar("llm_model", 64).nullable()
    val llmProvider = varchar("llm_provider", 32).nullable()
    val inputTokens = integer("input_tokens").default(0)
    val outputTokens = integer("output_tokens").default(0)
    val costUsd = double("cost_usd").default(0.0)
    val latencyMs = integer("latency_ms").default(0)

    // Snapshots for rollback — references a snapshot group (set of files captured before one tool execution)
    val snapshotIdBeforeWrite = varchar("snapshot_id_before_write", 36).references(SnapshotGroupsTable.id, onDelete = ReferenceOption.SET_NULL).nullable()

    // Timestamps
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    val startedAt = long("started_at").nullable()
    val completedAt = long("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Unique constraint: each task can only have one subtask at a given order_index
        uniqueIndex("uk_task_order", taskId, orderIndex)
    }
}

/**
 * Subtask data class for results
 */
data class Subtask(
    val id: String,
    val taskId: String,
    val orderIndex: Int,
    val kind: SubtaskKind,
    val status: TaskStatus,
    val description: String,
    val paramsJson: String?,
    val stepPlanJson: String?,
    val summary: String?,
    val requiresApproval: Boolean,
    val approvalStatus: ApprovalStatus,
    val approvedAt: Long?,
    val result: String?,
    val errorMessage: String?,
    val errorStacktrace: String?,
    val llmModel: String?,
    val llmProvider: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val latencyMs: Int,
    val snapshotIdBeforeWrite: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)
