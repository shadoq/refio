package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.cli.tui.input.SlashActions
import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/**
 * Suggestions for `/`, `@` and `!` in a frame above the prompt: name, what it does and its key on the left;
 * on a wide enough chat the selected entry is explained on the right.
 */
object CommandPopup {

    data class Entry(val description: String, val shortcut: String?, val kind: String)

    private const val MAX_ROWS = 8
    private const val DETAIL_MIN_WIDTH = 90

    private val TASK_CONTROLS = mapOf(
        "/goal" to "Goal the completion judge checks at the end of a turn",
        "/until" to "Run in rounds until a command exits with 0",
        "/long" to "Long task: on, off, rounds N, hours H",
    )

    private val CONTEXT_REFERENCES = mapOf(
        "@file:" to "Attach a file's content",
        "@folder:" to "Attach a directory tree",
        "@selection" to "Attach the editor selection",
        "@current" to "Attach the current file",
        "@open_files" to "Attach the open files",
        "@recent" to "Attach recently edited files",
        "@problems" to "Attach the reported problems",
        "@terminal" to "Attach the terminal output",
        "@git_diff" to "Attach uncommitted changes",
        "@git_commit:" to "Attach a commit",
        "@grep:" to "Attach grep results",
        "@codebase:" to "Semantic search in the index (RAG)",
        "@docs:" to "Search indexed documentation",
        "@url:" to "Attach a web page",
    )

    private val templates: Map<String, SlashPrompt> by lazy {
        runCatching { SlashPrompt.BUILTINS.associateBy { "/${it.name}" } }.getOrDefault(emptyMap())
    }

    fun describe(candidate: String): Entry {
        SlashActions.ALL.firstOrNull { "/${it.name}" == candidate }?.let {
            return Entry(it.description, it.shortcut, "action")
        }
        TASK_CONTROLS[candidate]?.let { return Entry(it, null, "long task") }
        templates[candidate]?.let { return Entry(it.description, null, "prompt template") }
        CONTEXT_REFERENCES[candidate]?.let { return Entry(it, null, "context") }
        if (candidate.startsWith("@file:") || candidate.startsWith("@folder:")) return Entry(candidate.substringAfter(':'), null, "context")
        if (candidate.startsWith("!")) return Entry("Run the ${candidate.removePrefix("!")} subagent", null, "subagent")
        return Entry("", null, "")
    }

    fun render(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val candidates = state.autocompleteCandidates
        if (candidates.isEmpty()) return emptyList()
        val frame = Frame(theme, glyphs)
        val rows = MAX_ROWS.coerceAtMost(candidates.size)
        val inner = frame.innerSize(width, rows + 2).first
        val selected = state.autocompleteSelectedIndex.coerceIn(0, candidates.lastIndex)
        val start = (selected - rows + 1).coerceAtLeast(0)
        val withDetail = inner >= DETAIL_MIN_WIDTH
        val listWidth = if (withDetail) inner * 55 / 100 else inner
        val nameWidth = candidates.maxOf { it.length }.coerceAtMost(listWidth / 2).coerceAtLeast(4)

        val list = (start until (start + rows).coerceAtMost(candidates.size)).map { i ->
            val entry = describe(candidates[i])
            val isSelected = i == selected
            val marker = if (isSelected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
            val shortcut = entry.shortcut.orEmpty()
            val descWidth = (listWidth - 2 - nameWidth - 2 - (if (shortcut.isNotEmpty()) shortcut.length + 1 else 0)).coerceAtLeast(0)
            val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(candidates[i], nameWidth, glyphs.ellipsis), nameWidth)
            val desc = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(entry.description, descWidth, glyphs.ellipsis), descWidth)
            marker + " " + theme.style(name, fg = if (isSelected) ThemeColor.ACCENT else ThemeColor.TEXT, bold = isSelected) + "  " +
                theme.style(desc, fg = ThemeColor.DIM) + (if (shortcut.isNotEmpty()) " " + theme.style(shortcut, fg = ThemeColor.ACCENT) else "")
        }
        val body = if (!withDetail) list else {
            val detail = detailLines(candidates[selected], inner - listWidth - 3, theme)
            list.indices.union(detail.indices).sorted().map { r ->
                TuiRenderBuffer.fitToWidth(list.getOrElse(r) { "" }, listWidth) + " " + theme.style(glyphs.vertical, fg = ThemeColor.FRAME) + " " + detail.getOrElse(r) { "" }
            }
        }
        val title = when (candidates.first().firstOrNull()) {
            '/' -> "commands"
            '!' -> "subagents"
            else -> "context"
        }
        return frame.render(width, body.size + 2, title, "↑↓ select · Tab complete · Enter run · Esc close", FrameState.POPUP, body)
    }

    private fun detailLines(candidate: String, width: Int, theme: Theme): List<String> {
        val entry = describe(candidate)
        val lines = mutableListOf(theme.style(TextFit.truncateEnd(candidate, width), fg = ThemeColor.TEXT, bold = true))
        lines += TextFit.wrapWords(entry.description, width).take(3).map { theme.style(it, fg = ThemeColor.TEXT) }
        val note = when {
            entry.shortcut != null -> "Same as ${entry.shortcut}."
            entry.kind.isNotEmpty() -> entry.kind.replaceFirstChar { it.uppercase() } + "."
            else -> null
        }
        note?.let { lines += theme.style(TextFit.truncateEnd(it, width), fg = ThemeColor.DIM) }
        return lines
    }
}
