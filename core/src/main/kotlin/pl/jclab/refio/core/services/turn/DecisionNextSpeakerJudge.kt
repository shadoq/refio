package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.decision.DecisionClient
import pl.jclab.refio.core.llm.decision.YesNoQuestion

/**
 * "Is the agent really done?" answered by a decision model instead of a chat model. The model
 * gets the same facts the LLM judge reads and three narrow yes/no questions; each answer is a
 * probability that something is still wrong, so the strongest one is the probability that the
 * agent should continue.
 *
 * Three narrow questions instead of one broad "is it finished?": on a 24-case set of real judge
 * inputs the broad question let through a reply that claimed "the project compiles" with no build
 * tool in the transcript, while the dedicated "claim without evidence" question caught it. The
 * same set showed a 0.8B model is not up to three questions, so the model choice matters.
 *
 * Generic mode only: a free-form `/goal` condition cannot be turned into fixed questions, so the
 * goal-aware judge stays on the LLM.
 */
class DecisionNextSpeakerJudge(private val clientFor: (provider: String) -> DecisionClient) {

    /** [pContinue] is the largest of [probabilities]; [strongest] names the question that gave it. */
    data class Outcome(
        val pContinue: Double,
        val strongest: String,
        val probabilities: Map<String, Double>,
        val inputTokens: Int,
        val latencyMs: Long
    )

    enum class Step { CONTINUE, FINISHED, ASK_LLM }

    suspend fun judge(provider: String, model: String, state: String, taskId: String?): Outcome {
        val result = clientFor(provider).askYesNo(model, state, QUESTIONS, taskId, SOURCE)
        val (strongest, pContinue) = result.probabilities.entries.maxBy { it.value }
        return Outcome(pContinue, strongest, result.probabilities, result.inputTokens, result.latencyMs)
    }

    companion object {
        /** Same billing source as the LLM judge, so both show up together in the API log. */
        const val SOURCE = "NextSpeakerJudge"

        const val Q_MISSING = "missing"
        const val Q_ANNOUNCES = "announces"
        const val Q_UNPROVEN = "unproven"

        val QUESTIONS: Map<String, YesNoQuestion> = linkedMapOf(
            Q_MISSING to YesNoQuestion(
                "List every distinct deliverable the user request asks for (a file, an edit, a test, a command output, " +
                    "an answer). Is at least one of them still missing, i.e. neither present in the agent's reply nor produced " +
                    "by the tools used? A requested file or test counts as produced only if a write tool " +
                    "(create_new_file, code_editing, multi_edit, advance_code_editing) was used. " +
                    "In PLAN mode the agent cannot edit files: there the deliverable is the plan text itself."
            ),
            Q_ANNOUNCES to YesNoQuestion(
                "Does the agent's reply end by announcing an action it has not performed yet, " +
                    "such as 'Let me...', 'Next I'll...', 'I will now...', 'Moving on to...'?"
            ),
            Q_UNPROVEN to YesNoQuestion(
                "Does the agent's reply claim a result (tests pass, the project compiles, a file was written) " +
                    "that none of the listed tools could have produced? Running tests or a build requires run_terminal_command."
            )
        )

        /**
         * The situation the decision model reads: the LLM judge's user block plus the mode, which
         * tells it that in PLAN the plan text is the deliverable.
         */
        internal fun buildState(
            mode: TaskMode,
            userRequest: String?,
            boundedReply: String,
            renderedTools: String,
            iteration: Int,
            maxIterations: Int
        ): String = buildString {
            append("Mode: ").append(mode.name).append("\n\n")
            append("User request:\n")
            append(userRequest?.take(800) ?: "(unknown)")
            append("\n\nAgent's last reply (no tool calls were issued):\n")
            append(boundedReply)
            append("\n\nTools used so far in this turn: ")
            append(renderedTools)
            append("\nIteration: $iteration/$maxIterations")
        }

        /**
         * What to do with a "continue" probability. `decision`: continue at or above [threshold].
         * `hybrid`: trust the decision model outside the band and ask the LLM inside it (edges
         * included); a band entered the wrong way round is read with its edges swapped.
         */
        fun decisionStep(hybrid: Boolean, pContinue: Double, threshold: Double, bandLow: Double, bandHigh: Double): Step {
            if (!hybrid) {
                return if (pContinue >= threshold) Step.CONTINUE else Step.FINISHED
            }
            val low = minOf(bandLow, bandHigh)
            val high = maxOf(bandLow, bandHigh)
            return when {
                pContinue < low -> Step.FINISHED
                pContinue > high -> Step.CONTINUE
                else -> Step.ASK_LLM
            }
        }
    }
}
