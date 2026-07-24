package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("TurnCompletionGuardian")

/**
 * beforeFinish guardian hook (lesson S03E03 — autonomy & feedback loops).
 *
 * Runs after the LLM produces a final text response (no more tool calls) and before the turn
 * is persisted/closed. A guardian can either let the turn finish ([GuardianDecision.Pass])
 * or push the loop back into another iteration with an injected SYSTEM nudge
 * ([GuardianDecision.Reenter]).
 *
 * This is intentionally separate from [pl.jclab.refio.core.services.TaskVerifier]:
 * - TaskVerifier is a single, optional, LLM-driven check ("did the assistant claim something
 *   that the evidence does not support?").
 * - Guardians are a *list* of cheap, deterministic / domain-specific checks that can each
 *   independently demand re-entry (e.g. "AGENT wrote files but never ran a verification step",
 *   "user asked for X and X is still missing in the response", "no subagent was called for a
 *   delegated task", ...).
 *
 * Re-entry is bounded by [GuardianRegistry.maxReentries] per turn so a misbehaving guardian
 * cannot create an infinite loop.
 */
interface TurnCompletionGuardian {
    /** Stable id used for logging and to count per-guardian re-entries. */
    val name: String

    /**
     * Inspect the turn state.
     *
     * Implementations MUST be idempotent and side-effect-free apart from logging — the same
     * context may be checked again on the next iteration if another guardian re-entered.
     */
    suspend fun check(context: GuardianContext): GuardianDecision
}

/**
 * Snapshot of turn state passed to guardians.
 *
 * Only includes fields that are stable at the natural-completion exit point of
 * [pl.jclab.refio.core.services.AgentTurnLoop]. Adding new fields is a non-breaking change.
 */
data class GuardianContext(
    val taskId: String,
    val mode: TaskMode,
    val runProfile: TurnRunProfile,
    val iteration: Int,
    val maxIterations: Int,
    /** Original user request text, if resolvable from history. */
    val userRequest: String?,
    /** Final assistant text the model is about to send. */
    val finalResponse: String,
    /**
     * Names of tools used during this turn, one entry per call (repeats preserved, in call
     * order). NOT deduplicated — a guardian needs the call count to verify "use tool X N times"
     * requirements (e.g. three `run_terminal_command` calls). Use `.distinct()` at the call site
     * if only the set of names matters.
     */
    val toolsUsed: List<String>,
    /** How many WRITE-mode tools executed in this turn (includes run_terminal_command/run_code). */
    val writeToolsExecutedInTurn: Int,
    /**
     * How many real FILE-producing writes (edit/create) executed this turn - the strict deliverable
     * signal, excluding run_terminal_command/run_code which leave no file. Defaults to 0 so older
     * construction sites stay valid; the deliverable check treats a mkdir-only turn as no deliverable.
     */
    val fileWriteToolsExecutedInTurn: Int = 0,
    /** How many verification (read-only) tool calls happened after the last write. */
    val verificationToolsExecutedAfterWrite: Int,
    /** How many times any guardian has already requested re-entry in this turn. */
    val priorReentries: Int,
    /**
     * Snapshot of [toolsUsed].size at the moment of the most recent guardian re-entry.
     * Zero when no re-entry has happened yet in this turn. Lets a guardian detect that the
     * previous nudge produced no new tool call (i.e. the agent kept emitting plain text)
     * and short-circuit further re-entries instead of wasting tokens on the same loop.
     */
    val toolsUsedSizeAtPriorReentry: Int,
    /**
     * User-defined completion condition for `/goal`-style autonomous workflows. When non-null,
     * [pl.jclab.refio.core.services.turn.NextSpeakerJudgeGuardian] switches from generic
     * "is the turn finished?" judging to goal-aware "has THIS condition been met?" judging.
     * `null` (default) preserves the pre-`/goal` behavior verbatim.
     */
    val completionCondition: String? = null,
    /**
     * False when this is a SUBAGENT run whose profile grants NO file-write / exec tools — its
     * deliverable can ONLY be prose. A read-only subagent that produced a substantial reply HAS
     * delivered; the judge must not re-enter it, because there is no tool call that would "deliver"
     * and re-entry only pushes it to hallucinate tools it does not have (observed 2026-07:
     * security-engineer produced a full report, was re-entered, then looped on an unavailable
     * `think`). True (the safe default) for top-level runs and any subagent that CAN write — those
     * keep the strict "did you actually do something?" re-entry. Safe internal tools
     * (think/tasks/memory) do NOT count as write capability here.
     */
    val subagentHasWriteTools: Boolean = true
)

