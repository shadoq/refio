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
    inner class ExposureTimeoutTests {
        // Both UIs render only pendingRequests.first(), so a second concurrent request (parallel
        // invoke_subagent) is invisible while the first is on screen. Starting its timeout when it
        // was queued auto-rejected a request the user was never shown: think about the first one
        // for longer than the timeout and the second dies unseen. The clock must measure the time
        // the user could actually see the request.
        @Test
        fun `a queued request does not burn its timeout while another one is on screen`() = runTest {
            val service = ToolApprovalService(approvalTimeoutMs = 1_000)
            val first = request(args = mapOf("command" to "git status"))
            val second = request(args = mapOf("command" to "npm test"))

            val firstDecision = async { service.requestApproval(first) }
            val secondDecision = async { service.requestApproval(second) }
            delay(10)
            assertEquals(2, service.pendingRequests.value.size)

            // The user deliberates on the visible request for almost the whole timeout.
            delay(900)
            service.resolveApproval(first.requestId, ApprovalDecision.Approved)
            assertIs<ApprovalDecision.Approved>(firstDecision.await())

            // The second request only becomes visible now, so its own timeout starts now.
            delay(900)
            assertEquals(1, service.pendingRequests.value.size)

            service.resolveApproval(second.requestId, ApprovalDecision.Approved)
            assertIs<ApprovalDecision.Approved>(secondDecision.await())
        }

        @Test
        fun `the visible request still times out`() = runTest {
            val service = ToolApprovalService(approvalTimeoutMs = 1_000)
            val req = request()

            val decision = async { service.requestApproval(req) }
            delay(1_500)

            assertIs<ApprovalDecision.Rejected>(decision.await())
            assertTrue(service.pendingRequests.value.isEmpty())
        }

        // Requests are shown one at a time, so the order they are shown in must be the order they
        // arrived in - iterating a hash map handed the user an arbitrary one and starved the rest.
        @Test
        fun `requests are exposed in arrival order`() = runTest {
            val service = ToolApprovalService(approvalTimeoutMs = 0)
            val requests = (1..10).map { request(args = mapOf("command" to "step-$it")) }

            val decisions = requests.map { req -> async { service.requestApproval(req) } }
            delay(10)

            assertEquals(
                requests.map { it.requestId },
                service.pendingRequests.value.map { it.requestId }
            )

            service.cancelAll()
            decisions.forEach { d ->
                try {
                    d.await()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // cancelAll unblocks every waiter, queued ones included
                }
            }
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
