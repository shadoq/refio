package pl.jclab.refio.core.services.longtask

import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.turn.HarnessMessageTypes
import kotlin.coroutines.cancellation.CancellationException

private val logger = dualLogger("LongTaskRunner")

/** Runs one turn of the agent. */
fun interface TurnRunner {
    suspend fun run(request: TurnRequest): TurnResult
}

/** Round events for status lines. Called on the loop's coroutine; keep them short. */
interface LongTaskListener {
    fun onRoundStarted(round: Int, state: LongTaskState) {}
    fun onRoundFinished(record: RoundRecord, state: LongTaskState) {}
}

/** What the runner needs to know about a round beyond the turn's own result. */
interface RoundFacts {
    /** Successful file changes in the task, by any agent, since [sinceMs]. */
    fun filesWritten(taskId: String, sinceMs: Long): Int

    /** The main agent's plan. */
    fun planSteps(taskId: String): List<AgentPlanStep>

    /** Housekeeping after each round, such as pruning old file snapshots. */
    fun afterRound(taskId: String) {}
}

data class LongTaskOutcome(val state: LongTaskState, val lastTurn: TurnResult?)

/**
 * Runs a task turn after turn until its condition holds: a command that exits with 0, or a goal the
 * end-of-turn judge finds met. Each round is one turn plus one check; the loop also ends after
 * [LongTaskSpec.maxRounds] rounds, [LongTaskSpec.maxMinutes] minutes of work, or
 * [LongTaskSpec.maxNoProgressRounds] rounds in a row that moved nothing.
 */
