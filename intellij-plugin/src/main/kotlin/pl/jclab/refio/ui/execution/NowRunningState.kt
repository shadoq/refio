package pl.jclab.refio.ui.execution

import pl.jclab.refio.core.api.routers.LongTaskProgress
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.turn.TurnPhase
import pl.jclab.refio.core.services.turn.TurnStateSnapshot

/**
 * What the "now running" bar shows for a given turn state.
 *
 * Kept as a pure mapping so the rules - when the bar appears, whether the spinner animates and
 * what the user reads while a tool runs - can be checked without a UI.
 */
data class NowRunningState(
    val visible: Boolean,
    val busy: Boolean,
    val stepText: String,
    val detailText: String,
    val roundText: String = "",
) {

    companion object {

        val HIDDEN = NowRunningState(visible = false, busy = false, stepText = "", detailText = "")

        /** [longTask] is the session's long task, if any; only a running one shows up in the bar. */
        fun from(snapshot: TurnStateSnapshot, longTask: LongTaskProgress? = null): NowRunningState {
            val round = longTask?.takeIf { it.status == LongTaskStatus.RUNNING }
            val roundText = round?.line?.replaceFirstChar { it.uppercase() } ?: ""
            val phase = snapshot.phase
            if (phase == TurnPhase.IDLE || phase == TurnPhase.COMPLETED || phase == TurnPhase.FAILED) {
                // Between two rounds no turn runs while the harness checks the end condition, which
                // can take minutes; the bar stays up so the task does not look finished.
                if (round == null) {
                    return HIDDEN
                }
                return NowRunningState(
                    visible = true,
                    busy = true,
                    stepText = "",
                    detailText = "checking ${round.condition}",
                    roundText = roundText
                )
            }

            // Waiting phases are stalled on the user, not on the engine, so the spinner stops
            // while the bar stays up to explain why nothing is moving.
            val busy = phase != TurnPhase.WAITING_FOR_PERMISSION && phase != TurnPhase.WAITING_FOR_USER

            val stepText = if (snapshot.maxIterations > 0) {
                "Step ${snapshot.iteration}/${snapshot.maxIterations}"
            } else {
                "Step ${snapshot.iteration}"
            }

            return NowRunningState(
                visible = true,
                busy = busy,
                stepText = stepText,
                detailText = detailFor(snapshot),
                roundText = roundText
            )
        }

        private fun detailFor(snapshot: TurnStateSnapshot): String = when (snapshot.phase) {
            TurnPhase.EXECUTING_TOOLS -> snapshot.activeToolName ?: "running tools"
            TurnPhase.CALLING_MODEL -> "calling model"
            TurnPhase.BUILDING_PROMPT -> "building prompt"
            TurnPhase.FINALIZING -> "finalizing"
            TurnPhase.WAITING_FOR_PERMISSION -> "waiting for approval"
            TurnPhase.WAITING_FOR_USER -> "waiting for you"
            else -> snapshot.phase.name.lowercase().replace('_', ' ')
        }

        /** Compact elapsed time; a turn running for an hour still fits the bar. */
        fun formatElapsed(millis: Long): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
