package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.PlanStepSummaryResponse
import pl.jclab.refio.core.api.PlanSummaryResponse
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo

private val logger = dualLogger("AgentExecutor")

/**
 * Agent Executor - orchestrates step-by-step execution workflow
 *
 * Responsibilities:
 * - Planning: Generate execution plan for subtask (delegates to StepPlanner)
 * - Execution: Execute tools based on plan (delegates to ToolExecutor)
 * - Summarization: Create human-readable summary (delegates to StepSummarizer)
 *
 * This is the main orchestrator for agent execution.
 */
class AgentExecutor(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val toolExecutor: ToolExecutor,
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val configService: ConfigService,
    private val stepPlanner: StepPlanner? = null
) {
    private val stepSummarizer: StepSummarizer = StepSummarizer(llmClient, promptsService, configService, taskRepository)

    /**
     * Approve plan for a task.
     *
     * @throws IllegalStateException if plan is empty
     */
    suspend fun approvePlan(taskId: String) {
        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val subtasks = subtaskRepository.findByTaskId(taskId)
        if (subtasks.isEmpty()) {
            throw IllegalStateException("Cannot approve an empty plan. Generate a plan first.")
        }

        if (!task.planApproved) {
            logger.info { "[EXECUTOR] Plan approved for task: $taskId" }
            taskRepository.update(id = taskId, planApproved = true)
        }
    }

    /**
     * Reject plan and clear approval flag.
     */
    suspend fun rejectPlan(taskId: String, reason: String? = null) {
        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        if (task.planApproved) {
            logger.info {
                "[EXECUTOR] Plan rejected for task: $taskId" +
                    if (reason != null) " - Reason: $reason" else ""
            }
            taskRepository.update(id = taskId, planApproved = false)
        }
    }

    /**
     * Get plan summary for approval UI.
     */
    suspend fun getPlanSummary(taskId: String): PlanSummaryResponse {
        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val subtasks = subtaskRepository.findByTaskId(taskId)
        if (subtasks.isEmpty()) {
            throw IllegalStateException("Plan is empty. Generate a plan first.")
        }

        val steps = subtasks.map { subtask ->
            val toolName = subtask.kind.name.lowercase()
            val isWrite = toolName in WRITE_TOOLS
            PlanStepSummaryResponse(
                id = subtask.id,
                description = subtask.description,
                tool = toolName,
                status = subtask.status.name,
                isWrite = isWrite
            )
        }

        val writeSteps = steps.count { it.isWrite }
        val readOnlySteps = steps.size - writeSteps

        return PlanSummaryResponse(
            taskId = taskId,
            totalSteps = steps.size,
            readOnlySteps = readOnlySteps,
            writeSteps = writeSteps,
            requiresApproval = task.requiresPlanApproval,
            isApproved = task.planApproved,
            steps = steps
        )
    }

    /**
     * Plan subtask execution using StepPlanner.
     *
     * Flow:
     * 1. Validate subtask is in PENDING state
     * 2. Generate execution plan using StepPlanner
     * 3. Update subtask status to PLANNED
     * 4. Store plan in params_json (if not already there)
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Generated execution plan
     */
    suspend fun planStep(taskId: String, subtaskId: String): StepPlanResult {
        logger.info { "[EXECUTOR] Planning step: task=$taskId, subtask=$subtaskId" }
        val startTime = System.currentTimeMillis()

        try {
            // Check if StepPlanner is available
            if (stepPlanner == null) {
                throw IllegalStateException("StepPlanner not available - cannot generate plan")
            }

            // Fetch subtask
            val subtask = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            if (subtask.taskId != taskId) {
                throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
            }

            // Validate state - must be PENDING
            if (subtask.status != TaskStatus.PENDING) {
                throw IllegalStateException(
                    "Subtask must be PENDING before planning (current status=${subtask.status})"
                )
            }

            // Update status to RUNNING before LLM call (so UI shows activity)
            subtaskRepository.updateStatus(subtaskId, TaskStatus.RUNNING)
            logger.info { "[EXECUTOR] Updated subtask status to RUNNING before planning" }

            // Generate plan (RFC 0032: returns result directly)
            val planResult = stepPlanner.generatePlan(taskId, subtaskId)

            // Build ExecutionPlan from response
            val plan = ExecutionPlan(
                tools = listOf(planResult.toolCall),
                description = subtask.description.takeIf { !it.isNullOrBlank() } ?: "Execute step",
                estimatedDurationMs = 5000,
                dependencies = emptyList(),
                llmMetrics = planResult.metrics,
                planDecision = planResult.planDecision
            )

            // Oznacz narzędzia wymagające approval
            val toolsWithApproval = plan.tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "params" to tool.params,
                    "expected_output" to tool.expectedOutput,
                    "requires_approval" to false
                )
            }

            // Save plan to step_plan_json
            val planJson = gson.toJson(mapOf("tools" to toolsWithApproval))
            subtaskRepository.updateStepPlan(subtaskId, planJson)

            // Update subtask status to PLANNED
            subtaskRepository.updateStatus(subtaskId, TaskStatus.PLANNED)

            // Subtask LLM metrics auto-incremented inside LLMClient.complete() via
            // subtaskId StepPlanner passes to its complete() call. No manual SET here
            // (would clobber metrics from later sub-LLM calls in the same subtask).

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "[EXECUTOR] Step planned: $subtaskId with ${plan.tools.size} tool(s) in ${durationMs}ms" }

            return StepPlanResult(
                plan = plan,
                durationMs = durationMs,
                llmMetrics = plan.llmMetrics // US-027: Pass LLM metrics from plan
            )

        } catch (e: Exception) {
            logger.error(e) { "[EXECUTOR] Step planning failed" }

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            return StepPlanResult(
                plan = null,
                durationMs = durationMs,
                error = e.message
            )
        }
    }

    /**
     * Execute subtask with tools.
     *
     * Flow:
     * 1. Validate subtask is in PENDING or PLANNED state
     * 2. Execute tools from params_json
     * 3. Update status to SUCCESS/FAILED
     * 4. Generate summary
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Execution result with status, summary, and duration
     */
    suspend fun executeStep(
        taskId: String,
        subtaskId: String,
        listener: ExecutionEventListener? = null
    ): StepExecutionResult {
        logger.info { "[EXECUTOR] Executing step: task=$taskId, subtask=$subtaskId, streaming=${listener != null}" }
        val startTime = System.currentTimeMillis()

        try {
            // Always fetch fresh subtask from database to get updated stepPlanJson
            // (after planStep, the in-memory subtask object may be stale)
            val subtask = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            logger.debug { "[EXECUTOR] Fetched subtask: id=${subtask.id}, stepPlanJson=${subtask.stepPlanJson?.take(100) ?: "NULL"}" }

            if (subtask.taskId != taskId) {
                throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
            }

            // Validate state - must be PENDING, PLANNED, or RUNNING (if planning just completed)
            if (subtask.status !in listOf(TaskStatus.PENDING, TaskStatus.PLANNED, TaskStatus.RUNNING)) {
                throw IllegalStateException(
                    "Subtask must be PENDING, PLANNED, or RUNNING before execution (current status=${subtask.status})"
                )
            }
            
            // If status is RUNNING (from planning), update to PLANNED before execution
            if (subtask.status == TaskStatus.RUNNING) {
                subtaskRepository.updateStatus(subtaskId, TaskStatus.PLANNED)
                logger.info { "[EXECUTOR] Updated subtask status from RUNNING to PLANNED before execution" }
            }

            // Update to RUNNING
            subtaskRepository.updateStatus(subtaskId, TaskStatus.RUNNING)

            // Parse tool calls from params_json
            val toolCalls = parseToolCalls(subtask)

            // Inject taskId into params for tools
            val toolCallsWithTaskId = toolCalls.map { toolCall ->
                val paramsWithTaskId = toolCall.params.toMutableMap()
                paramsWithTaskId["taskId"] = taskId
                toolCall.copy(params = paramsWithTaskId)
            }

            logger.info { "[EXECUTOR] Executing ${toolCalls.size} tool(s) for subtask ${subtask.kind}" }

            val totalSteps = subtaskRepository.countByTaskId(taskId).toInt().coerceAtLeast(1)
            val stepToken = GlobalMetrics.beginOperation(
                OperationInfo.StepExecuting(subtask.orderIndex, totalSteps)
            )
            val executionResult = try {
                // Execute tools with streaming support for advance_code_editing
                toolExecutor.executeToolsWithStreaming(
                    toolCalls = toolCallsWithTaskId,
                    subtask = subtask,
                    listener = listener
                )
            } finally {
                GlobalMetrics.endOperation(stepToken)
            }

            // Update subtask with results
            val newStatus = if (executionResult.success) {
                TaskStatus.SUCCESS
            } else {
                TaskStatus.FAILED
            }

            subtaskRepository.updateStatus(subtaskId, newStatus)

            if (!executionResult.success) {
                val errorMessage = executionResult.errors.joinToString("; ")
                subtaskRepository.updateResult(
                    id = subtaskId,
                    result = gson.toJson(executionResult),
                    errorMessage = errorMessage
                )
            } else {
                subtaskRepository.updateResult(
                    id = subtaskId,
                    result = gson.toJson(executionResult)
                )
            }

            // Generate summary using StepSummarizer
            val updatedSubtask = subtaskRepository.findById(subtaskId)!!
            val summaryToken = GlobalMetrics.beginOperation(
                OperationInfo.StepSummarizing(updatedSubtask.orderIndex, totalSteps)
            )
            val summary = try {
                stepSummarizer.generateSummary(
                    subtask = updatedSubtask,
                    taskId = taskId,
                    executionResult = executionResult
                )
            } finally {
                GlobalMetrics.endOperation(summaryToken)
            }

            // Save summary to database
            subtaskRepository.updateSummary(subtaskId, summary)
            logger.info { "[EXECUTOR] Saved summary to database for subtask $subtaskId" }

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            // Create metrics from subtask execution (US-027)
            val metrics = if (updatedSubtask.inputTokens > 0 || updatedSubtask.outputTokens > 0) {
                pl.jclab.refio.core.db.MessageMetrics(
                    model = updatedSubtask.llmModel,
                    provider = updatedSubtask.llmProvider,
                    inputTokens = updatedSubtask.inputTokens,
                    outputTokens = updatedSubtask.outputTokens,
                    totalTokens = updatedSubtask.inputTokens + updatedSubtask.outputTokens,
                    costUsd = updatedSubtask.costUsd,
                    latencyMs = updatedSubtask.latencyMs,
                    startedAt = updatedSubtask.startedAt,
                    completedAt = updatedSubtask.completedAt,
                    subtaskId = subtaskId
                )
            } else null

            logger.info { "[EXECUTOR] Step completed: $subtaskId in ${durationMs}ms" }

            return StepExecutionResult(
                status = if (executionResult.success) "success" else "failed",
                result = executionResult,
                summary = summary,
                durationMs = durationMs,
                llmMetrics = metrics // US-027: Include subtask metrics
            )

        } catch (e: Exception) {
            logger.error(e) { "[EXECUTOR] Step execution failed" }

            // Update with error
            subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
            subtaskRepository.updateResult(
                id = subtaskId,
                result = null,
                errorMessage = e.message
            )

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            return StepExecutionResult(
                status = "failed",
                result = null,
                summary = "Execution failed: ${e.message}",
                durationMs = durationMs,
                error = e.message
            )
        }
    }

    /**
     * Parse tool calls from subtask step_plan_json.
     *
     * Expected format in step_plan_json:
     * {
     *   "tools": [
     *     {
     *       "name": "read_file",
     *       "params": {"path": "config.yaml"}
     *     }
     *   ]
     * }
     */
    private fun parseToolCalls(subtask: Subtask): List<ToolCall> {
        val stepPlanJson = subtask.stepPlanJson

        // If no step plan, return empty list
        if (stepPlanJson.isNullOrBlank()) {
            logger.warn { "No step plan found for subtask ${subtask.id}" }
            return emptyList()
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val stepPlan = gson.fromJson(stepPlanJson, Map::class.java) as Map<String, Any>

            @Suppress("UNCHECKED_CAST")
            val toolsArray = stepPlan["tools"] as? List<Map<String, Any>>

            if (toolsArray == null) {
                logger.warn { "No 'tools' array found in step_plan_json for subtask ${subtask.id}" }
                return emptyList()
            }

            return toolsArray.map { toolData ->
                val name = toolData["name"] as? String
                    ?: throw IllegalArgumentException("Tool 'name' is required")

                @Suppress("UNCHECKED_CAST")
                val toolParams = toolData["params"] as? Map<String, Any> ?: emptyMap()

                val expectedOutput = toolData["expected_output"] as? String

                ToolCall(
                    name = name,
                    params = toolParams,
                    expectedOutput = expectedOutput
                )
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse tool calls from step_plan_json: $stepPlanJson" }
            throw IllegalArgumentException("Invalid step_plan_json format: ${e.message}")
        }
    }

    /**
     * Generate human-readable summary of execution result.
     *
     * Format: "✓ Created 2 files, modified 1 file (2.3s)"
     */
    private fun generateSummary(result: ToolExecutionResult): String {
        if (!result.success) {
            val errorCount = result.errors.size
            return "❌ Failed: ${errorCount} error(s)"
        }

        val toolCount = result.toolsExecuted
        val affectedFiles = result.outputs
            .flatMap { it.result.affectedFiles }
            .distinct()

        val fileCount = affectedFiles.size

        val parts = mutableListOf<String>()

        if (toolCount > 0) {
            parts.add("$toolCount tool(s) executed")
        }

        if (fileCount > 0) {
            parts.add("$fileCount file(s) affected")
        }

        return if (parts.isEmpty()) {
            "✓ Completed successfully"
        } else {
            "✓ ${parts.joinToString(", ")}"
        }
    }

    companion object {
        private val WRITE_TOOLS = setOf(
            "create_new_file",
            "code_editing",
            "advance_code_editing",
            "multi_edit",
            "run_terminal_command"
        )
    }
}

/**
 * Step execution result
 */
data class StepExecutionResult(
    /**
     * Status: "success" or "failed"
     */
    val status: String,

    /**
     * Tool execution result
     */
    val result: ToolExecutionResult?,

    /**
     * Human-readable summary
     */
    val summary: String,

    /**
     * Execution duration in milliseconds
     */
    val durationMs: Int,

    /**
     * LLM/tool metrics from execution (US-027)
     */
    val llmMetrics: pl.jclab.refio.core.db.MessageMetrics? = null,

    /**
     * Error message (if failed)
     */
    val error: String? = null
)

/**
 * Step plan result
 */
data class StepPlanResult(
    /**
     * Generated execution plan
     */
    val plan: ExecutionPlan?,

    /**
     * Planning duration in milliseconds
     */
    val durationMs: Int,

    /**
     * LLM metrics from planning call (US-027)
     */
    val llmMetrics: pl.jclab.refio.core.db.MessageMetrics? = null,

    /**
     * Error message (if failed)
     */
    val error: String? = null
)
