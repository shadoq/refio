package pl.jclab.refio.cli.tui.views

import pl.jclab.refio.cli.tui.components.StatusBar
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import pl.jclab.refio.core.api.SubtaskResponse

/**
 * Steps of the current task: one row per step (`status · # · tool · target · time or state`), the
 * selected step's details under the list and the step actions wrapped onto two lines at the bottom.
 * Each step is one row the core stored; a tool called twice is two steps.
 */
object TuiStepsView {

    /** Statuses the core (`SUCCESS`) and older data (`COMPLETED`) use for a finished step. */
    val DONE_STATUSES = setOf("SUCCESS", "COMPLETED")

    private val ACTIONS = listOf("[a]pprove", "[s]kip", "[d]elete", "[u/j] move", "[p]ause", "[r]eplan", "[C]ancel all")
    private const val KIND_MAX = 18

    fun renderToBuffer(
        state: TuiState,
        width: Int,
        height: Int,
        theme: Theme = TuiColors.theme,
        glyphs: Glyphs = Glyphs.UNICODE,
    ): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        val planApproval = state.pendingPlanApproval
        if (planApproval != null && planApproval.isVisible) {
            renderPlanApproval(buf, planApproval.steps, width, height, theme)
            return buf
        }

        val steps = state.subtasks
        if (steps.isEmpty()) {
            buf.addLine(theme.style("No steps yet: PLAN and AGENT modes create them.", fg = ThemeColor.DIM))
            return buf
        }

        buf.addLine(summary(state, steps, theme))
        val toolbar = wrapActions(width).map { theme.style(it, fg = ThemeColor.DIM) }
        val selected = steps.getOrNull(state.selectedStepIndex)
        val detail = selected?.let { details(it, width, theme) }.orEmpty()

