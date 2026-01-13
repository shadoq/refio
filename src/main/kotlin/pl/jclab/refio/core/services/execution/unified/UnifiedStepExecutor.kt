package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("UnifiedStepExecutor")

/**
 * Unified Step Executor - single execution loop for all modes.
 *
 * Provides a single, consistent execution loop that works with different strategies:
 * - SimpleAutoStrategy: Basic auto-execution without orchestration
 * - OrchestrationStrategy: Auto-execution with reflection and plan modification
 * - InteractiveStrategy: Manual approval workflow (future)
 *
 * Benefits:
 * - Single source of truth for execution logic
 * - No code duplication across modes
 * - Strategy pattern allows different behaviors
 * - Observer pattern allows UI updates without coupling
 */
class UnifiedStepExecutor(
    private val taskRepository: TaskRepository
) {
    companion object {
        /**
         * Maximum number of consecutive step failures before stopping execution.
         * This prevents infinite loops when steps keep failing.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
    /**
     * Execute steps with given strategy and listener.
     *
     * Main execution loop:
     * 1. Find next step (strategy decides which)
     * 2. Notify listener (onStepPreparing)
     * 3. Prepare plan (strategy creates plan)
     * 4. Notify listener (onStepExecuting)
     * 5. Execute step (strategy executes)
     * 6. Notify listener (onStepCompleted/onStepFailed)
     * 7. Check if should continue (strategy decides)
     * 8. Repeat until no more steps or stop condition
     *
     * @param taskId Task ID
     * @param strategy Execution strategy to use
     * @param listener Event listener for UI updates (optional)
     * @return Execution result with statistics
     */
    suspend fun execute(
        taskId: String,
        strategy: ExecutionStrategy,
        listener: ExecutionEventListener = NoOpListener
    ): ExecutionResult {
        logger.info { "[UNIFIED] Starting execution for task: $taskId" }
        val startTime = System.currentTimeMillis()

        var stepsExecuted = 0
        var stepsFailed = 0
        var stepsSkipped = 0
        var consecutiveFailures = 0  // Track consecutive failures for error recovery

        try {
            // Verify task exists
            val task = taskRepository.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")

            logger.info { "[UNIFIED] Task found: ${task.name}, mode=${task.mode}" }

            // Main execution loop
            while (true) {
                // Check cancellation
                if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                    logger.info { "[UNIFIED] Execution cancelled by user" }
                    throw java.util.concurrent.CancellationException("Operation cancelled by user")
                }

                // 1. Find next step
                val nextStep = strategy.findNextStep(taskId)

                if (nextStep == null) {
                    logger.info { "[UNIFIED] No more steps to execute" }
                    break
                }

                logger.info { "[UNIFIED] Processing step ${nextStep.orderIndex}: ${nextStep.description}" }

                // 2. Notify listener - preparing
                listener.onStepPreparing(nextStep)

                // 3. Prepare plan (pass listener for streaming callbacks)
                val plan = try {
                    strategy.preparePlan(nextStep, listener)
                } catch (e: Exception) {
                    logger.error(e) { "[UNIFIED] Failed to prepare step ${nextStep.id}" }
                    listener.onStepFailed(nextStep, e)
                    stepsFailed++
                    consecutiveFailures++

                    // Check consecutive failures limit
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        logger.error { "[UNIFIED] Exceeded maximum consecutive failures ($MAX_CONSECUTIVE_FAILURES), stopping execution" }
                        break
                    }

                    // Let strategy decide if we should continue after planning failure
                    val failedResult = StepResult(
                        subtaskId = nextStep.id,
                        status = "failed",
                        summary = "Planning failed: ${e.message}",
                        durationMs = 0,
                        error = e.message
                    )

                    if (!strategy.shouldContinue(failedResult, listener)) {
                        logger.warn { "[UNIFIED] Strategy decided to stop after planning failure" }
                        break
                    }

                    continue
                }

                logger.info { "[UNIFIED] Step prepared with ${plan.tools.size} tools" }

                // 4. Notify listener - executing
                listener.onStepExecuting(nextStep, plan)

                // 5. Execute step (with listener for streaming tool outputs)
                val result = try {
                    strategy.executeStep(nextStep, plan, listener)
                } catch (e: Exception) {
                    logger.error(e) { "[UNIFIED] Failed to execute step ${nextStep.id}" }
                    listener.onStepFailed(nextStep, e)
                    stepsFailed++
                    consecutiveFailures++

                    // Check consecutive failures limit
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        logger.error { "[UNIFIED] Exceeded maximum consecutive failures ($MAX_CONSECUTIVE_FAILURES), stopping execution" }
                        break
                    }

                    val failedResult = StepResult(
                        subtaskId = nextStep.id,
                        status = "failed",
                        summary = "Execution failed: ${e.message}",
                        durationMs = 0,
                        error = e.message
                    )

                    if (!strategy.shouldContinue(failedResult, listener)) {
                        logger.warn { "[UNIFIED] Strategy decided to stop after execution failure" }
                        break
                    }

                    continue
                }

                // 6. Track stats
                if (result.status == "success") {
                    stepsExecuted++
                    consecutiveFailures = 0  // Reset counter on success
                    logger.info { "[UNIFIED] Step completed successfully in ${result.durationMs}ms" }
                } else {
                    stepsFailed++
                    consecutiveFailures++
                    logger.warn { "[UNIFIED] Step failed: ${result.error}, consecutive failures: $consecutiveFailures" }

                    // Check consecutive failures limit
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        logger.error { "[UNIFIED] Exceeded maximum consecutive failures ($MAX_CONSECUTIVE_FAILURES), stopping execution" }
                        break
                    }
                }

                // 7. Notify listener - completed
                listener.onStepCompleted(nextStep, result)

                // 8. Check if should continue (pass listener for reflection streaming)
                val shouldContinue = strategy.shouldContinue(result, listener)

                if (!shouldContinue) {
                    logger.info { "[UNIFIED] Strategy decided to stop execution" }
                    break
                }
            }

            // Calculate stats
            val durationMs = System.currentTimeMillis() - startTime
            val stats = ExecutionStats(
                stepsExecuted = stepsExecuted,
                stepsFailed = stepsFailed,
                stepsSkipped = stepsSkipped,
                durationMs = durationMs
            )

            logger.info { "[UNIFIED] Execution complete: $stepsExecuted executed, $stepsFailed failed, ${durationMs}ms" }

            // Notify strategy and listener
            strategy.onExecutionComplete(taskId, stats)
            listener.onExecutionComplete(stats)

            return ExecutionResult.success(stats)

        } catch (e: Exception) {
            logger.error(e) { "[UNIFIED] Fatal execution error" }

            val durationMs = System.currentTimeMillis() - startTime
            val stats = ExecutionStats(
                stepsExecuted = stepsExecuted,
                stepsFailed = stepsFailed + 1,
                stepsSkipped = stepsSkipped,
                durationMs = durationMs
            )

            // Notify listener
            listener.onExecutionError(e)

            // Update task status to FAILED
            try {
                taskRepository.update(id = taskId, status = TaskStatus.FAILED)
            } catch (updateError: Exception) {
                logger.error(updateError) { "[UNIFIED] Failed to update task status to FAILED" }
            }

            return ExecutionResult.failure(e, stats)
        }
    }
}
