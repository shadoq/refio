package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.RegexSafetyValidator
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("ToolApprovalService")

/**
 * Manages tool approval flow for PermissionLevel.ASK.
 *
 * When a tool requires approval, TurnToolExecutor calls [requestApproval] which suspends
 * until the UI resolves via [resolveApproval]. Session-level trust rules allow auto-approval
 * for previously trusted tool+pattern combinations.
 */
class ToolApprovalService(
    /** Timeout in milliseconds for user to respond. 0 = no timeout. Default: 5 minutes. */
    private val approvalTimeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    data class ApprovalRequest(
        val requestId: String,
        val taskId: String,
        val toolName: String,
        val arguments: Map<String, Any>,
        val description: String,
        /** Concrete file change for editing tools, so the UI can show a diff before the write. */
        val proposedChange: ProposedChange? = null
    )

    sealed class ApprovalDecision {
        /** One-time approval — next time we ask again */
        data object Approved : ApprovalDecision()

        /** Approve + remember pattern for session. null pattern = trust all usages of this tool */
        data class Trusted(val toolName: String, val argsPattern: Regex? = null) : ApprovalDecision()

        /** Reject — stops the agent loop, returns to user prompt */
        data class Rejected(val reason: String? = null) : ApprovalDecision()
    }

    private data class PendingEntry(
        val request: ApprovalRequest,
        val deferred: CompletableDeferred<ApprovalDecision>
    )

    /** Pending requests: requestId → (request + deferred) */
    private val pending = ConcurrentHashMap<String, PendingEntry>()
    private val pendingLock = Any()

    /** Session-level trust rules: toolName → list of Regex patterns (null = unconditional trust) */
    private val sessionTrustRules = ConcurrentHashMap<String, MutableList<Regex?>>()
    private val sessionTrustRulesLock = Any()

    private val _pendingRequests = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingRequests: StateFlow<List<ApprovalRequest>> = _pendingRequests.asStateFlow()

    /**
     * Request approval from the user. Suspends until [resolveApproval] is called.
     * If the tool is already trusted in this session, returns Approved immediately.
     */
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        val trustPatterns = synchronized(sessionTrustRulesLock) {
            sessionTrustRules[request.toolName]?.toList().orEmpty()
        }
        if (trustPatterns.isNotEmpty()) {
            val argsString = serializeArgs(request.arguments)
            val isTrusted = trustPatterns.any { pattern ->
                pattern == null || pattern.containsMatchIn(argsString)
            }
            if (isTrusted) {
                logger.info { "[APPROVAL] Auto-approved ${request.toolName} (session trust)" }
                return ApprovalDecision.Approved
            }
        }

        val deferred = CompletableDeferred<ApprovalDecision>()
        synchronized(pendingLock) {
            pending[request.requestId] = PendingEntry(request, deferred)
            _pendingRequests.value = pending.values.map { it.request }
        }

        logger.info { "[APPROVAL] Waiting for user decision on ${request.toolName} (requestId=${request.requestId})" }

        return try {
            if (approvalTimeoutMs > 0) {
                withTimeout(approvalTimeoutMs) { deferred.await() }
            } else {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn { "[APPROVAL] Timeout after ${approvalTimeoutMs}ms for ${request.toolName}" }
            ApprovalDecision.Rejected("Approval timeout (${approvalTimeoutMs / 1000}s)")
        } finally {
            synchronized(pendingLock) {
                pending.remove(request.requestId)
                _pendingRequests.value = pending.values.map { it.request }
            }
        }
    }

    /**
     * Resolve a pending approval request. Called by UI.
     */
    fun resolveApproval(requestId: String, decision: ApprovalDecision) {
        if (decision is ApprovalDecision.Trusted) {
            decision.argsPattern?.pattern?.let { RegexSafetyValidator.validate(it) }
            synchronized(sessionTrustRulesLock) {
                sessionTrustRules
                    .getOrPut(decision.toolName) { mutableListOf() }
                    .add(decision.argsPattern)
            }
            logger.info { "[APPROVAL] Trusted ${decision.toolName} pattern=${decision.argsPattern?.pattern ?: "*"}" }
        }

        val entry = synchronized(pendingLock) {
            val removed = pending.remove(requestId)
            _pendingRequests.value = pending.values.map { it.request }
            removed
        }
        if (entry != null) {
            entry.deferred.complete(decision)
            logger.info { "[APPROVAL] Resolved requestId=$requestId decision=${decision::class.simpleName}" }
        } else {
            logger.warn { "[APPROVAL] No pending request for requestId=$requestId" }
        }
    }

    /**
     * Cancel all pending approvals (e.g. on task cancellation).
     */
    fun cancelAll() {
        synchronized(pendingLock) {
            pending.values.forEach { it.deferred.cancel() }
            pending.clear()
            _pendingRequests.value = emptyList()
        }
        logger.info { "[APPROVAL] Cancelled all pending approvals" }
    }

    /**
     * Reset session trust rules (e.g. on session end).
     */
    fun resetSessionTrustRules() {
        synchronized(sessionTrustRulesLock) {
            sessionTrustRules.clear()
        }
        logger.info { "[APPROVAL] Reset session trust rules" }
    }

    fun onSessionEnd() {
        resetSessionTrustRules()
    }

    private fun serializeArgs(args: Map<String, Any>): String {
        return args.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}
