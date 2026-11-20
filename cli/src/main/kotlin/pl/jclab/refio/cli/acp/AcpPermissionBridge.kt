package pl.jclab.refio.cli.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.turn.ToolApprovalService

private val logger = dualLogger("AcpPermissionBridge")

/**
 * Hands Refio's pending tool approvals to the IDE as `session/request_permission` and feeds the
 * answer back. The same hook the headless auto-approver uses, with a person on the other end.
 */
class AcpPermissionBridge(
    private val peer: AcpPeer,
    private val approvals: ToolApprovalService,
    private val sessionForTask: (taskId: String) -> AcpSession?,
    private val scope: CoroutineScope,
) {
    private val handled = HashSet<String>()

    fun start(): Job = scope.launch {
        approvals.pendingRequests.collect { pending ->
            // The flow re-emits the whole queue on every change; each request is asked once.
            pending.filter { handled.add(it.requestId) }.forEach { request -> scope.launch { ask(request) } }
        }
    }

    private suspend fun ask(request: ToolApprovalService.ApprovalRequest) {
        val session = sessionForTask(request.taskId)
        if (session == null) {
            logger.warn { "[ACP] approval for task ${request.taskId} without an IDE session - rejected" }
            approvals.resolveApproval(request.requestId, ToolApprovalService.ApprovalDecision.Rejected("No IDE session for this task"))
            return
        }
        // The turn loop builds the request id as "<taskId>_<toolCallId>"; reusing the tool call id
        // lets the IDE show the approval and the later execution as one entry.
        val toolCallId = request.requestId.removePrefix("${request.taskId}_")
        val toolCall = session.announceToolCall(toolCallId, request.toolName, request.arguments, "pending", request.proposedChange)

        val answer = try {
            peer.request("session/request_permission", buildJsonObject {
                put("sessionId", session.id)
                put("toolCall", toolCall)
                put("options", AcpMappings.permissionOptions(request.toolName))
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "[ACP] permission request failed: ${e.message}" }
            null
        }
        val decision = AcpMappings.permissionDecision(answer, request.toolName)
        logger.info { "[ACP] ${request.toolName} -> ${decision::class.simpleName}" }
        approvals.resolveApproval(request.requestId, decision)
        if (decision !is ToolApprovalService.ApprovalDecision.Approved && decision !is ToolApprovalService.ApprovalDecision.Trusted) {
            session.updateToolCall(toolCallId, "failed", "Rejected in the IDE")
        }
    }
}
