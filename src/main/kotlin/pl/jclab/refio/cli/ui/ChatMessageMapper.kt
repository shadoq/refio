package pl.jclab.refio.cli.ui

import androidx.compose.ui.graphics.Color
import pl.jclab.refio.core.agents.events.AgentEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps AgentEvents to UIChatMessages with per-agent color assignment.
 */
object ChatMessageMapper {

    private val agentColors = listOf(
        Color(0xFF6C63FF), // Purple
        Color(0xFF00BFA5), // Teal
        Color(0xFFFF6D00), // Orange
        Color(0xFF2196F3), // Blue
        Color(0xFFE91E63), // Pink
        Color(0xFF4CAF50), // Green
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF9C27B0), // Deep Purple
    )

    private val assignedColors = ConcurrentHashMap<String, Color>()
    private var colorIndex = 0

    fun getAgentColor(agentId: String): Color {
        return assignedColors.getOrPut(agentId) {
            val color = agentColors[colorIndex % agentColors.size]
            colorIndex++
            color
        }
    }

    fun mapEvent(event: AgentEvent): UIChatMessage? {
        val color = getAgentColor(event.sourceAgentId)

        return when (event) {
            is AgentEvent.AgentStarted -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Agent '${event.agentName}' started: ${event.task}",
                agentId = event.sourceAgentId,
                agentName = event.agentName,
                agentColor = color,
                messageType = MessageType.AGENT_STARTED
            )

            is AgentEvent.AgentCompleted -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = event.summary,
                agentId = event.sourceAgentId,
                agentName = null,
                agentColor = color,
                messageType = MessageType.AGENT_COMPLETED
            )

            is AgentEvent.AgentFailed -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Agent failed: ${event.error}",
                agentId = event.sourceAgentId,
                agentName = null,
                agentColor = color,
                messageType = MessageType.AGENT_FAILED
            )

            is AgentEvent.StreamChunk -> if (event.isComplete) {
                UIChatMessage(
                    id = event.id,
                    timestamp = event.timestamp,
                    role = "assistant",
                    content = event.accumulated,
                    agentId = event.sourceAgentId,
                    agentColor = color,
                    isStreaming = false
                )
            } else null // Intermediate chunks handled by ComposeWorkflowListener

            is AgentEvent.ApprovalRequired -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "${event.action} (risk: ${event.risk})",
                agentId = event.sourceAgentId,
                agentColor = color,
                messageType = MessageType.APPROVAL_REQUEST
            )

            is AgentEvent.ArtifactProduced -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Artifact: ${event.artifact.name} (${event.artifact.type})",
                agentId = event.sourceAgentId,
                agentColor = color,
                messageType = MessageType.ARTIFACT
            )

            is AgentEvent.DataRequest -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Data request: ${event.query}",
                agentId = event.sourceAgentId,
                agentColor = color,
                messageType = MessageType.DATA_EXCHANGE
            )

            is AgentEvent.DataResponse -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Data response to ${event.targetAgentId}: ${event.response.take(200)}",
                agentId = event.sourceAgentId,
                agentColor = color,
                messageType = MessageType.DATA_EXCHANGE
            )

            is AgentEvent.ProgressUpdate -> UIChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "[${event.phase}] ${event.message}",
                agentId = event.sourceAgentId,
                agentColor = color
            )

            // Events that don't produce chat messages
            is AgentEvent.ApprovalDecision, is AgentEvent.SpawnAgentRequest, is AgentEvent.AgentSpawned -> null
        }
    }

    fun reset() {
        assignedColors.clear()
        colorIndex = 0
    }
}
