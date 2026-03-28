package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Task status enum
 */
enum class TaskStatus {
    NEW,
    PENDING,
    PLANNED,  // Subtask has been prepared but not yet executed (US-101)
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}

/**
 * Task mode enum
 */
enum class TaskMode {
    CHAT,
    PLAN,
    AGENT
}

/**
 * Execution mode enum for approval workflow
 */
enum class ExecutionMode {
    INTERACTIVE,
    AUTO
}

/**
 * Approval status enum
 */
enum class ApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    SKIPPED,
    AUTO_APPROVED,
    NOT_REQUIRED
}

/**
 * Subtask kind enum
 */
enum class SubtaskKind {
    PLAN_STEP,
    PROJECT_ANALYSIS,
    CODE_EDITING,
    ADVANCE_CODE_EDITING,
    MULTI_LINE_EDITOR,
    CREATE_NEW_FILE,
    MULTI_EDIT,
    READ_FILE,
    READ_DIRECTORY,
    VIEW_DIFF,
    FILE_SEARCH,
    GREP_SEARCH,
    RUN_TERMINAL_COMMAND,
    HTTP_REQUEST,
    RUN_CODE,
    KNOWLEDGE_BASE,
    INVOKE_SUBAGENT
}

/**
 * Message role enum
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

/**
 * Config scope enum
 */
enum class ConfigScope {
    APP,
    PROJECT,
    TASK
}

/**
 * Tasks table definition using Exposed ORM DSL
 */
object TasksTable : Table("tasks") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val name = varchar("name", 255)
    val mode = enumerationByName<TaskMode>("mode", 16)
    val status = enumerationByName<TaskStatus>("status", 16).default(TaskStatus.NEW)
    val readOnly = bool("read_only").default(false)
    val pinned = bool("pinned").default(false)
    val executionMode = enumerationByName<ExecutionMode>("execution_mode", 16).default(ExecutionMode.INTERACTIVE)
    val requiresPlanApproval = bool("requires_plan_approval").default(false)
    val planApproved = bool("plan_approved").default(false)
    val rate = integer("rate").nullable()  // User rating: 1 (positive) or -1 (negative), null if not rated
    val tokensIn = integer("tokens_in").default(0)  // Total input tokens for this task
    val tokensOut = integer("tokens_out").default(0)  // Total output tokens for this task
    val costUsd = double("cost_usd").default(0.0)  // Total cost in USD for this task
    val uiState = text("ui_state").nullable()  // JSON: {selectedModel, thinkingEnabled, noEgressEnabled}
    val coreApiVersion = varchar("core_api_version", 16).nullable()
    val projectId = varchar("project_id", 512).default("legacy_unknown")
    val projectPath = text("project_path").default("unknown")
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    // Plan execution tracking (US-001: Plan as Specification)
    val sourcePlanId = varchar("source_plan_id", 128).nullable()  // Link to source plan for AGENT sessions
    val planVersion = integer("plan_version").nullable()  // Plan version at execution time

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_tasks_project", false, projectId)
        index("idx_tasks_project_created", false, projectId, createdAt)
        index("idx_tasks_source_plan", false, sourcePlanId)
    }
}

/**
 * Task data class for results
 */
data class Task(
    val id: String,
    val name: String,
    val mode: TaskMode,
    val status: TaskStatus,
    val readOnly: Boolean,
    val pinned: Boolean,
    val executionMode: ExecutionMode,
    val requiresPlanApproval: Boolean,
    val planApproved: Boolean,
    val uiState: String?,  // JSON: {selectedModel, thinkingEnabled, noEgressEnabled}
    val coreApiVersion: String?,
    val projectId: String,
    val projectPath: String,
    val rate: Int? = null,  // User rating: 1 (positive) or -1 (negative), null if not rated
    val tokensIn: Int = 0,  // Total input tokens for this task
    val tokensOut: Int = 0,  // Total output tokens for this task
    val costUsd: Double = 0.0,  // Total cost in USD for this task
    val sourcePlanId: String? = null,  // Link to source plan for AGENT sessions (US-001)
    val planVersion: Int? = null,  // Plan version at execution time (US-001)
    val createdAt: Long,
    val updatedAt: Long
)
