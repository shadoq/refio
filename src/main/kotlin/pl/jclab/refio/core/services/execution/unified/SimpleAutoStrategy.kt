package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("SimpleAutoStrategy")

/**
 * Simple auto execution strategy without orchestration.
 *
 * Executes steps sequentially without reflection or plan modification.
 * Continues execution even if steps fail.
 */
class SimpleAutoStrategy(
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val agentExecutor: AgentExecutor
) : ExecutionStrategy {

    override suspend fun findNextStep(taskId: String): Subtask? {
        val pendingSubtasks = subtaskRepository.findByStatus(taskId, TaskStatus.PENDING)
            .sortedBy { it.orderIndex }

        return pendingSubtasks.firstOrNull()
    }

    override suspend fun preparePlan(subtask: Subtask, listener: ExecutionEventListener?): StepPlan {
        logger.info { "[SIMPLE] Planning step: ${subtask.id}, streaming=${listener != null}" }

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

        return StepPlan(
            subtaskId = subtask.id,
            description = plan.description,
            tools = plan.tools.map { tool ->
                ToolPlan(name = tool.name, params = tool.params)
            },
            estimatedDurationMs = null,
            planDecision = plan.planDecision
        )
    }

    override suspend fun executeStep(subtask: Subtask, plan: StepPlan, listener: ExecutionEventListener?): StepResult {
        logger.info { "[SIMPLE] Executing step: ${subtask.id}, streaming=${listener != null}" }

        val execResult = agentExecutor.executeStep(
            taskId = subtask.taskId,
            subtaskId = subtask.id,
            listener = listener
        )

        // Extract code changes metadata from execution result
        val codeChangesMetadata = extractCodeChangesMetadata(execResult, subtask)

        // Build combined metadata (step_summary + code_changes)
        val messageMetadata = if (codeChangesMetadata != null) {
            logger.info { "[SIMPLE] Adding code changes metadata to step summary message" }
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
        // Simple strategy: continue execution even if steps fail (no reflection)
        if (result.status != "success") {
            logger.warn { "[SIMPLE] Step failed, but continuing execution: ${result.error}" }
        } else {
            logger.info { "[SIMPLE] Step completed successfully, continuing" }
        }

        return true
    }

    override suspend fun onExecutionComplete(taskId: String, stats: ExecutionStats) {
        logger.info { "[SIMPLE] Execution complete for task $taskId: ${stats.stepsExecuted} executed, ${stats.stepsFailed} failed" }
    }

    /**
     * Extract code changes metadata from execution result.
     * Returns map with code_changes info or null if no code changes.
     */
    private fun extractCodeChangesMetadata(
        execResult: pl.jclab.refio.core.services.StepExecutionResult,
        subtask: Subtask
    ): Map<String, Any>? {
        logger.debug { "[METADATA] Extracting code changes metadata for subtask ${subtask.id}" }

        val toolResult = execResult.result ?: run {
            logger.debug { "[METADATA] No tool result in execution result" }
            return null
        }

        // Find code editing tools
        val codeEditingTools = toolResult.outputs.filter { output ->
            output.tool in listOf("code_editing", "advance_code_editing") && output.result.success
        }
        logger.debug { "[METADATA] Found ${codeEditingTools.size} code editing tools in result" }

        if (codeEditingTools.isEmpty()) return null

        val toolOutput = codeEditingTools.first()
        logger.debug { "[METADATA] Using tool: ${toolOutput.tool}" }

        val metadata = toolOutput.result.metadata ?: run {
            logger.warn { "[METADATA] No metadata in tool result" }
            return null
        }
        logger.debug { "[METADATA] Tool metadata: $metadata" }

        val filePath = metadata["path"] as? String ?: run {
            logger.warn { "[METADATA] Missing 'path' in tool metadata" }
            return null
        }
        val addedLines = (metadata["added_lines"] as? Number)?.toInt() ?: 0
        val removedLines = (metadata["removed_lines"] as? Number)?.toInt() ?: 0

        logger.info { "[METADATA] Extracted from tool: path=$filePath, +$addedLines -$removedLines" }

        if (addedLines == 0 && removedLines == 0) {
            logger.debug { "[METADATA] No line changes, skipping metadata" }
            return null
        }

        val snapshotId = subtask.snapshotIdBeforeWrite ?: ""
        logger.info { "[METADATA] Subtask snapshotIdBeforeWrite: $snapshotId" }

        val metadataMap = mapOf<String, Any>(
            "type" to "code_changes",
            "file_path" to filePath,
            "added_lines" to addedLines,
            "removed_lines" to removedLines,
            "snapshot_id" to snapshotId
        )

        logger.info { "[METADATA] Created metadata map: $metadataMap" }

        return metadataMap
    }
}
