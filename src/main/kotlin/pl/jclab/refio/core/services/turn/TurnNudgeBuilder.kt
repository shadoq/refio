package pl.jclab.refio.core.services.turn

/**
 * Builds nudge messages for agent behavior correction.
 *
 * When the model violates expectations (missing intent, no WRITE tools, etc.),
 * a nudge message is sent to guide it back to correct behavior.
 */
object TurnNudgeBuilder {
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
            "For AGENT mode return intent as one of: implementation | analysis. " +
            "Use format: {\"actions\":[...],\"response\":\"...\",\"intent\":\"implementation|analysis\"} " +
            "and include \"thinking\" only if you have useful short reasoning to add. " +
            "IMPORTANT: If the user reports a problem or asks for a fix, you MUST use tools to read and fix the file. " +
            "You cannot finish without action when the user explicitly asks for changes."
    }

    /**
     * Build nudge message for invalid tool call format.
     */
    fun buildInvalidFormatMessage(mode: String): String {
        return if (mode == "PLAN") {
            "Invalid plan format. Respond ONLY with JSON using either: " +
                "{\"actions\":[...]} for READ_ONLY tools, or {\"plan\":\"...\",\"subtasks\":[...],\"actions\":[]} " +
                "for the final plan. Use exact tool names/params from <available_tools>."
        } else {
            "Invalid tool call format. Respond ONLY with JSON: " +
                "{\"actions\":[...],\"response\":\"your status or final answer\",\"intent\":\"implementation|analysis\"}. " +
                "'response' and 'intent' are REQUIRED and must be non-empty. " +
                "Include 'thinking' only when it adds short useful reasoning. " +
                "Use exact tool names and parameters from <available_tools>."
        }
    }
}
