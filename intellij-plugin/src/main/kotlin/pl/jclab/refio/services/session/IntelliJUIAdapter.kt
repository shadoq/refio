package pl.jclab.refio.services.session

import com.intellij.openapi.project.Project
import pl.jclab.refio.core.api.UIAdapter
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.notification.NotificationService
import java.util.concurrent.CompletableFuture

/**
 * IntelliJ-specific implementation of [UIAdapter].
 *
 * Bridges core UI operations to IntelliJ platform services:
 * - Notifications via [NotificationService]
 * - Logging via [PluginLogger]
 * - Status updates via the dual logger (StatusBar is handled separately via StateFlows)
 */
class IntelliJUIAdapter(private val project: Project) : UIAdapter {

    private val logger = dualLogger("IntelliJUIAdapter")

    override fun showMessage(message: String) {
        NotificationService.showInfo(project, "Refio", message)
    }

    override fun showError(error: String) {
        NotificationService.showError(project, "Refio", error)
    }

    override fun updateStatus(status: String) {
        logger.info { status }
    }

    override fun showProgress(title: String, fraction: Double) {
        val percentText = if (fraction < 0) "indeterminate" else "${(fraction * 100).toInt()}%"
        logger.info { "Progress: $title ($percentText)" }
    }

    override fun askQuestion(question: String): CompletableFuture<String> {
        // For now, log the question. Full interactive question support is handled
        // by UserInteraction (orchestration-level) rather than this adapter.
        logger.info { "Question asked: $question" }
        return CompletableFuture<String>().also {
            it.completeExceptionally(
                UnsupportedOperationException(
                    "Interactive questions should use UserInteraction service"
                )
            )
        }
    }

    override fun log(level: String, message: String) {
        when (level.uppercase()) {
            "DEBUG" -> logger.debug { message }
            "INFO" -> logger.info { message }
            "WARN" -> logger.warn { message }
            "ERROR" -> logger.error { message }
            else -> logger.info { "[$level] $message" }
        }
    }
}
