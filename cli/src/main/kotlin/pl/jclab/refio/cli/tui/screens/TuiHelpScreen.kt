package pl.jclab.refio.cli.tui.screens

import pl.jclab.refio.cli.tui.input.SlashActions
import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/**
 * Help: every key and command as frames in three columns on a wide screen, two at medium width and one on a
 * narrow screen, where it scrolls. `/` filters the rows.
 */
object TuiHelpScreen {

    data class Row(val key: String, val text: String)
    data class Section(val title: String, val rows: List<Row>)

    private const val KEY_WIDTH_MAX = 20

    val SECTIONS: List<Section> = listOf(
        Section("Navigation", listOf(
            Row("F1", "Help (this screen)"),
            Row("F2-F8", "Panels: steps, context, RAG, logs, debug, API, files"),
            Row("F2-F8 again", "Close the panel and the right column"),
            Row("F9", "Settings"),
            Row("Alt+H", "Session history"),
            Row("Esc", "Close the panel, back to the chat"),
            Row("Tab", "Focus: panel or prompt"),
        )),
        Section("Chat & input", listOf(
            Row("Enter", "Send"),
            Row("Shift+Tab", "Mode: CHAT, PLAN, AGENT"),
            Row("Ctrl+O", "Choose the model"),
            Row("Ctrl+W", "New session"),
            Row("Ctrl+L", "Continue; a stopped long task gets its next round"),
            Row("Ctrl+D", "Summarize the conversation"),
            Row("Ctrl+C", "Cancel the running turn"),
            Row("Ctrl+Q", "Quit"),
        )),
        Section("Toggles", listOf(
            Row("Ctrl+T", "Reasoning effort: off, low, medium, high"),
            Row("Ctrl+E", "Execution: auto or interactive"),
            Row("Ctrl+N", "No-egress: block network calls"),
        )),
        Section("Messages", listOf(
            Row("↑/↓ PgUp/PgDn", "Scroll the chat"),
            Row("Ctrl+P / Ctrl+B", "Select the previous / next message or tool"),
            Row("Space / Enter", "Expand the selected tool's output"),
            Row("o", "Open the selected tool's whole output"),
            Row("v", "Show the whole selected message"),
            Row("Ctrl+Y", "Copy the selection (or the last message)"),
            Row("Ctrl+F", "Filter by agent (multi-agent runs)"),
        )),
        Section("Approval", listOf(
            Row("y", "Yes, this once"),
            Row("a / t", "Yes, and allow the tool in this folder for the session"),
            Row("d", "Open the full diff first"),
            Row("n", "No; say what to change in the prompt"),
            Row("↑/↓ Enter", "Pick a choice and run it"),
        )),
        Section("Answer cut off", listOf(
            Row("c", "Continue"),
            Row("s", "Split the task into steps (PLAN)"),
            Row("l", "Raise the output limit and continue"),
            Row("m", "Switch the model"),
        )),
        Section("Panel focus", listOf(
            Row("", "Tab gives a panel the keys: letters run its actions."),
            Row("", "Without focus letters go to the prompt; arrows always move."),
        )),
        Section("Steps panel (F2)", listOf(
            Row("↑/↓", "Select a step"),
            Row("a / s / d", "Approve / skip / delete"),
            Row("u / j", "Move up / down"),
            Row("p", "Pause or resume"),
            Row("r", "Replan"),
            Row("C", "Cancel all pending steps"),
        )),
        Section("Context panel (F3)", listOf(
            Row("↑/↓", "Select a section"),
            Row("Enter / i", "Show the section's content"),
        )),
        Section("RAG panel (F4)", listOf(
            Row("↑/↓ Enter", "Select and open a file"),
            Row("v", "Chunks of the selected file"),
            Row("r / e / s / c", "Reindex / embeddings / stop / clear"),
        )),
        Section("Logs and API (F5, F7)", listOf(
            Row("Enter", "Whole entry / formatted detail"),
            Row("p", "Pause log updates"),
            Row("f", "Level filter (logs), provider filter (API)"),
        )),
        Section("Files panel (F8)", listOf(
            Row("Enter / Backspace", "Open / parent folder"),
            Row("a", "Add as @context"),
            Row("h / i / o / r", "Hidden files / info / open outside / refresh"),
        )),
        Section("Settings (F9)", listOf(
            Row("←/→ 1-0 -", "Section"),
            Row("↑/↓ Enter", "Field; toggle, cycle or edit"),
            Row("Tab", "Next group"),
            Row("a / k / x", "Tools: whole group ON / ASK / OFF"),
            Row("r r / e / p / l", "Reset all / export / export to project / reload"),
        )),
        Section("In the prompt", listOf(
            Row("@file:<path>", "Attach a file"),
            Row("@folder:<path>", "Attach a directory tree"),
            Row("@git_diff", "Attach uncommitted changes"),
            Row("@codebase:<query>", "Search the index (RAG)"),
            Row("@docs:<query>", "Search indexed documentation"),
            Row("!<agent>", "Run a subagent"),
            Row("/<template>", "Prompt template, e.g. /explain /fix /test"),
        )),
        Section("Commands", SlashActions.ALL.map { Row("/${it.name}", "${it.description} (${it.shortcut})") } + listOf(
            Row("/until <command>", "Rounds until the command exits with 0"),
            Row("/goal <condition>", "Goal the judge checks after a turn"),
            Row("/long on | off", "Run the goal in rounds / single turns"),
            Row("/long rounds | hours", "Limits of a long task"),
        )),
        Section("Modes", listOf(
            Row("CHAT", "Conversation only, no tools"),
            Row("PLAN", "Read-only tools; builds a plan"),
            Row("AGENT", "All tools; snapshots files before edits"),
        )),
    )

