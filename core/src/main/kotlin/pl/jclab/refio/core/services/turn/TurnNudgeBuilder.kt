package pl.jclab.refio.core.services.turn

/**
 * Builds nudge messages for agent behavior correction.
 *
 * When the model violates expectations (missing intent, no WRITE tools, etc.),
 * a nudge message is sent to guide it back to correct behavior.
 */
object TurnNudgeBuilder {
    fun buildReadBeforeWriteMessage(paths: List<String>): String {
        val renderedPaths = paths.joinToString(", ")
        return "Warning: edit requested for file(s) not read recently: $renderedPaths. " +
            "Read the current file state first before using code_editing or multi_edit."
    }

    /**
     * Build nudge message when agent spends too many iterations reading without writing.
     * Triggered by read-only budget guard (ADR-0044).
     */
    fun buildReadingBudgetExceededMessage(): String {
        return "You have spent many iterations reading files without executing any WRITE tool. " +
            "If this is an implementation task, move from analysis to file creation/modification now. " +
            "Use the information you've already gathered and avoid further reading unless it is clearly necessary."
    }

    /**
     * Build nudge message for missing intent field.
     * Also pushes the model to take action if user is requesting changes.
     */
    fun buildMissingIntentNudgeMessage(): String {
        return "Your JSON response is missing required field 'intent'. " +
            "For AGENT mode return intent as one of: implementation | analysis | response. " +
            "Use format: {\"actions\":[...],\"response\":\"...\",\"intent\":\"implementation|analysis|response\"} " +
            "and include \"thinking\" only if you have useful short reasoning to add. " +
            "IMPORTANT: If the user reports a problem or asks for a fix, you MUST use tools to read and fix the file. " +
            "You cannot finish without action when the user explicitly asks for changes."
    }

    /**
     * Build nudge message when agent gives up after a tool error instead of retrying or working around it.
     */
    /**
     * Build nudge message when agent stops after a transient HTTP error (timeout, 5xx, 429).
     */
    fun buildTransientHttpErrorNudgeMessage(): String {
        return "The HTTP request failed with a transient error (timeout, 5xx, or rate limit). " +
            "This is likely temporary. Retry the request after a short delay (use run_terminal_command with 'sleep 2'). " +
            "Do NOT give up on the task due to a transient network error."
    }

    /**
     * Build nudge message when agent returns plain text without any JSON structure.
     * Kept short on purpose: long instructions push thinking-models (qwen3) into long internal
     * reasoning that yields empty content. Two sentences + one example is enough.
     */
    fun buildPlainTextNudgeMessage(): String {
        return "Reply with JSON only: " +
            "{\"actions\":[{\"tool\":\"NAME\",\"arguments\":{...}}],\"response\":\"...\",\"intent\":\"implementation\"}. " +
            "No prose, no markdown fences."
    }

    /**
     * Build nudge message when the model returned an empty body (content + thinking both blank).
     * Distinct from plain-text nudge: here the model produced literally nothing.
     */
    fun buildEmptyContentNudgeMessage(): String {
        return "Your previous reply was empty. Send the JSON envelope now: " +
            "{\"actions\":[...],\"response\":\"...\",\"intent\":\"implementation|analysis|response\"}."
    }

    /**
     * Build nudge message when the agent has called the same tool many times.
     * Surfaces TurnGuardrails.LoopStatus.WARN to the model — without this the
     * warning is only logged and the agent never realizes it is stuck.
     */
    fun buildLoopWarningNudgeMessage(toolName: String, totalCalls: Int, consecutiveCalls: Int): String {
        return "[HARNESS WARNING] You have called `$toolName` $totalCalls times in this task " +
            "(consecutive: $consecutiveCalls). This usually means you are stuck in a loop and " +
            "are re-discovering the same information. Before calling `$toolName` again: " +
            "1) Use memory(action=\"read\") to recall what you have already learned. " +
            "2) Use memory(action=\"get_subtask_output\", subtask_id=\"<ref#>\") to recover full data " +
            "from a previous tool result if its summary lost details you need. " +
            "3) If you cannot make progress, return a final response describing what you tried " +
            "and ask the user for clarification — DO NOT keep retrying the same approach."
    }

    /**
     * Build nudge message for invalid/malformed JSON format.
     * Kept terse — see [buildPlainTextNudgeMessage] for rationale.
     */
    fun buildInvalidFormatMessage(mode: String): String {
        return if (mode == "PLAN") {
            "Invalid format. Reply with JSON only: {\"actions\":[...]} or " +
                "{\"plan\":\"...\",\"subtasks\":[...],\"actions\":[]}. Escape inner quotes with \\\\\"."
        } else {
            "Invalid JSON. Reply with: " +
                "{\"actions\":[{\"tool\":\"NAME\",\"arguments\":{...}}],\"response\":\"...\",\"intent\":\"implementation\"}. " +
                "Escape inner quotes inside strings with \\\\\"."
        }
    }
}
