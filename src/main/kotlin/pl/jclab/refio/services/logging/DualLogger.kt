package pl.jclab.refio.services.logging

import mu.KLogger
import mu.KotlinLogging
import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * Dual logger that logs to both kotlin-logging (IntelliJ standard logger)
 * and PluginLogger (for UI display).
 *
 * This ensures that all core logging is visible both in:
 * 1. IntelliJ's idea.log (via kotlin-logging)
 * 2. Plugin's LogsPanel UI (via PluginLogger)
 *
 * Usage:
 * ```kotlin
 * private val logger = dualLogger()
 *
 * logger.info { "Message" }
 * logger.error(throwable) { "Error occurred" }
 * ```
 */
class DualLogger(
    private val kotlinLogger: KLogger,
    private val component: String
) {
    private fun safeMessage(msg: Any?): String {
        val message = msg?.toString() ?: "null"
        return pl.jclab.refio.core.security.SecureLogger.redact(message)
    }
    /**
     * Plugin logger instance - may be null when running in embedded core context
     * (outside IntelliJ Application thread).
     *
     * When null, DualLogger gracefully degrades to kotlin-logging only.
     */
    private val pluginLogger: PluginLogger?
        get() = try {
            PluginLogger.getInstance()
        } catch (e: Exception) {
            // Application is null (embedded core context) - skip UI logging
            null
        }

    /**
     * Log debug message to both loggers.
     */
    fun debug(msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.debug { message }
        pluginLogger?.debug(component, message)
    }

    /**
     * Log info message to both loggers.
     */
    fun info(msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.info { message }
        pluginLogger?.info(component, message)
    }

    /**
     * Log warning message to both loggers.
     */
    fun warn(msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.warn { message }
        pluginLogger?.warn(component, message)
    }

    /**
     * Log warning message with throwable to both loggers.
     */
    fun warn(throwable: Throwable, msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.warn(throwable) { message }
        pluginLogger?.warn(component, safeMessage("$message\n${throwable.stackTraceToString()}"))
    }

    /**
     * Log error message to both loggers.
     */
    fun error(msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.error { message }
        pluginLogger?.error(component, message)
    }

    /**
     * Log error message with throwable to both loggers.
     * Compatible with kotlin-logging syntax: logger.error(exception) { "message" }
     */
    fun error(throwable: Throwable, msg: () -> Any?) {
        val message = safeMessage(msg())
        kotlinLogger.error(throwable) { message }
        pluginLogger?.error(component, message, throwable)
    }

    /**
     * Log error with throwable (no message).
     * Compatible with kotlin-logging syntax: logger.error(exception)
     */
    fun error(throwable: Throwable) {
        val message = safeMessage(throwable.message ?: "Exception occurred")
        kotlinLogger.error(throwable) { message }
        pluginLogger?.error(component, message, throwable)
    }

    /**
     * Log API request to both console/plugin logs AND api_logs database.
     *
     * Error #15: Centralized API logging
     *
     * @param provider Provider name (ollama, openai, anthropic)
     * @param model Model identifier
     * @param endpoint API endpoint URL
     * @param requestJson Request payload as JSON string
     * @param taskId Task ID for database logging (optional)
     * @param subtaskId Subtask ID for database logging (optional)
     */
    fun apiRequest(
        provider: String,
        model: String,
        endpoint: String,
        requestJson: String,
        taskId: String? = null,
        subtaskId: String? = null
    ) {
        // Log to console/plugin
        val safeRequestJson = pl.jclab.refio.core.security.SecureLogger.redact(requestJson)
        val truncated = if (safeRequestJson.length > 500) {
            safeRequestJson.substring(0, 500) + "... (truncated)"
        } else {
            safeRequestJson
        }
        debug { "[$component] API Request to $provider/$model: $truncated" }

        // Log to database (taskId can be null for tool-level calls)
        try {
            val apiLogRepo = pl.jclab.refio.core.db.repositories.ApiLogRepository()
            apiLogRepo.create(
                taskId = taskId,
                subtaskId = subtaskId,
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestPayload = requestJson,
                responsePayload = null,
                httpStatus = null,
                inputTokens = 0,
                outputTokens = 0,
                costUsd = 0.0,
                latencyMs = 0,
                errorMessage = "REQUEST_PENDING",
                errorType = null
            )
        } catch (e: Exception) {
            error(e) { "[$component] Failed to log API request to database" }
        }
    }

    /**
     * Log API response to both console/plugin logs AND api_logs database.
     *
     * Error #15: Centralized API logging
     *
     * @param provider Provider name
     * @param model Model identifier
     * @param endpoint API endpoint URL
     * @param requestJson Request payload as JSON string
     * @param responseJson Response payload as JSON string
     * @param httpStatus HTTP status code
     * @param inputTokens Input tokens count
     * @param outputTokens Output tokens count
     * @param costUsd Cost in USD
     * @param latencyMs Latency in milliseconds
     * @param taskId Task ID for database logging (optional)
     * @param subtaskId Subtask ID for database logging (optional)
     */
    fun apiResponse(
        provider: String,
        model: String,
        endpoint: String,
        requestJson: String,
        responseJson: String,
        httpStatus: Int,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        latencyMs: Int,
        taskId: String? = null,
        subtaskId: String? = null,
        source: String? = null,
        rawApiResponseChunk: String? = null
    ) {
        // Log to console/plugin
        val safeResponseJson = pl.jclab.refio.core.security.SecureLogger.redact(responseJson)
        val truncated = if (safeResponseJson.length > 500) {
            safeResponseJson.substring(0, 500) + "... (truncated)"
        } else {
            safeResponseJson
        }
        info {
            "[$component] API Response from $provider/$model: " +
            "status=$httpStatus, tokens=$inputTokens/$outputTokens, cost=$$costUsd, latency=${latencyMs}ms"
        }
        debug { "[$component] Response body: $truncated" }
        if (!rawApiResponseChunk.isNullOrBlank()) {
            val safeRawChunk = pl.jclab.refio.core.security.SecureLogger.redact(rawApiResponseChunk)
            val rawChunkPreview = if (safeRawChunk.length > 8192) {
                safeRawChunk.substring(0, 8192) + "... (truncated)"
            } else {
                safeRawChunk
            }
            debug { "[$component] Raw last API chunk: $rawChunkPreview" }
        }

        val responsePayloadForStorage = if (rawApiResponseChunk.isNullOrBlank()) {
            responseJson
        } else {
            gson.toJson(
                mapOf(
                    "normalized_response" to responseJson,
                    "raw_last_chunk" to rawApiResponseChunk
                )
            )
        }

        // Log to database (taskId can be null for tool-level calls)
        try {
            val apiLogRepo = pl.jclab.refio.core.db.repositories.ApiLogRepository()
            apiLogRepo.create(
                taskId = taskId,
                subtaskId = subtaskId,
                provider = provider,
                model = model,
                endpoint = endpoint,
                source = source,
                requestPayload = requestJson,
                responsePayload = responsePayloadForStorage,
                httpStatus = httpStatus,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = costUsd,
                latencyMs = latencyMs,
                errorMessage = null,
                errorType = null
            )
        } catch (e: Exception) {
            error(e) { "[$component] Failed to log API response to database" }
        }
    }

    /**
     * Log API error to both console/plugin logs AND api_logs database.
     *
     * Error #15: Centralized API logging
     *
     * @param provider Provider name
     * @param model Model identifier
     * @param endpoint API endpoint URL
     * @param requestJson Request payload as JSON string
     * @param httpStatus HTTP status code (may be null for network errors)
     * @param error Exception that occurred
     * @param latencyMs Latency in milliseconds
     * @param taskId Task ID for database logging (optional)
     * @param subtaskId Subtask ID for database logging (optional)
     */
    fun apiError(
        provider: String,
        model: String,
        endpoint: String,
        requestJson: String,
        httpStatus: Int?,
        error: Throwable,
        latencyMs: Int,
        taskId: String? = null,
        subtaskId: String? = null,
        source: String? = null
    ) {
        // Log to console/plugin
        this.error(error) {
            "[$component] API Error from $provider/$model: " +
            "status=$httpStatus, latency=${latencyMs}ms, error=${safeMessage(error.message)}"
        }

        // Log to database (taskId can be null for tool-level calls)
        try {
            val apiLogRepo = pl.jclab.refio.core.db.repositories.ApiLogRepository()
            apiLogRepo.create(
                taskId = taskId,
                subtaskId = subtaskId,
                provider = provider,
                model = model,
                endpoint = endpoint,
                source = source,
                requestPayload = requestJson,
                responsePayload = null,
                httpStatus = httpStatus,
                inputTokens = 0,
                outputTokens = 0,
                costUsd = 0.0,
                latencyMs = latencyMs,
                errorMessage = error.message,
                errorType = error::class.simpleName
            )
        } catch (e: Exception) {
            this.error(e) { "[$component] Failed to log API error to database" }
        }
    }
}

/**
 * Create a dual logger instance for the calling class.
 *
 * Component name is derived from the class name.
 *
 * Example:
 * ```kotlin
 * private val logger = dualLogger()
 * ```
 */
inline fun <reified T : Any> T.dualLogger(): DualLogger {
    val componentName = T::class.simpleName ?: "Unknown"
    return DualLogger(
        kotlinLogger = KotlinLogging.logger(T::class.java.name),
        component = componentName
    )
}

/**
 * Create a dual logger instance with explicit component name.
 *
 * Use this at top-level (outside classes).
 *
 * Example:
 * ```kotlin
 * private val logger = dualLogger("MyComponent")
 * ```
 */
fun dualLogger(component: String): DualLogger {
    return DualLogger(
        kotlinLogger = KotlinLogging.logger(component),
        component = component
    )
}
