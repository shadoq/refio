package pl.jclab.refio.cli

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.MultiAgentSessionRequest
import pl.jclab.refio.core.api.MultiAgentSessionResponse
import pl.jclab.refio.core.api.routers.MultiAgentRouter
import pl.jclab.refio.core.services.turn.ToolApprovalService
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiAgentAutoApproveTest {

    @Test
    fun `auto approve reaches multi-agent runs`() = runBlocking {
        // Nobody clicks approve in a headless workflow. Without the pattern deciding, every agent's
        // command that needs approval waited out the timeout and was refused.
        val approvals = ToolApprovalService(approvalTimeoutMs = 2_000)
        val decisions = CopyOnWriteArrayList<ApprovalDecision>()
        val multiAgentRouter = mockk<MultiAgentRouter>()
        coEvery { multiAgentRouter.launchMultiAgentSession(any(), any()) } coAnswers {
            decisions += approvals.requestApproval(
                ApprovalRequest("r1", "t-coder", "run_terminal_command", mapOf("command" to "npm test"), "run the tests")
            )
            decisions += approvals.requestApproval(
                ApprovalRequest("r2", "t-reviewer", "run_terminal_command", mapOf("command" to "rm -rf build"), "clean up")
            )
            MultiAgentSessionResponse(sessionId = "s1", name = "w", status = "COMPLETED", agents = emptyList(), createdAt = 0)
        }
        val router = mockk<CoreApiRouter>()
        every { router.toolApprovalService } returns approvals
        every { router.multiAgentRouter } returns multiAgentRouter

        runMultiAgentSession(router, MultiAgentSessionRequest(name = "w", yamlDefinition = "name: w"), Regex("^npm "))

        assertEquals(ApprovalDecision.Approved, decisions[0])
        assertTrue(decisions[1] is ApprovalDecision.NotPermitted, "a command outside the pattern is refused at once: ${decisions[1]}")
    }
}
