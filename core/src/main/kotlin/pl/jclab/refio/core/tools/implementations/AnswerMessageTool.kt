package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.util.UUID

/**
 * Reply to a pending [AgentEvent.DataRequest] addressed to this agent.
 *
 * Emits a [AgentEvent.DataResponse] that unblocks the sender's suspended turn
 * (see AgentTurnLoop AWAITING_RESPONSE handling).
 *
 * Validation: the requestId must currently be pending on this agent's inbox. This blocks
 * a hallucinating LLM from forging responses to requests that were never addressed to it.
 */
class AnswerMessageTool(
    private val agentEventBus: AgentEventBus,
    private val agentInboxRegistry: AgentInboxRegistry
) : Tool {
    override val name = "answer_message"
    override val description = """Reply to a pending message from another agent.
Use this to answer a question that arrived via the system inbox (see system note listing pending requestIds).
Pass the exact requestId from the inbox entry and your answer as 'response'.
Only works inside a multi-agent session; outside of one the call will be rejected."""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "string",
                "description" to "The id of the pending request (from the inbox system note)"
            ),
            "response" to mapOf(
                "type" to "string",
                "description" to "The answer to send back to the requesting agent"
            )
        ),
        "required" to listOf("requestId", "response")
    )

    override fun validateParams(params: Map<String, Any>) {
        val requestId = params["requestId"] as? String
        if (requestId.isNullOrBlank()) throw IllegalArgumentException("Parameter 'requestId' is required")
        val response = params["response"] as? String
        if (response.isNullOrBlank()) throw IllegalArgumentException("Parameter 'response' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val agentName = params[ToolInternalParams.AGENT_NAME] as? String
            ?: return ToolResult.error("answer_message requires a multi-agent context (no AGENT_NAME)")
        val sessionId = params[ToolInternalParams.SESSION_ID] as? String
            ?: return ToolResult.error("answer_message requires a multi-agent context (no SESSION_ID)")
        val requestId = params["requestId"] as? String
            ?: return ToolResult.error("requestId required")
        val response = params["response"] as? String
            ?: return ToolResult.error("response required")

        val inbox = agentInboxRegistry.find(sessionId, agentName)
            ?: return ToolResult.error(
                "No inbox for agent '$agentName' in session $sessionId — not a multi-agent session?"
            )

        val original = inbox.snapshotPending().firstOrNull { it.id == requestId }
            ?: return ToolResult.error(
                "No pending request with id=$requestId for agent '$agentName'. " +
                    "Check the inbox system note for the correct requestId."
            )

        agentEventBus.emit(
            AgentEvent.DataResponse(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                sourceAgentId = agentName,
                timestamp = System.currentTimeMillis(),
                correlationId = original.correlationId,
                targetAgentId = original.sourceAgentId,
                requestId = requestId,
                response = response,
                artifacts = emptyList(),
            )
        )
        inbox.markAnswered(requestId)

        return ToolResult(
            success = true,
            output = "Response delivered to ${original.sourceAgentId}",
            metadata = mapOf(
                "requestId" to requestId,
                "targetAgentId" to original.sourceAgentId
            )
        )
    }
}
