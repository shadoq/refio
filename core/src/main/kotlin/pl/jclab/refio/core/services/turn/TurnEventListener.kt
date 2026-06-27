package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.ToolCallData

/**
 * Listener for turn events (tool execution, streaming, etc.).
 * Extracted from AgentTurnLoop to avoid circular dependencies.
 */
interface TurnEventListener : TurnCompletionListener {
    fun onTurnStarted(
        taskId: String,
        mode: pl.jclab.refio.core.db.TaskMode,
        runId: String,
        parentRunId: String?,
        depth: Int
    ) {}

    fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData) {}

    fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {}

    fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean) {}

    fun onStreamChunk(taskId: String, delta: String, accumulated: String) {}

    /**
     * The model is streaming a NATIVE tool call's arguments, BEFORE the call is dispatched for
     * execution (docs/0064). Distinct from [onToolStreamChunk], which streams a tool's *result*
     * during execution. [accumulatedArguments] is the raw arguments JSON received so far.
     */
    fun onLlmToolCallProgress(taskId: String, index: Int, toolName: String?, accumulatedArguments: String) {}

    fun onToolBatchCompleted(taskId: String, summary: ToolBatchSummary.BatchSummary) {}
}
