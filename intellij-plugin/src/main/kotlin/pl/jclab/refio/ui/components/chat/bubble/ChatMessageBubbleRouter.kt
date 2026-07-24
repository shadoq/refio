package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

internal class ChatMessageBubbleRouter(
    private val userBubbleRenderer: UserBubbleRenderer,
    private val assistantBubbleRenderer: AssistantBubbleRenderer,
    private val toolBubbleRenderer: ToolBubbleRenderer,
    private val otherBubbleRenderer: OtherBubbleRenderer
) {
    /**
     * Render one message's bubble.
     *
     * [showAgentHeader] is decided by the caller as a pure function of the whole transcript
     * ([pl.jclab.refio.api.models.AgentGrouping]) and folded into the render cache key, so the
     * header decision no longer depends on renderer-internal mutable state that drifted out of sync
     * when cached bubbles skipped the renderer.
     */
    fun render(message: Message, showAgentHeader: Boolean): JPanel {
        val bubble = when (message.role) {
            "user" -> userBubbleRenderer.render(message)
            "assistant" -> assistantBubbleRenderer.render(message)
            "tool" -> toolBubbleRenderer.render(message)
            else -> otherBubbleRenderer.render(message)
        }

        // Wrap with an agent header when this message opens a subagent run. Applies to every role so
        // tool calls, user injections (subagent prompts), and assistant replies all end up under the
        // same visible group for the agent that produced them.
        val agentName = message.agentName
        if (agentName != null && showAgentHeader) {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = false
            wrapper.add(createAgentHeader(agentName, message.agentDepth ?: 0), BorderLayout.NORTH)
            wrapper.add(bubble, BorderLayout.CENTER)
            return wrapper
        }

        return bubble
    }

    private fun createAgentHeader(agentName: String, depth: Int): JPanel {
        val color = agentColor(agentName)
        val indent = "  ".repeat(depth)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 4, 2, 4)
            add(JLabel("${indent}Agent: $agentName").apply {
                foreground = color
                font = font.deriveFont(Font.BOLD, 11f)
            }, BorderLayout.WEST)
        }
    }

    companion object {
        /** Deterministic color from agent name */
        fun agentColor(name: String): Color {
            val hash = name.hashCode()
            val hue = (hash and 0x7FFFFFFF) % 360
            return Color.getHSBColor(hue / 360f, 0.6f, 0.85f)
        }
    }
}
