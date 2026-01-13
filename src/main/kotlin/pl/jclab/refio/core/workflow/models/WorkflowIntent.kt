package pl.jclab.refio.core.workflow.models

import pl.jclab.refio.api.models.ContextReference

/**
 * Intent resolved for a workflow turn.
 */
sealed interface WorkflowIntent {
    val taskId: String

    data class Chat(
        override val taskId: String,
        val input: String,
        val contextRefs: List<ContextReference>,
        val model: String?,
        val provider: String?
    ) : WorkflowIntent

    data class Plan(
        override val taskId: String,
        val input: String,
        val contextRefs: List<ContextReference>,
        val model: String?,
        val provider: String?,
        val interactive: Boolean
    ) : WorkflowIntent

    data class ExecuteStep(
        override val taskId: String,
        val subtaskId: String
    ) : WorkflowIntent

    data class Subagent(
        override val taskId: String,
        val name: String,
        val prompt: String,
        val contextRefs: List<ContextReference>,
        val model: String?,
        val provider: String?
    ) : WorkflowIntent

    data class AnswerQuestion(
        override val taskId: String,
        val questionId: String,
        val answer: String
    ) : WorkflowIntent

    /**
     * Ask user for clarification before proceeding.
     * Used when intent classifier determines request is ambiguous.
     */
    data class AskClarification(
        override val taskId: String,
        val question: String,
        val options: List<String> = emptyList(),
        val reasoning: String
    ) : WorkflowIntent

    /**
     * Execute a single tool directly without creating a plan.
     * Used for simple read operations that don't require multi-step execution.
     */
    data class ExecuteTool(
        override val taskId: String,
        val toolName: String,
        val toolArgs: Map<String, Any>,
        val reasoning: String,
        val contextRefs: List<ContextReference> = emptyList()
    ) : WorkflowIntent
}
