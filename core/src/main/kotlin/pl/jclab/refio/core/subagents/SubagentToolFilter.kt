package pl.jclab.refio.core.subagents

/**
 * Which tools a subagent may use under the `tools:` / `disallowedTools:` lists of its definition.
 * Claude Code tool names in those lists are mapped to Refio names when the definition is parsed
 * ([SubagentParser]), so everything here compares Refio names.
 */
object SubagentToolFilter {
    /**
     * Narzędzia READ_ONLY (bezpieczne dla wszystkich subagentów).
     */
    val READ_ONLY_TOOLS = setOf(
        "read_file",
        "read_directory",
        "file_search",
        "grep_search",
        "view_diff"
    )

    /**
     * SAFE internal tools automatically available to every subagent, even under a `tools:`
     * whitelist that does not list them (unless explicitly in disallowedTools).
     *
     * These have NO side effects on user files and NO network egress — they only manage the
     * agent's own reasoning/state (`think` records a reasoning step; `tasks`/`memory` manage
     * plan and working memory). Keeping them always-available closes a recurring class of bug:
     * a persona body that instructs the model to `think(...)` while the whitelist forbids it,
     * so the model loops on a tool the harness then rejects (observed 2026-07 with
     * `security-engineer`; the code comment in TurnPromptBuilder already noted the same for
     * `api-documenter`). Egress tools (web_search, http_request, fetch_webpage) are deliberately
     * NOT here — they are only granted when a definition whitelists them.
     *
     * Single source of truth: the three `isToolAllowedByProfile` copies in the turn package
     * (TurnPromptBuilder / ToolCallParser / TurnToolExecutor) reference this set so the system
     * prompt, native tool schemas, JSON-parse validation, and execution all agree.
     */
    val SYSTEM_TOOLS = setOf(
        "tasks", "memory", "think"
    )

    /**
     * Whether [toolName] is available under a profile with the given allow/deny lists. The single
     * source of truth for the three `isToolAllowedByProfile` copies in the turn package
     * (TurnPromptBuilder / ToolCallParser / TurnToolExecutor), which gate the system-prompt tool
     * matrix + native schemas, the JSON-parse validation, and execution respectively. Keeping
     * them delegating here means the three sites agree.
     *
     * Whitelist: the tool is listed OR it is a safe [SYSTEM_TOOLS] (think/tasks/memory) — a
     * whitelist that omits `think` must not make the model loop on a persona-instructed tool the
     * harness rejects. Blacklist: allowed unless explicitly denied. Neither: allowed.
     */
    fun isToolAllowedUnderProfile(
        toolName: String,
        allowedTools: List<String>?,
        disallowedTools: List<String>?,
    ): Boolean {
        val normalized = toolName.lowercase()
        val allowed = allowedTools?.map { it.lowercase() }?.toSet()
        val disallowed = disallowedTools?.map { it.lowercase() }?.toSet()
        return when {
            allowed != null -> normalized in allowed || normalized in SYSTEM_TOOLS
            disallowed != null -> normalized !in disallowed
            else -> true
        }
    }
}
