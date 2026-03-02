package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.ui.theme.LCATheme
import javax.swing.JPanel

internal class OtherBubbleRenderer(
    private val context: Context
) : BaseBubbleRenderer() {

    internal interface Context {
        val bubbleContentContext: BubbleContentContext
    }

    fun render(message: Message): JPanel {
        return createConversationSummaryBubble(message) ?: createSystemBubble(message)
    }

    private fun createConversationSummaryBubble(message: Message): JPanel? {
        val summaryMetadata = MessageMetadataExtractor.extractConversationSummaryMetadata(message) ?: return null
        val summaryContent = message.content.ifBlank { "No summary content." }
        val cleanedSummary = if (summaryContent.startsWith("**Conversation summary")) {
            summaryContent.substringAfter("\n\n", summaryContent).ifBlank { summaryContent }
        } else {
            summaryContent
        }
        val summarySubtitle = if (summaryMetadata.summarizedCount > 0) {
            "\u2022 ${summaryMetadata.summarizedCount} messages"
        } else {
            null
        }

        return createUniversalBubble(
            icon = "\uD83D\uDCDD",
            title = "Summary",
            subtitle = summarySubtitle,
            content = cleanedSummary,
            backgroundColor = LCATheme.summaryBubbleBackground,
            foregroundColor = LCATheme.summaryBubbleForeground,
            context = context.bubbleContentContext
        )
    }

    private fun createSystemBubble(message: Message): JPanel {
        return createUniversalBubble(
            icon = "\u2699\uFE0F",
            title = "System",
            content = message.content,
            backgroundColor = LCATheme.systemBubbleBackground,
            foregroundColor = LCATheme.systemBubbleForeground,
            context = context.bubbleContentContext
        )
    }
}
