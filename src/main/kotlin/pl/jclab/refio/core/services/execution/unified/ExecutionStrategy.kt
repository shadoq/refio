package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.db.Subtask

/**
 * Execution strategy interface.
 *
 * Defines how steps are found, prepared, executed, and how decisions are made
 * about continuing execution.
 */
interface ExecutionStrategy {
    /**
     * Find next step to execute.
     *
     * @param taskId Task ID
     * @return Next subtask to execute, or null if no more steps
     */
    suspend fun findNextStep(taskId: String): Subtask?

    /**
     * Prepare step plan (planning phase).
     *
     * Optionally accepts an ExecutionEventListener to emit streaming chunks
     * during LLM planning via onStepPlanningStream callback.
     *
     * @param subtask Subtask to plan
     * @param listener Optional listener for streaming callbacks during planning
     * @return Step plan with tools and parameters
     */
    suspend fun preparePlan(subtask: Subtask, listener: ExecutionEventListener? = null): StepPlan

    /**
     * Execute step with plan (execution phase).
     *
     * Optionally accepts an ExecutionEventListener to emit streaming chunks
     * during tool code generation via onToolCodeGenerationStream callback.
     *
     * @param subtask Subtask to execute
     * @param plan Step plan from prepare phase
     * @param listener Optional listener for streaming callbacks during tool execution
     * @return Step execution result
     */
    suspend fun executeStep(subtask: Subtask, plan: StepPlan, listener: ExecutionEventListener? = null): StepResult

    /**
     * Decide whether to continue execution after step completes.
     *
     * Optionally accepts an ExecutionEventListener to emit streaming chunks
     * during LLM reflection via onReflectionStream callback.
     *
     * @param result Step result
     * @param listener Optional listener for streaming callbacks during reflection
     * @return true to continue, false to stop
     */
    suspend fun shouldContinue(result: StepResult, listener: ExecutionEventListener? = null): Boolean

    /**
     * Called after execution completes (success or failure).
     *
     * Allows strategy to perform cleanup or save final state.
     *
     * @param taskId Task ID
     * @param stats Execution statistics
     */
    suspend fun onExecutionComplete(taskId: String, stats: ExecutionStats) {
        // Default: no-op
    }
}
