package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private val logger = dualLogger("TaskRepository")

/**
 * Repository for Task database operations
 * Provides CRUD operations and queries for tasks
 */
class TaskRepository {

    /**
     * Create a new task
     * @param id Optional explicit ID for the task. If null, a UUID is auto-generated.
     */
    fun create(
        name: String,
        mode: TaskMode,
        projectId: String,
        projectPath: String,
        readOnly: Boolean = false,
        pinned: Boolean = false,
        executionMode: ExecutionMode = ExecutionMode.INTERACTIVE,
        requiresPlanApproval: Boolean = false,
        planApproved: Boolean = false,
        uiState: String? = null,
        coreApiVersion: String? = null,
        sourcePlanId: String? = null,
        planVersion: Int? = null,
        id: String? = null
    ): Task {
        return transaction {
            val taskId = TasksTable.insert {
                if (id != null) {
                    it[TasksTable.id] = id
                }
                it[TasksTable.name] = name
                it[TasksTable.mode] = mode
                it[TasksTable.readOnly] = readOnly
                it[TasksTable.pinned] = pinned
                it[TasksTable.executionMode] = executionMode
                it[TasksTable.requiresPlanApproval] = requiresPlanApproval
                it[TasksTable.planApproved] = planApproved
                it[TasksTable.uiState] = uiState
                it[TasksTable.coreApiVersion] = coreApiVersion
                it[TasksTable.projectId] = projectId
                it[TasksTable.projectPath] = projectPath
                it[TasksTable.sourcePlanId] = sourcePlanId
                it[TasksTable.planVersion] = planVersion
            } get TasksTable.id

            logger.info { "Created task: id=$taskId, name=$name, mode=$mode, executionMode=$executionMode" }

            findById(taskId) ?: throw IllegalStateException("Failed to retrieve created task")
        }
    }

    /**
     * Find task by ID
     */
    fun findById(id: String): Task? {
        return transaction {
            TasksTable.selectAll()
                .where { TasksTable.id eq id }
                .map { rowToTask(it) }
                .singleOrNull()
        }
    }

    /**
     * List all tasks with optional filtering
     */
    fun findAll(
        mode: TaskMode? = null,
        status: TaskStatus? = null,
        pinned: Boolean? = null,
        readOnly: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0,
        projectId: String? = null,
        sourcePlanId: String? = null
    ): List<Task> {
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()

            mode?.let { conditions.add(TasksTable.mode eq it) }
            status?.let { conditions.add(TasksTable.status eq it) }
            pinned?.let { conditions.add(TasksTable.pinned eq it) }
            readOnly?.let { conditions.add(TasksTable.readOnly eq it) }
            projectId?.let { conditions.add(TasksTable.projectId eq it) }
            sourcePlanId?.let { conditions.add(TasksTable.sourcePlanId eq it) }

            TasksTable.selectAll()
                .apply { if (conditions.isNotEmpty()) where { conditions.reduce { acc, op -> acc and op } } }
                .orderBy(TasksTable.createdAt to SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { rowToTask(it) }
        }
    }

    /**
     * Update task fields
     */
    fun update(
        id: String,
        name: String? = null,
        mode: TaskMode? = null,
        status: TaskStatus? = null,
        readOnly: Boolean? = null,
        pinned: Boolean? = null,
        executionMode: ExecutionMode? = null,
        requiresPlanApproval: Boolean? = null,
        planApproved: Boolean? = null,
        uiState: String? = null,
        rate: Int? = null
    ): Task? {
        return transaction {
            findById(id) ?: return@transaction null

            TasksTable.update({ TasksTable.id eq id }) {
                name?.let { value -> it[TasksTable.name] = value }
                mode?.let { value -> it[TasksTable.mode] = value }
                status?.let { value -> it[TasksTable.status] = value }
                readOnly?.let { value -> it[TasksTable.readOnly] = value }
                pinned?.let { value -> it[TasksTable.pinned] = value }
                executionMode?.let { value -> it[TasksTable.executionMode] = value }
                requiresPlanApproval?.let { value -> it[TasksTable.requiresPlanApproval] = value }
                planApproved?.let { value -> it[TasksTable.planApproved] = value }
                uiState?.let { value -> it[TasksTable.uiState] = value }
                rate?.let { value -> it[TasksTable.rate] = value }
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated task: id=$id, rate=$rate" }

            findById(id)
        }
    }

    /**
     * Increment task metrics (add to existing values)
     * Used after each LLM call to accumulate costs
     */
    fun incrementMetrics(
        id: String,
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
        cachedTokens: Int = 0
    ): Task? {
        return transaction {
            // Atomic in-database increment - avoids the read-modify-write race
            // when multiple LLM calls finish concurrently.
            val updated = TasksTable.update({ TasksTable.id eq id }) {
                with(SqlExpressionBuilder) {
                    it[TasksTable.tokensIn] = TasksTable.tokensIn + tokensIn
                    it[TasksTable.tokensOut] = TasksTable.tokensOut + tokensOut
                    it[TasksTable.cachedTokens] = TasksTable.cachedTokens + cachedTokens
                    it[TasksTable.costUsd] = TasksTable.costUsd + costUsd
                }
                it[updatedAt] = System.currentTimeMillis()
            }
            if (updated == 0) return@transaction null

            logger.info { "Incremented task metrics: id=$id, +$tokensIn/$tokensOut tokens, +\$${"%.6f".format(costUsd)}" }

            findById(id)
        }
    }

    /**
     * Delete task by ID (CASCADE will delete related entities)
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = TasksTable.deleteWhere { TasksTable.id eq id }
            if (deleted > 0) {
                logger.info { "Deleted task: id=$id" }
                true
            } else {
                logger.warn { "Task not found for deletion: id=$id" }
                false
            }
        }
    }

    /**
     * Count tasks with optional filtering
     */
    fun count(
        mode: TaskMode? = null,
        status: TaskStatus? = null,
        pinned: Boolean? = null,
        readOnly: Boolean? = null,
        projectId: String? = null
    ): Long {
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()

            mode?.let { conditions.add(TasksTable.mode eq it) }
            status?.let { conditions.add(TasksTable.status eq it) }
            pinned?.let { conditions.add(TasksTable.pinned eq it) }
            readOnly?.let { conditions.add(TasksTable.readOnly eq it) }
            projectId?.let { conditions.add(TasksTable.projectId eq it) }

            TasksTable.selectAll()
                .apply { if (conditions.isNotEmpty()) where { conditions.reduce { acc, op -> acc and op } } }
                .count()
        }
    }

