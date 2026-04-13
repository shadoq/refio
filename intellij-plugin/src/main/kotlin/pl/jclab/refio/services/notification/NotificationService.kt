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
