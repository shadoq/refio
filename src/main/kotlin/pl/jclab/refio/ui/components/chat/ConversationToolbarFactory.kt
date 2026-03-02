package pl.jclab.refio.ui.components.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

internal class ConversationToolbarFactory(
    private val project: Project,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    private val parentComponent: JComponent,
    private val onContinueRequested: (() -> Unit)? = null
) {

    private val logger = dualLogger("ConversationToolbarFactory")

    fun createConversationToolbar(): JPanel {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            border = LCATheme.compoundBorder(
                LCATheme.customLineBorder(LCATheme.subtleSeparatorColor, 1, 0, 0, 0),
                LCATheme.paddedBorder(4, 0, 4, 0)
            )
            background = LCATheme.backgroundColor
        }

        toolbar.add(createIconButton("\uD83D\uDC4D", "Rate conversation positive") {
            rateConversation(1)
        })

        toolbar.add(createIconButton("\uD83D\uDC4E", "Rate conversation negative") {
            rateConversation(-1)
        })

        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 16)
        })

        toolbar.add(createIconButton("⤵", "Continue from last point") {
            continueConversation()
        })

        toolbar.add(createIconButton("\uD83D\uDCE6", "Summarize and compact conversation") {
            compactConversation()
        })

        toolbar.add(createIconButton("\uD83D\uDCCB", "Copy entire conversation") {
            val messages = sessionManager.messages.value
            if (messages.isEmpty()) {
                showNotification("Info", "No messages to copy", NotificationType.INFORMATION)
                return@createIconButton
            }
            ChatConversationExportUtil.copyConversation(sessionManager.activeSession.value, messages)
            showNotification("Success", "Conversation copied to clipboard")
        })

        toolbar.add(createIconButton("\uD83D\uDCBE", "Export conversation to file") {
            val session = sessionManager.activeSession.value ?: return@createIconButton
            val messages = sessionManager.messages.value
            if (messages.isEmpty()) {
                showNotification("Info", "No messages to export", NotificationType.INFORMATION)
                return@createIconButton
            }
            ChatConversationExportUtil.exportConversation(
                parent = parentComponent,
                session = session,
                messages = messages,
                onSuccess = { fileName ->
                    showNotification("Success", "Conversation exported to $fileName")
                },
                onError = { error ->
                    logger.error { "Failed to export conversation: $error" }
                    showNotification("Error", error, NotificationType.ERROR)
                }
            )
        })

        return toolbar
    }

    private fun createIconButton(
        icon: String,
        tooltip: String,
        preferredWidth: Int = 28,
        action: () -> Unit
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(preferredWidth, 24)
            font = font.deriveFont(if (icon.length > 2) 11f else 14f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                scope.launch {
                    try {
                        action()
                    } catch (e: Exception) {
                        logger.error(e) { "Toolbar action failed" }
                        showNotification("Error", e.message ?: "Action failed", NotificationType.ERROR)
                    }
                }
            }
        }
    }

    private fun rateConversation(rating: Int) {
        val session = sessionManager.activeSession.value ?: return

        scope.launch {
            try {
                logger.info { "Rating conversation ${session.id}: $rating" }

                sessionManager.apiRouter.updateTask(
                    session.id, pl.jclab.refio.core.api.UpdateTaskRequest(rate = rating)
                )

                val message = if (rating > 0) {
                    "\uD83D\uDC4D Thanks for positive feedback!"
                } else {
                    "\uD83D\uDC4E Thanks for feedback. We'll improve!"
                }

                showNotification("Feedback", message)
                logger.info { "Successfully saved rating for task ${session.id}" }

            } catch (e: Exception) {
                logger.error(e) { "Failed to rate conversation" }
                showNotification("Error", "Failed to save rating: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    private fun continueConversation() {
        if (onContinueRequested == null) {
            logger.warn { "Continue requested but no handler is configured" }
            showNotification("Info", "Prompt input is not available", NotificationType.INFORMATION)
            return
        }

        onContinueRequested.invoke()
    }

    private fun compactConversation() {
        val session = sessionManager.activeSession.value ?: return

        logger.info { "Compacting conversation for session ${session.id}" }
        showNotification("Summary", "Generating conversation summary...", NotificationType.INFORMATION)

        scope.launch {
            try {
                sessionManager.generateSummary()
                showNotification("Success", "Conversation summary created", NotificationType.INFORMATION)
            } catch (e: Exception) {
                logger.error(e) { "Failed to compact conversation" }
                showNotification(
                    "Error",
                    "Failed to generate conversation summary: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun showNotification(
        title: String, content: String, type: NotificationType = NotificationType.INFORMATION
    ) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("Refio", title, content, type), project
            )
        }
    }
}
