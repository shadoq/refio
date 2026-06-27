package pl.jclab.refio.core.workflow

/**
 * Workflow event listener for UI updates.
 *
 * Pure streaming/UI-notification contract used by the per-platform session bindings
 * (CLI TUI, IntelliJ plugin) and [pl.jclab.refio.core.session.DefaultWorkflowStreamingListener].
 * The old plan/step orchestrator was removed; the intent-coupled callbacks went with it.
 */
interface WorkflowEventListener {
    fun onStreamChunk(chunk: String) {}
    fun onStreamComplete(content: String) {}

    fun onChatStarted() {}
    fun onToolStarted(toolName: String) {}
    fun onSubagentStarted(subagentName: String) {}
    fun onPlanningStarted() {}
    fun onStepStarted(subtaskId: String) {}

    fun onWorkflowError(error: Exception) {}

    fun onQuestionAsked(questionId: String, question: String, options: List<String>? = null) {}
}
