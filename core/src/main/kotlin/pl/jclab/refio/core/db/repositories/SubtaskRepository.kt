package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("SubtaskRepository")

/**
 * Repository for Subtask database operations
 * Manages individual steps within task execution plans
 */
class SubtaskRepository {

    /**
     * Create a new subtask
     */
    fun create(
        taskId: String,
        orderIndex: Int,
        kind: SubtaskKind,
        description: String,
        paramsJson: String? = null,
        stepPlanJson: String? = null,
        requiresApproval: Boolean = false,
        status: TaskStatus = TaskStatus.PENDING,
        llmModel: String? = null,
        llmProvider: String? = null
    ): Subtask {
        return transaction {
            val subtaskId = SubtasksTable.insert {
                it[SubtasksTable.taskId] = taskId
                it[SubtasksTable.orderIndex] = orderIndex
                it[SubtasksTable.kind] = kind
                it[SubtasksTable.status] = status
                it[SubtasksTable.description] = description
                it[SubtasksTable.paramsJson] = paramsJson
                it[SubtasksTable.stepPlanJson] = stepPlanJson
                it[SubtasksTable.requiresApproval] = requiresApproval
                it[approvalStatus] = if (requiresApproval) ApprovalStatus.PENDING_APPROVAL else ApprovalStatus.NOT_REQUIRED
                it[SubtasksTable.llmModel] = llmModel
                it[SubtasksTable.llmProvider] = llmProvider
            } get SubtasksTable.id

            logger.info { "Created subtask: id=$subtaskId, taskId=$taskId, kind=$kind, status=$status" }

            findById(subtaskId) ?: throw IllegalStateException("Failed to retrieve created subtask")
        }
    }

    /**
     * Find subtask by ID
     */
    fun findById(id: String): Subtask? {
        return transaction {
            SubtasksTable.selectAll()
                .where { SubtasksTable.id eq id }
                .map { rowToSubtask(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all subtasks for a task, ordered by orderIndex
     */
    fun findByTaskId(taskId: String): List<Subtask> {
        return transaction {
            SubtasksTable.selectAll()
                .where { SubtasksTable.taskId eq taskId }
                .orderBy(SubtasksTable.orderIndex to SortOrder.ASC)
                .map { rowToSubtask(it) }
        }
    }

    /**
     * Find subtasks by status
     */
    fun findByStatus(taskId: String, status: TaskStatus): List<Subtask> {
        return transaction {
            SubtasksTable.selectAll()
                .where { (SubtasksTable.taskId eq taskId) and (SubtasksTable.status eq status) }
                .orderBy(SubtasksTable.orderIndex to SortOrder.ASC)
                .map { rowToSubtask(it) }
        }
    }

    /**
     * Find subtasks requiring approval
     */
    fun findPendingApproval(taskId: String): List<Subtask> {
        return transaction {
            SubtasksTable.selectAll()
                .where {
                    (SubtasksTable.taskId eq taskId) and
                    (SubtasksTable.approvalStatus eq ApprovalStatus.PENDING_APPROVAL)
                }
                .orderBy(SubtasksTable.orderIndex to SortOrder.ASC)
                .map { rowToSubtask(it) }
        }
    }

    /**
     * Update subtask status
     */
    fun updateStatus(id: String, status: TaskStatus): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.status] = status
                it[updatedAt] = System.currentTimeMillis()

                when (status) {
                    TaskStatus.RUNNING -> it[startedAt] = System.currentTimeMillis()
                    TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED ->
                        it[completedAt] = System.currentTimeMillis()
                    else -> {}
                }
            }

            logger.info { "Updated subtask status: id=$id, status=$status" }
            findById(id)
        }
    }

