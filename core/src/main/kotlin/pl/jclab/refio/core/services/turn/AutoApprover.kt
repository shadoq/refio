package pl.jclab.refio.core.services.turn

/**
 * Pure decision for the headless `--auto-approve <regex>` flow.
 *
 * A regex match approves the tool call; anything else is rejected immediately (fail-closed) so a
 * headless run never blocks on the 5-minute approval timeout. The wiring that subscribes to
 * [ToolApprovalService.pendingRequests] and applies these decisions lives in the CLI
 * (`AutoApproveListener`); only TUI/interactive runs ever ask a human.
 */
object AutoApprover {

    /** The text matched against the regex: the command argument if present, else a best-effort fallback. */
    fun candidateText(toolName: String, arguments: Map<String, Any>): String =
        (arguments["command"] ?: arguments["cmd"])?.toString()
            ?: arguments.values.joinToString(" ") { it.toString() }.ifBlank { toolName }

    fun decide(
        toolName: String,
        arguments: Map<String, Any>,
        autoApprove: Regex,
    ): ToolApprovalService.ApprovalDecision {
        val text = candidateText(toolName, arguments)
        return if (autoApprove.containsMatchIn(text)) {
            ToolApprovalService.ApprovalDecision.Approved
        } else {
            ToolApprovalService.ApprovalDecision.Rejected("auto-approve: '$text' did not match /${autoApprove.pattern}/")
        }
    }
}
