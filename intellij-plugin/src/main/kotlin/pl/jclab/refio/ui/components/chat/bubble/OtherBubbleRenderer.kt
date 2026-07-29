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
        return createGuardianNudgeBubble(message)
            ?: createConversationSummaryBubble(message)
            ?: createSystemBubble(message)
    }

    /**
     * Gentle render for guardian re-entry nudges. The stored content is a model-facing
     * "STOP — the turn is NOT finished" instruction; showing it verbatim reads like a hard
     * failure. Instead we surface a soft "agent guidance" note so the user still sees that
     * something was triggered, without the alarming wall of text (which remains in the DB).
     */
    private fun createGuardianNudgeBubble(message: Message): JPanel? {
        if (!MessageMetadataExtractor.isGuardianNudge(message)) return null
        // Attribute the nudge to its origin. A guardian re-entry fired inside a subagent
        // turn carries the subagent's name; surfaced unattributed in the unified transcript
        // it reads as if the MAIN agent were being nagged right after delegating. Naming the
        // subagent makes clear whose turn was steered.
        val agent = message.agentName?.takeIf { it.isNotBlank() }
        return createUniversalBubble(
            title = if (agent != null) "Agent guidance · $agent" else "Agent guidance",
            content = if (agent != null) {
                "Refio prompted the '$agent' subagent to finish the remaining steps before ending its turn."
            } else {
                "Refio prompted the agent to finish the remaining steps before ending the turn."
            },
            backgroundColor = LCATheme.summaryBubbleBackground,
            foregroundColor = LCATheme.summaryBubbleForeground,
            context = context.bubbleContentContext
        )
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
            title = "System",
            content = message.content,
            backgroundColor = LCATheme.systemBubbleBackground,
            foregroundColor = LCATheme.systemBubbleForeground,
            context = context.bubbleContentContext
        )
    }
}
