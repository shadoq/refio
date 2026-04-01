package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import javax.swing.JPanel

internal class ChatMessageBubbleRouter(
    private val userBubbleRenderer: UserBubbleRenderer,
    private val assistantBubbleRenderer: AssistantBubbleRenderer,
    private val toolBubbleRenderer: ToolBubbleRenderer,
    private val otherBubbleRenderer: OtherBubbleRenderer
) {
    fun render(message: Message): JPanel {
        return when (message.role) {
            "user" -> userBubbleRenderer.render(message)
            "assistant" -> assistantBubbleRenderer.render(message)
            "tool" -> toolBubbleRenderer.render(message)
            else -> otherBubbleRenderer.render(message)
        }
    }
}
