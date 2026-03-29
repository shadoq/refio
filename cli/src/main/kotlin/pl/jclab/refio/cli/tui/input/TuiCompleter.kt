package pl.jclab.refio.cli.tui.input

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import pl.jclab.refio.api.models.SlashCommand

/**
 * JLine3 Completer for TUI prompt input.
 * Provides completion for @mentions, /slash commands (prompt templates), and !subagents.
 */
class TuiCompleter : Completer {

    /** System commands removed — all operations accessed through GUI keybindings/screens */
    private val systemCommands = emptyList<String>()

    /** Slash command prompt templates from SlashCommand.BUILTINS */
    private val slashCommands: List<Pair<String, String>> by lazy {
        try {
            SlashCommand.BUILTINS.map { "/${it.name}" to it.description }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val mentionPrefixes = listOf(
        "@file:", "@folder:", "@codebase:", "@git-diff", "@git-commit:",
        "@grep:", "@url:", "@docs:", "@clipboard"
    )

    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val word = line.word() ?: return

        when {
            word.startsWith("/") -> {
                // Slash commands (prompt templates) first
                slashCommands.filter { it.first.startsWith(word) }
                    .forEach { candidates.add(Candidate(it.first, it.first, null, it.second, null, null, true)) }
                // Then system commands
                systemCommands.filter { it.startsWith(word) }
                    .forEach { candidates.add(Candidate(it)) }
            }
            word.startsWith("@") -> {
                mentionPrefixes.filter { it.startsWith(word) }
                    .forEach { candidates.add(Candidate(it)) }
            }
        }
    }
}
