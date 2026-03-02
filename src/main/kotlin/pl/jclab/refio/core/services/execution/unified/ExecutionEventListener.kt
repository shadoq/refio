package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.db.Subtask

/**
 * Execution event listener interface.
 *
 * Allows external components (e.g., UI) to react to execution events
 * without being tightly coupled to execution logic.
 */
interface ExecutionEventListener {
    /**
     * Called before step preparation starts.
     *
     * @param step Subtask being prepared
     */
    fun onStepPreparing(step: Subtask) {}

    /**
     * Called during step planning with streaming content from LLM.
     *
     * @param step Subtask being planned
     * @param streamContent Current streaming content (JSON being generated)
     * @param isComplete True if streaming is complete
     */
    fun onStepPlanningStream(step: Subtask, streamContent: String, isComplete: Boolean) {}

    /**
     * Called after step is prepared and before execution starts.
     *
     * @param step Subtask being executed
     * @param plan Step plan with tools
     */
    fun onStepExecuting(step: Subtask, plan: StepPlan) {}

    /**
     * Called during reflection with streaming content from LLM.
     *
     * @param step Subtask that was executed
     * @param streamContent Current streaming content (JSON being generated)
     * @param isComplete True if streaming is complete
     */
    fun onReflectionStream(step: Subtask, streamContent: String, isComplete: Boolean) {}

    /**
     * Called during tool code generation with streaming content from LLM.
     * Used by AdvanceCodeEditingTool to show code being generated in real-time.
     *
     * @param step Subtask executing the tool
     * @param toolName Name of the tool generating code
     * @param filePath Path of the file being created/edited
     * @param streamContent Current streaming content (code being generated)
     * @param isComplete True if streaming is complete
     */
    fun onToolCodeGenerationStream(
        step: Subtask,
        toolName: String,
        filePath: String,
        streamContent: String,
        isComplete: Boolean
    ) {}

    /**
     * Called after step execution completes.
     *
     * @param step Subtask that was executed
     * @param result Execution result
     */
    fun onStepCompleted(step: Subtask, result: StepResult) {}

    /**
     * Called when step execution fails.
     *
     * @param step Subtask that failed
     * @param error Error that occurred
     */
    fun onStepFailed(step: Subtask, error: Throwable) {}

    /**
     * Called when all steps are complete.
     *
     * @param stats Execution statistics
     */
    fun onExecutionComplete(stats: ExecutionStats) {}

    /**
     * Called when execution encounters fatal error.
     *
     * @param error Error that occurred
     */
    fun onExecutionError(error: Throwable) {}
}

