package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.services.orchestration.*
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("OrchestrationStrategy")

/**
 * Orchestration strategy with reflection and plan modification.
 *
 * Executes steps with reflection loop:
 * - Execute step
 * - Reflect on result
 * - Decide action (continue, modify plan, ask user, abort)
 * - Execute actions (add/skip/modify/retry steps)
 * - Continue or stop
 */
class OrchestrationStrategy(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val agentExecutor: AgentExecutor,
    private val reflectionEngine: ReflectionEngine,
    private val planModifier: PlanModifier,
    private val userInteraction: UserInteraction
) : ExecutionStrategy {

    companion object {
        /**
         * Maximum number of ADD_STEP actions allowed per reflection cycle.
         * This prevents the orchestrator from adding too many steps at once,
         * which could cause plan explosion or infinite loops.
         */
        private const val MAX_ADD_STEPS_PER_CYCLE = 3

        /**
         * Maximum number of plan modifications allowed per task execution.
         * This prevents infinite loops where the orchestrator keeps modifying the plan.
         */
        private const val MAX_PLAN_MODIFICATIONS = 10
    }

    private val reflections = mutableListOf<ReflectionDecision>()
    private var tempMessageIds = mutableListOf<String>()
    private var planModificationCount = 0

    override suspend fun findNextStep(taskId: String): Subtask? {
        val pendingSubtasks = subtaskRepository.findByStatus(taskId, TaskStatus.PENDING)
            .sortedBy { it.orderIndex }

        return pendingSubtasks.firstOrNull()
    }

    override suspend fun preparePlan(subtask: Subtask, listener: ExecutionEventListener?): StepPlan {
        logger.info { "[ORCHESTRATION] Planning step: ${subtask.id}, streaming=${listener != null}" }

        // RFC 0032: Use callback-based streaming
        val contentBuilder = StringBuilder()

        val onChunk: ((StreamChunk) -> Unit)? = if (listener != null) { chunk ->
            contentBuilder.clear()
            contentBuilder.append(chunk.accumulated)
            listener.onStepPlanningStream(subtask, chunk.accumulated, chunk.isComplete)
        } else null

        // Use unified planStepWithStreaming method
        val planResult = agentExecutor.planStepWithStreaming(
            taskId = subtask.taskId,
            subtaskId = subtask.id,
            stream = listener != null,
            onChunk = onChunk
        )

        if (planResult.error != null) {
            throw IllegalStateException("Planning failed: ${planResult.error}")
        }

        val plan = planResult.plan
            ?: throw IllegalStateException("No plan returned from AgentExecutor")

        // Show "executing" message in UI (will be deleted after execution)
        val toolsList = plan.tools.joinToString("\n") { tool ->
            val argsPreview = tool.params.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
            val argsText = if (argsPreview.isEmpty()) "" else " ($argsPreview)"
            "  • ${tool.name}$argsText"
        }

        val executingMessage = "⏳ **Step ${subtask.orderIndex}**: ${plan.description}\n\n" +
                "**Tools:**\n$toolsList\n\n" +
                "Executing..."

        val createdMessage = chatMessageRepository.create(
            taskId = subtask.taskId,
            role = MessageRole.SYSTEM,
            content = executingMessage,
            metadata = gson.toJson(
                mapOf(
                    "type" to "step_executing",
                    "subtask_id" to subtask.id,
                    "step_number" to subtask.orderIndex,
                    "tools_count" to plan.tools.size
                )
            )
        )

        tempMessageIds.add(createdMessage.id)
        logger.info { "[ORCHESTRATION] Created temp execution message: ${createdMessage.id}" }

        return StepPlan(
            subtaskId = subtask.id,
            description = plan.description,
            tools = plan.tools.map { tool ->
                ToolPlan(
                    name = tool.name,
                    params = tool.params
                )
            },
            estimatedDurationMs = null,
            planDecision = plan.planDecision
        )
    }

    override suspend fun executeStep(subtask: Subtask, plan: StepPlan, listener: ExecutionEventListener?): StepResult {
        logger.info { "[ORCHESTRATION] Executing step: ${subtask.id}, streaming=${listener != null}" }

        val execResult = agentExecutor.executeStep(
            taskId = subtask.taskId,
            subtaskId = subtask.id,
            listener = listener
        )

        // Delete temporary "executing" message
        tempMessageIds.forEach { tempId ->
            try {
                chatMessageRepository.delete(tempId)
                logger.info { "[ORCHESTRATION] Deleted temp message: $tempId" }
            } catch (e: Exception) {
                logger.warn(e) { "[ORCHESTRATION] Failed to delete temp message: $tempId" }
            }
        }
        tempMessageIds.clear()

        // Extract code changes metadata from execution result
        val codeChangesMetadata = extractCodeChangesMetadata(execResult, subtask)

        // Build combined metadata (step_summary + code_changes)
        val messageMetadata = if (codeChangesMetadata != null) {
            logger.info { "[ORCHESTRATION] Adding code changes metadata to step summary message" }
            codeChangesMetadata
        } else {
            mapOf(
                "type" to "step_summary",
                "subtask_id" to subtask.id,
                "step_number" to subtask.orderIndex
            )
        }

        val metadataJson = gson.toJson(messageMetadata)

        // Save summary to chat
        chatMessageRepository.create(
            taskId = subtask.taskId,
            role = MessageRole.ASSISTANT,
            content = "📝 **Step ${subtask.orderIndex} Summary:**\n\n${execResult.summary}",
            metadata = metadataJson
        )

        return StepResult(
            subtaskId = subtask.id,
            status = execResult.status,
            summary = execResult.summary,
            durationMs = execResult.durationMs,
            error = execResult.error,
            executionResult = execResult
        )
    }

    override suspend fun shouldContinue(result: StepResult, listener: ExecutionEventListener?): Boolean {
        logger.info { "[ORCHESTRATION] Reflecting on step result: ${result.status}" }

        // Load task and subtask for reflection
        val subtask = subtaskRepository.findById(result.subtaskId)
            ?: throw IllegalStateException("Subtask not found: ${result.subtaskId}")

        val task = taskRepository.findById(subtask.taskId)
            ?: throw IllegalStateException("Task not found: ${subtask.taskId}")

        // Handle planning failures (executionResult is null)
        val execResult = result.executionResult
        if (execResult == null) {
            val errorDetail = result.error ?: result.summary
            logger.warn { "[ORCHESTRATION] Planning failed, cannot reflect: $errorDetail" }

            // Save planning failure to chat
            chatMessageRepository.create(
                taskId = task.id,
                role = MessageRole.SYSTEM,
                content = "❌ **Planning Error - Step ${subtask.orderIndex}:**\n\n$errorDetail",
                metadata = gson.toJson(
                    mapOf(
                        "type" to "planning_error",
                        "subtask_id" to subtask.id,
                        "error" to errorDetail
                    )
                )
            )

            // Mark subtask as failed
            subtaskRepository.updateStatus(id = subtask.id, status = TaskStatus.FAILED)

            // Stop execution on planning failure
            return false
        }

        // Reflect on result with streaming
        val contentBuilder = StringBuilder()
        val onChunk: ((StreamChunk) -> Unit)? = if (listener != null) { chunk ->
            contentBuilder.clear()
            contentBuilder.append(chunk.accumulated)
            listener.onReflectionStream(subtask, chunk.accumulated, chunk.isComplete)
        } else null

        val reflection = reflectionEngine.reflect(
            task = task,
            subtask = subtask,
            result = execResult,
            stream = listener != null,
            onChunk = onChunk
        )
        reflections.add(reflection)

        logger.info { "[ORCHESTRATION] Reflection decision: ${reflection.decision}" }

        // Save reflection to chat
        val reflectionContent = buildString {
            append("🤔 **Reflection - Step ${subtask.orderIndex}:**\n\n")
            append("${reflection.analysis}\n\n")
            append("**Decision:** ${reflection.decision.name.lowercase().replace('_', ' ')}\n\n")
            append("**Reasoning:** ${reflection.reasoning}")
        }

        chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.SYSTEM,
            content = reflectionContent,
            metadata = gson.toJson(
                mapOf(
                    "type" to "reflection",
                    "decision" to reflection.decision.name,
                    "subtask_id" to subtask.id
                )
            )
        )

        // Handle decision
        return handleDecision(task, reflection)
    }

    override suspend fun onExecutionComplete(taskId: String, stats: ExecutionStats) {
        logger.info { "[ORCHESTRATION] Execution complete for task $taskId" }

        // Save orchestration completion marker
        val finalContent = buildString {
            append("✅ **Orchestration Complete**\n\n")
            append("- Steps executed: ${stats.stepsExecuted}\n")
            append("- Steps failed: ${stats.stepsFailed}\n")
            append("- Reflections: ${reflections.size}\n")
            append("- Plan modifications: ${reflections.count { it.decision == DecisionType.MODIFY_PLAN }}\n")
            append("- Duration: ${stats.durationMs / 1000.0}s")
        }

        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = finalContent,
            metadata = gson.toJson(mapOf("type" to "orchestration_complete"))
        )

        // Reset counters for next execution
        planModificationCount = 0
        reflections.clear()
    }

    /**
     * Handle reflection decision and execute actions.
     *
     * @return true to continue execution, false to stop
     */
    private suspend fun handleDecision(
        task: pl.jclab.refio.core.db.Task,
        reflection: ReflectionDecision
    ): Boolean {
        logger.info { "[ORCHESTRATION] Handling decision: ${reflection.decision}" }

        return when (reflection.decision) {
            DecisionType.CONTINUE -> {
                logger.info { "[ORCHESTRATION] Continuing with plan" }
                true
            }

            DecisionType.MODIFY_PLAN -> {
                logger.info { "[ORCHESTRATION] Modifying plan: ${reflection.actions.size} actions" }

                // SAFEGUARD: Check if we've exceeded max plan modifications
                planModificationCount++
                if (planModificationCount > MAX_PLAN_MODIFICATIONS) {
                    logger.warn { "[ORCHESTRATION] Exceeded max plan modifications ($MAX_PLAN_MODIFICATIONS), stopping execution" }

                    chatMessageRepository.create(
                        taskId = task.id,
                        role = MessageRole.SYSTEM,
                        content = "⚠️ **Orchestrator Limit Reached:** Maximum plan modifications ($MAX_PLAN_MODIFICATIONS) exceeded. " +
                                "Stopping execution to prevent infinite loops. Consider simplifying the task or breaking it into smaller pieces.",
                        metadata = gson.toJson(mapOf("type" to "orchestrator_limit_reached"))
                    )

                    taskRepository.update(id = task.id, status = TaskStatus.FAILED)
                    return false // Stop execution
                }

                // SAFEGUARD: Limit ADD_STEP actions to max 3 per reflection cycle
                val addStepActions = reflection.actions.filterIsInstance<ReflectionAction.AddStep>()
                if (addStepActions.size > MAX_ADD_STEPS_PER_CYCLE) {
                    logger.warn { "[ORCHESTRATION] Reflection tried to add ${addStepActions.size} steps, limiting to $MAX_ADD_STEPS_PER_CYCLE. Discarding excess actions." }

                    // Save warning to chat
                    chatMessageRepository.create(
                        taskId = task.id,
                        role = MessageRole.SYSTEM,
                        content = "⚠️ **Orchestrator Limit:** Attempted to add ${addStepActions.size} steps in one cycle. " +
                                "Limiting to first $MAX_ADD_STEPS_PER_CYCLE steps to prevent plan explosion.",
                        metadata = gson.toJson(mapOf("type" to "orchestrator_warning"))
                    )
                }

                // Filter actions: keep only first MAX_ADD_STEPS_PER_CYCLE ADD_STEP actions
                var addStepCount = 0
                val filteredActions = reflection.actions.filter { action ->
                    if (action is ReflectionAction.AddStep) {
                        addStepCount++
                        addStepCount <= MAX_ADD_STEPS_PER_CYCLE
                    } else {
                        true // Keep all non-ADD_STEP actions
                    }
                }

                // Execute filtered actions with error handling
                filteredActions.forEach { action ->
                    try {
                        when (action) {
                            is ReflectionAction.AddStep -> {
                                planModifier.addSubtask(
                                    taskId = task.id,
                                    afterStep = action.afterStep,
                                    description = action.description,
                                    kind = action.kind ?: "plan_step",
                                    suggestedParams = action.suggestedParams
                                )
                            }

                            is ReflectionAction.SkipStep -> {
                                planModifier.skipSubtask(
                                    taskId = task.id,
                                    step = action.step,
                                    reason = action.reason
                                )
                            }

                            is ReflectionAction.ModifyStep -> {
                                planModifier.modifySubtask(
                                    taskId = task.id,
                                    step = action.step,
                                    newDescription = action.newDescription,
                                    newParams = action.newParams
                                )
                            }

                            is ReflectionAction.RetryStep -> {
                                planModifier.retrySubtask(
                                    taskId = task.id,
                                    step = action.step,
                                    reason = action.reason
                                )
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        // Tool validation error - log and notify via chat, but don't crash
                        logger.warn(e) { "[ORCHESTRATION] Plan modification failed: ${e.message}" }

                        chatMessageRepository.create(
                            taskId = task.id,
                            role = MessageRole.SYSTEM,
                            content = "⚠️ **Plan Modification Error:**\n\n${e.message}\n\n" +
                                    "Attempted action: ${action::class.simpleName}\n" +
                                    "The orchestrator will continue with remaining actions.",
                            metadata = gson.toJson(mapOf(
                                "type" to "plan_modification_error",
                                "error" to e.message,
                                "action_type" to action::class.simpleName
                            ))
                        )
                    }
                }

                true // Continue with modified plan
            }

            DecisionType.ASK_USER -> {
                logger.info { "[ORCHESTRATION] Asking user question" }

                val question = reflection.question
                    ?: "I need your guidance to continue. What should I do?"

                val questionId = userInteraction.askQuestion(
                    taskId = task.id,
                    question = question,
                    options = reflection.questionOptions
                )

                // Wait for response (suspends until user provides answer)
                val response = userInteraction.waitForResponse(questionId)

                // Save response to chat
                chatMessageRepository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = response,
                    metadata = gson.toJson(
                        mapOf(
                            "type" to "question_response",
                            "question_id" to questionId
                        )
                    )
                )

                logger.info { "[ORCHESTRATION] Received user response, resuming execution" }

                true // Continue after receiving response
            }

            DecisionType.ABORT -> {
                logger.warn { "[ORCHESTRATION] Aborting execution: ${reflection.reasoning}" }

                chatMessageRepository.create(
                    taskId = task.id,
                    role = MessageRole.SYSTEM,
                    content = "⚠️ **Execution Aborted:**\n\n${reflection.reasoning}",
                    metadata = gson.toJson(mapOf("type" to "orchestration_abort"))
                )

                taskRepository.update(id = task.id, status = TaskStatus.FAILED)

                false // Stop execution
            }
        }
    }

    /**
     * Extract code changes metadata from execution result.
     * Returns map with code_changes info or null if no code changes.
     */
    private fun extractCodeChangesMetadata(
        execResult: pl.jclab.refio.core.services.StepExecutionResult,
        subtask: Subtask
    ): Map<String, Any>? {
        val toolResult = execResult.result ?: return null

        // Find code editing tools
        val codeEditingTools = toolResult.outputs.filter { output ->
            output.tool in listOf("code_editing", "advance_code_editing") && output.result.success
        }

        if (codeEditingTools.isEmpty()) return null

        val toolOutput = codeEditingTools.first()
        val metadata = toolOutput.result.metadata ?: return null

        val filePath = metadata["path"] as? String ?: return null
        val addedLines = (metadata["added_lines"] as? Number)?.toInt() ?: 0
        val removedLines = (metadata["removed_lines"] as? Number)?.toInt() ?: 0

        if (addedLines == 0 && removedLines == 0) return null

        val snapshotId = subtask.snapshotIdBeforeWrite ?: ""

        return mapOf<String, Any>(
            "type" to "code_changes",
            "file_path" to filePath,
            "added_lines" to addedLines,
            "removed_lines" to removedLines,
            "snapshot_id" to snapshotId
        )
    }
}
