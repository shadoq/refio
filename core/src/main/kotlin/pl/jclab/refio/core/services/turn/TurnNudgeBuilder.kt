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
            "Before retrying, call think({\"thought\": \"...\"}) once to plan: was the URL correct? " +
            "Are headers complete? Should the next attempt change anything? " +
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
            "are re-discovering the same information. STOP and call think({\"thought\": \"...\"}) " +
            "as your VERY NEXT action — explicitly answer: (a) what am I trying to find, " +
            "(b) what have the previous $toolName calls already returned, (c) why isn't that enough, " +
            "(d) what *different* approach (other tool, different args) could break the loop. " +
            "Then, based on that thought: " +
            "1) Use memory(action=\"read\") to recall what you have already learned. " +
            "2) Use memory(action=\"get_subtask_output\", subtask_id=\"<ref#>\") to recover full data " +
            "from a previous tool result if its summary lost details you need. " +
            "3) If you cannot make progress, return a final response describing what you tried " +
            "and ask the user for clarification — DO NOT keep retrying the same approach."
    }

    /**
     * Build a standalone nudge that suggests the agent use the `think` tool to break out of
     * indecision/repetition. Cheaper than [buildLoopWarningNudgeMessage] — used when the agent
     * has not yet hit a hard loop but is producing churn (e.g. repeated empty actions, repeated
     * tool errors of different kinds, or oscillation between intents).
     */
    fun buildThinkSuggestionNudgeMessage(reason: String): String {
        return "[HARNESS HINT] Progress check: $reason " +
            "Call think({\"thought\": \"...\"}) before your next action and write down: " +
            "what you know, what you don't know, and what the next *concrete* step is. " +
            "The think tool has no side effects — it only forces a structured pause."
    }

    /**
     * Build nudge message for the EffectKeyedLoopTracker — fired when the agent has
     * been hammering the same target object (file path / command / URL) without
     * qualitative change in approach. Distinct from [buildLoopWarningNudgeMessage]
     * which fires on tool *name* repetition; this fires on tool *effect* repetition,
     * which is a strictly stronger signal of being stuck.
     *
     * @param toolName the tool name being abused (e.g. `code_editing`)
     * @param target a human-readable description of the target object (e.g.
     *   `S03E05/savethem_agent.py` or `python S03E05/run.py`)
     * @param totalCount how many times the (tool, target) pair has been invoked
     */
    fun buildEffectKeyedLoopMessage(toolName: String, target: String, totalCount: Int): String {
        return "[HARNESS WARNING — STUCK ON SAME OBJECT] You have invoked `$toolName` " +
            "$totalCount times against the same target ($target). Different surface tools " +
            "operating on the same file/command count as the SAME effect — alternating " +
            "edit→run→edit→run on the same object IS still a loop. " +
            "Hard rule for your next action: do NOT call `$toolName` against `$target` " +
            "again until you have done at least ONE of the following: " +
            "(a) Verified the assumptions embedded in `$target` against an authoritative " +
            "external source (the API itself, the docs, a discovery endpoint, an unread " +
            "config file). Hardcoded constants/rules in scripts are routinely the actual " +
            "bug — patching them in different shapes will keep producing wrong answers. " +
            "(b) Recovered the FULL output of the most recent run via " +
            "memory(action=\"get_subtask_output\", subtask_id=\"<id>\", offset=0, limit=64000) " +
            "and read the middle that was hidden by head+tail summarization. " +
            "(c) Asked the user for clarification — explain what you tried, what changed " +
            "between attempts, and what you are now stuck on. " +
            "Continuing to patch `$target` without doing one of (a)-(c) is forbidden — " +
            "the harness will count further attempts toward an abort threshold."
    }

    /**
     * Build a STRATEGY_CHANGE_REQUIRED nudge for the OutputHashTracker — fired when
     * `run_terminal_command` or `run_code` produces byte-identical (modulo whitespace)
     * tail output for the same command N times in a row. This is the signal that the
     * agent's *understanding* of the problem is stable but wrong: the script keeps
     * doing exactly the same thing because the agent's edits aren't actually changing
     * the relevant behaviour.
     */
    fun buildStrategyChangeRequiredMessage(toolName: String, target: String, identicalRuns: Int): String {
        return "[HARNESS WARNING — STRATEGY CHANGE REQUIRED] $toolName for `$target` has " +
            "now produced **byte-identical output $identicalRuns times in a row**. The edits " +
            "you have been making between runs are not changing the runtime behaviour in any " +
            "observable way. This means one of: " +
            "(1) you are editing the wrong file or wrong region; " +
            "(2) the bug is in code/data that runs BEFORE the visible failure (assumptions, " +
            "hardcoded constants, input data) — i.e. the visible error is a symptom, not the " +
            "cause; " +
            "(3) the script's behaviour depends on external state (API responses, environment, " +
            "data files) that you have not actually inspected. " +
            "Mandatory next action: STOP re-running `$target`. Choose ONE: " +
            "(a) read the FULL middle of the previous run via " +
            "memory(action=\"get_subtask_output\", subtask_id=\"<id>\", offset=0, limit=64000) " +
            "to see what the script is actually doing internally; " +
            "(b) probe the external system the script depends on directly (call the API, " +
            "fetch the docs, list available endpoints/tools); " +
            "(c) read a DIFFERENT file in the project that might explain the assumptions " +
            "embedded in `$target`; " +
            "(d) report the situation to the user. " +
            "Re-running `$target` with another small edit is NOT an option — it will produce " +
            "the same identical output again."
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