class LongTaskRunner(
    private val turnRunner: TurnRunner,
    private val store: TaskStateRepository,
    private val checkFactory: (taskId: String, spec: LongTaskSpec) -> RoundCheck,
    private val progressFile: ProgressFileWriter?,
    private val facts: RoundFacts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCancelled: () -> Boolean = GlobalMetrics::isCancelled,
    /**
     * The task's settings as they are now, read before every round, so `/until`, `/long` and `/goal`
     * typed while the loop runs apply from its next round; null once the user turned the mode off.
     * Without it the loop keeps the settings it started with.
     */
    private val currentSpec: ((taskId: String) -> LongTaskSpec?)? = null,
) {
    /**
     * Runs rounds for [request] until the loop ends. A new message starts counting from round 1;
     * [resumeFrom] continues a saved run with its rounds and working time, starting with the next
     * round instead of the user's message again.
     */
    suspend fun run(
        request: TurnRequest,
        spec: LongTaskSpec,
        listener: LongTaskListener? = null,
        resumeFrom: LongTaskState? = null,
    ): LongTaskOutcome {
        val taskId = request.taskId
        val extra = resumeFrom?.let { Allowance.after(it, spec) } ?: Allowance.NONE
        var check = checkFactory(taskId, spec)
        var state = resumeFrom?.copy(spec = extra.applyTo(spec), status = LongTaskStatus.RUNNING, stopReason = null)
            ?: LongTaskState(taskId = taskId, spec = spec, status = LongTaskStatus.RUNNING)
        var lastTurn: TurnResult? = null
        save(state)
        while (state.status == LongTaskStatus.RUNNING) {
            val latest = (if (currentSpec == null) spec else currentSpec.invoke(taskId))?.let(extra::applyTo)
            if (latest == null) {
                state = state.copy(status = LongTaskStatus.INCOMPLETE, stopReason = TURNED_OFF)
                break
            }
            if (latest.untilCommand != state.spec.untilCommand || latest.goal != state.spec.goal) {
                check = checkFactory(taskId, latest)
            }
            state = state.copy(spec = latest)
            val limit = limitReached(state)
            if (limit != null || isCancelled()) {
                state = if (limit != null) {
                    state.copy(status = LongTaskStatus.INCOMPLETE, stopReason = limit)
                } else {
                    state.copy(status = LongTaskStatus.CANCELLED, stopReason = STOPPED)
                }
                break
            }
            val round = state.roundsDone + 1
            listener?.onRoundStarted(round, state)
            check.beforeRound()
            val startedAt = clock()
            val turnRequest = if (resumeFrom == null && round == 1) {
                request.copy(userInput = LongTaskPrompts.firstRound(request.userInput, state.spec), inputType = null)
            } else {
                request.copy(
                    userInput = LongTaskPrompts.nextRound(request.userInput, state, safeSteps(taskId)),
                    inputType = HarnessMessageTypes.LONG_TASK_ROUND,
                    userContextRefs = emptyList(),
                )
            }
            val turn = try {
                turnRunner.run(turnRequest)
            } catch (e: CancellationException) {
                throw stopped(state, round, startedAt, e)
            } catch (e: Exception) {
                logger.warn { "[LONG_TASK] round $round of $taskId failed: ${e.message}" }
                null
            }
            lastTurn = turn ?: lastTurn
            // Stop often lands during the check, the longest part of many rounds.
            state = try {
                finishRound(state, round, startedAt, turn, check, listener)
            } catch (e: CancellationException) {
                throw stopped(state, round, startedAt, e)
            }
        }
        save(state)
        writeProgressFile(state)
        return LongTaskOutcome(state, lastTurn)
    }

    /** Checks the condition after the round's turn, records the round and decides whether the loop goes on. */
    private suspend fun finishRound(
        state: LongTaskState,
        round: Int,
        startedAt: Long,
        turn: TurnResult?,
        check: RoundCheck,
        listener: LongTaskListener?,
    ): LongTaskState {
        val stopped = turn?.stopReason == TurnStopReason.CANCELLED || isCancelled()
        val checkable = turn != null && !stopped && turn.stopReason !in UNCHECKED
        val result = if (checkable && turn != null) check.check(turn) else null
        val steps = safeSteps(state.taskId)
        val draft = RoundRecord(
            round = round,
            durationMs = clock() - startedAt,
            iterations = turn?.iterations ?: 0,
            turnStopReason = (turn?.stopReason ?: TurnStopReason.EXCEPTION).name,
            checkPassed = result?.passed,
            exitCode = result?.exitCode,
            errorLines = result?.errorLines,
            filesWritten = runCatching { facts.filesWritten(state.taskId, startedAt) }.getOrDefault(0),
            planStepsDone = steps.count { it.status == PlanStepStatus.COMPLETED },
            progress = false,
        )
        val record = draft.copy(progress = checkable && RoundProgress.madeProgress(state.rounds.lastOrNull(), draft))
        val counted = state.copy(
            roundsDone = round,
            totalIterations = state.totalIterations + record.iterations,
            activeMs = state.activeMs + record.durationMs,
            noProgressRounds = if (record.progress) 0 else state.noProgressRounds + 1,
            rounds = state.rounds + record,
            lastCheckTail = result?.tail ?: state.lastCheckTail,
        )
        val next = when {
            stopped -> counted.copy(status = LongTaskStatus.CANCELLED, stopReason = STOPPED)
            result?.passed == true -> counted.copy(status = LongTaskStatus.SUCCEEDED)
            turn?.stopReason == TurnStopReason.COST_LIMIT -> counted.copy(status = LongTaskStatus.INCOMPLETE, stopReason = "cost limit reached")
            turn?.stopReason == TurnStopReason.DENIED_TOOL -> counted.copy(status = LongTaskStatus.INCOMPLETE, stopReason = "a tool call was rejected")
            counted.noProgressRounds >= state.spec.maxNoProgressRounds ->
                counted.copy(status = LongTaskStatus.INCOMPLETE, stopReason = "no progress in ${counted.noProgressRounds} rounds in a row")
            else -> counted
        }
        save(next)
        writeProgressFile(next)
        listener?.onRoundFinished(record, next)
        runCatching { facts.afterRound(state.taskId) }.onFailure { logger.warn { "[LONG_TASK] after-round cleanup failed: ${it.message}" } }
        return next
    }

    /** Saves the loop as stopped by the user in [round], so it can be continued, and returns [e] to rethrow. */
    private fun stopped(state: LongTaskState, round: Int, startedAt: Long, e: CancellationException): CancellationException {
        save(state.copy(status = LongTaskStatus.CANCELLED, roundsDone = round, activeMs = state.activeMs + clock() - startedAt, stopReason = STOPPED))
        return e
    }

    private fun limitReached(state: LongTaskState): String? {
        val spec = state.spec
        return when {
            state.roundsDone >= spec.maxRounds -> "round limit of ${spec.maxRounds} reached"
            spec.maxMinutes > 0 && state.activeMs >= spec.maxMinutes * MS_PER_MINUTE -> "time limit of ${spec.maxMinutes} minutes reached"
            else -> null
        }
    }

    /** Saves the run over the task's row, keeping the settings and the plan the row already holds. */
    private fun save(state: LongTaskState) {
        runCatching {
            store.upsert(LongTaskStateCodec.applyTo(store.get(state.taskId) ?: TaskStateRow(state.taskId), state))
        }.onFailure { logger.warn { "[LONG_TASK] could not save the state of ${state.taskId}: ${it.message}" } }
    }

    private fun writeProgressFile(state: LongTaskState) {
        val writer = progressFile ?: return
        runCatching { writer.write(state, state.spec.goal, safeSteps(state.taskId)) }
            .onFailure { logger.warn { "[LONG_TASK] could not write the progress file: ${it.message}" } }
    }

    private fun safeSteps(taskId: String): List<AgentPlanStep> = runCatching { facts.planSteps(taskId) }.getOrDefault(emptyList())

    /** Rounds and minutes added to the limits of a continued loop. */
    private data class Allowance(val rounds: Int, val minutes: Long) {
        fun applyTo(spec: LongTaskSpec): LongTaskSpec = if (this == NONE) {
            spec
        } else {
            spec.copy(
                maxRounds = spec.maxRounds + rounds,
                maxMinutes = if (spec.maxMinutes > 0) spec.maxMinutes + minutes else 0,
            )
        }

        companion object {
            val NONE = Allowance(0, 0)

            /**
             * A loop continued after it used up its rounds or its working time gets as much again:
             * on the same limits it would end at once, and Continue would do nothing.
             */
            fun after(saved: LongTaskState, spec: LongTaskSpec) = Allowance(
                rounds = if (saved.roundsDone >= spec.maxRounds) saved.roundsDone else 0,
                minutes = if (spec.maxMinutes > 0 && saved.activeMs >= spec.maxMinutes * MS_PER_MINUTE) {
                    (saved.activeMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE
                } else {
                    0
                },
            )
        }
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val STOPPED = "stopped by the user"
        const val TURNED_OFF = "long task mode was turned off"

        /** A turn that ended on an error or a rejected tool did no work worth checking. */
        val UNCHECKED = setOf(TurnStopReason.EXCEPTION, TurnStopReason.DENIED_TOOL)
    }
}
