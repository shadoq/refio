package pl.jclab.refio.core.workflow.models

import pl.jclab.refio.core.api.ExecuteStepResponse
import pl.jclab.refio.core.api.PlanningResponse
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.subagents.models.SubagentResult as SubagentResultModel

/**
 * Result of a resolved intent.
 */
sealed interface IntentResult {
    data class ChatResult(val response: ChatResponse) : IntentResult
    data class PlanResult(val response: PlanningResponse) : IntentResult
    data class StepResult(val response: ExecuteStepResponse) : IntentResult
    data class SubagentResult(val response: SubagentResultModel) : IntentResult
    data class AnswerResult(val taskId: String) : IntentResult

    /**
     * Result of asking user for clarification.
     * Contains the question ID for tracking the response.
     */
    data class ClarificationResult(
        val taskId: String,
        val questionId: String,
        val question: String,
        val options: List<String>
    ) : IntentResult

    /**
     * Result of single tool execution.
     * Contains the tool output and metadata.
     */
    data class ToolResult(
        val taskId: String,
        val toolName: String,
        val output: String,
        val success: Boolean
    ) : IntentResult
}
