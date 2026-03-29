package pl.jclab.refio.services.logging

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Application-level logging service for plugin debugging
 */
@Service(Service.Level.APP)
class PluginLogger {

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val maxEntries = 1000
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    /**
     * Log debug message
     */
    fun debug(component: String, message: String) {
        log(LogLevel.DEBUG, component, message)
    }

    /**
     * Log info message
     */
    fun info(component: String, message: String) {
        log(LogLevel.INFO, component, message)
    }

    /**
     * Log warning message
     */
    fun warn(component: String, message: String) {
        log(LogLevel.WARN, component, message)
    }

    /**
     * Log error message
     */
    fun error(component: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(LogLevel.ERROR, component, fullMessage)
    }

    /**
     * Log HTTP request
     */
    fun logRequest(method: String, url: String, body: String? = null) {
        val message = buildString {
            append("→ $method $url")
            if (body != null) {
                append("\nBody: $body")
            }
        }
        log(LogLevel.HTTP, "HTTP", message)
    }

    /**
     * Log HTTP response
     */
    fun logResponse(method: String, url: String, statusCode: Int, body: String? = null, durationMs: Long? = null) {
        val message = buildString {
            append("← $method $url - $statusCode")
            if (durationMs != null) {
                append(" (${durationMs}ms)")
            }
            if (body != null) {
                append("\nBody: ${body.take(500)}${if (body.length > 500) "..." else ""}")
            }
        }
        log(LogLevel.HTTP, "HTTP", message)
    }

    /**
     * Log LLM API call
     */
    fun apiCall(
        provider: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        latencyMs: Int,
        error: String? = null
    ) {
        val message = if (error == null) {
            "[API] $provider/$model → IN: $inputTokens OUT: $outputTokens Cost: \$${String.format("%.4f", costUsd)} Latency: ${latencyMs}ms - OK"
        } else {
            "[API] $provider/$model → ERROR: $error Latency: ${latencyMs}ms"
        }
        log(if (error == null) LogLevel.HTTP else LogLevel.ERROR, "LLM-API", message)
    }

    /**
     * Clear all log entries
     */
    fun clear() {
        _logEntries.value = emptyList()
    }

    private fun log(level: LogLevel, component: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            component = component,
            message = message
        )

        _logEntries.value = (_logEntries.value + entry).takeLast(maxEntries)
    }

    companion object {
        fun getInstance(): PluginLogger {
            return ApplicationManager.getApplication().getService(PluginLogger::class.java)
        }
    }
}

/**
 * Log entry
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val component: String,
    val message: String
) {
    fun format(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(timestamp))
        return "[$time] [${level.name}] [$component] $message"
    }
}

/**
 * Log level
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    HTTP
}
