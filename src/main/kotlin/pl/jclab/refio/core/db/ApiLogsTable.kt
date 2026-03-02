package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * API logs table definition using Exposed ORM DSL
 * Stores complete LLM API call history with secret redaction
 */
object ApiLogsTable : Table("api_logs") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE).nullable()  // Nullable for tool-level calls
    val subtaskId = varchar("subtask_id", 36).references(SubtasksTable.id, onDelete = ReferenceOption.CASCADE).nullable()

    // Provider details
    val provider = varchar("provider", 32)  // e.g., "anthropic", "openai", "ollama"
    val model = varchar("model", 64)
    val endpoint = varchar("endpoint", 255)  // API endpoint URL
    val requestSource = varchar("request_source", 64).nullable()  // Source of request: "Chat", "StepPlanner", "ToolSelector", etc.

    // Request/Response (secrets redacted)
    val requestPayload = largeText("request_payload")  // Full request as JSON (with secrets masked)
    val responsePayload = largeText("response_payload").nullable()  // Full response as JSON
    val httpStatus = integer("http_status").nullable()

    // Metrics
    val inputTokens = integer("input_tokens").default(0)
    val outputTokens = integer("output_tokens").default(0)
    val costUsd = double("cost_usd").default(0.0)
    val latencyMs = integer("latency_ms").default(0)

    // Error tracking
    val errorMessage = text("error_message").nullable()
    val errorType = varchar("error_type", 64).nullable()  // e.g., "rate_limit", "timeout", "auth_error"

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient retrieval by task
        index("idx_api_logs_task", false, taskId, createdAt)
        // Index for analytics by provider/model
        index("idx_api_logs_provider_model", false, provider, model, createdAt)
    }
}

/**
 * API log data class for results
 */
data class ApiLog(
    val id: String,
    val taskId: String?,  // Nullable for tool-level calls without task context
    val subtaskId: String?,
    val provider: String,
    val model: String,
    val endpoint: String,
    val requestSource: String?,
    val requestPayload: String,
    val responsePayload: String?,
    val httpStatus: Int?,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val latencyMs: Int,
    val errorMessage: String?,
    val errorType: String?,
    val createdAt: Long
)

/**
 * API log statistics for global metrics
 */
data class ApiLogStatistics(
    val totalCalls: Long,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCost: Double,
    val avgLatencyMs: Int,
    val errorCount: Long
)
