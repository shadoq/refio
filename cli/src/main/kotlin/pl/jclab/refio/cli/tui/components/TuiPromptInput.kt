package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Body of the PROMPT frame (mode, model and session numbers live in the status bar):
 * ```
 * Context: [@file:app.kt] [@git_diff]              (if refs present)
 * ⟳ round 2/12 ...                                 (long task)
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

    private const val PLACEHOLDER = "@file  /command  !agent"

    /** Lines drawn above the first input line (context tags, long-task progress, paste marker). */
    fun inputLineOffset(state: TuiState): Int =
        (if (extractContextRefs(state.inputBuffer).isNotEmpty()) 1 else 0) +
            (if (state.longTaskLine != null) 1 else 0) +
            (if (state.pastedContent != null) 1 else 0)

    /** Render prompt to lines (for buffer-based rendering). */
    fun renderToLines(state: TuiState, availableWidth: Int = 60): List<String> {
        val result = mutableListOf<String>()

        // Context tags line (if user has @ references in input)
        val contextRefs = extractContextRefs(state.inputBuffer)
        if (contextRefs.isNotEmpty()) {
            val tags = contextRefs.joinToString(" ") { ref ->
                TuiColors.accent("[$ref]")
            }
            result.add(TuiColors.muted("Context: ") + tags)
        }

        // Long task: which round, how much of the time limit, how the last check went
        state.longTaskLine?.let { result.add(TuiColors.accent("⟳ ") + TuiColors.muted(it)) }

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

        // Ensure at least one input line; an empty prompt shows the reference syntax
        if (visibleLines.isEmpty() || inputBuffer.isEmpty()) {
            if (visibleLines.isNotEmpty()) result.removeAt(result.lastIndex)
            val hint = if (state.pendingToolApproval != null) "answer the approval above first" else PLACEHOLDER
            result.add(TuiColors.accent(prefix) + TuiColors.muted(hint))
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

}
