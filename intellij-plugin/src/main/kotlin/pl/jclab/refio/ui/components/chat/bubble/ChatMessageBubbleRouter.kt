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
    /** Track current agent group for collapsible sections */
    private var lastAgentName: String? = null

    fun render(message: Message): JPanel {
        val bubble = when (message.role) {
            "user" -> userBubbleRenderer.render(message)
            "assistant" -> assistantBubbleRenderer.render(message)
            "tool" -> toolBubbleRenderer.render(message)
            else -> otherBubbleRenderer.render(message)
        }

        // Wrap with agent header if message comes from a subagent
        val agentName = message.agentName
        if (agentName != null && message.role == "assistant") {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = false

            // Add agent header if this is a new agent group
            if (agentName != lastAgentName) {
                val header = createAgentHeader(agentName, message.agentDepth ?: 0)
                wrapper.add(header, BorderLayout.NORTH)
                lastAgentName = agentName
            }

            wrapper.add(bubble, BorderLayout.CENTER)
            return wrapper
        }

        if (agentName == null && message.role == "assistant") {
            lastAgentName = null
        }

        return bubble
    }

    /** Reset agent group tracking (e.g., on conversation clear) */
    fun resetAgentTracking() {
        lastAgentName = null
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
