package pl.jclab.refio.cli.tui.state

import pl.jclab.refio.core.agents.events.AgentEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps AgentEvents to TuiChatMessages with per-agent color index assignment.
 * Counterpart of Compose ChatMessageMapper, using color indices instead of Color objects.
 */
object TuiChatMessageMapper {

    private val assignedColorIndices = ConcurrentHashMap<String, Int>()
    private var colorIndex = 0

    fun getAgentColorIndex(agentId: String): Int {
        return assignedColorIndices.getOrPut(agentId) {
            val idx = colorIndex
            colorIndex++
            idx
        }
    }

    fun mapEvent(event: AgentEvent): TuiChatMessage? {
        val colorIdx = getAgentColorIndex(event.sourceAgentId)

        return when (event) {
            is AgentEvent.AgentStarted -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Agent '${event.agentName}' started: ${event.task}",
                agentId = event.sourceAgentId,
                agentName = event.agentName,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.AGENT_STARTED
            )

            is AgentEvent.AgentCompleted -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = event.summary,
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.AGENT_COMPLETED
            )

            is AgentEvent.AgentFailed -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Agent failed: ${event.error}",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.AGENT_FAILED
            )

            // StreamChunk events are handled by TuiWorkflowListener (accumulation + dedup).
            // Do NOT create messages here — it would duplicate the streaming output.
            is AgentEvent.StreamChunk -> null

            is AgentEvent.ApprovalRequired -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "${event.action} (risk: ${event.risk})",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.APPROVAL_REQUEST
            )

            is AgentEvent.ArtifactProduced -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Artifact: ${event.artifact.name} (${event.artifact.type})",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.ARTIFACT
            )

            is AgentEvent.DataRequest -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Data request: ${event.query}",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.DATA_EXCHANGE
            )

            is AgentEvent.DataResponse -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Data response to ${event.targetAgentId}: ${event.response.take(200)}",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.DATA_EXCHANGE
            )

            is AgentEvent.ProgressUpdate -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "[${event.phase}] ${event.message}",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx
            )

            is AgentEvent.ApprovalDecision, is AgentEvent.SpawnAgentRequest, is AgentEvent.AgentSpawned -> null

            // Turn lifecycle events are consumed by GUI trace panel, not shown in TUI chat.
            is AgentEvent.TurnStarted,
            is AgentEvent.TurnEnded,
            is AgentEvent.LLMCallCompleted,
            is AgentEvent.ToolCalled -> null

            // Guardrail abort IS shown in TUI — user needs to see why the stream stopped.
            // Rendered as AGENT_FAILED so it picks up the red "✗" status icon.
            is AgentEvent.StreamAborted -> TuiChatMessage(
                id = event.id,
                timestamp = event.timestamp,
                role = "agent_event",
                content = "Stream aborted by guardrail [${event.code}]: ${event.reason} " +
                    "(partial=${event.partialLength} chars)",
                agentId = event.sourceAgentId,
                agentColorIndex = colorIdx,
                messageType = TuiMessageType.AGENT_FAILED
            )
        }
    }

    fun reset() {
        assignedColorIndices.clear()
        colorIndex = 0
    }
}
