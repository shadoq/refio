package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Prompt input area — mirrors IntelliJ plugin's PromptInputPanel.
 *
 * Layout:
 * ```
 * ────────────────────────────────────────────────────────────────
 * [CHAT] ollama/qwen2.5:7b  Ctrl+M=mode  @=context  !=subagent
 * > user input here_
 * ```
 *
 * During streaming:
 * ```
 * ────────────────────────────────────────────────────────────────
 * [AGENT] ollama/qwen2.5:7b  streaming...
 * > _
 * ```
 */
object TuiPromptInput {
    fun render(terminal: Terminal, state: TuiState) {
        for (line in renderToLines(state)) {
            terminal.println(line)
        }
    }

    /** Render prompt to lines (for buffer-based rendering). */
    fun renderToLines(state: TuiState): List<String> {
        val result = mutableListOf<String>()

        // Separator
        result.add(TuiColors.border("─".repeat(60)))

        // Status line: mode + model + toggles or streaming indicator
        val modeIcon = when (state.mode) {
            "CHAT" -> "💬"
            "PLAN" -> "📝"
            "AGENT" -> "🤖"
            else -> ">"
        }
        val mode = TuiColors.accent("[$modeIcon ${state.mode}]")
        val model = state.model?.let { TuiColors.muted(" $it") } ?: TuiColors.muted(" default")

        // Toggle indicators
        val execIcon = if (state.executionMode == "AUTO") "⚡" else "🤚"
        val thinkIcon = if (state.thinkingEnabled) "🧠" else ""
        val egressIcon = if (state.noEgressEnabled) "🔒" else ""
        val toggles = listOfNotNull(
            execIcon,
            thinkIcon.ifEmpty { null },
            egressIcon.ifEmpty { null }
        ).joinToString("")
        val togglesPart = if (toggles.isNotEmpty()) TuiColors.muted(" $toggles") else ""

        if (state.isStreaming) {
            val streaming = TuiColors.statusRunning(" streaming...")
            result.add("$mode$model$togglesPart$streaming")
        } else {
            val hints = TuiColors.muted("  Ctrl+M=mode Ctrl+T=think Ctrl+E=exec @=ctx")
            result.add("$mode$model$togglesPart$hints")
        }

        // Input line
        result.add(TuiColors.accent("> ") + state.inputBuffer)

        return result
    }
}