    /**
     * Update subtask approval status
     */
    fun updateApprovalStatus(id: String, approvalStatus: ApprovalStatus): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.approvalStatus] = approvalStatus
                it[updatedAt] = System.currentTimeMillis()

                if (approvalStatus == ApprovalStatus.APPROVED) {
                    it[approvedAt] = System.currentTimeMillis()
                }
            }

            logger.info { "Updated subtask approval: id=$id, approvalStatus=$approvalStatus" }
            findById(id)
        }
    }

    /**
     * Update subtask step plan
     */
    fun updateStepPlan(id: String, stepPlanJson: String): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.stepPlanJson] = stepPlanJson
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask step plan: id=$id" }
            findById(id)
        }
    }

    /**
     * Update subtask execution result
     */
    fun updateResult(
        id: String,
        result: String?,
        summary: String? = null,
        errorMessage: String? = null,
        errorStacktrace: String? = null
    ): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.result] = result
                it[SubtasksTable.summary] = summary
                it[SubtasksTable.errorMessage] = errorMessage
                it[SubtasksTable.errorStacktrace] = errorStacktrace
                it[updatedAt] = System.currentTimeMillis()
            }

            findById(id)
        }
    }

    /**
     * Update LLM metrics (US-027)
     * Simpler version that only updates tokens, cost, and latency
     */
    fun updateMetrics(
        subtaskId: String,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        latencyMs: Int
    ): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq subtaskId }) {
                it[SubtasksTable.inputTokens] = inputTokens
                it[SubtasksTable.outputTokens] = outputTokens
                it[SubtasksTable.costUsd] = costUsd
                it[SubtasksTable.latencyMs] = latencyMs
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask metrics: id=$subtaskId, tokens=$inputTokens/$outputTokens, cost=$costUsd" }
            findById(subtaskId)
        }
    }

    /**
     * Update LLM metrics (full version with model/provider)
     */
    fun updateLlmMetrics(
        id: String,
        llmModel: String?,
        llmProvider: String?,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        latencyMs: Int
    ): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.llmModel] = llmModel
                it[SubtasksTable.llmProvider] = llmProvider
                it[SubtasksTable.inputTokens] = inputTokens
                it[SubtasksTable.outputTokens] = outputTokens
                it[SubtasksTable.costUsd] = costUsd
                it[SubtasksTable.latencyMs] = latencyMs
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask LLM metrics: id=$id, tokens=$inputTokens/$outputTokens, cost=$costUsd" }
            findById(id)
        }
    }

    /**
     * Link snapshot to subtask (before write operation)
     */
    fun linkSnapshot(id: String, snapshotId: String): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[snapshotIdBeforeWrite] = snapshotId
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Linked snapshot to subtask: id=$id, snapshotId=$snapshotId" }
            findById(id)
        }
    }

    /**
     * Update orderIndex for a subtask (for reordering)
     */
    fun updateOrderIndex(id: String, newOrderIndex: Int): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[orderIndex] = newOrderIndex
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask orderIndex: id=$id, newOrderIndex=$newOrderIndex" }
            findById(id)
        }
    }

    /**
     * Atomically shift existing subtasks and create a new one.
     * This prevents UNIQUE constraint violations when inserting in the middle of a plan.
     *
     * All operations happen in a single transaction:
     * 1. Shift all subtasks with orderIndex >= insertAt by +1 (from highest to lowest)
     * 2. Create new subtask at insertAt
     */
    fun createWithShift(
        taskId: String,
        insertAt: Int,
        kind: SubtaskKind,
        description: String,
        paramsJson: String? = null,
        stepPlanJson: String? = null,
        requiresApproval: Boolean = false,
        status: TaskStatus = TaskStatus.PENDING,
        llmModel: String? = null,
        llmProvider: String? = null
    ): Subtask {
        return transaction {
            // Step 1: Find all subtasks that need to be shifted
            val subtasksToShift = SubtasksTable.selectAll()
                .where { (SubtasksTable.taskId eq taskId) and (SubtasksTable.orderIndex greaterEq insertAt) }
                .orderBy(SubtasksTable.orderIndex, SortOrder.DESC)  // Process from highest to lowest to avoid conflicts
                .map { it[SubtasksTable.id] to it[SubtasksTable.orderIndex] }

            // Step 2: Shift each subtask by +1 (from highest to lowest)
            for ((subtaskId, currentIndex) in subtasksToShift) {
                SubtasksTable.update({ SubtasksTable.id eq subtaskId }) {
                    it[orderIndex] = currentIndex + 1
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            if (subtasksToShift.isNotEmpty()) {
                logger.info { "Shifted ${subtasksToShift.size} subtasks to make room at index $insertAt" }
            }

            // Step 3: Create new subtask at the target index
            val newSubtaskId = SubtasksTable.insert {
                it[SubtasksTable.taskId] = taskId
                it[orderIndex] = insertAt
                it[SubtasksTable.kind] = kind
                it[SubtasksTable.status] = status
                it[SubtasksTable.description] = description
                it[SubtasksTable.paramsJson] = paramsJson
                it[SubtasksTable.stepPlanJson] = stepPlanJson
                it[SubtasksTable.requiresApproval] = requiresApproval
                it[approvalStatus] = if (requiresApproval) ApprovalStatus.PENDING_APPROVAL else ApprovalStatus.NOT_REQUIRED
                it[SubtasksTable.llmModel] = llmModel
                it[SubtasksTable.llmProvider] = llmProvider
            } get SubtasksTable.id

            logger.info { "Created subtask atomically: id=$newSubtaskId, taskId=$taskId, insertAt=$insertAt, kind=$kind" }

            findById(newSubtaskId) ?: throw IllegalStateException("Failed to retrieve created subtask")
        }
    }

    /**
     * Delete subtask by ID
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = SubtasksTable.deleteWhere { SubtasksTable.id eq id }
            if (deleted > 0) {
                logger.info { "Deleted subtask: id=$id" }
                true
            } else {
                false
            }
        }
    }

    /**
     * Delete all subtasks for a task
     */
    fun deleteByTaskId(taskId: String): Int {
        return transaction {
            val deleted = SubtasksTable.deleteWhere { SubtasksTable.taskId eq taskId }
            logger.info { "Deleted $deleted subtasks for task: taskId=$taskId" }
            deleted
        }
    }

    /**
     * Delete all PENDING and PLANNED subtasks for a task
     */
    fun deletePendingByTaskId(taskId: String): Int {
        return transaction {
            val deleted = SubtasksTable.deleteWhere {
                (SubtasksTable.taskId eq taskId) and
                ((status eq TaskStatus.PENDING) or (status eq TaskStatus.PLANNED))
            }
            logger.info { "Deleted $deleted pending/planned subtasks for task: taskId=$taskId" }
            deleted
        }
    }

    /**
     * Count subtasks for a task
     */
    fun countByTaskId(taskId: String): Long {
        return transaction {
            SubtasksTable.selectAll()
                .where { SubtasksTable.taskId eq taskId }
                .count()
        }
    }

    /**
     * Get maximum order_index for a task
     * Returns null if no subtasks exist for this task
     */
    fun getMaxOrderIndex(taskId: String): Int? {
        return transaction {
            SubtasksTable.select(SubtasksTable.orderIndex)
                .where { SubtasksTable.taskId eq taskId }
                .maxOfOrNull { it[SubtasksTable.orderIndex] }
        }
    }

    /**
     * Update subtask summary (LLM-generated summary)
     */
    fun updateSummary(id: String, summary: String): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                it[SubtasksTable.summary] = summary
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask summary: id=$id" }
            findById(id)
        }
    }

    /**
     * Update subtask description and params.
     */
    fun update(
        id: String,
        description: String? = null,
        paramsJson: String? = null
    ): Subtask? {
        return transaction {
            SubtasksTable.update({ SubtasksTable.id eq id }) {
                if (description != null) {
                    it[SubtasksTable.description] = description
                }
                if (paramsJson != null) {
                    it[SubtasksTable.paramsJson] = paramsJson
                }
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated subtask: id=$id" }
            findById(id)
        }
    }

    /**
     * Map database row to Subtask data class
     */
    private fun rowToSubtask(row: ResultRow): Subtask {
        return Subtask(
            id = row[SubtasksTable.id],
            taskId = row[SubtasksTable.taskId],
            orderIndex = row[SubtasksTable.orderIndex],
            kind = row[SubtasksTable.kind],
            status = row[SubtasksTable.status],
            description = row[SubtasksTable.description],
            paramsJson = row[SubtasksTable.paramsJson],
            stepPlanJson = row[SubtasksTable.stepPlanJson],
            summary = row[SubtasksTable.summary],
            requiresApproval = row[SubtasksTable.requiresApproval],
            approvalStatus = row[SubtasksTable.approvalStatus],
            approvedAt = row[SubtasksTable.approvedAt],
            result = row[SubtasksTable.result],
            errorMessage = row[SubtasksTable.errorMessage],
            errorStacktrace = row[SubtasksTable.errorStacktrace],
            llmModel = row[SubtasksTable.llmModel],
            llmProvider = row[SubtasksTable.llmProvider],
            inputTokens = row[SubtasksTable.inputTokens],
            outputTokens = row[SubtasksTable.outputTokens],
            costUsd = row[SubtasksTable.costUsd],
            latencyMs = row[SubtasksTable.latencyMs],
            snapshotIdBeforeWrite = row[SubtasksTable.snapshotIdBeforeWrite],
            createdAt = row[SubtasksTable.createdAt],
            updatedAt = row[SubtasksTable.updatedAt],
            startedAt = row[SubtasksTable.startedAt],
            completedAt = row[SubtasksTable.completedAt]
        )
    }
}
