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
     * This happens when weaker models "forget" the required output format mid-task.
     */
    fun buildPlainTextNudgeMessage(): String {
        return "Your previous response was plain text without any JSON structure. " +
            "You MUST respond with valid JSON in the required format: " +
            "{\"actions\":[{\"tool\":\"...\",\"arguments\":{...}}],\"response\":\"your status\",\"intent\":\"implementation|analysis|response\"}. " +
            "If you need to use tools, include them in 'actions'. If the task is truly complete, " +
            "use {\"actions\":[],\"response\":\"final answer\",\"intent\":\"response\"}. " +
            "NEVER respond with plain text — always use the JSON envelope."
    }

    fun buildToolErrorGiveUpNudgeMessage(): String {
        return "A tool returned an error but you stopped instead of continuing. Do NOT give up on the task. " +
            "Analyze the error and decide how to proceed: retry after a delay (use run_terminal_command with 'sleep N'), " +
            "try a different approach, or adjust your parameters. Continue working until the task is complete."
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
            "Your previous response contained malformed JSON that could not be parsed. " +
                "Common causes: unescaped quotes inside string values, truncated content_type (use \"application/json\" not \"application/\"). " +
                "Respond ONLY with valid JSON: " +
                "{\"actions\":[{\"tool\":\"...\",\"arguments\":{...}}],\"response\":\"your status or final answer\",\"intent\":\"implementation|analysis|response\"}. " +
                "'response' and 'intent' are REQUIRED and must be non-empty. " +
                "IMPORTANT: When the 'body' parameter contains JSON, ensure all inner quotes are properly escaped with backslash. " +
                "Use exact tool names and parameters from <available_tools>."
        }
    }
}
