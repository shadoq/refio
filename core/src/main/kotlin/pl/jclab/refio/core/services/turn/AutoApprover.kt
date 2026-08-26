package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.tools.security.ShellCommandAnalyzer

/**
 * Pure decision for the headless `--auto-approve <regex>` flow.
 *
 * A regex match approves the tool call; anything else is denied immediately (fail-closed) so a
 * headless run never blocks on the 5-minute approval timeout. The wiring that subscribes to
 * [ToolApprovalService.pendingRequests] and applies these decisions lives in the CLI
 * (`AutoApproveListener`); only TUI/interactive runs ever ask a human.
 *
 * A denial is [ToolApprovalService.ApprovalDecision.NotPermitted], never `Rejected`: the gate is
 * refusing one call, not relaying a human's decision to stop the turn.
 *
 * The match is a substring match by design - the documented usage (`--auto-approve "^git "`) and
 * the e2e harness both rely on it. That makes the regex a statement about *part* of the command
 * line while the shell runs all of it, so checking the line as a whole would approve a second,
 * unvetted command riding along: `^git status` must not approve `git status && python3 exfil.py`.
 *
 * The rule is therefore "every command the shell would run has to match", not "no chaining".
 * Rejecting chained lines outright looked safe but broke ordinary work: a model verifying its own
 * output with `python3 app.py add x && python3 app.py list` had both halves covered by the regex
 * and was refused anyway. Each unit reported by [ShellCommandAnalyzer.commandUnits] (the raw line,
 * every chained segment, command substitutions, and the payload of wrappers such as `sh -c`) is
 * matched separately, so the approved set is what actually executes.
 *
 * Redirection is not a unit boundary: `python3 app.py > out.txt` runs exactly the program the regex
 * vetted. That is a deliberately narrower test than the one `CommandRuleMatcher` applies before an
 * automatic ALLOW, because the approval prompt shows the full command line to a human anyway.
 */
object AutoApprover {

    /** The text matched against the regex: the command argument if present, else a best-effort fallback. */
    fun candidateText(toolName: String, arguments: Map<String, Any>): String =
        commandArgument(arguments)
            ?: arguments.values.joinToString(" ") { it.toString() }.ifBlank { toolName }

    fun decide(
        toolName: String,
        arguments: Map<String, Any>,
        autoApprove: Regex,
    ): ToolApprovalService.ApprovalDecision {
        // Only a real command argument is split into units. The fallback text is a dump of whatever
        // a non-command tool was handed (file content, URLs, JSON), where `;` and `|` are ordinary
        // data and splitting on them would invent commands nobody is going to run.
        val command = commandArgument(arguments)
        if (command != null) {
            val unapproved = ShellCommandAnalyzer.commandUnits(command)
                .filterNot { isNotAProgram(it) }
                .firstOrNull { !autoApprove.containsMatchIn(it) }
            return if (unapproved == null) {
                ToolApprovalService.ApprovalDecision.Approved
            } else {
                ToolApprovalService.ApprovalDecision.NotPermitted(
                    "auto-approve: '$unapproved' did not match /${autoApprove.pattern}/"
                )
            }
        }

        val text = candidateText(toolName, arguments)
        return if (autoApprove.containsMatchIn(text)) {
            ToolApprovalService.ApprovalDecision.Approved
        } else {
            ToolApprovalService.ApprovalDecision.NotPermitted(
                "auto-approve: '$text' did not match /${autoApprove.pattern}/"
            )
        }
    }

    private fun commandArgument(arguments: Map<String, Any>): String? =
        (arguments["command"] ?: arguments["cmd"])?.toString()

    /**
     * Filters the units that are not a program invocation at all, so their text is never held
     * against the approval regex.
     *
     * Two shapes show up. A redirection keeps its operator, because the block rules anchor on `>`,
     * which leaves units like `> out.txt`. And a file-descriptor redirect is split twice - `2>&1`
     * breaks at `>` and again at `&` - leaving a bare `1`. Rejecting either would refuse every
     * ordinary build-and-capture command; `2>&1` alone cost a scenario its turn.
     *
     * The letter test is what separates them from real commands: a program name has letters in it,
     * a descriptor or an operator does not.
     */
    private fun isNotAProgram(unit: String): Boolean {
        val trimmed = unit.trimStart()
        if (trimmed.startsWith(">") || trimmed.startsWith("<")) {
            return true
        }
        return trimmed.none { it.isLetter() }
    }
}
