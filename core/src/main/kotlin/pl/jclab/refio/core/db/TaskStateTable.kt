package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * What a task needs to survive a restart beyond its messages: the main agent's plan, and the state
 * of a long task (the condition it runs until, its limits, the rounds so far) together with the
 * process that is running it. One row per task, created on first use.
 */
object TaskStateTable : Table("task_state") {
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val planJson = text("plan_json").nullable()
    val untilCommand = text("until_command").nullable()
    val maxRounds = integer("max_rounds").nullable()
    val maxMinutes = long("max_minutes").nullable()
    val loopStatus = varchar("loop_status", 16).nullable()
    val roundsDone = integer("rounds_done").default(0)
    val noProgressRounds = integer("no_progress_rounds").default(0)
    val totalIterations = integer("total_iterations").default(0)
    val activeMs = long("active_ms").default(0)
    val roundsJson = text("rounds_json").nullable()
    val stopReason = varchar("stop_reason", 64).nullable()
    val lastCheckTail = text("last_check_tail").nullable()
    val ownerPid = long("owner_pid").nullable()
    val ownerStartedAt = long("owner_started_at").nullable()
    val updatedAt = long("updated_at").default(0)

    override val primaryKey = PrimaryKey(taskId)
}
