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

}
