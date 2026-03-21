package pl.jclab.refio.core.api

import java.util.concurrent.CompletableFuture

/**
 * Platform-agnostic adapter for UI interactions.
 *
 * This interface lives in the core package (no IntelliJ dependencies) to enable
 * future CLI/web support. Platform-specific implementations (e.g., IntelliJUIAdapter)
 * bridge to the actual UI framework.
 *
 * Current operations:
 * - Notifications (info, warning, error messages)
 * - Status updates (progress indicators)
 * - User interaction (questions requiring response)
 * - Logging (plugin-level log display)
 */
interface UIAdapter {
    /**
     * Show an informational message to the user.
     */
    fun showMessage(message: String)

    /**
     * Show an error message to the user.
     */
    fun showError(error: String)

    /**
     * Update the status display (e.g., status bar text).
     */
    fun updateStatus(status: String)

    /**
     * Show progress for a long-running operation.
     *
     * @param title Description of the operation
     * @param fraction Progress fraction (0.0 to 1.0), or -1.0 for indeterminate
     */
    fun showProgress(title: String, fraction: Double)

    /**
     * Ask the user a question and wait for a response.
     *
     * @param question The question to display
     * @return A future that completes with the user's answer
     */
    fun askQuestion(question: String): CompletableFuture<String>

    /**
     * Log a message at the specified level for UI display.
     *
     * @param level Log level (e.g., "DEBUG", "INFO", "WARN", "ERROR")
     * @param message The log message
     */
    fun log(level: String, message: String)
}
