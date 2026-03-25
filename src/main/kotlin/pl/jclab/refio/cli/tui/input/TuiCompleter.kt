package pl.jclab.refio.cli.tui.input

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

/**
 * JLine3 Completer for TUI prompt input.
 * Provides completion for @mentions, /commands, and !subagents.
 */
class TuiCompleter : Completer {

    private val commands = listOf(
        "/help", "/quit", "/clear", "/mode", "/model",
        "/history", "/settings", "/debug-refresh", "/logs-refresh",
        "/rag-search", "/rag-refresh", "/api-export",
        "/resume", "/replan", "/cancel",
        "/no-egress"
    )

    private val mentionPrefixes = listOf(
        "@file:", "@folder:", "@codebase:", "@git-diff", "@git-commit:",
        "@grep:", "@url:", "@docs:", "@clipboard"
    )

    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val word = line.word() ?: return

        when {
            word.startsWith("/") -> {
                commands.filter { it.startsWith(word) }
                    .forEach { candidates.add(Candidate(it)) }
            }
            word.startsWith("@") -> {
                mentionPrefixes.filter { it.startsWith(word) }
                    .forEach { candidates.add(Candidate(it)) }
            }
        }
    }
}
