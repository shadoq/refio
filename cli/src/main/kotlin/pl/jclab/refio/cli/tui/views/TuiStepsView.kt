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
            renderPlanApproval(buf, planApproval.steps, width, height)
            return buf
        }

        val subtasks = state.subtasks
        if (subtasks.isEmpty()) {
            buf.addLine(TuiColors.muted("No active steps."))
            buf.addLine(TuiColors.muted("Start a task in PLAN or AGENT mode."))
            return buf
        }

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

            // Data preservation badge for completed steps
            val dataBadge = if (subtask.status == "COMPLETED" && subtask.result != null && subtask.resultSummary != null) {
                val resultLen = subtask.result!!.length
                val summaryLen = subtask.resultSummary!!.length
                if (resultLen <= 32_000 && resultLen > summaryLen) {
                    TuiColors.statusSuccess(" [FULL]")
                } else if (resultLen > 32_000) {
                    TuiColors.statusPending(" [SUMM]")
                } else ""
            } else ""

            // Model + duration suffix for completed/failed steps
            val modelSuffix = if (subtask.status in listOf("COMPLETED", "FAILED")) {
                val parts = mutableListOf<String>()
                subtask.model?.let { parts.add(it) }
                if (subtask.startedAt != null && subtask.finishedAt != null) {
                    val durationSec = (subtask.finishedAt!! - subtask.startedAt!!) / 1000.0
                    parts.add("${String.format("%.1f", durationSec)}s")
                }
                if (parts.isNotEmpty()) TuiColors.muted(" [${parts.joinToString(" ")}]") else ""
            } else ""

            val line = "$prefix[$orderNum] $statusIcon ${statusStyle(subtask.status.padEnd(9))} ${subtask.description}$dataBadge$modelSuffix"
            buf.addLine(line)

            // Show expanded details for selected step
            if (isSelected && subtask.status in listOf("COMPLETED", "FAILED", "RUNNING")) {
                if (subtask.description.isNotBlank()) {
                    buf.addLine(TuiColors.muted("       Desc: ${subtask.description.take(120)}"))
                }
                subtask.provider?.let {
                    buf.addLine(TuiColors.muted("       Provider: $it"))
                }
                subtask.model?.let {
                    buf.addLine(TuiColors.muted("       Model: $it"))
                }
                if (subtask.tokensIn > 0 || subtask.tokensOut > 0 || subtask.costUsd > 0.0) {
                    val totalTokens = subtask.tokensIn + subtask.tokensOut
                    buf.addLine(TuiColors.muted(
                        "       Tokens: ${subtask.tokensIn} in / ${subtask.tokensOut} out ($totalTokens total)"
                    ))
                    buf.addLine(TuiColors.muted(
                        "       Cost: $${String.format("%.6f", subtask.costUsd)}"
                    ))
                }
                if (subtask.startedAt != null && subtask.finishedAt != null) {
                    val durationSec = (subtask.finishedAt!! - subtask.startedAt!!) / 1000.0
                    buf.addLine(TuiColors.muted("       Duration: ${String.format("%.1f", durationSec)}s"))
                }
                if (subtask.kind.isNotEmpty()) {
                    buf.addLine(TuiColors.muted("       Tool: ${subtask.kind}"))
                }
                subtask.paramsJson?.let {
                    buf.addLine(TuiColors.muted("       Args: ${it.take(150)}"))
                }
                subtask.resultSummary?.let {
                    buf.addLine(TuiColors.muted("       Result: ${it.take(200)}"))
                }
            }

            // Show error for failed steps (always visible, not just when selected)
            if (subtask.status == "FAILED" && subtask.errorMessage != null) {
                buf.addLine(TuiColors.statusFailed("       Error: ${subtask.errorMessage}"))
            }
        }

        // Toolbar hint
        buf.addLine()
        buf.addLine(TuiColors.muted("[a]pprove [s]kip [d]elete [u/j]move [p]ause [r]eplan [R]un [C]ancel-all"))
        return buf
    }

    private fun renderPlanApproval(
        buf: TuiRenderBuffer,
        steps: List<pl.jclab.refio.core.api.SubtaskResponse>,
        width: Int,
        height: Int
    ) {
        val readSteps = steps.count { it.kind.startsWith("read") || it.kind == "grep_search" || it.kind == "file_search" }
        val writeSteps = steps.size - readSteps

        buf.addLine(TuiColors.highlight("=== Plan Approval ==="))
        buf.addLine()
        buf.addLine("${steps.size} steps: $readSteps read-only, $writeSteps write")
        buf.addLine()

        for ((index, step) in steps.withIndex()) {
            if (buf.lineCount >= height - 4) break
            val toolSuffix = if (step.kind.isNotEmpty()) " (${step.kind})" else ""
            buf.addLine("  ${index + 1}. ${step.description}$toolSuffix")
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
