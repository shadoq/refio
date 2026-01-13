package pl.jclab.refio.core.workflow

/**
 * Result of LLM-based intent classification.
 *
 * Represents the decision made by the intent classifier about how to handle user input.
 * See docs/features/0017-new-workflow.md for specification.
 */
sealed interface IntentClassificationResult {
    val reasoning: String

    /**
     * User asked a question that can be answered without using any tools.
     * Route to Chat executor for direct LLM response.
     */
    data class ChatResponse(
        override val reasoning: String
    ) : IntentClassificationResult

    /**
     * Request is ambiguous or missing critical information.
     * Ask the user for clarification before proceeding.
     */
    data class ClarificationNeeded(
        override val reasoning: String,
        val question: String,
        val options: List<String> = emptyList()
    ) : IntentClassificationResult

    /**
     * Task requires exactly one tool execution.
     * Execute the tool directly without creating a plan.
     */
    data class SingleTool(
        override val reasoning: String,
        val toolName: String,
        val toolArgs: Map<String, Any>
    ) : IntentClassificationResult

    /**
     * Task requires multiple steps or code modifications.
     * Route to Plan executor to create execution plan.
     */
    data class MultiStepPlan(
        override val reasoning: String
    ) : IntentClassificationResult
}
