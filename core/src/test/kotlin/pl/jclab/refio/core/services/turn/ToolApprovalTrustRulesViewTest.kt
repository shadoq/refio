package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalRequest
import pl.jclab.refio.core.services.turn.ToolApprovalService.TrustRuleView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Trust" in the approval prompt silences every later prompt that matches, so the user needs to
 * see what they granted. The listing must show the granted rules, and must not outlive the session
 * that granted them, as the rules themselves do not.
 */
class ToolApprovalTrustRulesViewTest {

    private val service = ToolApprovalService()

    private suspend fun TestScope.grant(toolName: String, args: Map<String, Any>, decision: ApprovalDecision.Trusted) {
        val request = ApprovalRequest(
            requestId = "req_${System.nanoTime()}",
            taskId = "task_1",
            toolName = toolName,
            arguments = args,
            description = toolName,
        )
        val pending = async { service.requestApproval(request) }
        delay(10)
        service.resolveApproval(request.requestId, decision)
        pending.await()
    }

    @Test
    fun `trust granted in the prompt is listed with its tool and pattern`() = runTest {
        grant(
            "run_terminal_command",
            mapOf("command" to "git status"),
            ApprovalDecision.Trusted("run_terminal_command", Regex("^command=git ")),
        )
        grant("read_file", mapOf("path" to "src/A.kt"), ApprovalDecision.Trusted("read_file"))

        assertEquals(
            listOf(
                TrustRuleView("read_file", argsPattern = null),
                TrustRuleView("run_terminal_command", argsPattern = "^command=git "),
            ),
            service.activeTrustRules(),
        )
    }

    @Test
    fun `no rules are listed once the session that granted them has ended`() = runTest {
        grant("read_file", mapOf("path" to "src/A.kt"), ApprovalDecision.Trusted("read_file"))

        service.onSessionEnd()

        assertTrue(service.activeTrustRules().isEmpty())
    }

    @Test
    fun `a listing taken earlier does not change when more trust is granted`() = runTest {
        grant("read_file", mapOf("path" to "src/A.kt"), ApprovalDecision.Trusted("read_file"))
        val before = service.activeTrustRules()

        grant("list_files", mapOf("path" to "src"), ApprovalDecision.Trusted("list_files"))

        assertEquals(listOf(TrustRuleView("read_file", argsPattern = null)), before)
    }
}
