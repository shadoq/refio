package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import pl.jclab.refio.core.db.TaskStateTable
import pl.jclab.refio.core.services.PlanStore
import pl.jclab.refio.core.services.maintenance.LeaseStore

/** One task's row of [pl.jclab.refio.core.db.TaskStateTable]. */
data class TaskStateRow(
    val taskId: String,
    val planJson: String? = null,
    val untilCommand: String? = null,
    val maxRounds: Int? = null,
    val maxMinutes: Long? = null,
    val loopStatus: String? = null,
    val roundsDone: Int = 0,
    val noProgressRounds: Int = 0,
    val totalIterations: Int = 0,
    val activeMs: Long = 0,
    val roundsJson: String? = null,
    val stopReason: String? = null,
    val lastCheckTail: String? = null,
    val ownerPid: Long? = null,
    val ownerStartedAt: Long? = null,
    val updatedAt: Long = 0,
)

/**
 * Reads and writes [TaskStateTable]. Three writers share one row and each owns its columns: the plan
 * ([savePlan]), the long-task loop ([upsert]) and the process lease ([claimLease], [releaseLease]).
 * A row is created by whichever of them comes first.
 */
class TaskStateRepository(private val clock: () -> Long = System::currentTimeMillis) : PlanStore, LeaseStore {

    fun get(taskId: String): TaskStateRow? = transaction {
        TaskStateTable.select { TaskStateTable.taskId eq taskId }.singleOrNull()?.toRow()
    }

    /** Writes the long-task columns; the plan and the lease are left as they are. */
    fun upsert(row: TaskStateRow) {
        transaction {
            ensureRow(row.taskId)
            TaskStateTable.update({ TaskStateTable.taskId eq row.taskId }) {
                it[untilCommand] = row.untilCommand
                it[maxRounds] = row.maxRounds
                it[maxMinutes] = row.maxMinutes
                it[loopStatus] = row.loopStatus
                it[roundsDone] = row.roundsDone
                it[noProgressRounds] = row.noProgressRounds
                it[totalIterations] = row.totalIterations
                it[activeMs] = row.activeMs
                it[roundsJson] = row.roundsJson
                it[stopReason] = row.stopReason
                it[lastCheckTail] = row.lastCheckTail
                it[updatedAt] = clock()
            }
        }
    }

    override fun savePlan(taskId: String, json: String?) {
        transaction {
            ensureRow(taskId)
            TaskStateTable.update({ TaskStateTable.taskId eq taskId }) {
                it[planJson] = json
                it[updatedAt] = clock()
            }
        }
    }

    override fun loadPlan(taskId: String): String? = transaction {
        TaskStateTable.slice(TaskStateTable.planJson).select { TaskStateTable.taskId eq taskId }
            .singleOrNull()?.get(TaskStateTable.planJson)
    }

    /**
     * Marks [taskId] as run by the process [pid] started at [startedAt]. The start time tells a live
     * owner from a new process that was given the same pid after a restart.
     */
    override fun claimLease(taskId: String, pid: Long, startedAt: Long) {
        transaction {
            ensureRow(taskId)
            TaskStateTable.update({ TaskStateTable.taskId eq taskId }) {
                it[ownerPid] = pid
                it[ownerStartedAt] = startedAt
                it[updatedAt] = clock()
            }
        }
    }

    override fun releaseLease(taskId: String) {
        transaction {
            TaskStateTable.update({ TaskStateTable.taskId eq taskId }) {
                it[ownerPid] = null
                it[ownerStartedAt] = null
                it[updatedAt] = clock()
            }
        }
    }

    fun findLeased(): List<TaskStateRow> = transaction {
        TaskStateTable.select { TaskStateTable.ownerPid.isNotNull() }.map { it.toRow() }
    }

    /** Insert-or-nothing, so two processes creating the row at once do not collide on the key. */
    private fun ensureRow(taskId: String) {
        TaskStateTable.insertIgnore {
            it[TaskStateTable.taskId] = taskId
            it[updatedAt] = clock()
        }
    }

    private fun ResultRow.toRow() = TaskStateRow(
        taskId = this[TaskStateTable.taskId],
        planJson = this[TaskStateTable.planJson],
        untilCommand = this[TaskStateTable.untilCommand],
        maxRounds = this[TaskStateTable.maxRounds],
        maxMinutes = this[TaskStateTable.maxMinutes],
        loopStatus = this[TaskStateTable.loopStatus],
        roundsDone = this[TaskStateTable.roundsDone],
        noProgressRounds = this[TaskStateTable.noProgressRounds],
        totalIterations = this[TaskStateTable.totalIterations],
        activeMs = this[TaskStateTable.activeMs],
        roundsJson = this[TaskStateTable.roundsJson],
        stopReason = this[TaskStateTable.stopReason],
        lastCheckTail = this[TaskStateTable.lastCheckTail],
        ownerPid = this[TaskStateTable.ownerPid],
        ownerStartedAt = this[TaskStateTable.ownerStartedAt],
        updatedAt = this[TaskStateTable.updatedAt],
    )
}
