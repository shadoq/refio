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
 * Context: [@file:app.kt] [@git_diff]              (if refs present)
 * [CHAT] ollama/qwen2.5:7b  Ctrl+M=mode  @=context  !=subagent
 * > user input here_
 * ```
 */
object TuiPromptInput {
    fun render(terminal: Terminal, state: TuiState) {
        for (line in renderToLines(state)) {
            terminal.println(line)
        }
    }

    /** Maximum number of visible input lines (expands from 1 to this) */
    private const val MAX_INPUT_LINES = 4

    /** Render prompt to lines (for buffer-based rendering). */
    fun renderToLines(state: TuiState, availableWidth: Int = 60): List<String> {
        val result = mutableListOf<String>()

        // Separator
        result.add(TuiColors.border("─".repeat(60)))

        // Context tags line (if user has @ references in input)
        val contextRefs = extractContextRefs(state.inputBuffer)
        if (contextRefs.isNotEmpty()) {
            val tags = contextRefs.joinToString(" ") { ref ->
                TuiColors.accent("[$ref]")
            }
            result.add(TuiColors.muted("Context: ") + tags)
        }

        // Status line: mode + model + toggles + metrics (like plugin StatusBar)
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

        // Metrics (mirrors plugin StatusBar: context, tokens, cost, requests)
        val ctxBar = renderContextBar(state)
        val tokIn = formatTokensShort(state.sessionTokensIn)
        val tokOut = formatTokensShort(state.sessionTokensOut)
        val cost = String.format(java.util.Locale.US, "%.4f", state.totalCostUsd)
        val reqCount = state.apiLogs.size
        val metrics = " $ctxBar ${TuiColors.muted("⬇${tokIn} ⬆${tokOut} ${reqCount}req \$${cost}")}"

        if (state.pendingQuestionId != null) {
            val questionHint = TuiColors.statusPending(" [?] Awaiting answer")
            result.add("$mode$model$togglesPart$questionHint$metrics")
        } else if (state.isStreaming) {
            val streaming = TuiColors.statusRunning(" streaming...")
            result.add("$mode$model$togglesPart$streaming$metrics")
        } else {
            result.add("$mode$model$togglesPart$metrics")
        }

        // Input lines — expand up to MAX_INPUT_LINES based on content
        val inputBuffer = state.inputBuffer
        val cursorPos = state.cursorPosition.coerceIn(0, inputBuffer.length)
        val prefix = "> "
        val prefixLen = 2
        val editableWidth = (availableWidth - prefixLen).coerceAtLeast(10)

        // Check for pasted content marker
        if (state.pastedContent != null) {
            val pastedLen = state.pastedContent.length
            val preview = state.pastedContent.take(30).replace("\n", "↵")
            result.add(TuiColors.accent(prefix) + TuiColors.muted("[pasted ${pastedLen} chars: $preview...]"))
            result.add(TuiColors.accent(prefix) + inputBuffer)
            return result
        }

        // Split input into visual lines by wrapping at editableWidth
        val inputLines = wrapInputForDisplay(inputBuffer, editableWidth, cursorPos)
        val visibleLines = inputLines.take(MAX_INPUT_LINES)

        for ((i, line) in visibleLines.withIndex()) {
            val linePrefix = if (i == 0) TuiColors.accent(prefix) else TuiColors.muted("  ")
            result.add(linePrefix + line)
        }

        // Show overflow indicator if content exceeds MAX_INPUT_LINES
        if (inputLines.size > MAX_INPUT_LINES) {
            result.add(TuiColors.muted("  ↓ ${inputLines.size - MAX_INPUT_LINES} more lines..."))
        }

        // Ensure at least one input line
        if (visibleLines.isEmpty()) {
            result.add(TuiColors.accent(prefix))
        }

        return result
    }

    /**
     * Wrap input text into display lines based on available width.
     * Respects explicit newlines and wraps long lines.
     * Returns the line containing the cursor as the visible line for cursor positioning.
     */
    private fun wrapInputForDisplay(input: String, width: Int, cursorPos: Int): List<String> {
        if (input.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        // Split by explicit newlines first
        val explicitLines = input.split("\n")
        for (line in explicitLines) {
            if (line.length <= width) {
                lines.add(line)
            } else {
                // Wrap long lines at width boundary
                var pos = 0
                while (pos < line.length) {
                    val end = (pos + width).coerceAtMost(line.length)
                    lines.add(line.substring(pos, end))
                    pos = end
                }
            }
        }
        return lines
    }

    /**
     * Calculate cursor row and column within the wrapped input display.
     */
    fun getCursorRowCol(input: String, cursorPos: Int, editableWidth: Int): Pair<Int, Int> {
        if (input.isEmpty()) return Pair(0, 0)

        var charsConsumed = 0
        var row = 0
        val explicitLines = input.split("\n")
        for ((lineIdx, line) in explicitLines.withIndex()) {
            if (line.length <= editableWidth) {
                if (charsConsumed + line.length >= cursorPos) {
                    return Pair(row, cursorPos - charsConsumed)
                }
                charsConsumed += line.length + 1 // +1 for \n
                row++
            } else {
                var pos = 0
                while (pos < line.length) {
                    val end = (pos + editableWidth).coerceAtMost(line.length)
                    val segmentLen = end - pos
                    if (charsConsumed + pos + segmentLen >= cursorPos) {
                        return Pair(row, cursorPos - charsConsumed - pos)
                    }
                    row++
                    pos = end
                }
                charsConsumed += line.length + 1
            }
        }
        return Pair(row, 0)
    }

    /** Extract @reference tags from input text */
    private fun extractContextRefs(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val refRegex = Regex("""@\w+:?\S*""")
        return refRegex.findAll(input).map { it.value }.toList()
    }

    /** Estimate token count for a context reference (heuristic: 4 chars = 1 token) */
    fun estimateRefTokens(ref: String, projectRoot: String?): String {
        if (projectRoot == null) return ""
        return try {
            when {
                ref.startsWith("@file:") -> {
                    val path = ref.removePrefix("@file:")
                    val file = java.io.File(path).let {
                        if (it.isAbsolute) it else java.io.File(projectRoot, path)
                    }
                    if (file.exists() && file.isFile) {
                        val tokens = (file.length() / 4).toInt()
                        formatTokens(tokens)
                    } else ""
                }
                ref.startsWith("@folder:") -> "~2K"
                ref == "@git_diff" -> "~1K"
                ref.startsWith("@codebase:") -> "~2K"
                ref.startsWith("@docs:") -> "~2K"
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun formatTokens(tokens: Int): String = when {
        tokens > 1000 -> "${String.format(java.util.Locale.US, "%.1f", tokens / 1000.0)}K"
        else -> "${tokens}"
    }

    /** Compact context bar [████░░ 45%] */
    private fun renderContextBar(state: TuiState): String {
        val used = state.contextUsedTokens
        val max = state.contextMaxTokens
        if (max <= 0) return ""

        val pct = (used.toDouble() / max * 100).toInt().coerceIn(0, 100)
        val barWidth = 8
        val filled = (barWidth * pct / 100).coerceIn(0, barWidth)
        val empty = barWidth - filled

        val barColor = when {
            pct >= 90 -> TuiColors.statusFailed
            pct >= 75 -> TuiColors.statusPending
            else -> TuiColors.statusSuccess
        }
        return "[${barColor("█".repeat(filled))}${TuiColors.muted("░".repeat(empty))}${pct}%]"
    }

    private fun formatTokensShort(tokens: Long): String = when {
        tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
}
