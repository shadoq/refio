package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalRequest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolApprovalServiceTest {

    private val service = ToolApprovalService()

    private fun request(
        toolName: String = "run_terminal_command",
        args: Map<String, Any> = mapOf("command" to "git status")
    ) = ApprovalRequest(
        requestId = "req_${System.nanoTime()}",
        taskId = "task_1",
        toolName = toolName,
        arguments = args,
        description = "$toolName: ${args.values.firstOrNull()}"
    )

    @Nested
    inner class ApproveTests {
        @Test
        fun `should resolve with Approved when user approves`() = runTest {
            val req = request()
            val deferred = async { service.requestApproval(req) }
            delay(10)

            service.resolveApproval(req.requestId, ApprovalDecision.Approved)
            val result = deferred.await()

            assertIs<ApprovalDecision.Approved>(result)
        }

        @Test
        fun `should clear pending requests after resolve`() = runTest {
            val req = request()
            val deferred = async { service.requestApproval(req) }
            delay(10)

            assertEquals(1, service.pendingRequests.value.size)

            service.resolveApproval(req.requestId, ApprovalDecision.Approved)
            deferred.await()

            assertTrue(service.pendingRequests.value.isEmpty())
        }
    }

    @Nested
    inner class TrustTests {
        @Test
        fun `should auto-approve trusted tool on subsequent requests`() = runTest {
            val req1 = request()
            val deferred1 = async { service.requestApproval(req1) }
            delay(10)

            service.resolveApproval(req1.requestId, ApprovalDecision.Trusted("run_terminal_command"))
            deferred1.await()

            // Second request with same tool — should auto-approve
            val req2 = request()
            val result2 = service.requestApproval(req2)
            assertIs<ApprovalDecision.Approved>(result2)
        }

        @Test
        fun `should auto-approve when pattern matches`() = runTest {
            val req1 = request(args = mapOf("command" to "git push origin main"))
            val deferred1 = async { service.requestApproval(req1) }
            delay(10)

            service.resolveApproval(
                req1.requestId,
                ApprovalDecision.Trusted("run_terminal_command", Regex("^command=git\\s+.*"))
            )
            deferred1.await()

            // Matching command
            val req2 = request(args = mapOf("command" to "git status"))
            val result2 = service.requestApproval(req2)
            assertIs<ApprovalDecision.Approved>(result2)
        }

        @Test
        fun `should NOT auto-approve when pattern does not match`() = runTest {
            val req1 = request(args = mapOf("command" to "git push"))
            val deferred1 = async { service.requestApproval(req1) }
            delay(10)

            service.resolveApproval(
                req1.requestId,
                ApprovalDecision.Trusted("run_terminal_command", Regex("^command=git\\s+.*"))
            )
            deferred1.await()

            // Non-matching tool
            val req2 = request(toolName = "http_request", args = mapOf("url" to "https://example.com"))
            val deferred2 = async { service.requestApproval(req2) }
            delay(10)

            // Should be pending (not auto-approved)
            assertEquals(1, service.pendingRequests.value.size)

            service.resolveApproval(req2.requestId, ApprovalDecision.Approved)
            deferred2.await()
        }
    }

    @Nested
    inner class RejectTests {
        @Test
        fun `should resolve with Rejected when user rejects`() = runTest {
            val req = request()
            val deferred = async { service.requestApproval(req) }
            delay(10)

            service.resolveApproval(req.requestId, ApprovalDecision.Rejected("too dangerous"))
            val result = deferred.await()

            assertIs<ApprovalDecision.Rejected>(result)
            assertEquals("too dangerous", (result as ApprovalDecision.Rejected).reason)
        }
    }

    @Nested
    inner class CancelTests {
        @Test
        fun `cancelAll should cancel all pending requests`() = runTest {
            val req = request()
            val deferred = async {
                try {
                    service.requestApproval(req)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    null
                }
            }
            delay(10)

            assertEquals(1, service.pendingRequests.value.size)
            service.cancelAll()

            val result = deferred.await()
            assertEquals(null, result)
            assertTrue(service.pendingRequests.value.isEmpty())
        }
    }

    @Nested
    inner class SessionTrustRulesTests {
        @Test
        fun `resetSessionTrustRules should clear trust`() = runTest {
            // Trust a tool
            val req1 = request()
            val deferred1 = async { service.requestApproval(req1) }
            delay(10)
            service.resolveApproval(req1.requestId, ApprovalDecision.Trusted("run_terminal_command"))
            deferred1.await()

            // Reset trust rules
            service.resetSessionTrustRules()

            // Should ask again
            val req2 = request()
            val deferred2 = async { service.requestApproval(req2) }
            delay(10)

            assertEquals(1, service.pendingRequests.value.size)

            service.resolveApproval(req2.requestId, ApprovalDecision.Approved)
            deferred2.await()
        }
    }

    @Nested
    inner class PendingRequestsFlowTests {
        @Test
        fun `pendingRequests should expose request details`() = runTest {
            val req = request(args = mapOf("command" to "npm test"))
            val deferred = async { service.requestApproval(req) }
            delay(10)

            val pending = service.pendingRequests.value
            assertEquals(1, pending.size)
            assertEquals(req.toolName, pending[0].toolName)
            assertEquals(req.requestId, pending[0].requestId)

            service.resolveApproval(req.requestId, ApprovalDecision.Approved)
            deferred.await()
        }
    }
}
