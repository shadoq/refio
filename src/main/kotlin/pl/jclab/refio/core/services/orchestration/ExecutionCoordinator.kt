package pl.jclab.refio.core.services.orchestration

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo

private val logger = dualLogger("ExecutionCoordinator")

/**
 * Execution Coordinator - main orchestrator with reflection loop.
 *
 * Coordinates step-by-step execution with reflection after each step.
 * This is the "brain" of intelligent task execution.
 */
class ExecutionCoordinator(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val agentExecutor: AgentExecutor,
    private val reflectionEngine: ReflectionEngine,
    private val planModifier: PlanModifier,
    private val userInteraction: UserInteraction
) {

    /**
     * Execute task with intelligent orchestration.
     *
     * Main loop:
     * 1. Find next PENDING subtask
     * 2. Plan and execute step
     * 3. Reflect on result
     * 4. Handle decision (continue, modify plan, ask user, abort)
     * 5. Repeat until all steps done or aborted
     */
    suspend fun executeWithReflection(taskId: String): OrchestrationResult {
        logger.info { "[ORCHESTRATOR] Starting intelligent execution for task: $taskId" }
        val startTime = System.currentTimeMillis()

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        var stepsExecuted = 0
        var stepsFailed = 0
        val reflections = mutableListOf<ReflectionDecision>()
        val totalSteps = subtaskRepository.countByTaskId(taskId).toInt().coerceAtLeast(1)
        val orchestrationToken = GlobalMetrics.beginOperation(
            OperationInfo.Orchestration("Running", null, totalSteps)
        )

        try {
            while (true) {
                // Find next PENDING subtask
                val nextSubtask = subtaskRepository.findByStatus(taskId, TaskStatus.PENDING)
                    .sortedBy { it.orderIndex }
                    .firstOrNull()

                if (nextSubtask == null) {
                    logger.info { "[ORCHESTRATOR] No more PENDING subtasks" }
                    break
                }

                logger.info { "[ORCHESTRATOR] Executing step ${nextSubtask.orderIndex}: ${nextSubtask.description}" }

                // Plan step
                val planResult = agentExecutor.planStep(taskId, nextSubtask.id)
                if (planResult.error != null) {
                    logger.error { "[ORCHESTRATOR] Planning failed: ${planResult.error}" }
                    stepsFailed++

                    // Reflect even on planning failure
                    val reasoningToken = GlobalMetrics.beginOperation(
                        OperationInfo.StepReasoning(nextSubtask.orderIndex, totalSteps)
                    )
                    val reflection = try {
                        reflectionEngine.reflect(
                            task = task,
                            subtask = nextSubtask,
                            result = pl.jclab.refio.core.services.StepExecutionResult(
                                status = "failed",
                                result = null,
                                summary = "Planning failed: ${planResult.error}",
                                durationMs = planResult.durationMs,
                                error = planResult.error
                            )
                        )
                    } finally {
                        GlobalMetrics.endOperation(reasoningToken)
                    }
                    reflections.add(reflection)

                    val shouldContinue = handleDecision(task, reflection)
                    if (!shouldContinue) break
                    continue
                }

                // Show "executing" message in UI with tools and parameters (similar to StepExecutionService.autoApproveNextStep)
                // Save as temporary message - will be deleted after execution
                var tempMessageId: String? = null
                val plan = planResult.plan
                if (plan != null) {
                    val toolsList = plan.tools.joinToString("\n") { tool ->
                        val argsPreview = tool.params.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
                        val argsText = if (argsPreview.isEmpty()) "" else " ($argsPreview)"
                        "  • ${tool.name}$argsText"
                    }

                    val executingMessage = "⏳ **Step ${nextSubtask.orderIndex}**: ${plan.description}\n\n" +
                            "**Tools:**\n$toolsList\n\n" +
                            "Executing..."

                    val createdMessage = chatMessageRepository.create(
                        taskId = taskId,
                        role = MessageRole.SYSTEM,
                        content = executingMessage,
                        metadata = gson.toJson(mapOf(
                            "type" to "step_executing",
                            "subtask_id" to nextSubtask.id,
                            "step_number" to nextSubtask.orderIndex,
                            "tools_count" to plan.tools.size
                        ))
                    )

                    tempMessageId = createdMessage.id
                    logger.info { "[ORCHESTRATOR] Displayed execution message for step ${nextSubtask.orderIndex} with ${plan.tools.size} tools (temp ID: $tempMessageId)" }
                }

                // Execute step
                val execResult = agentExecutor.executeStep(taskId, nextSubtask.id)

                // Delete temporary "executing" message after execution completes
                if (tempMessageId != null) {
                    try {
                        chatMessageRepository.delete(tempMessageId)
                        logger.info { "[ORCHESTRATOR] Deleted temporary execution message: $tempMessageId" }
                    } catch (e: Exception) {
                        logger.warn(e) { "[ORCHESTRATOR] Failed to delete temporary message: $tempMessageId" }
                    }
                }

                if (execResult.status == "success") {
                    stepsExecuted++
                } else {
                    stepsFailed++
                }

                // Extract code changes metadata from execution result
                val codeChangesMetadata = extractCodeChangesMetadata(execResult, nextSubtask)

                // Build combined metadata (step_summary + code_changes)
                val messageMetadata = if (codeChangesMetadata != null) {
                    logger.info { "[EXEC_COORD] Adding code changes metadata to step summary message" }
                    // Merge both metadata types
                    codeChangesMetadata
                } else {
                    // Only step_summary metadata
                    mapOf(
                        "type" to "step_summary",
                        "subtask_id" to nextSubtask.id,
                        "step_number" to nextSubtask.orderIndex
                    )
                }

                // Save summary to chat (so user sees progress)
                chatMessageRepository.create(
                    taskId = taskId,
                    role = MessageRole.ASSISTANT,
                    content = "📝 **Step ${nextSubtask.orderIndex} Summary:**\n\n${execResult.summary}",
                    metadata = gson.toJson(messageMetadata)
                )

                // ✨ REFLECTION ✨
                val reasoningToken = GlobalMetrics.beginOperation(
                    OperationInfo.StepReasoning(nextSubtask.orderIndex, totalSteps)
                )
                val reflection = try {
                    reflectionEngine.reflect(task, nextSubtask, execResult)
                } finally {
                    GlobalMetrics.endOperation(reasoningToken)
                }
                reflections.add(reflection)

                // Save reflection analysis to chat (with collapsible details)
                val reflectionContent = buildString {
                    append("🤔 **Reflection - Step ${nextSubtask.orderIndex}:**\n\n")
                    append("${reflection.analysis}\n\n")
                    append("**Decision:** ${reflection.decision.name.lowercase().replace('_', ' ')}\n\n")
                    append("**Reasoning:** ${reflection.reasoning}")
                }

                chatMessageRepository.create(
                    taskId = taskId,
                    role = MessageRole.SYSTEM,
                    content = reflectionContent,
                    metadata = gson.toJson(mapOf(
                        "type" to "reflection",
                        "decision" to reflection.decision.name,
                        "subtask_id" to nextSubtask.id
                    ))
                )

                // Handle decision
                val shouldContinue = handleDecision(task, reflection)

                if (!shouldContinue) {
                    logger.info { "[ORCHESTRATOR] Execution stopped by reflection decision" }
                    break
                }
            }

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            logger.info { "[ORCHESTRATOR] Execution complete: $stepsExecuted executed, $stepsFailed failed" }

            // Save final summary
            val finalContent = buildString {
                append("✅ **Orchestration Complete**\n\n")
                append("- Steps executed: $stepsExecuted\n")
                append("- Steps failed: $stepsFailed\n")
                append("- Reflections: ${reflections.size}\n")
                append("- Plan modifications: ${reflections.count { it.decision == DecisionType.MODIFY_PLAN }}\n")
                append("- Duration: ${durationMs / 1000.0}s")
            }

            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.SYSTEM,
                content = finalContent,
                metadata = gson.toJson(mapOf(
                    "type" to "orchestration_complete"
                ))
            )

            return OrchestrationResult(
                success = stepsFailed == 0,
                stepsExecuted = stepsExecuted,
                stepsFailed = stepsFailed,
                reflections = reflections,
                durationMs = durationMs
            )

        } catch (e: Exception) {
            logger.error(e) { "[ORCHESTRATOR] Execution failed" }

            val durationMs = (System.currentTimeMillis() - startTime).toInt()

            // Save error to chat
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.SYSTEM,
                content = "❌ **Orchestration Failed:** ${e.message}",
                metadata = gson.toJson(mapOf(
                    "type" to "orchestration_error",
                    "error" to e.message
                ))
            )

            return OrchestrationResult(
                success = false,
                stepsExecuted = stepsExecuted,
                stepsFailed = stepsFailed + 1,
                reflections = reflections,
                durationMs = durationMs,
                error = e.message
            )
        } finally {
            GlobalMetrics.endOperation(orchestrationToken)
        }
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
        logger.info { "[ORCHESTRATOR] Handling decision: ${reflection.decision}" }

        return when (reflection.decision) {
            DecisionType.CONTINUE -> {
                // Continue with plan
                logger.info { "[ORCHESTRATOR] Continuing with plan" }
                true
            }

            DecisionType.MODIFY_PLAN -> {
                // Execute plan modifications
                logger.info { "[ORCHESTRATOR] Modifying plan: ${reflection.actions.size} actions" }

                reflection.actions.forEach { action ->
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
                }

                true // Continue with modified plan
            }

            DecisionType.ASK_USER -> {
                // Ask user question and wait for response
                logger.info { "[ORCHESTRATOR] Asking user question" }

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
                    metadata = gson.toJson(mapOf(
                        "type" to "question_response",
                        "question_id" to questionId
                    ))
                )

                logger.info { "[ORCHESTRATOR] Received user response, resuming execution" }

                true // Continue after receiving response
            }

            DecisionType.ABORT -> {
                // Abort execution
                logger.warn { "[ORCHESTRATOR] Aborting execution: ${reflection.reasoning}" }

                chatMessageRepository.create(
                    taskId = task.id,
                    role = MessageRole.SYSTEM,
                    content = "⚠️ **Execution Aborted:**\n\n${reflection.reasoning}",
                    metadata = gson.toJson(mapOf(
                        "type" to "orchestration_abort"
                    ))
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
        subtask: pl.jclab.refio.core.db.Subtask
    ): Map<String, Any>? {
        val toolResult = execResult.result ?: run {
            logger.debug { "[EXEC_COORD] No tool result in execution result" }
            return null
        }

        logger.debug { "[EXEC_COORD] Checking ${toolResult.outputs.size} tool outputs for code changes" }

        // Find code editing tools
        val codeEditingTools = toolResult.outputs.filter { output ->
            output.tool in listOf("code_editing", "advance_code_editing") && output.result.success
        }

        if (codeEditingTools.isEmpty()) {
            logger.debug { "[EXEC_COORD] No code editing tools found" }
            return null
        }

        val toolOutput = codeEditingTools.first()
        val metadata = toolOutput.result.metadata ?: return null

        val filePath = metadata["path"] as? String ?: return null
        val addedLines = (metadata["added_lines"] as? Number)?.toInt() ?: 0
        val removedLines = (metadata["removed_lines"] as? Number)?.toInt() ?: 0

        if (addedLines == 0 && removedLines == 0) return null

        logger.info { "[EXEC_COORD] ✅ Found code changes: file=$filePath, added=$addedLines, removed=$removedLines" }

        return mapOf<String, Any>(
            "type" to "code_changes",
            "file_path" to filePath,
            "added_lines" to addedLines,
            "removed_lines" to removedLines,
            "snapshot_id" to (subtask.snapshotIdBeforeWrite ?: "")
        )
    }
}

/**
 * Orchestration execution result
 */
data class OrchestrationResult(
    val success: Boolean,
    val stepsExecuted: Int,
    val stepsFailed: Int,
    val reflections: List<ReflectionDecision>,
    val durationMs: Int,
    val error: String? = null
)