    /**
     * Check if task exists
     */
    fun exists(id: String): Boolean {
        return transaction {
            TasksTable.selectAll()
                .where { TasksTable.id eq id }
                .count() > 0
        }
    }

    /**
     * Find tasks created after given timestamp (for cursor pagination)
     */
    fun findByCreatedAtBefore(
        timestamp: Long,
        limit: Int = 50,
        mode: TaskMode? = null,
        status: TaskStatus? = null,
        projectId: String? = null
    ): List<Task> {
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(TasksTable.createdAt less timestamp)

            mode?.let { conditions.add(TasksTable.mode eq it) }
            status?.let { conditions.add(TasksTable.status eq it) }
            projectId?.let { conditions.add(TasksTable.projectId eq it) }

            TasksTable.selectAll()
                .where { conditions.reduce { acc, op -> acc and op } }
                .orderBy(TasksTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToTask(it) }
        }
    }

    /**
     * List tasks with aggregated stats (tokens, cost) from chat_messages
     * Required for history panel
     */
    fun listTasksWithStats(limit: Int = 100): List<TaskWithStats> {
        return transaction {
            val tasks = TasksTable.selectAll()
                .orderBy(TasksTable.updatedAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToTask(it) }

            val statsByTask = aggregateMessageStats(tasks.map { it.id })

            tasks.map { task ->
                val stats = statsByTask[task.id] ?: MessageStats()
                TaskWithStats(
                    task = task,
                    tokensIn = stats.tokensIn,
                    tokensOut = stats.tokensOut,
                    costUsd = stats.costUsd
                )
            }
        }
    }

    /**
     * Get single task with aggregated stats
     */
    fun getTaskWithStats(id: String): TaskWithStats? {
        val task = findById(id) ?: return null

        return transaction {
            val stats = aggregateMessageStats(listOf(id))[id] ?: MessageStats()
            TaskWithStats(
                task = task,
                tokensIn = stats.tokensIn,
                tokensOut = stats.tokensOut,
                costUsd = stats.costUsd
            )
        }
    }

    private data class MessageStats(
        var tokensIn: Int = 0,
        var tokensOut: Int = 0,
        var costUsd: Double = 0.0
    )

    /**
     * Aggregate per-task token/cost stats from chat_messages without loading full rows.
     *
     * Primary source: a single SQL SUM over the dedicated numeric columns grouped by task.
     * Fallback: rows written before the numeric columns existed (all three columns null)
     * carry their metrics as JSON in metadata - those are fetched with a narrow projection
     * (task id, message id, metadata) and parsed per row. Metrics JSON uses the
     * MessageMetrics field names (camelCase), with legacy snake_case keys accepted.
     */
    private fun aggregateMessageStats(taskIds: List<String>): Map<String, MessageStats> {
        if (taskIds.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MessageStats>()

        val tokensInSum = ChatMessagesTable.tokensIn.sum()
        val tokensOutSum = ChatMessagesTable.tokensOut.sum()
        val costSum = ChatMessagesTable.cost.sum()

        ChatMessagesTable
            .select(ChatMessagesTable.taskId, tokensInSum, tokensOutSum, costSum)
            .where { ChatMessagesTable.taskId inList taskIds }
            .groupBy(ChatMessagesTable.taskId)
            .forEach { row ->
                val stats = result.getOrPut(row[ChatMessagesTable.taskId]) { MessageStats() }
                stats.tokensIn += row[tokensInSum] ?: 0
                stats.tokensOut += row[tokensOutSum] ?: 0
                stats.costUsd += row[costSum] ?: 0.0
            }

        ChatMessagesTable
            .select(ChatMessagesTable.taskId, ChatMessagesTable.id, ChatMessagesTable.metadata)
            .where {
                (ChatMessagesTable.taskId inList taskIds) and
                        ChatMessagesTable.tokensIn.isNull() and
                        ChatMessagesTable.tokensOut.isNull() and
                        ChatMessagesTable.cost.isNull() and
                        ChatMessagesTable.metadata.isNotNull()
            }
            .forEach { row ->
                val metadata = row[ChatMessagesTable.metadata] ?: return@forEach
                val stats = result.getOrPut(row[ChatMessagesTable.taskId]) { MessageStats() }
                try {
                    val metadataMap = pl.jclab.refio.core.utils.GsonInstance.gson.fromJson(
                        metadata,
                        Map::class.java
                    )
                    metadataMap?.let { map ->
                        ((map["inputTokens"] ?: map["tokens_in"]) as? Number)?.let { stats.tokensIn += it.toInt() }
                        ((map["outputTokens"] ?: map["tokens_out"]) as? Number)?.let { stats.tokensOut += it.toInt() }
                        ((map["costUsd"] ?: map["cost_usd"]) as? Number)?.let { stats.costUsd += it.toDouble() }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to parse metadata for message ${row[ChatMessagesTable.id]}: ${e.message}" }
                }
            }

        return result
    }

    fun getForProject(projectId: String, limit: Int = 200): List<Task> = transaction {
        TasksTable.selectAll().where { TasksTable.projectId eq projectId }
            .orderBy(TasksTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { rowToTask(it) }
    }

    fun getLastForProject(projectId: String): Task? = transaction {
        TasksTable.selectAll().where { TasksTable.projectId eq projectId }
            .orderBy(TasksTable.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { rowToTask(it) }
    }

    /**
     * Map database row to Task data class
     */
    private fun rowToTask(row: ResultRow): Task {
        return Task(
            id = row[TasksTable.id],
            name = row[TasksTable.name],
            mode = row[TasksTable.mode],
            status = row[TasksTable.status],
            readOnly = row[TasksTable.readOnly],
            pinned = row[TasksTable.pinned],
            executionMode = row[TasksTable.executionMode],
            requiresPlanApproval = row[TasksTable.requiresPlanApproval],
            planApproved = row[TasksTable.planApproved],
            uiState = row[TasksTable.uiState],
            coreApiVersion = row[TasksTable.coreApiVersion],
            projectId = row[TasksTable.projectId],
            projectPath = row[TasksTable.projectPath],
            rate = row[TasksTable.rate],
            tokensIn = row[TasksTable.tokensIn],
            tokensOut = row[TasksTable.tokensOut],
            cachedTokens = row[TasksTable.cachedTokens],
            costUsd = row[TasksTable.costUsd],
            sourcePlanId = row[TasksTable.sourcePlanId],
            planVersion = row[TasksTable.planVersion],
            completionCondition = row[TasksTable.completionCondition],
            createdAt = row[TasksTable.createdAt],
            updatedAt = row[TasksTable.updatedAt]
        )
    }

    /**
     * Set or clear the `/goal` completion condition for a task.
     *
     * When non-null, [pl.jclab.refio.core.services.turn.NextSpeakerJudgeGuardian] switches
     * to a goal-aware prompt that asks "has this condition been met based on the agent's
     * response?" instead of the generic "is the turn finished?". Pass `null` to clear.
     *
     * @return true when the task existed (and the update ran), false when no task matched.
     */
    fun setCompletionCondition(id: String, condition: String?): Boolean {
        return transaction {
            val updated = TasksTable.update({ TasksTable.id eq id }) {
                it[completionCondition] = condition
                it[updatedAt] = System.currentTimeMillis()
            }
            if (updated > 0) {
                logger.info { "Set completion condition for task $id: ${condition?.take(80) ?: "(cleared)"}" }
                true
            } else {
                logger.warn { "Task not found for setCompletionCondition: id=$id" }
                false
            }
        }
    }

    /**
     * Get the active completion condition for a task, or null if none is set.
     * Called by the turn loop on every guardian evaluation (cheap point read by PK).
     */
    fun getCompletionCondition(id: String): String? {
        return transaction {
            TasksTable
                .select(TasksTable.completionCondition)
                .where { TasksTable.id eq id }
                .singleOrNull()
                ?.get(TasksTable.completionCondition)
        }
    }
}

/**
 * Task with aggregated statistics
 * Used for history panel with token/cost data
 */
data class TaskWithStats(
    val task: Task,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double
)
