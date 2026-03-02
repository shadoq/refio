package pl.jclab.refio.services.notification

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("NotificationService")

/**
 * Service for showing IntelliJ IDE notifications (balloons).
 *
 * Provides non-intrusive notifications to user without modal dialogs.
 * All notifications appear in IDE's notification balloon and event log.
 */
object NotificationService {
    private const val NOTIFICATION_GROUP_ID = "Refio.Notifications"

    /**
     * Show information notification (blue icon)
     *
     * @param project Project context (null for application-level notification)
     * @param title Notification title
     * @param content Notification message
     */
    fun showInfo(project: Project?, title: String, content: String) {
        showNotification(project, title, content, NotificationType.INFORMATION)
    }

    /**
     * Show warning notification (yellow icon)
     *
     * @param project Project context (null for application-level notification)
     * @param title Notification title
     * @param content Notification message
     */
    fun showWarning(project: Project?, title: String, content: String) {
        showNotification(project, title, content, NotificationType.WARNING)
    }

    /**
     * Show error notification (red icon)
     *
     * @param project Project context (null for application-level notification)
     * @param title Notification title
     * @param content Notification message
     */
    fun showError(project: Project?, title: String, content: String) {
        showNotification(project, title, content, NotificationType.ERROR)
    }

    /**
     * Show RAG service unavailable notification
     * (only shown once per circuit breaker open event)
     */
    fun showRagUnavailable(project: Project?, providerType: String, endpoint: String) {
        val title = "RAG Search Unavailable"
        val content = when (providerType) {
            "ollama" -> "Cannot connect to Ollama at $endpoint. RAG search is disabled. " +
                    "Make sure Ollama is running and has the embedding model loaded."
            "openai" -> "Cannot connect to OpenAI embedding API. RAG search is disabled. " +
                    "Check your API key and network connection."
            else -> "Cannot connect to embedding provider ($providerType). RAG search is disabled."
        }

        showWarning(project, title, content)

        logger.info { "Showed RAG unavailable notification: $providerType at $endpoint" }
    }

    private fun showNotification(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType
    ) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)

            val notification: Notification = notificationGroup.createNotification(
                title,
                content,
                type
            )

            // Show notification
            notification.notify(project)

            logger.debug { "Notification shown: [$type] $title - $content" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to show notification: $title" }
        }
    }
}
