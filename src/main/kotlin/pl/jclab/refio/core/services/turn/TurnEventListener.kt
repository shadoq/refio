package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.ToolCallData

/**
 * Listener for turn events (tool execution, streaming, etc.).
 * Extracted from AgentTurnLoop to avoid circular dependencies.
 */
interface TurnEventListener {
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

    fun onTurnCompleted(
        taskId: String,
        result: pl.jclab.refio.core.services.TurnResult,
        runId: String,
        parentRunId: String?,
        depth: Int
    ) {}
}
