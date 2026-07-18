package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.util.UUID

/**
 * Tool for sending a message to the parent orchestrator or another agent.
 *
 * When used, the tool emits a DataRequest event and returns immediately
 * with AWAITING_RESPONSE metadata. The TurnToolExecutor is responsible for
 * detecting this metadata and suspending the turn loop until a DataResponse arrives.
 *
 * Message types:
 * - question: needs an answer (turn suspended until response)
 * - info: FYI notification (no suspension)
 * - blocker: cannot continue without help (turn suspended)
 */
class SendMessageTool(
    private val agentEventBus: AgentEventBus,
    private val agentInboxRegistry: AgentInboxRegistry? = null
) : Tool {
    override val name = "send_message"
    override val description = """Send a message to the parent orchestrator or another agent.
Use when you need information that you cannot find yourself.
For 'question' and 'blocker' types, your execution will PAUSE until a response is received (max 5 minutes).
For 'info' type, the message is sent without pausing.
The parent agent may ask the user if it doesn't know the answer."""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "to" to mapOf(
                "type" to "string",
                "description" to "Target: 'parent' (default) or agent name",
                "default" to "parent"
            ),
            "message" to mapOf(
                "type" to "string",
                "description" to "Your question or information to communicate"
            ),
            "type" to mapOf(
                "type" to "string",
                "enum" to listOf("question", "info", "blocker"),
                "description" to "Message type: question (needs answer), info (FYI), blocker (cannot continue)"
            )
        ),
        "required" to listOf("message", "type")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val agentId = params[pl.jclab.refio.core.tools.base.ToolInternalParams.AGENT_ID] as? String ?: return ToolResult.error("No agent context")
        val sessionId = params[pl.jclab.refio.core.tools.base.ToolInternalParams.SESSION_ID] as? String ?: (params[pl.jclab.refio.core.tools.base.ToolInternalParams.TASK_ID] as? String ?: "")
        val parentRunId = params[pl.jclab.refio.core.tools.base.ToolInternalParams.PARENT_RUN_ID] as? String
        val message = params["message"] as? String ?: return ToolResult.error("message required")
        val type = params["type"] as? String ?: "question"
        val to = params["to"] as? String ?: "parent"

        // Routing rules:
        //   - "parent": resolved via PARENT_RUN_ID (subagent → invoking turn). Kept unchanged.
        //   - named peer: validated against AgentInboxRegistry. Unknown peer fails fast instead of
        //     letting AgentTurnLoop suspend for 5 minutes on a response that will never come.
        //   - blank: rejected. Broadcast (no target) is not supported.
        val targetAgentId: String = when {
            to == "parent" -> parentRunId
                ?: return ToolResult.error("'to: parent' used outside a subagent invocation (no PARENT_RUN_ID)")
            to.isBlank() -> return ToolResult.error(
                "'to' is required — use an agent name (peer) or 'parent' (invoking turn). Broadcast is not supported."
            )
            else -> {
                if (agentInboxRegistry != null && sessionId.isNotBlank() &&
                    !agentInboxRegistry.isRegistered(sessionId, to)
                ) {
                    val known = agentInboxRegistry.listAgents(sessionId).sorted()
                    return ToolResult.error(
                        "No agent named '$to' in session $sessionId. " +
                            "Known peers: ${if (known.isEmpty()) "(none)" else known.joinToString()}"
                    )
                }
                to
            }
        }

        val requestId = UUID.randomUUID().toString()

        // Emit DataRequest event to the bus
        agentEventBus.emit(
            pl.jclab.refio.core.agents.events.AgentEvent.DataRequest(
                id = requestId,
                sessionId = sessionId,
                sourceAgentId = agentId,
                timestamp = System.currentTimeMillis(),
                correlationId = requestId,
                targetAgentId = targetAgentId,
                query = message,
                context = mapOf("type" to type, "from_tool" to "send_message")
            )
        )

        return when (type) {
            "info" -> {
                // Info messages don't pause execution
                ToolResult(
                    success = true,
                    output = "Info sent to $to: $message",
                    metadata = mapOf(
                        "type" to "MESSAGE_SENT",
                        "requestId" to requestId,
                        "messageType" to type,
                        "target" to to
                    )
                )
            }
            else -> {
                // question and blocker types cause the turn to pause
                ToolResult(
                    success = true,
                    output = "Message sent to $to. Waiting for response...",
                    metadata = mapOf(
                        "type" to "AWAITING_RESPONSE",
                        "requestId" to requestId,
                        "messageType" to type,
                        "target" to to
                    )
                )
            }
        }
    }
}