    /** Legend of the status bar, drawn with the glyphs the screen uses. */
    private fun statusSection(glyphs: Glyphs) = Section("Status bar", listOf(
        Row("CHAT PLAN AGENT", "Mode badge"),
        Row(listOf(glyphs.done, glyphs.failed, glyphs.running, glyphs.pending, glyphs.attention).joinToString(" "), "Done, failed, running, pending, needs you"),
        Row("ctx " + glyphs.gaugeFull.repeat(4) + glyphs.gaugeEmpty.repeat(2), "Context used; amber from 75%, red from 90%"),
        Row("${glyphs.arrowDown} ${glyphs.arrowUp}", "Tokens in and out this session"),
    ))

    /** Sections with the rows matching [query] (key or text); a matching title keeps all its rows. */
    fun filter(query: String?, glyphs: Glyphs = Glyphs.UNICODE): List<Section> {
        val all = SECTIONS + statusSection(glyphs)
        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) return all
        return all.mapNotNull { section ->
            if (section.title.lowercase().contains(q)) return@mapNotNull section
            val rows = section.rows.filter { it.key.lowercase().contains(q) || it.text.lowercase().contains(q) }
            if (rows.isEmpty()) null else Section(section.title, rows)
        }
    }

    fun columnsFor(width: Int): Int = when {
        width >= LayoutMode.WIDE_MIN_WIDTH -> 3
        width >= LayoutMode.MEDIUM_MIN_WIDTH -> 2
        else -> 1
    }

    fun renderToLines(state: TuiState, width: Int, contentHeight: Int, theme: Theme = TuiColors.theme, glyphs: Glyphs = Glyphs.UNICODE): List<String> {
        val frame = Frame(theme, glyphs)
        val sections = filter(state.helpFilter, glyphs)
        val columns = columnsFor(width)
        val colWidths = List(columns) { i -> (width - (columns - 1)) / columns + if (i == columns - 1) (width - (columns - 1)) % columns else 0 }
        val framed = sections.map { section -> sectionFrame(section, colWidths.first(), frame, theme, glyphs) }

        // Fill the columns in order, moving on once a column reaches its share of the total height.
        val target = (framed.sumOf { it.size } + columns - 1) / columns
        val stacks = List(columns) { mutableListOf<String>() }
        var col = 0
        for ((i, lines) in framed.withIndex()) {
            if (col < columns - 1 && stacks[col].isNotEmpty() && stacks[col].size + lines.size > target) col++
            val section = sections[i]
            stacks[col] += if (colWidths[col] == colWidths.first()) lines else sectionFrame(section, colWidths[col], frame, theme, glyphs)
        }
        val tall = stacks.maxOf { it.size }
        val all = (0 until tall).map { r -> stacks.indices.joinToString(" ") { c -> TuiRenderBuffer.fitToWidth(stacks[c].getOrElse(r) { "" }, colWidths[c]) } }

        val bodyHeight = (contentHeight - 1).coerceAtLeast(1)
        val maxOffset = (all.size - bodyHeight).coerceAtLeast(0)
        val offset = state.helpScrollOffset.coerceIn(0, maxOffset)
        val shown = all.drop(offset).take(bodyHeight)
        val footer = when {
            state.helpFilter != null -> theme.style("/ ", fg = ThemeColor.ACCENT) + state.helpFilter + "_" +
                theme.style(if (sections.isEmpty()) "   nothing matches" else "   Esc clear", fg = ThemeColor.DIM)
            maxOffset > 0 -> theme.style("/ filter · ↑↓ PgUp PgDn scroll (${offset + 1}-${offset + shown.size} of ${all.size}) · Esc close", fg = ThemeColor.DIM)
            else -> theme.style("/ filter · Esc or F1 close", fg = ThemeColor.DIM)
        }
        return (shown + List(bodyHeight - shown.size) { "" } + footer).map { TuiRenderBuffer.fitToWidth(it, width) }
    }

    private fun sectionFrame(section: Section, width: Int, frame: Frame, theme: Theme, glyphs: Glyphs): List<String> {
        val inner = frame.innerSize(width, 3).first
        val keyWidth = section.rows.maxOf { it.key.length }.coerceAtMost(KEY_WIDTH_MAX).coerceAtMost(inner / 2)
        val body = section.rows.map { row ->
            if (row.key.isEmpty()) {
                theme.style(TextFit.truncateEnd(row.text, inner, glyphs.ellipsis), fg = ThemeColor.DIM)
            } else {
                theme.style(TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(row.key, keyWidth, glyphs.ellipsis), keyWidth), fg = ThemeColor.ACCENT) + "  " +
                    TextFit.truncateEnd(row.text, (inner - keyWidth - 2).coerceAtLeast(1), glyphs.ellipsis)
            }
        }
        return frame.render(width, body.size + 2, section.title, null, FrameState.INACTIVE, body)
    }
}
