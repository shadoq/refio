package pl.jclab.refio.core.services.logging

import mu.KLogger
import mu.KotlinLogging

/**
 * Core logger for embedded core services (platform-agnostic).
 *
 * This logger ONLY logs to kotlin-logging (idea.log), NOT to PluginLogger UI.
 * Core services should not depend on plugin UI infrastructure.
 *
 * Usage:
 * ```kotlin
 * private val logger = coreLogger("ComponentName")
 *
 * logger.info { "Message" }
 * logger.error(throwable) { "Error occurred" }
 * ```
 */
class CoreLogger(private val kotlinLogger: KLogger) {
    private fun safeMessage(msg: Any?): String {
        val message = msg?.toString() ?: "null"
        return pl.jclab.refio.core.security.SecureLogger.redact(message)
    }

    /**
     * Log debug message
     */
    fun debug(msg: () -> Any?) {
        kotlinLogger.debug { safeMessage(msg()) }
    }

    /**
     * Log info message
     */
    fun info(msg: () -> Any?) {
        kotlinLogger.info { safeMessage(msg()) }
    }

    /**
     * Log warning message
     */
    fun warn(msg: () -> Any?) {
        kotlinLogger.warn { safeMessage(msg()) }
    }

    /**
     * Log warning message with throwable
     */
    fun warn(throwable: Throwable, msg: () -> Any?) {
        kotlinLogger.warn(throwable) { safeMessage(msg()) }
    }

    /**
     * Log error message
     */
    fun error(msg: () -> Any?) {
        kotlinLogger.error { safeMessage(msg()) }
    }

    /**
     * Log error message with throwable
     * Compatible with kotlin-logging syntax: logger.error(exception) { "message" }
     */
    fun error(throwable: Throwable, msg: () -> Any?) {
        kotlinLogger.error(throwable) { safeMessage(msg()) }
    }

    /**
     * Log error with throwable (no message)
     * Compatible with kotlin-logging syntax: logger.error(exception)
     */
    fun error(throwable: Throwable) {
        kotlinLogger.error(throwable) { safeMessage(throwable.message ?: "Exception occurred") }
    }
}

/**
 * Create a core logger instance for the calling class.
 *
 * Component name is derived from the class name.
 *
 * Example:
 * ```kotlin
 * private val logger = coreLogger()
 * ```
 */
inline fun <reified T : Any> T.coreLogger(): CoreLogger {
    return CoreLogger(KotlinLogging.logger(T::class.java.name))
}

/**
 * Create a core logger instance with explicit component name.
 *
 * Use this at top-level (outside classes).
 *
 * Example:
 * ```kotlin
 * private val logger = coreLogger("MyComponent")
 * ```
 */
fun coreLogger(component: String): CoreLogger {
    return CoreLogger(KotlinLogging.logger(component))
}
