package pl.jclab.refio.cli

import kotlinx.coroutines.coroutineScope
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.MultiAgentSessionRequest
import pl.jclab.refio.core.api.MultiAgentSessionResponse

/**
 * Runs a multi-agent session from the command line. With [autoApprove] set, a tool call of any
 * agent that needs approval is decided by the pattern instead of waiting out the approval timeout,
 * the same way it is for a single headless turn.
 */
internal suspend fun runMultiAgentSession(
    router: CoreApiRouter,
    request: MultiAgentSessionRequest,
    autoApprove: Regex?,
): MultiAgentSessionResponse = coroutineScope {
    val listener = autoApprove?.let { AutoApproveListener(router.toolApprovalService, it, this) }
    try {
        router.multiAgentRouter.launchMultiAgentSession(request)
    } finally {
        listener?.job?.cancel()
    }
}
