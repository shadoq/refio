package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.components.TuiMessageBubble
import pl.jclab.refio.cli.tui.components.TuiPromptInput
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Chat tab view — message list + prompt input.
 * Renders into a TuiRenderBuffer for split-pane composition.
 */
object TuiChatView {

    private const val WRAP_CACHE_MAX = 500

    private data class CachedWrap(val msgHash: Int, val width: Int, val lines: List<String>)

    /**
     * Per-message cache of rendered + wrapped lines, keyed by message id.
     * A message re-renders only when its content changed (streaming last
     * message) or the width changed; all other messages reuse cached lines.
     * Access is confined to the render path (TuiRenderer.render is
     * synchronized). LRU-evicted so long sessions stay bounded.
     */
    private val wrapCache = object : LinkedHashMap<String, CachedWrap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedWrap>): Boolean =
            size > WRAP_CACHE_MAX
    }

    private fun wrappedLinesFor(terminal: Terminal, msg: TuiChatMessage, width: Int): List<String> {
        val hash = msg.hashCode()
        val cached = wrapCache[msg.id]
        if (cached != null && cached.msgHash == hash && cached.width == width) {
            return cached.lines
        }
        val lines = TuiMessageBubble.renderToLines(terminal, msg).flatMap { wrapLine(it, width) }
        wrapCache[msg.id] = CachedWrap(hash, width, lines)
        return lines
    }

    /** Render chat messages (without prompt) into a buffer. */
    fun renderMessages(terminal: Terminal, state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)
        // Apply agent filter if set
        val messages = if (state.agentFilter != null) {
            state.messages.filter { it.agentName == state.agentFilter || it.role == "user" || it.role == "system" }
        } else {
            state.messages
        }

        if (messages.isEmpty()) {
            // Welcome screen for an empty session: show what is configured and
            // how to start, instead of a bare "no messages" line.
            buf.addLine(TuiColors.highlight("Welcome to Refio"))
            buf.addLine()
            buf.addLine("  Model: ${TuiColors.accent(state.model?.takeIf { it.isNotBlank() } ?: "(not selected - Ctrl+O)")}")
            buf.addLine("  Mode:  ${TuiColors.accent(state.mode)} ${TuiColors.muted("(Shift+Tab to cycle CHAT/PLAN/AGENT)")}")
            buf.addLine()
            buf.addLine(TuiColors.muted("  Type a message and press Enter to start."))
            buf.addLine(TuiColors.muted("  [F1] Help  [F9] Settings  [Ctrl+O] Model  [Ctrl+Q] Quit"))
            buf.addLine()
            return buf
        }

        // Render messages and wrap all lines to buffer width first,
        // then take the last `height` lines (accounting for wrapping).
        val allWrapped = mutableListOf<String>()
        val selectedIdx = state.selectedMessageIndex
        for ((idx, msg) in messages.withIndex()) {
            val wrapped = wrappedLinesFor(terminal, msg, width)
            if (idx == selectedIdx) {
                allWrapped.add(TuiColors.statusPending("  ► ─── selected ───"))
                allWrapped.addAll(wrapped)
                allWrapped.add(TuiColors.statusPending("  ── Ctrl+Y to copy ──"))
            } else {
                allWrapped.addAll(wrapped)
            }
            allWrapped.add("") // blank line between messages
        }

        // Transient native tool-call indicator: shown only while the model streams a tool call's
        // arguments (toolCallProgress non-null), removed once the LLM stream completes.
        state.toolCallProgress?.let { progress ->
            val name = progress.name ?: "tool"
            val args = progress.accumulatedArguments.let { if (it.length > 60) it.take(60) + "…" else it }
            allWrapped.add(TuiColors.tool("⚙ $name($args)"))
        }

        // Apply scroll offset: 0 = bottom (most recent), >0 = scrolled up
        val offset = state.scrollOffset.coerceIn(0, (allWrapped.size - height).coerceAtLeast(0))
        val endIdx = (allWrapped.size - offset).coerceAtLeast(0)
        val startIdx = (endIdx - height).coerceAtLeast(0)
        val visible = allWrapped.subList(startIdx, endIdx)
        for (line in visible) {
            if (buf.lineCount >= height) break
            buf.addLine(line)
        }

        return buf
    }

    /** Render prompt area into a buffer (separator + mode + input). Dynamic height for multi-line. */
    fun renderPrompt(state: TuiState, width: Int): TuiRenderBuffer {
        val lines = TuiPromptInput.renderToLines(state, width)
        val maxHeight = lines.size.coerceIn(3, 8) // min 3, max 8 lines
        val buf = TuiRenderBuffer(width, maxHeight)
        for (line in lines) {
            buf.addLine(line)
        }
        return buf
    }

    /** Wrap a single line to fit within maxWidth, preserving ANSI codes. */
    private fun wrapLine(line: String, maxWidth: Int): List<String> {
        val visLen = TuiRenderBuffer.visibleLength(line)
        if (visLen <= maxWidth || maxWidth <= 0) return listOf(line)

        // For lines with ANSI codes, simple char-based split won't work well.
        // Strip ANSI, wrap the plain text, re-add prefix styling per chunk.
        val stripped = TuiRenderBuffer.stripAnsi(line)
        val chunks = mutableListOf<String>()
        var pos = 0
        while (pos < stripped.length) {
            val end = (pos + maxWidth).coerceAtMost(stripped.length)
            chunks.add(stripped.substring(pos, end))
            pos = end
        }
        // If original had ANSI and we only have one chunk after strip, return original
        if (chunks.size <= 1) return listOf(line)
        return chunks
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
