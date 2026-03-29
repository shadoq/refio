package pl.jclab.refio.core.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Execution metrics for message or step.
 * Stored as JSON in ChatMessagesTable.metadata.
 */
data class MessageMetrics(
    // LLM metrics
    val model: String? = null,              // e.g., "claude-3-5-sonnet-20241022"
    val provider: String? = null,           // e.g., "anthropic"
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Double = 0.0,
    val latencyMs: Int = 0,

    // Tool metrics (dla Agent mode)
    val toolsUsed: List<ToolUsageMetric> = emptyList(),
    val toolExecutionTimeMs: Int = 0,

    // Subtask reference (jeśli to execution summary)
    val subtaskId: String? = null,

    // Timestamps
    val startedAt: Long? = null,
    val completedAt: Long? = null
) {
    companion object {
        private val gson: Gson = GsonBuilder()
            .setPrettyPrinting()
            .create()

        /**
         * Parse from JSON string
         */
        fun fromJson(json: String?): MessageMetrics? {
            if (json.isNullOrBlank()) return null
            return try {
                gson.fromJson(json, MessageMetrics::class.java)
            } catch (e: Exception) {
                logger.warn { "Failed to parse message metrics: ${e.message}" }
                null
            }
        }

        /**
         * Serialize to JSON string
         */
        fun toJson(metrics: MessageMetrics): String {
            return gson.toJson(metrics)
        }

        /**
         * Create from LLM response
         */
        fun fromLLMResponse(
            model: String,
            provider: String,
            inputTokens: Int,
            outputTokens: Int,
            costUsd: Double,
            latencyMs: Int,
            startedAt: Long,
            completedAt: Long
        ): MessageMetrics {
            return MessageMetrics(
                model = model,
                provider = provider,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
                costUsd = costUsd,
                latencyMs = latencyMs,
                startedAt = startedAt,
                completedAt = completedAt
            )
        }

        /**
         * Create from subtask (execution summary)
         */
        fun fromSubtask(subtask: Subtask, toolsUsed: List<ToolUsageMetric> = emptyList()): MessageMetrics {
            val executionTime = if (subtask.startedAt != null && subtask.completedAt != null) {
                (subtask.completedAt - subtask.startedAt).toInt()
            } else {
                subtask.latencyMs
            }

            return MessageMetrics(
                model = subtask.llmModel,
                provider = subtask.llmProvider,
                inputTokens = subtask.inputTokens,
                outputTokens = subtask.outputTokens,
                totalTokens = subtask.inputTokens + subtask.outputTokens,
                costUsd = subtask.costUsd,
                latencyMs = subtask.latencyMs,
                toolsUsed = toolsUsed,
                toolExecutionTimeMs = executionTime,
                subtaskId = subtask.id,
                startedAt = subtask.startedAt,
                completedAt = subtask.completedAt
            )
        }
    }
}

/**
 * Metryka użycia pojedynczego narzędzia
 */
data class ToolUsageMetric(
    val toolName: String,
    val executionTimeMs: Int,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Extension method: Parse metrics from metadata JSON
 */
fun ChatMessage.getMetrics(): MessageMetrics? {
    return MessageMetrics.fromJson(this.metadata)
}

/**
 * Extension method: Check if message has metrics
 */
fun ChatMessage.hasMetrics(): Boolean {
    return !this.metadata.isNullOrBlank()
}
