package pl.jclab.refio.core.services.longtask

import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.services.turn.HarnessMessageTypes

/** The messages that start the rounds of a long task. */
internal object LongTaskPrompts {
    private const val REQUEST_CHARS = 2_000
    private const val MAX_OPEN_STEPS = 10
    private val OPEN = setOf(PlanStepStatus.PENDING, PlanStepStatus.IN_PROGRESS, PlanStepStatus.FAILED)

    /**
     * Why many rounds add up instead of each starting over: a plan of small steps that a command can
     * check, worked through one step at a time.
     */
    private const val PLAN_FIRST =
        "Before changing anything, make a plan with the `tasks` tool (action \"plan\"): small steps in order, " +
            "each one you can check with a command such as a build or a test, with that command in the step's " +
            "description. Then work through the steps in order, run each step's check, and mark a step completed " +
            "with `tasks` (action \"update\") only after its check passes."

    /** How round 1 sets its note apart from the user's words. */
    private val ROUND_ONE_NOTE = "\n\n" + HarnessMessageTypes.PREFIX + " "

    /**
     * The request a resumed loop works on, from the user's messages in the task's main thread,
     * oldest first: the latest message that started a loop, without the note round 1 added to it.
     * A message typed while the loop ran is not the request, and the note would ask a resumed round
     * to plan from scratch. Without such a message, the last one.
     */
    fun originalRequest(userMessages: List<String>): String? {
        userMessages.asReversed().forEach { text ->
            val noteAt = text.lastIndexOf(ROUND_ONE_NOTE)
            if (noteAt >= 0 && text.endsWith(PLAN_FIRST)) {
                return text.substring(0, noteAt)
            }
        }
        return userMessages.lastOrNull()
    }

    /** Round 1: the user's own message, with a note on how the task ends and how to work through it. */
    fun firstRound(userInput: String, spec: LongTaskSpec): String =
        userInput.trimEnd() + "\n\n" + HarnessMessageTypes.PREFIX + " " + condition(spec) +
            " The harness checks this after each of your turns and starts another round while it does not hold, " +
            "up to ${spec.maxRounds} rounds. " + PLAN_FIRST

    /**
     * Every later round, and the first round of a resumed loop: what was asked, how the last check
     * went and what is still open. Stored as a harness message, so the user's own words stay apart.
     */
    fun nextRound(userInput: String, state: LongTaskState, steps: List<AgentPlanStep>): String = buildString {
        val spec = state.spec
        appendLine("Round ${state.roundsDone + 1} of ${spec.maxRounds} of a long task. ${condition(spec)}")
        appendLine()
        appendLine("The request:")
        appendLine(userInput.trim().let { if (it.length > REQUEST_CHARS) it.take(REQUEST_CHARS) + " [...]" else it })
        appendLine()
        val last = state.rounds.lastOrNull()
        when {
            last == null -> appendLine("The previous run of this task stopped before its round ended.")
            last.checkPassed == null -> appendLine("Round ${last.round} ended before its check ran (${last.turnStopReason.lowercase()}).")
            spec.untilCommand != null -> appendLine(
                "After round ${last.round} the check still fails" +
                    (last.exitCode?.let { " (exit code $it)" } ?: "") + ". End of its output:"
            )
            else -> appendLine("After round ${last.round} the goal is not met yet.")
        }
        state.lastCheckTail?.takeIf { it.isNotBlank() && last?.checkPassed != null }?.let {
            appendLine("```")
            appendLine(it.trimEnd())
            appendLine("```")
        }
        val open = steps.filter { it.status in OPEN }
        if (open.isNotEmpty()) {
            appendLine()
            appendLine("Open plan steps:")
            open.take(MAX_OPEN_STEPS).forEach { appendLine("- ${it.status.icon} ${it.index + 1}. ${it.title}") }
            if (open.size > MAX_OPEN_STEPS) {
                appendLine("- and ${open.size - MAX_OPEN_STEPS} more")
            }
        }
        appendLine()
        val next = open.firstOrNull()
        when {
            steps.isEmpty() -> append(
                "There is no plan yet: make one with the `tasks` tool first, small steps each checkable with a command. "
            )
            next != null -> append("Work on step ${next.index + 1} (${next.title}) first and run its check before marking it completed. ")
            else -> append(
                "All plan steps are marked completed, but the condition does not hold: find what is still missing, " +
                    "add it to the plan as a step and do it. "
            )
        }
        append(
            "Continue from where the work stopped; .refio/progress.md lists the rounds so far. " +
                "End your turn when you believe the condition holds."
        )
    }

    private fun condition(spec: LongTaskSpec): String = when {
        spec.untilCommand != null -> "The task is done when `${spec.untilCommand}` exits with code 0."
        else -> "The task is done when this goal is met: ${spec.goal}"
    }
}
