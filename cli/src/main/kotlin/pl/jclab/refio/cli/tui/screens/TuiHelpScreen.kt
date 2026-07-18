package pl.jclab.refio.cli.tui.screens

import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Help screen — TUI keybindings reference and usage guide.
 * Press F1 to open, Esc or F1 again to close.
 * Scrollable with ↑/↓ and PgUp/PgDn.
 */
object TuiHelpScreen {

    fun renderToLines(state: TuiState, width: Int, contentHeight: Int): List<String> {
        val w = (width - 4).coerceAtLeast(40)
        val allLines = buildHelpContent(w)

        // Apply scroll offset
        val maxOffset = (allLines.size - contentHeight + 1).coerceAtLeast(0)
        val offset = state.helpScrollOffset.coerceIn(0, maxOffset)

        val buf = TuiRenderBuffer(width, contentHeight)
        val visible = allLines.drop(offset).take(contentHeight - 1) // -1 for scroll indicator
        for (line in visible) {
            buf.addLine(line)
        }

        // Scroll position indicator at bottom
        if (allLines.size > contentHeight) {
            val pct = if (maxOffset > 0) (offset * 100 / maxOffset) else 0
            val scrollHint = "  ↑/↓ PgUp/PgDn to scroll  ─  ${offset + 1}–${(offset + contentHeight - 1).coerceAtMost(allLines.size)}/${allLines.size} ($pct%)"
            buf.addLine(TuiColors.muted(scrollHint))
        }

        return buf.getLines()
    }

    private fun buildHelpContent(w: Int): List<String> {
        val lines = mutableListOf<String>()

        fun add(s: String = "") { lines.add(s) }
        fun section(title: String) {
            lines.add(TuiColors.highlight("  $title"))
            lines.add(TuiColors.border("  ${"─".repeat(40)}"))
        }
        fun key(key: String, desc: String) {
            lines.add("  ${TuiColors.accent(key.padEnd(20))} $desc")
        }

        add(TuiColors.highlight("Refio TUI — Help & Keybindings"))
        add(TuiColors.border("─".repeat(w)))
        add()

        // Navigation
        section("Navigation")
        key("F1", "Help (this screen)")
        key("F2–F8", "Toggle side panel (Steps, Context, RAG, Logs, Debug, API, Files)")
        key("F9", "Settings (←/→ switch tabs, ↑/↓ navigate, Enter toggle/edit)")
        key("Alt+H", "Session history")
        key("Escape", "Close panel / back to chat")
        add()

        // Chat & Input
        section("Chat & Input")
        key("Enter", "Send message")
        key("Shift+Tab", "Cycle mode: CHAT → PLAN → AGENT")
        key("Tab", "Toggle focus: panel actions ↔ text input")
        key("Ctrl+O", "Select model (popup)")
        key("Ctrl+W", "New session")
        key("Ctrl+L", "Continue conversation (add context and resume)")
        key("Ctrl+D", "Summarize conversation")
        key("Ctrl+C", "Cancel current operation / streaming")
        key("Ctrl+Q", "Quit Refio")
        add()

        // Toggles
        section("Toggles")
        key("Ctrl+T", "Cycle reasoning effort (OFF / LOW / MEDIUM / HIGH)")
        key("Ctrl+E", "Toggle execution mode (AUTO ↔ INTERACTIVE)")
        key("Ctrl+N", "Toggle no-egress mode (local models only)")
        add()

        // Message Navigation
        section("Message Navigation")
        key("↑/↓", "Scroll chat / navigate in active panel")
        key("PgUp/PgDn", "Scroll chat by 10 lines")
        key("Ctrl+P", "Select previous message")
        key("Ctrl+B", "Select next message")
        key("Ctrl+Y", "Copy selected (or last) message to clipboard")
        key("Ctrl+F", "Filter chat by agent (multi-agent sessions)")
        add()

        // Context References
        section("Context References (type in prompt)")
        key("@file:<path>", "Attach file content")
        key("@folder:<path>", "Attach directory tree")
        key("@git_diff", "Attach uncommitted changes")
        key("@codebase:<query>", "Semantic search via RAG")
        key("@docs:<query>", "Search indexed documentation")
        key("!<agent>", "Invoke subagent (Tab to autocomplete)")
        key("/<command>", "Slash command / prompt template (Tab to autocomplete)")
        add()

        // Panel focus info
        section("Panel Focus")
        add("  ${TuiColors.muted("When a side panel is active, press Tab to toggle focus.")}")
        add("  ${TuiColors.muted("Focused panel: letter keys trigger panel actions (a/s/d/r/...).")}")
        add("  ${TuiColors.muted("Unfocused: letter keys go to text input. Arrow keys always navigate.")}")
        add()

        // Steps panel (F2)
        section("Steps Panel (F2)")
        key("↑/↓", "Navigate steps")
        key("a / s / d", "Approve / Skip / Delete step")
        key("u / j", "Move step up / down")
        key("p", "Pause/resume execution")
        key("r", "Replan steps")
        key("R", "Execute selected step")
        key("C", "Cancel all pending steps")
        add()

        // Files panel (F8)
        section("File Browser (F8)")
        key("↑/↓", "Navigate files")
        key("Enter", "Open directory / preview file")
        key("Backspace", "Go to parent directory")
        key("h", "Toggle hidden files")
        key("a", "Add selected file/folder as @context")
        key("o", "Open in external editor")
        key("i", "Show file info")
        key("r", "Refresh listing")
        add()

        // Context panel (F3)
        section("Context Panel (F3)")
        key("↑/↓", "Navigate context sections")
        key("Enter / i", "Show full section content")
        add()

        // Logs panel (F5)
        section("Logs Panel (F5)")
        key("↑/↓", "Navigate log entries")
        key("Enter", "Show full log message")
        key("p", "Pause/resume log updates")
        key("f", "Cycle level filter (All/DEBUG/INFO/WARN/ERROR)")
        add()

        // RAG panel (F4)
        section("RAG Panel (F4)")
        key("r / e / s / c", "Reindex / Embeddings / Stop / Clear")
        key("v", "View chunks for selected file")
        add()

        // API Logs panel (F7)
        section("API Logs (F7)")
        key("↑/↓", "Navigate logs / scroll detail view")
        key("Enter", "Toggle detail view (formatted JSON)")
        key("f", "Cycle provider filter")
        key("PgUp/PgDn", "Fast scroll in detail view")
        add()

        // Modes
        section("Execution Modes")
        add("  ${TuiColors.accent("CHAT")}    Conversation only, no tools. Direct LLM interaction.")
        add("  ${TuiColors.accent("PLAN")}    Read-only tools (6). Creates execution plans, max 15 iterations.")
        add("  ${TuiColors.accent("AGENT")}   Full tools (12). Autonomous execution, max 25 iterations.")
        add()

        // Slash commands
        section("Common Slash Commands (prompt templates)")
        add("  ${TuiColors.muted("/explain  /refactor  /test  /fix  /implement  /optimize")}")
        add("  ${TuiColors.muted("/review   /translate /docs  /security    /debug")}")
        add("  ${TuiColors.muted("Type / in prompt for full list with autocomplete.")}")
        add()

        // System commands
        section("System Commands")
        add("  ${TuiColors.muted("/help  /history  /export <path>  /resend  /rewind  /edit  /quit")}")
        add()

        // Footer
        add(TuiColors.border("─".repeat(w)))
        add(TuiColors.muted("  Press Esc or F1 to close this help."))

        return lines
    }
}
