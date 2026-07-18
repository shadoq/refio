package pl.jclab.refio.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.jclab.refio.core.services.turn.AutoApprover
import pl.jclab.refio.core.services.turn.ToolApprovalService

/**
 * Headless auto-approval. Subscribes to [ToolApprovalService.pendingRequests] and
 * resolves each request via [AutoApprover] so a `--headless` run never blocks on the 5-minute
 * approval timeout. Wired only under `--headless`; TUI/interactive runs always ask a human.
 *
 * [job] runs until cancelled — the caller cancels it once the turn finishes.
 */
class AutoApproveListener(
    private val toolApprovalService: ToolApprovalService,
    private val autoApprove: Regex,
    scope: CoroutineScope,
) {
    private val handled = HashSet<String>()

    val job: Job = scope.launch {
        toolApprovalService.pendingRequests.collect { pending ->
            pending.forEach { req ->
                // requestId is unique per request; only resolve each one once (the flow re-emits the
                // whole list on every change).
                if (handled.add(req.requestId)) {
                    toolApprovalService.resolveApproval(
                        req.requestId,
                        AutoApprover.decide(req.toolName, req.arguments, autoApprove),
                    )
                }
            }
        }
    }
}
