package pl.jclab.refio.ui.components.steps

import pl.jclab.refio.core.api.SubtaskResponse

/**
 * One execution step, reduced to what a single 26 px row shows.
 *
 * A step used to occupy a ~95 px card that repeated its own duration twice, so seven steps filled
 * the screen and the plan as a whole was invisible. Everything below is precomputed for the
 * renderer; the full payload stays behind the details dialog.
 */
data class StepRowView(
    val id: String,
    val number: Int,
    val kind: String,
    val description: String,
    val state: State,
    val statusName: String,
    val durationMs: Long,
    val errorMessage: String?,
    val canApprove: Boolean,
    val canSkip: Boolean
) {

    enum class State { OK, FAILED, RUNNING, PENDING, SKIPPED }

    val title: String
        get() = description.ifBlank { kind }

    companion object {

        private val SKIPPABLE = setOf("PENDING_APPROVAL", "PENDING", "PLANNED")

        fun from(subtask: SubtaskResponse, number: Int): StepRowView = StepRowView(
            id = subtask.id,
            number = number,
            kind = subtask.kind,
            description = subtask.description,
            state = stateOf(subtask.status),
            statusName = subtask.status.uppercase(),
            durationMs = durationOf(subtask),
            errorMessage = subtask.errorMessage?.takeIf { it.isNotBlank() },
            canApprove = subtask.status == "PENDING_APPROVAL",
            canSkip = subtask.status in SKIPPABLE
        )

        fun stateOf(status: String): State = when (status.uppercase()) {
            "SUCCESS" -> State.OK
            "FAILED" -> State.FAILED
            "RUNNING" -> State.RUNNING
            "SKIPPED", "CANCELED" -> State.SKIPPED
            else -> State.PENDING
        }

        /**
         * Wall-clock time of the step. A step that never finished contributes nothing, so the
         * plan total stays the sum of work actually done.
         */
        fun durationOf(subtask: SubtaskResponse): Long {
            val startedAt = subtask.startedAt ?: return 0L
            val finishedAt = subtask.completedAt ?: subtask.finishedAt ?: return 0L
            return (finishedAt - startedAt).coerceAtLeast(0L)
        }

        fun formatDuration(millis: Long): String = when {
            millis <= 0 -> ""
            millis < 1000 -> "${millis}ms"
            millis < 60_000 -> String.format("%.1fs", millis / 1000.0)
            else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
        }
    }
}

/**
 * Plan-level roll-up shown above the step list: how many steps, how many succeeded or failed,
 * and how long the whole thing took.
 */
data class PlanSummaryModel(
    val total: Int,
    val ok: Int,
    val failed: Int,
    val running: Int,
    val totalDurationMs: Long
) {

    val hasFailures: Boolean get() = failed > 0

    companion object {

        val EMPTY = PlanSummaryModel(0, 0, 0, 0, 0L)

        fun from(steps: List<StepRowView>): PlanSummaryModel = PlanSummaryModel(
            total = steps.size,
            ok = steps.count { it.state == StepRowView.State.OK },
            failed = steps.count { it.state == StepRowView.State.FAILED },
            running = steps.count { it.state == StepRowView.State.RUNNING },
            totalDurationMs = steps.sumOf { it.durationMs }
        )
    }
}
