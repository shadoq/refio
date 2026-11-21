package pl.jclab.refio.core.services.longtask

import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.utils.GsonInstance

/** Maps a [LongTaskState] to the run columns of a task's state row and back. */
internal object LongTaskStateCodec {

    /** [row] with the run columns taken from [state]; the task's settings and plan stay. */
    fun applyTo(row: TaskStateRow, state: LongTaskState): TaskStateRow = row.copy(
        loopStatus = state.status.name,
        roundsDone = state.roundsDone,
        noProgressRounds = state.noProgressRounds,
        totalIterations = state.totalIterations,
        activeMs = state.activeMs,
        roundsJson = GsonInstance.gson.toJson(state.rounds),
        stopReason = state.stopReason,
        lastCheckTail = state.lastCheckTail,
    )

    /** The saved run of [row], or null when the task never ran as a long task. */
    fun toState(row: TaskStateRow, spec: LongTaskSpec): LongTaskState? {
        val status = row.loopStatus?.let { name -> LongTaskStatus.values().firstOrNull { it.name == name } } ?: return null
        return LongTaskState(
            taskId = row.taskId,
            spec = spec,
            status = status,
            roundsDone = row.roundsDone,
            noProgressRounds = row.noProgressRounds,
            totalIterations = row.totalIterations,
            activeMs = row.activeMs,
            rounds = rounds(row.roundsJson),
            stopReason = row.stopReason,
            lastCheckTail = row.lastCheckTail,
        )
    }

    /** [row] with the run forgotten; the task's settings stay. */
    fun clearRun(row: TaskStateRow): TaskStateRow = row.copy(
        loopStatus = null, roundsDone = 0, noProgressRounds = 0, totalIterations = 0, activeMs = 0,
        roundsJson = null, stopReason = null, lastCheckTail = null,
    )

    /** The rounds stored in a row's rounds column; empty when none or unreadable. */
    fun rounds(json: String?): List<RoundRecord> {
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching { GsonInstance.gson.fromJson(json, Array<RoundRecord>::class.java)?.toList() }.getOrNull() ?: emptyList()
    }
}
