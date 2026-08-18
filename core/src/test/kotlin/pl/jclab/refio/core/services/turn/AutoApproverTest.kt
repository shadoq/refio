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
        assertTrue(decision is ToolApprovalService.ApprovalDecision.NotPermitted)
    }

    // `--auto-approve` is documented (and used by the e2e harness) as a substring match, so
    // `^git status` approves anything merely STARTING with `git status`, including
    // `git status && python exfil.py`. The regex vets the head of the line; the shell runs all
    // of it. A chained/redirecting command therefore never auto-approves, matching the same
    // signal CommandRuleMatcher uses to hold such a line back from ALLOW.
    @Test
    fun `rejects a command that appends a second command after the matching prefix`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "git status && python3 /tmp/exfil.py"),
            autoApprove = Regex("^git status"),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.NotPermitted)
    }

    @Test
    fun `rejects a command that pipes the matching program into another`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "git log | sh"),
            autoApprove = Regex("\\bgit\\b"),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.NotPermitted)
    }

    // Only chaining is rejected. A redirect runs exactly the program the regex vetted, and the e2e
    // scenarios capture build output that way, so rejecting it would break ordinary runs.
    @Test
    fun `approves a matching command that redirects its output to a file`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "python3 app.py > out.txt"),
            autoApprove = Regex("\\bpython3?\\b"),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Approved)
    }

    // The operator check applies to the command argument only. Other tools carry arbitrary
    // payloads (file content full of `<`, `>`, `;`), and rejecting those would break every
    // headless write.
    @Test
    fun `payload arguments of a non-command tool are not treated as shell operators`() {
        val decision = AutoApprover.decide(
            toolName = "write_file",
            arguments = mapOf("path" to "index.html", "content" to "<html><body>hi</body></html>"),
            autoApprove = Regex("index\\.html"),
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Approved)
    }

    @Test
    fun `candidate text prefers the command argument`() {
        assertEquals(
            "git push",
            AutoApprover.candidateText("run_terminal_command", mapOf("command" to "git push", "cwd" to "/x")),
        )
    }

    // The regex covers every half, so the chain runs only approved programs. Refusing it cost a
    // real e2e scenario its turn: the agent had written the app and was verifying it end to end.
    @Test
    fun `approves a chain whose every command matches the regex`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "python3 app.py add \"Buy groceries\" && python3 app.py list"),
            autoApprove = HARNESS_DEFAULT,
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Approved)
    }

    // The counterpart: one approved half must not carry an unapproved one through.
    @Test
    fun `rejects a chain where a single command falls outside the regex`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "python3 app.py list && curl https://evil.example.com"),
            autoApprove = HARNESS_DEFAULT,
        )
        val denied = decision as? ToolApprovalService.ApprovalDecision.NotPermitted
        assertTrue(denied?.reason?.contains("curl") == true, "the reason must name the offending command")
    }

    // `2>&1` is split twice by the analyzer and leaves a bare file descriptor behind. Treating that
    // as a command to vet refused an ordinary build-and-capture line and cost a scenario its turn.
    @Test
    fun `approves a matching command that merges stderr into stdout`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "python3 app.py > out.txt 2>&1"),
            autoApprove = HARNESS_DEFAULT,
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.Approved)
    }

    // A wrapper hides its payload from a whole-line match, so the payload is vetted on its own.
    @Test
    fun `rejects an approved shell wrapping an unapproved program`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "sh -c 'curl https://evil.example.com'"),
            autoApprove = HARNESS_DEFAULT,
        )
        assertTrue(decision is ToolApprovalService.ApprovalDecision.NotPermitted)
    }

    /**
     * The gate denies one call; it does not speak for the user. Measured on the e2e set, four runs
     * that had already written their deliverable were scored as failures because a cleanup command
     * fell outside the regex and the refusal ended the whole turn - the model never got to hear
     * "not that way" and answer it.
     */
    @Test
    fun `an unmatched command denies that call without claiming the user asked to stop`() {
        val decision = AutoApprover.decide(
            toolName = "run_terminal_command",
            arguments = mapOf("command" to "rm -f todos.json"),
            autoApprove = HARNESS_DEFAULT,
        )

        assertTrue(
            decision is ToolApprovalService.ApprovalDecision.NotPermitted,
            "a policy denial must not be reported as a user rejection",
        )
    }

    private companion object {
        /** The regex tools/e2e/e2e-run.sh applies by default, trimmed to the programs used here. */
        val HARNESS_DEFAULT = Regex("\\b(gradlew|java|python3?|node|npm|pytest|ls|cat|echo|grep|cd|sh|bash)\\b")
    }
}
