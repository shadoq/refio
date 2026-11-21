package pl.jclab.refio.core.llm.decision

/**
 * Client for "decision models": models that do not write text but answer typed questions about a
 * supplied state with a probability per answer (Ollama `/v1/systemone`, OpenAI `/v1/decisions`).
 *
 * Only yes/no questions are modelled because that is all the callers need; both APIs also offer
 * multiple-choice and score questions.
 */
interface DecisionClient {
    /**
     * Ask every question in [questions] about [state] in one request.
     *
     * @return the probability that each answer is "yes", keyed by question name
     * @throws DecisionCallException when the call fails or an answer is missing
     */
    suspend fun askYesNo(
        model: String,
        state: String,
        questions: Map<String, YesNoQuestion>,
        taskId: String?,
        source: String
    ): DecisionResult
}

/** One yes/no question; [instructions] states what a "yes" means. */
data class YesNoQuestion(val instructions: String)

data class DecisionResult(
    /** Probability (0..1) that the answer is "yes", keyed by question name. */
    val probabilities: Map<String, Double>,
    val inputTokens: Int,
    val latencyMs: Long
)

class DecisionCallException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
