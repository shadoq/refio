package pl.jclab.refio.cli.tui.views

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState

/**
 * Steps tab view — list of subtasks with selection, metrics, and action toolbar.
 */
object TuiStepsView {

    fun renderToBuffer(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        val buf = TuiRenderBuffer(width, height)

        // Plan approval overlay takes priority
        val planApproval = state.pendingPlanApproval
        if (planApproval != null && planApproval.isVisible) {
            renderPlanApproval(buf, planApproval.plan, width, height)
            return buf
        }

        // Show subtasks if available, otherwise fall back to legacy steps
        val subtasks = state.subtasks
        val steps = state.steps

        if (subtasks.isEmpty() && steps.isEmpty()) {
            buf.addLine(TuiColors.muted("No active steps."))
            buf.addLine(TuiColors.muted("Start a task in PLAN or AGENT mode."))
            return buf
        }

        // Subtask-based view (PLAN/AGENT mode)
        if (subtasks.isNotEmpty()) {
            val completed = subtasks.count { it.status in listOf("COMPLETED", "SKIPPED") }
            val failed = subtasks.count { it.status == "FAILED" }
            val running = subtasks.count { it.status == "RUNNING" }

            buf.addLine(TuiColors.highlight("Steps (${completed}/${subtasks.size} done" +
                    (if (failed > 0) ", ${failed} failed" else "") +
                    (if (running > 0) ", ${running} running" else "") +
                    ")") +
                    (if (state.isPaused) TuiColors.statusPending(" [PAUSED]") else "")
            )
            buf.addLine()

            for ((index, subtask) in subtasks.withIndex()) {
                if (buf.lineCount >= height - 4) break

                val isSelected = index == state.selectedStepIndex
                val prefix = if (isSelected) "> " else "  "
                val orderNum = "${index + 1}".padStart(2)
                val statusIcon = statusIcon(subtask.status)
                val statusStyle = statusStyle(subtask.status)

                // Model + duration suffix for completed/failed steps
                val modelSuffix = if (subtask.status in listOf("COMPLETED", "FAILED")) {
                    val parts = mutableListOf<String>()
                    if (subtask.model != null) parts.add(subtask.model)
                    if (subtask.startedAt != null && subtask.finishedAt != null) {
                        val durationSec = (subtask.finishedAt - subtask.startedAt) / 1000.0
                        parts.add("${String.format("%.1f", durationSec)}s")
                    }
                    if (parts.isNotEmpty()) TuiColors.muted(" [${parts.joinToString(" ")}]") else ""
                } else ""

                val line = "$prefix[$orderNum] $statusIcon ${statusStyle(subtask.status.padEnd(9))} ${subtask.name}$modelSuffix"
                buf.addLine(line)

                // Show expanded details for selected step
                if (isSelected && subtask.status in listOf("COMPLETED", "FAILED", "RUNNING")) {
                    if (subtask.tokensIn > 0 || subtask.costUsd > 0.0) {
                        buf.addLine(TuiColors.muted(
                            "       Tokens: ${subtask.tokensIn}/${subtask.tokensOut}  " +
                                    "Cost: $${String.format("%.4f", subtask.costUsd)}"
                        ))
                    }
                    if (subtask.toolName != null) {
                        buf.addLine(TuiColors.muted("       Tool: ${subtask.toolName}"))
                    }
                    if (subtask.resultSummary != null) {
                        val summary = subtask.resultSummary.take(120)
                        buf.addLine(TuiColors.muted("       Result: $summary"))
                    }
                }

                // Show error for failed steps (always visible, not just when selected)
                if (subtask.status == "FAILED" && subtask.error != null) {
                    buf.addLine(TuiColors.statusFailed("       Error: ${subtask.error}"))
                }
            }

            // Toolbar hint
            buf.addLine()
            buf.addLine(TuiColors.muted("[a]pprove [s]kip [d]elete [u/j]move [p]ause [r]eplan [R]un [C]ancel-all"))
            return buf
        }

        // Legacy step view (from workflow listener)
        buf.addLine(TuiColors.highlight("Steps (${steps.size})"))
        buf.addLine()

        for (step in steps) {
            if (buf.lineCount >= height - 2) break
            val statusStyle = statusStyle(step.status)
            buf.addLine("${statusStyle("[${step.status}]")} ${step.name}")

            if (step.expanded && step.details.isNotBlank()) {
                for (line in step.details.lines()) {
                    if (buf.lineCount >= height - 1) break
                    buf.addLine(TuiColors.muted("  $line"))
                }
            }
        }

        return buf
    }

    private fun renderPlanApproval(
        buf: TuiRenderBuffer,
        plan: pl.jclab.refio.cli.tui.state.TuiPlan,
        width: Int,
        height: Int
    ) {
        buf.addLine(TuiColors.highlight("=== Plan Approval ==="))
        buf.addLine()
        buf.addLine("${plan.steps.size} steps: ${plan.totalReadSteps} read-only, ${plan.totalWriteSteps} write")
        buf.addLine()

        for ((index, step) in plan.steps.withIndex()) {
            if (buf.lineCount >= height - 4) break
            buf.addLine("  ${index + 1}. ${step.name}" +
                    (if (step.toolName != null) " (${step.toolName})" else ""))
        }

        buf.addLine()
        buf.addLine(TuiColors.highlight("[y] Approve  [n] Reject"))
    }

    private fun statusIcon(status: String): String = when (status) {
        "NEW" -> "○"
        "PENDING" -> "◌"
        "APPROVED" -> "◎"
        "RUNNING" -> "●"
        "COMPLETED" -> "✓"
        "FAILED" -> "✗"
        "SKIPPED" -> "⊘"
        else -> "?"
    }

    private fun statusStyle(status: String): TextColors = when (status) {
        "NEW" -> TuiColors.statusNew
        "PENDING" -> TuiColors.statusPending
        "RUNNING" -> TuiColors.statusRunning
        "COMPLETED", "OK" -> TuiColors.statusSuccess
        "FAILED" -> TuiColors.statusFailed
        "APPROVED" -> TuiColors.statusRunning
        "SKIPPED" -> TuiColors.muted
        else -> TuiColors.muted
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val buf = renderToBuffer(state, 200, contentHeight)
        for (line in buf.getLines()) {
            terminal.println(line)
        }
    }
}
