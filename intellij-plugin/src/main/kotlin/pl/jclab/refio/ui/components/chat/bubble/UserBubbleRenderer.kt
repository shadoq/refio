package pl.jclab.refio.ui.components.chat.bubble

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.ui.components.chat.EditableUserBubble
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class UserBubbleRenderer(
    private val context: Context
) : BaseBubbleRenderer() {

    internal interface Context {
        val project: Project
        val bubbleCompactGap: Int
        val bubbleContentContext: BubbleContentContext
        fun rewindAndResendFromMessage(messageId: String, newContent: String)
    }

    fun render(message: Message): JPanel {
        val outerPanel = createOuterPanel()
        val factory = context.bubbleContentContext.componentFactory
        val messageContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val messageBlock = context.bubbleContentContext.createMessageBlock(LCATheme.userBubbleBackground).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        messageBlock.add(
            factory.createBubbleHeader(
                icon = "\uD83D\uDC64",
                title = "You",
                foregroundColor = LCATheme.userBubbleForeground
            ).apply { alignmentX = Component.LEFT_ALIGNMENT }
        )

        val contentPanel = factory.createBubbleContentPanel(
            content = message.content,
            backgroundColor = LCATheme.userBubbleBackground,
            foregroundColor = LCATheme.userBubbleForeground,
            isUser = true
        )

        val editableBubble = EditableUserBubble(
            project = context.project,
            initialText = message.content,
            contentComponent = contentPanel,
            onSubmit = { newContent ->
                context.rewindAndResendFromMessage(message.id, newContent)
            }
        ).apply { alignmentX = Component.LEFT_ALIGNMENT }

        val userActions = factory.wrapRightAligned(
            factory.createMessageActionsPanel(
                message = message,
                onEdit = { editableBubble.beginEditing() }
            )
        ).apply { alignmentX = Component.LEFT_ALIGNMENT }

        messageBlock.add(editableBubble)
        messageBlock.add(Box.createVerticalStrut(context.bubbleCompactGap))
        messageBlock.add(userActions)

        messageContainer.add(messageBlock)

        val contextMetadata = MessageMetadataExtractor.extractUserContextMetadata(message)
        if (contextMetadata != null && contextMetadata.contextRefs.isNotEmpty()) {
            messageContainer.add(Box.createVerticalStrut(context.bubbleCompactGap))
            val contextBadge = factory.createContextBadge(contextMetadata)
            contextBadge.alignmentX = Component.LEFT_ALIGNMENT
            messageContainer.add(contextBadge)
        }

        return addToOuter(outerPanel, messageContainer)
    }
}
