package pl.jclab.refio.core.api.routers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.longtask.LongTaskCommand
import pl.jclab.refio.core.services.longtask.LongTaskListener
import pl.jclab.refio.core.services.longtask.LongTaskRunner
import pl.jclab.refio.core.services.longtask.LongTaskSpec
import pl.jclab.refio.core.services.longtask.LongTaskState
import pl.jclab.refio.core.services.longtask.LongTaskStateCodec
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.longtask.LongTaskStatusLine
import pl.jclab.refio.core.services.longtask.ProgressFileWriter
import pl.jclab.refio.core.services.longtask.RoundCheck
import pl.jclab.refio.core.services.longtask.RoundFacts
import pl.jclab.refio.core.services.longtask.RoundRecord
import pl.jclab.refio.core.services.maintenance.TaskLease
import pl.jclab.refio.core.services.longtask.TurnRunner
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.turn.TurnEventListener
import kotlin.math.roundToLong

private val logger = dualLogger("LongTaskRouter")

/** Live state of a running long task, for status lines in the TUI and the IDE. */
data class LongTaskProgress(
    val taskId: String,
    val status: LongTaskStatus,
    val round: Int,
    val maxRounds: Int,
    val activeMs: Long,
    val maxMinutes: Long,
    val condition: String,
    val lastCheck: String?,
    /** Ready to show, e.g. "round 2/12 · 47m of 4h · until: npm test · round 1: check failed (exit 1)". */
    val line: String,
)

/**
 * Entry point for sending a message to the agent: a single turn, or a loop of rounds when the task
 * has an end condition (`/until`, or `/goal` with `/long on`). Also holds the per-task settings the
 * `/until` and `/long` commands change.
 */
