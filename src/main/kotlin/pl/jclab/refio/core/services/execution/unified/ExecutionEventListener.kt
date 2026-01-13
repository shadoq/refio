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

/**
 * No-op implementation for convenience.
 */
object NoOpListener : ExecutionEventListener

/**
 * Composite listener that forwards events to multiple listeners.
 */
class CompositeListener(
    private val listeners: List<ExecutionEventListener>
) : ExecutionEventListener {
    override fun onStepPreparing(step: Subtask) {
        listeners.forEach { it.onStepPreparing(step) }
    }

    override fun onStepPlanningStream(step: Subtask, streamContent: String, isComplete: Boolean) {
        listeners.forEach { it.onStepPlanningStream(step, streamContent, isComplete) }
    }

    override fun onStepExecuting(step: Subtask, plan: StepPlan) {
        listeners.forEach { it.onStepExecuting(step, plan) }
    }

    override fun onReflectionStream(step: Subtask, streamContent: String, isComplete: Boolean) {
        listeners.forEach { it.onReflectionStream(step, streamContent, isComplete) }
    }

    override fun onToolCodeGenerationStream(
        step: Subtask,
        toolName: String,
        filePath: String,
        streamContent: String,
        isComplete: Boolean
    ) {
        listeners.forEach { it.onToolCodeGenerationStream(step, toolName, filePath, streamContent, isComplete) }
    }

    override fun onStepCompleted(step: Subtask, result: StepResult) {
        listeners.forEach { it.onStepCompleted(step, result) }
    }

    override fun onStepFailed(step: Subtask, error: Throwable) {
        listeners.forEach { it.onStepFailed(step, error) }
    }

    override fun onExecutionComplete(stats: ExecutionStats) {
        listeners.forEach { it.onExecutionComplete(stats) }
    }

    override fun onExecutionError(error: Throwable) {
        listeners.forEach { it.onExecutionError(error) }
    }
}
