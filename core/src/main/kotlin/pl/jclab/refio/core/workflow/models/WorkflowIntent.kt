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
}
