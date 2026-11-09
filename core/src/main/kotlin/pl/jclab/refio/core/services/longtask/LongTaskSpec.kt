package pl.jclab.refio.core.services.longtask

/**
 * A task that runs turn after turn until a condition holds. A round is one turn of the agent plus
 * one check of the condition: the [untilCommand] exits with 0, or (without a command) the judge at
 * the end of the turn found the [goal] delivered.
 */
data class LongTaskSpec(
    val untilCommand: String?,
    val goal: String?,
    val maxRounds: Int,
    val maxMinutes: Long,
    val maxNoProgressRounds: Int,
    val checkTimeoutSeconds: Int,
)

enum class LongTaskStatus { RUNNING, SUCCEEDED, INCOMPLETE, FAILED, CANCELLED, INTERRUPTED }

/** What one round did and how its check went. */
data class RoundRecord(
    val round: Int,
    val durationMs: Long,
    val iterations: Int,
    val turnStopReason: String,
    /** Null when the round ended before its check ran. */
    val checkPassed: Boolean?,
    val exitCode: Int?,
    val errorLines: Int?,
    val filesWritten: Int,
    val planStepsDone: Int,
    val progress: Boolean,
)

/** Where a long task stands; saved after every round. */
data class LongTaskState(
    val taskId: String,
    val spec: LongTaskSpec,
    val status: LongTaskStatus,
    val roundsDone: Int = 0,
    val noProgressRounds: Int = 0,
    val totalIterations: Int = 0,
    /** Time spent in rounds; waiting between rounds and time with the process closed do not count. */
    val activeMs: Long = 0,
    val rounds: List<RoundRecord> = emptyList(),
    val stopReason: String? = null,
    val lastCheckTail: String? = null,
)
