package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.state.StoredChatMessages
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TranscriptItem
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/**
 * Notice shown in place of an answer the core stopped at the output limit, with the ways out as keys:
 * `c` continue, `s` switch to PLAN with a request to split the task in the prompt, `l` raise the output limit and continue,
 * `m` switch model. When the context window, not the output limit, left too little room, `l` would not
 * help, so the notice says so and offers no `l`.
 */
object OutputLimitNotice {

    private const val TRUNCATED = "OUTPUT_TRUNCATED"

    /** Request `s` puts in the prompt after switching to PLAN mode. */
    const val SPLIT_PROMPT = "The previous answer was cut off at the output limit. Split the task into smaller steps that each fit in one answer, then do them one at a time."

    /** Raised limit offered by `l`: twice the current one. */
    fun raisedLimit(current: Int): Int = current * 2

    fun applies(msg: TuiChatMessage): Boolean =
        msg.role == "assistant" && msg.metadata[StoredChatMessages.META_STOP_REASON] == TRUNCATED

    /** True when the notice is the last thing in the transcript, so its keys may act. */
    fun isActive(state: TuiState): Boolean {
        if (state.isStreaming || state.inputBuffer.isNotEmpty()) return false
        val last = TranscriptBuilder.build(TranscriptBuilder.visibleMessages(state.messages, state.agentFilter)).lastOrNull()
        return last is TranscriptItem.Message && applies(last.message)
    }

    /**
     * Estimate whether the context window was the tighter cap: for Ollama the core shrinks the output budget
     * to what is left of `num_ctx` after the prompt, so a full window cuts answers short whatever the
     * output limit says.
     */
    fun windowIsTheCap(state: TuiState): Boolean {
        val provider = state.model?.substringBefore('/', missingDelimiterValue = "")?.lowercase()
        if (provider != "ollama" || state.contextMaxTokens <= 0) return false
        return state.contextMaxTokens - state.contextUsedTokens < state.maxOutputTokens
    }

    fun render(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs, keysActive: Boolean): List<String> {
        val frame = Frame(theme, glyphs)
        val inner = frame.innerSize(width, 4).first
        val byWindow = windowIsTheCap(state)
        val reason = if (byWindow) {
            "The context window (${pl.jclab.refio.cli.tui.views.TuiContextView.windowSize(state.contextMaxTokens)}) left too little room for the answer. Raise the provider's context size in Settings, or start a new session."
        } else {
            "Stopped at the output limit (${state.maxOutputTokens} tokens) before the answer was complete."
        }
        val actions = buildList {
            add("[c] continue")
            add("[s] split into steps")
            if (!byWindow) add("[l] raise limit to ${raisedLimit(state.maxOutputTokens)}")
            add("[m] switch model")
            add("F5 logs")
        }.joinToString("  ")
        val actionColour = if (keysActive) ThemeColor.WARN else ThemeColor.DIM
        val body = TextFit.wrapWords(reason, inner).map { theme.style(it, fg = ThemeColor.TEXT) } +
            theme.style(TextFit.truncateEnd(actions, inner, glyphs.ellipsis), fg = actionColour)
        return frame.render(width, body.size + 2, "! output limit", null, FrameState.WARNING, body)
    }
}
