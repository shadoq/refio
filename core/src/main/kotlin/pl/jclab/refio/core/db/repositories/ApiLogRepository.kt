package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("ApiLogRepository")

/**
 * Repository for ApiLog database operations
 * Manages complete LLM API call history with secret redaction
 */
class ApiLogRepository {

    /**
     * Create a new API log entry
     */
    fun create(
        taskId: String? = null,  // Nullable for tool-level calls without task context
        subtaskId: String? = null,
        provider: String,
        model: String,
        endpoint: String,
        source: String? = null,
        requestPayload: String,
        responsePayload: String? = null,
        httpStatus: Int? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        costUsd: Double = 0.0,
        latencyMs: Int = 0,
        errorMessage: String? = null,
        errorType: String? = null
    ): ApiLog {
        return transaction {
            // Error #15: Redact secrets before saving
            val safeRequestPayload = pl.jclab.refio.core.security.SecureLogger.redact(requestPayload)
            val safeResponsePayload = responsePayload?.let { pl.jclab.refio.core.security.SecureLogger.redact(it) }

            val logId = ApiLogsTable.insert {
                it[ApiLogsTable.taskId] = taskId
                it[ApiLogsTable.subtaskId] = subtaskId
                it[ApiLogsTable.provider] = provider
                it[ApiLogsTable.model] = model
                it[ApiLogsTable.endpoint] = endpoint
                it[requestSource] = source
                it[ApiLogsTable.requestPayload] = safeRequestPayload
                it[ApiLogsTable.responsePayload] = safeResponsePayload
                it[ApiLogsTable.httpStatus] = httpStatus
                it[ApiLogsTable.inputTokens] = inputTokens
                it[ApiLogsTable.outputTokens] = outputTokens
                it[ApiLogsTable.costUsd] = costUsd
                it[ApiLogsTable.latencyMs] = latencyMs
                it[ApiLogsTable.errorMessage] = errorMessage
                it[ApiLogsTable.errorType] = errorType
            } get ApiLogsTable.id

            logger.info {
                "Created API log: id=$logId, provider=$provider, model=$model, " +
                "tokens=$inputTokens/$outputTokens, cost=$costUsd, latency=${latencyMs}ms"
            }

            findById(logId) ?: throw IllegalStateException("Failed to retrieve created API log")
        }
    }

    /**
     * Find API log by ID
     */
    fun findById(id: String): ApiLog? {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.id eq id }
                .map { rowToApiLog(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all API logs for a task
     */
    fun findByTaskId(taskId: String): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.taskId eq taskId }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs for a specific subtask
     */
    fun findBySubtaskId(subtaskId: String): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.subtaskId eq subtaskId }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs by provider
     */
    fun findByProvider(taskId: String, provider: String): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { (ApiLogsTable.taskId eq taskId) and (ApiLogsTable.provider eq provider) }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs with errors
     */
    fun findErrors(taskId: String): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { (ApiLogsTable.taskId eq taskId) and ApiLogsTable.errorMessage.isNotNull() }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Calculate total cost for a task
     */
    fun calculateTotalCost(taskId: String): Double {
        return transaction {
            ApiLogsTable.select(ApiLogsTable.costUsd.sum())
                .where { ApiLogsTable.taskId eq taskId }
                .singleOrNull()?.get(ApiLogsTable.costUsd.sum()) ?: 0.0
        }
    }

    /**
     * Calculate total tokens for a task
     */
    fun calculateTotalTokens(taskId: String): Pair<Long, Long> {
        return transaction {
            val result = ApiLogsTable.select(
                ApiLogsTable.inputTokens.sum(),
                ApiLogsTable.outputTokens.sum()
            )
                .where { ApiLogsTable.taskId eq taskId }
                .singleOrNull()

            val inputTokens = result?.get(ApiLogsTable.inputTokens.sum())?.toLong() ?: 0L
            val outputTokens = result?.get(ApiLogsTable.outputTokens.sum())?.toLong() ?: 0L

            Pair(inputTokens, outputTokens)
        }
    }

    /**
     * Delete API log by ID
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = ApiLogsTable.deleteWhere { ApiLogsTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Delete all API logs for a task
     */
    fun deleteByTaskId(taskId: String): Int {
        return transaction {
            val deleted = ApiLogsTable.deleteWhere { ApiLogsTable.taskId eq taskId }
            logger.info { "Deleted $deleted API logs for task: taskId=$taskId" }
            deleted
        }
    }