        // Rows get what is left; details shrink first, the toolbar stays.
        val reserved = 1 + toolbar.size + 1
        val detailRows = detail.take((height - reserved - 1 - minOf(steps.size, 3)).coerceAtLeast(0))
        val rowSpace = (height - reserved - (if (detailRows.isEmpty()) 0 else detailRows.size + 1)).coerceAtLeast(1)
        val first = (state.selectedStepIndex - rowSpace + 1).coerceIn(0, (steps.size - rowSpace).coerceAtLeast(0))
        val kindWidth = steps.maxOf { it.kind.length }.coerceAtMost(KIND_MAX).coerceAtMost((width / 4).coerceAtLeast(4))
        steps.withIndex().drop(first).take(rowSpace).forEach { (index, step) ->
            buf.addLine(row(index, step, index == state.selectedStepIndex, kindWidth, width, theme, glyphs))
        }
        if (detailRows.isNotEmpty()) {
            buf.addLine()
            detailRows.forEach { buf.addLine(it) }
        }
        while (buf.lineCount < height - toolbar.size) buf.addLine()
        toolbar.forEach { buf.addLine(it) }
        return buf
    }

    private fun summary(state: TuiState, steps: List<SubtaskResponse>, theme: Theme): String {
        val done = steps.count { it.status.uppercase() in DONE_STATUSES }
        val failed = steps.count { it.status.uppercase() == "FAILED" }
        val running = steps.count { it.status.uppercase() == "RUNNING" }
        val parts = listOfNotNull(
            "$done/${steps.size} done",
            failed.takeIf { it > 0 }?.let { "$it failed" },
            running.takeIf { it > 0 }?.let { "$it running" },
        )
        val paused = if (state.isPaused) "  " + theme.style("paused", fg = ThemeColor.WARN, bold = true) else ""
        return theme.style(parts.joinToString(" · "), fg = ThemeColor.TEXT, bold = true) + paused
    }

    private fun row(index: Int, step: SubtaskResponse, selected: Boolean, kindWidth: Int, width: Int, theme: Theme, glyphs: Glyphs): String {
        val (glyph, colour) = statusGlyph(step.status, glyphs)
        val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
        val number = "#${index + 1}".padStart(3)
        val kind = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(step.kind, kindWidth, glyphs.ellipsis), kindWidth)
        val right = stateText(step)
        val targetWidth = (width - 1 - 2 - 4 - kindWidth - 2 - right.length - 1).coerceAtLeast(0)
        val target = TuiRenderBuffer.fitToWidth(TextFit.truncateMiddle(target(step), targetWidth, glyphs.ellipsis), targetWidth)
        val text = if (selected) ThemeColor.ACCENT else ThemeColor.TEXT
        return marker + theme.style(glyph, fg = colour) + " " + theme.style(number, fg = ThemeColor.DIM) + " " +
            theme.style(kind, fg = ThemeColor.WARN) + "  " + theme.style(target, fg = text, bold = selected) + " " +
            theme.style(right, fg = ThemeColor.DIM)
    }

    /** What the step worked on: the main argument of its call, else its description. */
    private fun target(step: SubtaskResponse): String =
        step.paramsJson?.let { TranscriptBuilder.mainArgument(it) }?.takeIf { it.isNotBlank() } ?: step.description

    private fun stateText(step: SubtaskResponse): String {
        val status = step.status.uppercase()
        val duration = if (step.startedAt != null && step.finishedAt != null) {
            TuiChatView.formatDuration((step.finishedAt!! - step.startedAt!!).coerceAtLeast(0))
        } else null
        return when (status) {
            in DONE_STATUSES -> duration ?: "done"
            "RUNNING" -> "running"
            "FAILED" -> duration?.let { "failed $it" } ?: "failed"
            else -> status.lowercase()
        }
    }

    internal fun statusGlyph(status: String, glyphs: Glyphs): Pair<String, ThemeColor> = when (status.uppercase()) {
        in DONE_STATUSES -> glyphs.done to ThemeColor.OK
        "FAILED" -> glyphs.failed to ThemeColor.ERR
        "RUNNING" -> glyphs.running to ThemeColor.INFO
        "INCOMPLETE" -> glyphs.attention to ThemeColor.WARN
        else -> glyphs.pending to ThemeColor.DIM
    }

    private fun details(step: SubtaskResponse, width: Int, theme: Theme): List<String> {
        fun line(label: String, value: String, colour: ThemeColor = ThemeColor.TEXT) =
            theme.style(label.padEnd(8), fg = ThemeColor.DIM) + theme.style(TextFit.truncateEnd(value.replace('\n', ' '), (width - 8).coerceAtLeast(1)), fg = colour)
        return buildList {
            if (step.description.isNotBlank()) add(line("Step", step.description))
            if (step.tokensIn > 0 || step.tokensOut > 0 || step.costUsd > 0.0) {
                val cost = if (step.costUsd > 0.0) " · " + StatusBar.formatCost(step.costUsd, isLocal = false) else ""
                add(line("Tokens:", "${step.tokensIn} in / ${step.tokensOut} out$cost"))
            }
            listOfNotNull(step.provider, step.model).takeIf { it.isNotEmpty() }?.let { add(line("Model", it.joinToString("/"))) }
            step.errorMessage?.takeIf { it.isNotBlank() }?.let { add(line("Error", it, ThemeColor.ERR)) }
            step.resultSummary?.takeIf { it.isNotBlank() }?.let { add(line("Result", it)) }
        }
    }

    /** The step actions, wrapped to [width] (two lines in a normal column). */
    internal fun wrapActions(width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (action in ACTIONS) {
            val candidate = if (line.isEmpty()) action else "$line  $action"
            if (TuiRenderBuffer.visibleLength(candidate) > width && line.isNotEmpty()) {
                lines += line
                line = action
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.map { TextFit.truncateEnd(it, width) }
    }

    private fun renderPlanApproval(buf: TuiRenderBuffer, steps: List<SubtaskResponse>, width: Int, height: Int, theme: Theme) {
        val readSteps = steps.count { it.kind.startsWith("read") || it.kind == "grep_search" || it.kind == "file_search" }
        buf.addLine(theme.style("PLAN APPROVAL", fg = ThemeColor.WARN, bold = true))
        buf.addLine(theme.style("${steps.size} steps: $readSteps read-only, ${steps.size - readSteps} write", fg = ThemeColor.DIM))
        buf.addLine()
        for ((index, step) in steps.withIndex()) {
            if (buf.lineCount >= height - 2) break
            val toolSuffix = if (step.kind.isNotEmpty()) " (${step.kind})" else ""
            buf.addLine(TextFit.truncateEnd("${(index + 1).toString().padStart(2)}. ${step.description}$toolSuffix", width))
        }
        buf.addLine()
        buf.addLine(theme.style("[y] approve  [n] reject", fg = ThemeColor.WARN))
    }
}
