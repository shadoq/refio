package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.components.TuiMessageBubble
import pl.jclab.refio.cli.tui.components.TuiPromptInput
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Chat tab view — message list + prompt input.
 * Renders into a TuiRenderBuffer for split-pane composition.
 */
object TuiChatView {

    /** Render chat messages (without prompt) into a buffer. */
    fun renderMessages(terminal: Terminal, state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        val messages = state.messages

        if (messages.isEmpty()) {
            buf.addLine(TuiColors.muted("No messages yet. Type a message and press Enter."))
            buf.addLine()
            return buf
        }

        // Render messages bottom-up: collect all lines, then take last `height` lines
        val allLines = mutableListOf<String>()
        for (msg in messages) {
            val msgLines = TuiMessageBubble.renderToLines(terminal, msg)
            allLines.addAll(msgLines)
            allLines.add("") // blank line between messages
        }

        // Take the last `height` lines (auto-scroll to bottom)
        val visible = if (allLines.size > height) allLines.takeLast(height) else allLines
        for (line in visible) {
            if (buf.lineCount >= height) break
            buf.addWrapped(line)
        }

        return buf
    }

    /** Render prompt area into a buffer (separator + mode + input). */
    fun renderPrompt(state: TuiState, width: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, 3)
        for (line in TuiPromptInput.renderToLines(state)) {
            buf.addLine(line)
        }
        return buf
    }

    /** Legacy render method for non-split mode (full-width). */
    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val messages = state.messages
        val visibleCount = (contentHeight - 4).coerceAtLeast(3)
        val visible = if (messages.size > visibleCount) messages.takeLast(visibleCount) else messages

        if (visible.isEmpty()) {
            terminal.println(TuiColors.muted("No messages yet. Type a message and press Enter."))
            terminal.println()
        } else {
            for (msg in visible) {
                TuiMessageBubble.render(terminal, msg)
                terminal.println()
            }
        }

        val rendered = if (visible.isEmpty()) 2 else visible.size * 3
        val remaining = (contentHeight - rendered - 3).coerceAtLeast(0)
        repeat(remaining) { terminal.println() }

        TuiPromptInput.render(terminal, state)
    }
}