    /**
     * Count API logs for a task
     */
    fun countByTaskId(taskId: String): Long {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.taskId eq taskId }
                .count()
        }
    }

    /**
     * Get recent API logs globally (across all tasks)
     */
    fun getRecentLogs(limit: Int = 10): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Get global statistics for all API calls
     */
    fun getGlobalStatistics(): ApiLogStatistics {
        return transaction {
            val result = ApiLogsTable.select(
                ApiLogsTable.id.count(),
                ApiLogsTable.inputTokens.sum(),
                ApiLogsTable.outputTokens.sum(),
                ApiLogsTable.costUsd.sum(),
                ApiLogsTable.latencyMs.avg()
            ).singleOrNull()

            val totalCalls = result?.get(ApiLogsTable.id.count()) ?: 0L
            val totalInputTokens = result?.get(ApiLogsTable.inputTokens.sum())?.toLong() ?: 0L
            val totalOutputTokens = result?.get(ApiLogsTable.outputTokens.sum())?.toLong() ?: 0L
            val totalCost = result?.get(ApiLogsTable.costUsd.sum()) ?: 0.0
            val avgLatency = result?.get(ApiLogsTable.latencyMs.avg())?.toInt() ?: 0

            val errorCount = ApiLogsTable.selectAll()
                .where { ApiLogsTable.errorMessage.isNotNull() }
                .count()

            ApiLogStatistics(
                totalCalls = totalCalls,
                totalInputTokens = totalInputTokens,
                totalOutputTokens = totalOutputTokens,
                totalCost = totalCost,
                avgLatencyMs = avgLatency,
                errorCount = errorCount
            )
        }
    }

    /**
     * Delete all API logs
     */
    fun deleteAll(): Int {
        return transaction {
            val deleted = ApiLogsTable.deleteAll()
            logger.info { "Deleted all API logs: count=$deleted" }
            deleted
        }
    }

    /**
     * Get API logs for export, newest first. Bounded by [limit] because log rows
     * carry large request/response payload text columns - an unbounded select can
     * pull the whole table into memory.
     */
    fun getAllLogs(limit: Int = 500): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs by provider globally (not limited to task)
     */
    fun findByProviderGlobal(provider: String, limit: Int = 50): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.provider eq provider }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs by model globally (not limited to task)
     */
    fun findByModelGlobal(model: String, limit: Int = 50): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.model eq model }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs by source globally (not limited to task)
     */
    fun findBySourceGlobal(source: String, limit: Int = 50): List<ApiLog> {
        return transaction {
            ApiLogsTable.selectAll()
                .where { ApiLogsTable.requestSource eq source }
                .orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Find API logs with multiple filters
     */
    fun findFiltered(
        provider: String? = null,
        model: String? = null,
        source: String? = null,
        limit: Int = 50
    ): List<ApiLog> {
        return transaction {
            var query = ApiLogsTable.selectAll()

            if (provider != null) {
                query = query.andWhere { ApiLogsTable.provider eq provider }
            }
            if (model != null) {
                query = query.andWhere { ApiLogsTable.model eq model }
            }
            if (source != null) {
                query = query.andWhere { ApiLogsTable.requestSource eq source }
            }

            query.orderBy(ApiLogsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToApiLog(it) }
        }
    }

    /**
     * Get distinct providers
     */
    fun getDistinctProviders(): List<String> {
        return transaction {
            ApiLogsTable.select(ApiLogsTable.provider)
                .withDistinct()
                .mapNotNull { it[ApiLogsTable.provider] }
                .sorted()
        }
    }

    /**
     * Get distinct models
     */
    fun getDistinctModels(): List<String> {
        return transaction {
            ApiLogsTable.select(ApiLogsTable.model)
                .withDistinct()
                .mapNotNull { it[ApiLogsTable.model] }
                .sorted()
        }
    }

    /**
     * Get distinct sources
     */
    fun getDistinctSources(): List<String> {
        return transaction {
            ApiLogsTable.select(ApiLogsTable.requestSource)
                .withDistinct()
                .mapNotNull { it[ApiLogsTable.requestSource] }
                .filterNotNull()
                .sorted()
        }
    }

    /**
     * Map database row to ApiLog data class
     */
    private fun rowToApiLog(row: ResultRow): ApiLog {
        return ApiLog(
            id = row[ApiLogsTable.id],
            taskId = row[ApiLogsTable.taskId],
            subtaskId = row[ApiLogsTable.subtaskId],
            provider = row[ApiLogsTable.provider],
            model = row[ApiLogsTable.model],
            endpoint = row[ApiLogsTable.endpoint],
            requestSource = row[ApiLogsTable.requestSource],
            requestPayload = row[ApiLogsTable.requestPayload],
            responsePayload = row[ApiLogsTable.responsePayload],
            httpStatus = row[ApiLogsTable.httpStatus],
            inputTokens = row[ApiLogsTable.inputTokens],
            outputTokens = row[ApiLogsTable.outputTokens],
            costUsd = row[ApiLogsTable.costUsd],
            latencyMs = row[ApiLogsTable.latencyMs],
            errorMessage = row[ApiLogsTable.errorMessage],
            errorType = row[ApiLogsTable.errorType],
            createdAt = row[ApiLogsTable.createdAt]
        )
    }
}
