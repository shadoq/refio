package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.db.ApiLog
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ApiLogsRouter")

/**
 * Router for API logs management operations.
 * Handles API call logging, statistics, filtering, and export.
 *
 * @property apiLogRepository API logs storage repository
 */
class ApiLogsRouter(
    private val apiLogRepository: ApiLogRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[ApiLogsRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[ApiLogsRouter] Shutting down" }
    }

    // ===== Query Operations =====

    /**
     * Get recent API logs.
     *
     * @param limit Maximum number of logs to return (default: 50)
     * @return List of recent API logs
     */
    fun getRecentApiLogs(limit: Int = 50): List<ApiLog> {
        logger.debug { "[ApiLogsRouter] Getting recent API logs: limit=$limit" }
        return apiLogRepository.getRecentLogs(limit)
    }

    /**
     * Get filtered API logs.
     *
     * @param provider Optional provider filter
     * @param model Optional model filter
     * @param source Optional source filter
     * @param limit Maximum number of logs to return (default: 50)
     * @return List of filtered API logs
     */
    fun getFilteredApiLogs(
        provider: String? = null,
        model: String? = null,
        source: String? = null,
        limit: Int = 50
    ): List<ApiLog> {
        logger.debug { "[ApiLogsRouter] Getting filtered API logs: provider=$provider, model=$model, source=$source, limit=$limit" }
        return apiLogRepository.findFiltered(provider, model, source, limit)
    }

    // ===== Statistics =====

    /**
     * Get global API log statistics.
     *
     * @return Global statistics for all API calls
     */
    fun getApiLogStatistics(): pl.jclab.refio.core.db.ApiLogStatistics {
        logger.debug { "[ApiLogsRouter] Getting API log statistics" }
        return apiLogRepository.getGlobalStatistics()
    }

    /**
     * Get distinct providers from API logs.
     *
     * @return List of unique providers
     */
    fun getDistinctProviders(): List<String> {
        logger.debug { "[ApiLogsRouter] Getting distinct providers" }
        return apiLogRepository.getDistinctProviders()
    }

    /**
     * Get distinct models from API logs.
     *
     * @return List of unique models
     */
    fun getDistinctModels(): List<String> {
        logger.debug { "[ApiLogsRouter] Getting distinct models" }
        return apiLogRepository.getDistinctModels()
    }

    /**
     * Get distinct sources from API logs.
     *
     * @return List of unique sources
     */
    fun getDistinctSources(): List<String> {
        logger.debug { "[ApiLogsRouter] Getting distinct sources" }
        return apiLogRepository.getDistinctSources()
    }

    // ===== Management Operations =====

    /**
     * Delete all API logs.
     *
     * @return Number of deleted logs
     */
    fun deleteAllApiLogs(): Int {
        logger.info { "[ApiLogsRouter] Deleting all API logs" }
        return apiLogRepository.deleteAll()
    }

    /**
     * Delete API logs for a specific task (session).
     */
    fun deleteApiLogsByTaskId(taskId: String): Int {
        logger.info { "[ApiLogsRouter] Deleting API logs: taskId=$taskId" }
        return apiLogRepository.deleteByTaskId(taskId)
    }

    // ===== Export Operations =====

    /**
     * Export all API logs to JSON.
     *
     * @return JSON string containing all logs
     */
    fun exportAllApiLogsToJson(): String {
        logger.info { "[ApiLogsRouter] Exporting all API logs to JSON" }
        val logs = apiLogRepository.getAllLogs()

        // Manual JSON formatting (simple approach)
        val jsonLogs = logs.joinToString(",\n  ") { log ->
            """
            {
                "id": "${log.id}",
                "taskId": ${if (log.taskId != null) "\"${log.taskId}\"" else "null"},
                "subtaskId": ${if (log.subtaskId != null) "\"${log.subtaskId}\"" else "null"},
                "provider": "${log.provider}",
                "model": "${log.model}",
                "endpoint": "${log.endpoint}",
                "requestSource": ${if (log.requestSource != null) "\"${log.requestSource}\"" else "null"},
                "httpStatus": ${log.httpStatus ?: "null"},
                "inputTokens": ${log.inputTokens},
                "outputTokens": ${log.outputTokens},
                "costUsd": ${log.costUsd},
                "latencyMs": ${log.latencyMs},
                "errorMessage": ${if (log.errorMessage != null) "\"${escapeJson(log.errorMessage)}\"" else "null"},
                "errorType": ${if (log.errorType != null) "\"${log.errorType}\"" else "null"},
                "createdAt": ${log.createdAt}
            }
            """.trimIndent()
        }

        return "[\n  $jsonLogs\n]"
    }

    /**
     * Export all API logs to CSV.
     *
     * @return CSV string containing all logs
     */
    fun exportAllApiLogsToCsv(): String {
        logger.info { "[ApiLogsRouter] Exporting all API logs to CSV" }
        val logs = apiLogRepository.getAllLogs()

        val header = "ID,Task ID,Subtask ID,Provider,Model,Endpoint,Source,HTTP Status,Input Tokens,Output Tokens,Cost USD,Latency MS,Error Type,Error Message,Created At"
        val rows = logs.joinToString("\n") { log ->
            listOf(
                log.id,
                log.taskId ?: "",
                log.subtaskId ?: "",
                log.provider,
                log.model,
                escapeCsv(log.endpoint),
                log.requestSource ?: "",
                log.httpStatus?.toString() ?: "",
                log.inputTokens.toString(),
                log.outputTokens.toString(),
                log.costUsd.toString(),
                log.latencyMs.toString(),
                log.errorType ?: "",
                escapeCsv(log.errorMessage ?: ""),
                log.createdAt.toString()
            ).joinToString(",")
        }

        return "$header\n$rows"
    }

    // ===== Helper Functions =====

    /**
     * Escape special characters in JSON strings.
     */
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Escape special characters in CSV fields.
     */
    private fun escapeCsv(str: String): String {
        return if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            "\"${str.replace("\"", "\"\"")}\""
        } else {
            str
        }
    }
}