class LongTaskRouter(
    private val store: TaskStateRepository,
    private val taskRepository: TaskRepository,
    private val configService: ConfigService,
    private val runTurn: suspend (TurnRequest, StreamCallback?, TurnEventListener?) -> TurnResult,
    private val facts: RoundFacts,
    private val checkFactory: (taskId: String, spec: LongTaskSpec) -> RoundCheck,
    private val progressFileFor: (taskId: String) -> ProgressFileWriter? = { null },
    /** The user's last own message in the task's main thread, which a resumed loop works on. */
    private val originalRequest: (taskId: String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCancelled: () -> Boolean = GlobalMetrics::isCancelled,
    private val taskLease: TaskLease? = null,
) : Router {

    private val _progress = MutableStateFlow<Map<String, LongTaskProgress>>(emptyMap())

    /** Long tasks of this project that ran in this process, by task id, with their latest round. */
    val progress: StateFlow<Map<String, LongTaskProgress>> = _progress.asStateFlow()

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    /** Sets the command the task runs until, or removes it when [command] is null. */
    fun setUntil(taskId: String, command: String?) {
        update(taskId) { it.copy(untilCommand = command?.trim()?.takeIf { text -> text.isNotEmpty() }) }
    }

    /** Sets the task's own limits; a null value keeps what is set. Setting one turns the loop on. */
    fun setLimits(taskId: String, maxRounds: Int?, maxMinutes: Long?) {
        update(taskId) { it.copy(maxRounds = maxRounds ?: it.maxRounds, maxMinutes = maxMinutes ?: it.maxMinutes) }
    }

    /** Turns the loop off for the task and forgets its last run; `/goal` keeps its single-turn meaning. */
    fun clear(taskId: String) {
        update(taskId) { LongTaskStateCodec.clearRun(it).copy(untilCommand = null, maxRounds = null, maxMinutes = null) }
        _progress.update { it - taskId }
    }

    /** How the next message of the task runs as a loop, or null when it runs as a single turn. */
    fun spec(taskId: String): LongTaskSpec? {
        val row = store.get(taskId)?.takeIf { enabled(it) } ?: return null
        val goal = goalOf(taskId)
        if (row.untilCommand == null && goal == null) {
            return null
        }
        return specOf(taskId, row, goal)
    }

    /** The task's last or current run, or null when it never ran in rounds. */
    fun state(taskId: String): LongTaskState? {
        val row = store.get(taskId) ?: return null
        return LongTaskStateCodec.toState(row, specOf(taskId, row, goalOf(taskId)))
    }

    /** Applies a parsed `/until` or `/long` command and returns the line to show the user. */
    fun handle(taskId: String, command: LongTaskCommand): String {
        val running = _progress.value[taskId]?.status == LongTaskStatus.RUNNING
        val reply = applyCommand(taskId, command)
        val changes = command !is LongTaskCommand.Status && command !is LongTaskCommand.Invalid
        return if (running && changes) "$reply $RUNNING_LOOP_NOTE" else reply
    }

    private fun applyCommand(taskId: String, command: LongTaskCommand): String = when (command) {
        is LongTaskCommand.Until -> {
            setUntil(taskId, command.command)
            describe(taskId)
        }
        LongTaskCommand.UntilOff -> {
            setUntil(taskId, null)
            "Removed the end command. " + describe(taskId)
        }
        LongTaskCommand.On -> turnOn(taskId)
        LongTaskCommand.Off -> {
            clear(taskId)
            "Long task mode is off: each message runs as a single turn."
        }
        LongTaskCommand.Status -> describe(taskId)
        is LongTaskCommand.Rounds -> {
            setLimits(taskId, command.rounds, null)
            describe(taskId)
        }
        is LongTaskCommand.Hours -> {
            setLimits(taskId, null, maxOf(1L, (command.hours * MINUTES_PER_HOUR).roundToLong()))
            describe(taskId)
        }
        is LongTaskCommand.Invalid -> command.message
    }

    /**
     * Runs [request] as a single turn, or as rounds until the task's condition holds. Only an AGENT
     * turn of the task itself loops: a subagent's turn belongs to its parent's round, and a PLAN or
     * CHAT turn cannot change what the check sees.
     */
    suspend fun runTurnOrLoop(
        request: TurnRequest,
        streamCallback: StreamCallback? = null,
        listener: TurnEventListener? = null,
        longTaskListener: LongTaskListener? = null,
    ): TurnResult {
        val loops = request.mode == TaskMode.AGENT && request.runProfile == TurnRunProfile.DEFAULT &&
            request.profileOverrides?.subagentName == null
        val spec = if (loops) {
            spec(request.taskId)
        } else {
            null
        }
        if (spec == null) {
            return runTurn(request, streamCallback, listener)
        }
        return runLoop(request, spec, null, streamCallback, listener, longTaskListener)
    }

    private suspend fun runLoop(
        request: TurnRequest,
        spec: LongTaskSpec,
        resumeFrom: LongTaskState?,
        streamCallback: StreamCallback?,
        listener: TurnEventListener?,
        longTaskListener: LongTaskListener?,
    ): TurnResult {
        var tokensIn = 0
        var tokensOut = 0
        var cost = 0.0
        val runner = LongTaskRunner(
            turnRunner = TurnRunner { roundRequest ->
                runTurn(roundRequest, streamCallback, listener).also {
                    tokensIn += it.tokensIn
                    tokensOut += it.tokensOut
                    cost += it.cost
                }
            },
            store = store,
            checkFactory = checkFactory,
            progressFile = progressFileFor(request.taskId),
            facts = facts,
            clock = clock,
            isCancelled = isCancelled,
            currentSpec = ::spec,
        )
        val outcome = try {
            // The checks between rounds run outside any turn; holding the task across the whole
            // loop lets the next process repair it even when this one stops during a check.
            val lease = taskLease
            if (lease == null) {
                runner.run(request, spec, Relay(longTaskListener), resumeFrom)
            } else {
                lease.holding(request.taskId) { runner.run(request, spec, Relay(longTaskListener), resumeFrom) }
            }
        } catch (e: Throwable) {
            // A stopped or failed loop must not leave a running round behind for the status bars.
            val saved = runCatching { state(request.taskId) }.getOrNull()
            if (saved != null && saved.status != LongTaskStatus.RUNNING) {
                publish(saved)
            } else {
                _progress.update { it - request.taskId }
            }
            throw e
        }
        publish(outcome.state)
        markTask(outcome.state)
        logger.info {
            "[LONG_TASK] ${request.taskId} ended ${outcome.state.status} after ${outcome.state.roundsDone} rounds" +
                (outcome.state.stopReason?.let { ": $it" } ?: "")
        }
        val last = outcome.lastTurn ?: TurnResult(
            success = false, response = "", iterations = 0, tokensIn = 0, tokensOut = 0, cost = 0.0,
            stopReason = TurnStopReason.EXCEPTION,
        )
        val succeeded = outcome.state.status == LongTaskStatus.SUCCEEDED
        return last.copy(
            success = succeeded,
            incomplete = !succeeded && outcome.state.status != LongTaskStatus.CANCELLED,
            iterations = outcome.state.totalIterations,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            cost = cost,
            stopReason = when (outcome.state.status) {
                LongTaskStatus.SUCCEEDED -> TurnStopReason.COMPLETED
                LongTaskStatus.CANCELLED -> TurnStopReason.CANCELLED
                else -> last.stopReason
            },
        )
    }

    /** True when the task's last loop stopped before its condition held and the condition is still set. */
    fun canResume(taskId: String): Boolean = spec(taskId) != null && state(taskId)?.status in RESUMABLE

    /**
     * Continues the task's last loop with its next round. [request] carries the mode and the model;
     * its text is replaced by the task's original request. Null when there is nothing to resume.
     */
    suspend fun resume(
        request: TurnRequest,
        streamCallback: StreamCallback? = null,
        listener: TurnEventListener? = null,
        longTaskListener: LongTaskListener? = null,
    ): TurnResult? {
        val taskId = request.taskId
        if (request.mode != TaskMode.AGENT) {
            return null
        }
        val spec = spec(taskId) ?: return null
        val saved = state(taskId)?.takeIf { it.status in RESUMABLE } ?: return null
        val original = originalRequest(taskId)?.takeIf { it.isNotBlank() } ?: return null
        return runLoop(request.copy(userInput = original), spec, saved, streamCallback, listener, longTaskListener)
    }

    private fun turnOn(taskId: String): String {
        val row = store.get(taskId)
        if (row?.untilCommand == null && goalOf(taskId) == null) {
            return "Set an end condition first: /until <command> or /goal <condition>."
        }
        update(taskId) { it.copy(maxRounds = it.maxRounds ?: configService.getTyped(ConfigKeys.LONG_TASK_MAX_ROUNDS, taskId)) }
        return describe(taskId)
    }

    /** One line on what the next message of the task will do, and how the last run ended. */
    private fun describe(taskId: String): String {
        val spec = spec(taskId)
        if (spec == null) {
            val row = store.get(taskId)
            return if (row != null && enabled(row)) {
                "Long task mode is on, but there is no end condition yet: set /until <command> or /goal <condition>."
            } else {
                "Long task mode is off. Turn it on with /until <command>, or with /goal <condition> and /long on."
            }
        }
        val time = if (spec.maxMinutes > 0) "${spec.maxMinutes} minutes of work" else "no time limit"
        val last = state(taskId)?.takeIf { it.roundsDone > 0 }?.let { run ->
            " Last run: ${run.status.name.lowercase()} after ${run.roundsDone} rounds" + (run.stopReason?.let { " ($it)" } ?: "") + "."
        }.orEmpty()
        return "Long task mode is on: the next message runs in rounds until ${conditionOf(spec)}, " +
            "up to ${spec.maxRounds} rounds and $time." + last
    }

    private fun specOf(taskId: String, row: TaskStateRow, goal: String?) = LongTaskSpec(
        untilCommand = row.untilCommand,
        goal = goal,
        maxRounds = row.maxRounds ?: configService.getTyped(ConfigKeys.LONG_TASK_MAX_ROUNDS, taskId),
        maxMinutes = row.maxMinutes ?: configService.getTyped(ConfigKeys.LONG_TASK_MAX_MINUTES, taskId),
        maxNoProgressRounds = configService.getTyped(ConfigKeys.LONG_TASK_MAX_NO_PROGRESS_ROUNDS, taskId),
        checkTimeoutSeconds = configService.getTyped(ConfigKeys.LONG_TASK_CHECK_TIMEOUT_SECONDS, taskId),
    )

    private fun enabled(row: TaskStateRow) = row.untilCommand != null || row.maxRounds != null || row.maxMinutes != null

    private fun goalOf(taskId: String): String? =
        runCatching { taskRepository.getCompletionCondition(taskId) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun conditionOf(spec: LongTaskSpec): String =
        spec.untilCommand?.let { "`$it` exits with code 0" } ?: "the goal is met: ${spec.goal}"

    private fun update(taskId: String, change: (TaskStateRow) -> TaskStateRow) {
        store.upsert(change(store.get(taskId) ?: TaskStateRow(taskId)))
    }

    /** A loop that did not reach its condition leaves the task marked as not done. */
    private fun markTask(state: LongTaskState) {
        val status = when (state.status) {
            LongTaskStatus.INCOMPLETE, LongTaskStatus.FAILED -> TaskStatus.INCOMPLETE
            LongTaskStatus.CANCELLED -> TaskStatus.CANCELED
            else -> return
        }
        runCatching { taskRepository.update(state.taskId, status = status) }
            .onFailure { logger.warn { "[LONG_TASK] could not mark task ${state.taskId}: ${it.message}" } }
    }

    private fun publish(state: LongTaskState, round: Int = state.roundsDone) {
        val spec = state.spec
        val entry = LongTaskProgress(
            taskId = state.taskId,
            status = state.status,
            round = round,
            maxRounds = spec.maxRounds,
            activeMs = state.activeMs,
            maxMinutes = spec.maxMinutes,
            condition = spec.untilCommand?.let { "until: $it" } ?: "goal: ${spec.goal}",
            lastCheck = state.rounds.lastOrNull()?.let(::checkLine),
            line = LongTaskStatusLine.of(state, round),
        )
        _progress.update { it + (state.taskId to entry) }
    }

    private fun checkLine(record: RoundRecord): String = when (record.checkPassed) {
        true -> "round ${record.round}: passed"
        false -> "round ${record.round}: failed" + (record.exitCode?.let { " (exit $it)" } ?: "")
        null -> "round ${record.round}: not checked"
    }

    /** Publishes every round to [progress] before passing it on. */
    private inner class Relay(private val outer: LongTaskListener?) : LongTaskListener {
        override fun onRoundStarted(round: Int, state: LongTaskState) {
            publish(state, round)
            outer?.onRoundStarted(round, state)
        }

        override fun onRoundFinished(record: RoundRecord, state: LongTaskState) {
            publish(state)
            outer?.onRoundFinished(record, state)
        }
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
        const val RUNNING_LOOP_NOTE = "The loop that is running now picks this up when its current round ends."

        /** A loop that stopped short of its condition; RUNNING is not here, another process may own it. */
        val RESUMABLE = setOf(LongTaskStatus.INTERRUPTED, LongTaskStatus.CANCELLED, LongTaskStatus.INCOMPLETE, LongTaskStatus.FAILED)
    }
}
