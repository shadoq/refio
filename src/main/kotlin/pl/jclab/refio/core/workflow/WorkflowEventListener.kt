package pl.jclab.refio.core.workflow

import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent

/**
 * Workflow event listener for UI updates.
 */
interface WorkflowEventListener {
    fun onDecisionPhase() {}
    fun onExecutionPhase(intent: WorkflowIntent) {}

    // Intent classification events (LLM-based decision making)
    fun onIntentClassificationStarted(model: String, mode: String) {}
    fun onIntentClassificationResult(result: IntentClassificationResult) {}
    fun onReflectionPhase() {}

    fun onStreamChunk(chunk: String) {}
    fun onStreamComplete(content: String) {}

    fun onChatStarted() {}
    fun onToolStarted(toolName: String) {}
    fun onSubagentStarted(subagentName: String) {}
    fun onPlanningStarted() {}
    fun onStepStarted(subtaskId: String) {}

    fun onIntentCompleted(intent: WorkflowIntent, result: IntentResult) {}
    fun onWorkflowComplete(result: IntentResult) {}
    fun onWorkflowError(error: Exception) {}

    fun onQuestionAsked(questionId: String, question: String, options: List<String>? = null) {}
    fun onApprovalRequired(subtask: Subtask) {}
}
