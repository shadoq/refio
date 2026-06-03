package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AutoApprover] — headless `--auto-approve <regex>` decision (docs/0063 §6.2). Matches → approve,
 * no match → reject (fail-closed so a headless run never hangs on the approval timeout).
 */
class AutoApproverTest {

    @Test
    fun `approves when the command matches the regex`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "git status"),
            autoApprove = Regex("^git (status|log|branch|diff)"),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Approved)
    }

    @Test
    fun `rejects when the command does not match`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "rm -rf /"),
            autoApprove = Regex("^git "),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Rejected)
    }

    @Test
    fun `candidate text prefers the command argument`() {
        assertEquals(
            "git push",
            AutoApprover.candidateText("run_terminal_command", mapOf("command" to "git push", "cwd" to "/x")),
        )
    }
}