/**
 * Result of a single guardian check.
 */
sealed class GuardianDecision {
    /** No issue found — let the turn complete. */
    data object Pass : GuardianDecision()

    /**
     * Push the loop back into another iteration.
     *
     * @param nudge SYSTEM message to inject into history. Should be short and instructive.
     * @param reason Short human-readable reason for logging / metrics.
     */
    data class Reenter(val nudge: String, val reason: String) : GuardianDecision()

    /**
     * The guardian determined the turn is NOT complete — the user's request was not delivered —
     * but no further re-entry will help (the single bounded re-entry is spent, or the prior nudge
     * produced no new tool call). The turn still finalizes (the assistant's last text is persisted)
     * but is reported as INCOMPLETE rather than SUCCESS, so an abandoned multi-step task is never
     * silently recorded as done. [reason] is logged.
     */
    data class Incomplete(val reason: String) : GuardianDecision()
}

/**
 * Holds zero or more guardians and runs them in order.
 *
 * The first [GuardianDecision.Reenter] short-circuits the rest, because injecting more than one
 * nudge per iteration would just bloat the prompt — subsequent guardians get a fresh chance on
 * the next iteration.
 */
class GuardianRegistry(
    private val guardians: List<TurnCompletionGuardian> = emptyList(),
    /** Hard cap on guardian-driven re-entries per turn. */
    val maxReentries: Int = DEFAULT_MAX_REENTRIES
) {
    val isEmpty: Boolean get() = guardians.isEmpty()

    /**
     * Run all guardians sequentially. Returns the first non-[GuardianDecision.Pass] decision,
     * or [GuardianDecision.Pass] if every guardian agrees the turn is done.
     *
     * Per-guardian exceptions are caught and treated as Pass — a broken guardian must never
     * block a turn from finishing.
     */
    suspend fun runChecks(context: GuardianContext): GuardianDecision {
        if (guardians.isEmpty()) return GuardianDecision.Pass
        if (context.priorReentries >= maxReentries) {
            logger.info {
                "[GUARDIAN] taskId=${context.taskId} re-entry budget exhausted " +
                    "(${context.priorReentries}/$maxReentries) — letting turn finish"
            }
            return GuardianDecision.Pass
        }
        for (guardian in guardians) {
            val decision = try {
                guardian.check(context)
            } catch (e: Exception) {
                logger.warn(e) { "[GUARDIAN] guardian=${guardian.name} threw — treating as Pass: ${e.message}" }
                GuardianDecision.Pass
            }
            if (decision is GuardianDecision.Reenter) {
                logger.info {
                    "[GUARDIAN] taskId=${context.taskId} guardian=${guardian.name} requested re-entry " +
                        "(${context.priorReentries + 1}/$maxReentries): ${decision.reason}"
                }
                return decision
            }
            if (decision is GuardianDecision.Incomplete) {
                logger.info {
                    "[GUARDIAN] taskId=${context.taskId} guardian=${guardian.name} marked turn INCOMPLETE: ${decision.reason}"
                }
                return decision
            }
        }
        return GuardianDecision.Pass
    }

    companion object {
        const val DEFAULT_MAX_REENTRIES = 2
    }
}
