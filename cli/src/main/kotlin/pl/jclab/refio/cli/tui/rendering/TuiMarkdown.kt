package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Markdown rendering for TUI using Mordant's built-in Markdown widget.
 */
object TuiMarkdown {
    /**
     * Render markdown text to styled terminal output.
     */
    fun render(terminal: Terminal, text: String) {
        if (text.isBlank()) return
        terminal.println(Markdown(text))
    }

    /**
     * Render markdown text to a string (for widget composition).
     */
    fun renderToString(terminal: Terminal, text: String): String {
        if (text.isBlank()) return ""
        return terminal.render(Markdown(text))
    }
}
