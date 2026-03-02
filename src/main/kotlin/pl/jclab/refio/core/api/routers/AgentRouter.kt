package pl.jclab.refio.core.api.routers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.project.Project
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.ToolExecutionResult
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.services.ToolCallOutput
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("AgentRouter")

/**
 * Router for agent execution operations.
 * Handles agent execution and monitoring.
 *
 * This router is responsible for:
 * - Planning and executing individual subtask steps
 * - Auto mode execution (execute all pending steps)
 * - Streaming execution progress
 *
 * @property agentExecutor Agent execution service
 * @property taskRepository Task management repository
 * @property subtaskRepository Subtask storage repository
 * @property chatMessageRepository Chat message storage (for approval messages)
 * @property configService Configuration service
 * @property llmClient LLM client
 * @property promptsService Prompts service
 * @property contextService Context service (for execution summaries)
 * @property projectRoot Project root path (for execution summaries)
 * @property ideProject IntelliJ project instance (for context building)
 */
class AgentRouter(
    private val agentExecutor: AgentExecutor?,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val configService: ConfigService,
    private val llmClient: pl.jclab.refio.core.llm.LLMClient,
    private val promptsService: pl.jclab.refio.core.services.PromptsService,
    private val contextService: ContextService?,
    private val projectRoot: Path?,
    private val ideProject: Project?,
    private val toolDescriptionBuilder: pl.jclab.refio.core.prompts.ToolDescriptionBuilder
) : Router {

    private val gson = pl.jclab.refio.core.utils.GsonInstance.gson

    override suspend fun initialize() {
        logger.info { "[AgentRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[AgentRouter] Shutting down" }
    }

    // ===== Agent Execution Operations =====

    /**
     * Plan subtask execution.
     *
     * Generates execution plan for a subtask using StepPlanner.
     * Updates subtask status from PENDING → PLANNED.
     * Saves approval message to chat_messages for UI display.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to plan
     * @return Plan with tools and estimated duration
     * @throws IllegalStateException if agent executor not available
     * @throws IllegalArgumentException if subtask not found
     */
    fun planSubtaskStep(taskId: String, subtaskId: String): PlanStepResponse {
        logger.info { "[AgentRouter] Planning subtask step: taskId=$taskId, subtaskId=$subtaskId" }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        return runBlocking {
            val result = agentExecutor.planStep(taskId, subtaskId)

            if (result.error != null) {
                throw IllegalStateException("Planning failed: ${result.error}")
            }

            val plan = result.plan!!

            // Get subtask for order index
            val subtask = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            // Save message to chat ONLY if subtask requires approval (INTERACTIVE mode)
            if (subtask.requiresApproval) {
                // Format approval message with tools
                val toolsList = plan.tools.joinToString("\n") { spec ->
                    val argsPreview = spec.params.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
                    val argsText = if (argsPreview.isEmpty()) "" else " ($argsPreview)"
                    "  • ${spec.name}$argsText"
                }

                // Format decision info (suggested vs selected)
                val decisionInfo = plan.planDecision?.let { decision ->
                    buildString {
                        append("\n**Plan Decision:**\n")
                        append("- Intent: ${decision.intent}\n")
                        append("- Suggested: `${decision.suggestedTool}`")
                        if (decision.suggestedParams.isNotEmpty()) {
                            append(" (${decision.suggestedParams.keys.joinToString(", ")})")
                        }
                        append("\n")
                        append("- Selected: `${decision.selectedTool}`")
                        if (decision.selectedParams.isNotEmpty()) {
                            append(" (${decision.selectedParams.keys.joinToString(", ")})")
                        }
                        append("\n")
                        if (decision.wasModified) {
                            append("- ⚠️ Modified by LLM")
                            decision.reasoning?.let { append(": $it") }
                        } else {
                            append("- ✓ Kept as suggested")
                        }
                    }
                } ?: ""

                val approvalMessage = """
📋 **Step ${subtask.orderIndex}**: ${plan.description}

**Tools:**
$toolsList
$decisionInfo

Approve execution?

**Subtask ID:** `$subtaskId`
                """.trimIndent()

                val metrics = plan.llmMetrics?.let { MessageMetrics.toJson(it) }

                chatMessageRepository.create(
                    taskId = taskId,
                    role = MessageRole.ASSISTANT,
                    content = approvalMessage,
                    metadata = metrics,
                    tokensIn = plan.llmMetrics?.inputTokens,
                    tokensOut = plan.llmMetrics?.outputTokens,
                    cost = plan.llmMetrics?.costUsd
                )

                logger.info { "[AgentRouter] Approval message saved for subtask $subtaskId (INTERACTIVE mode)" }
            } else {
                logger.info { "[AgentRouter] Skipping approval message for subtask $subtaskId (AUTO mode)" }
            }

            PlanStepResponse(
                tools = plan.tools.map { spec ->
                    ToolCallResponse(
                        name = spec.name,
                        params = spec.params,
                        expectedOutput = spec.expectedOutput
                    )
                },
                description = plan.description,
                estimatedDurationMs = plan.estimatedDurationMs,
                dependencies = plan.dependencies
            )
        }
    }

    /**
     * Execute subtask step.
     *
     * Executes tools from subtask plan using AgentExecutor.
     * Updates subtask status: PLANNED/PENDING → RUNNING → SUCCESS/FAILED.
     * Saves execution summary to chat_messages for UI display.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to execute
     * @return Execution result with status and summary
     * @throws IllegalStateException if agent executor not available
     * @throws IllegalArgumentException if subtask not found
     */
    fun executeSubtaskStep(taskId: String, subtaskId: String): ExecuteStepResponse {
        logger.info { "[AgentRouter] Executing subtask step: taskId=$taskId, subtaskId=$subtaskId" }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        return runBlocking {
            val result = agentExecutor.executeStep(taskId, subtaskId)

            // Get subtask for order index
            val subtask = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            // Format summary message
            val statusEmoji = if (result.status == "success") "✅" else "❌"
            val summaryMessage = """
$statusEmoji **Step ${subtask.orderIndex}**: ${subtask.description}

${result.summary}

_Execution time: ${result.durationMs}ms_
            """.trimIndent()

            val metrics = result.llmMetrics?.let { MessageMetrics.toJson(it) }

            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = summaryMessage,
                metadata = metrics,
                tokensIn = result.llmMetrics?.inputTokens,
                tokensOut = result.llmMetrics?.outputTokens,
                cost = result.llmMetrics?.costUsd
            )

            logger.info { "[AgentRouter] Execution summary saved for subtask $subtaskId: ${result.status}" }

            ExecuteStepResponse(
                status = result.status,
                summary = result.summary,
                durationMs = result.durationMs,
                error = result.error
            )
        }
    }

    /**
     * Execute subtask step with optional listener for streaming tool output.
     *
     * Skips re-planning and uses existing step plan (if present) to emit UI events.
     *
     * @param taskId Parent task ID
     * @param subtaskId Subtask ID to execute
     * @param externalListener Optional listener for streaming tool output
     * @return Execution result with status and summary
     * @throws IllegalStateException if agent executor not available
     * @throws IllegalArgumentException if subtask not found
     */
    fun executeSubtaskStepWithListener(
        taskId: String,
        subtaskId: String,
        externalListener: ExecutionEventListener? = null
    ): ExecuteStepResponse {
        logger.info {
            "[AgentRouter] Executing subtask step with listener: taskId=$taskId, subtaskId=$subtaskId"
        }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        return runBlocking {
            val subtaskForPlan = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            buildStepPlanFromJson(subtaskForPlan)?.let { plan ->
                externalListener?.onStepExecuting(subtaskForPlan, plan)
            }

            val result = agentExecutor.executeStep(taskId, subtaskId, externalListener)

            // Get subtask for order index
            val subtask = subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

            // Format summary message
            val statusEmoji = if (result.status == "success") "[OK]" else "[FAIL]"
            val summaryMessage = """
$statusEmoji **Step ${subtask.orderIndex}**: ${subtask.description}

${result.summary}

_Execution time: ${result.durationMs}ms_
            """.trimIndent()

            val metrics = result.llmMetrics?.let { MessageMetrics.toJson(it) }

            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = summaryMessage,
                metadata = metrics,
                tokensIn = result.llmMetrics?.inputTokens,
                tokensOut = result.llmMetrics?.outputTokens,
                cost = result.llmMetrics?.costUsd
            )

            logger.info { "[AgentRouter] Execution summary saved for subtask $subtaskId: ${result.status}" }

            ExecuteStepResponse(
                status = result.status,
                summary = result.summary,
                durationMs = result.durationMs,
                error = result.error
            )
        }
    }

    private fun buildStepPlanFromJson(
        subtask: pl.jclab.refio.core.db.Subtask
    ): pl.jclab.refio.core.services.execution.unified.StepPlan? {
        val planJson = subtask.stepPlanJson ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val planMap = gson.fromJson(planJson, Map::class.java) as Map<String, Any>

            @Suppress("UNCHECKED_CAST")
            val toolsArray = planMap["tools"] as? List<Map<String, Any>> ?: emptyList()

            val tools = toolsArray.mapNotNull { toolData ->
                val name = toolData["name"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val params = toolData["params"] as? Map<String, Any> ?: emptyMap()
                pl.jclab.refio.core.services.execution.unified.ToolPlan(
                    name = name,
                    params = params
                )
            }

            val description = (planMap["description"] as? String)
                ?: subtask.description

            val planDecision = planMap["planDecision"]?.let { decision ->
                gson.fromJson(gson.toJson(decision), pl.jclab.refio.core.api.PlanDecisionInfo::class.java)
            }

            pl.jclab.refio.core.services.execution.unified.StepPlan(
                subtaskId = subtask.id,
                description = description,
                tools = tools,
                planDecision = planDecision
            )
        } catch (e: Exception) {
            logger.warn(e) { "[AgentRouter] Failed to parse stepPlanJson for subtask ${subtask.id}" }
            null
        }
    }

    /**
     * Approve task plan for auto execution.
     */
    fun approvePlan(taskId: String) {
        logger.info { "[AgentRouter] Approving plan: taskId=$taskId" }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        runBlocking { agentExecutor.approvePlan(taskId) }
    }

    /**
     * Reject task plan for auto execution.
     */
    fun rejectPlan(taskId: String, reason: String? = null) {
        logger.info { "[AgentRouter] Rejecting plan: taskId=$taskId" }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        runBlocking { agentExecutor.rejectPlan(taskId, reason) }
    }

    /**
     * Get plan summary for approval UI.
     */
    fun getPlanSummary(taskId: String): PlanSummaryResponse {
        logger.info { "[AgentRouter] Getting plan summary: taskId=$taskId" }

        if (agentExecutor == null) {
            throw IllegalStateException("Agent execution not available - ToolRegistry not initialized")
        }

        return runBlocking { agentExecutor.getPlanSummary(taskId) }
    }

    // ===== Execution Summary =====

    /**
     * Generate execution summary via LLM after PLAN/AGENT completion.
     * Creates a detailed, natural language summary of what was accomplished.
     *
     * @param taskId Task ID
     * @return Summary text
     * @throws IllegalArgumentException if task not found
     */
    suspend fun generateExecutionSummary(taskId: String): String {
        logger.info { "[AgentRouter] Generating execution summary for task: $taskId" }

        // Check if ContextService is available
        if (contextService == null || projectRoot == null) {
            logger.warn { "[AgentRouter] ContextService or projectRoot not available, skipping summary generation" }
            return "Summary unavailable - missing project context"
        }

        val task = DatabaseFactory.dbQuery {
            taskRepository.findById(taskId)
        } ?: throw IllegalArgumentException("Task not found: $taskId")

        val subtasks = DatabaseFactory.dbQuery {
            subtaskRepository.findByTaskId(taskId)
        }

        // Build full project context using ContextService
        val projectContext = contextService.buildProjectContext(
            projectRoot = projectRoot,
            taskId = taskId,
            project = ideProject
        )

        // Calculate statistics
        val completedSubtasks = subtasks.filter { it.status == TaskStatus.SUCCESS }
        val failedSubtasks = subtasks.filter { it.status == TaskStatus.FAILED }
        val totalTokensIn = subtasks.sumOf { it.inputTokens }
        val totalTokensOut = subtasks.sumOf { it.outputTokens }
        val totalCost = subtasks.sumOf { it.costUsd }

        // Extract tools used
        val toolsUsed = mutableMapOf<String, Int>()
        subtasks.forEach { subtask ->
            subtask.stepPlanJson?.let { planJson ->
                try {
                    val plan = gson.fromJson(planJson, Map::class.java)

                    @Suppress("UNCHECKED_CAST")
                    val tools = (plan["tools"] as? List<Map<String, Any>>)
                    tools?.forEach { tool ->
                        val toolName = tool["name"] as? String ?: "unknown"
                        toolsUsed[toolName] = (toolsUsed[toolName] ?: 0) + 1
                    }
                } catch (e: Exception) {
                    // Ignore JSON parsing errors
                }
            }
        }

        // Extract models used
        val modelsUsed = subtasks.mapNotNull { it.llmModel }.distinct()
        val providersUsed = subtasks.mapNotNull { it.llmProvider }.distinct()

        // Aggregate changed files for summary metadata
        val changedFiles = aggregateChangedFilesForSummary(subtasks)

        // Build context for LLM
        val contextData = buildString {
            append("# Task Execution Data\n\n")

            append("## Project Context\n")
            append("- **Project Type**: ${projectContext.projectType}\n")
            append("- **Main Language**: ${projectContext.summary.mainLanguage}\n")
            append("- **Technologies**: ${projectContext.technologies.joinToString(", ")}\n")
            append("- **Total Files**: ${projectContext.structure.totalFiles}\n")
            if (projectContext.keyComponents.isNotEmpty()) {
                append("- **Key Components**: ${projectContext.keyComponents.take(5).joinToString(", ")}\n")
            }
            append("\n")

            append("## Task\n")
            append("- **Name**: ${task.name}\n")
            append("- **Mode**: ${task.mode.name}\n")
            append("- **Final Status**: ${task.status.name}\n")
            if (projectContext.userRequirements.isNotEmpty()) {
                val requirements =
                    projectContext.userRequirements.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                append("- **User Requirements**: $requirements\n")
            }
            append("\n")

            append("## Execution Flow - Detailed Steps\n")
            append("Executed ${subtasks.size} steps:\n\n")
            subtasks.forEachIndexed { idx, subtask ->
                val stepNum = idx + 1
                val statusIcon = when (subtask.status.name) {
                    "SUCCESS" -> "✅"
                    "FAILED" -> "❌"
                    "CANCELED" -> "⏭️"
                    else -> "⏸️"
                }
                append("**Step $stepNum** $statusIcon: ${subtask.description}\n")

                // Add tool details if available
                subtask.stepPlanJson?.let { planJson ->
                    try {
                        val plan = gson.fromJson(planJson, Map::class.java)

                        @Suppress("UNCHECKED_CAST")
                        val tools = (plan["tools"] as? List<Map<String, Any>>)
                        tools?.forEach { tool ->
                            val toolName = tool["name"] as? String ?: "unknown"

                            @Suppress("UNCHECKED_CAST")
                            val params = tool["params"] as? Map<String, Any>
                            val paramsStr =
                                params?.entries?.take(3)?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
                            append("  → Tool: **$toolName**${if (paramsStr.isNotEmpty()) " ($paramsStr)" else ""}\n")
                        }
                    } catch (e: Exception) {
                        // Ignore JSON parsing errors
                    }
                }

                // Add summary if available
                subtask.summary?.let { summary ->
                    if (summary.isNotBlank() && summary.length < 200) {
                        append("  → Result: ${summary.take(150)}${if (summary.length > 150) "..." else ""}\n")
                    }
                }

                // Add error if failed
                if (subtask.status.name == "FAILED" && subtask.errorMessage != null) {
                    append("  → Error: ${subtask.errorMessage}\n")
                }

                append("\n")
            }

            append("## Statistics\n")
            append("- **Completed Steps**: ${completedSubtasks.size}/${subtasks.size}\n")
            append("- **Failed Steps**: ${failedSubtasks.size}\n\n")

            // Add completed files from context
            if (projectContext.completedFiles.isNotEmpty()) {
                append("## Modified Files\n")
                projectContext.completedFiles.take(10).forEach { file ->
                    append("- $file\n")
                }
                if (projectContext.completedFiles.size > 10) {
                    append("... and ${projectContext.completedFiles.size - 10} more files\n")
                }
                append("\n")
            }

            append("## Costs and Metrics\n")
            append("- **Input Tokens**: ${totalTokensIn}\n")
            append("- **Output Tokens**: ${totalTokensOut}\n")
            append("- **Total Tokens**: ${totalTokensIn + totalTokensOut}\n")
            append("- **Total Cost**: $${"%.4f".format(totalCost)} USD\n\n")

            if (toolsUsed.isNotEmpty()) {
                append("## Tools Used\n")
                toolsUsed.entries.sortedByDescending { it.value }.take(5).forEach { (tool, count) ->
                    append("- **$tool**: ${count}× invocations\n")
                }
                append("\n")
            }

            if (modelsUsed.isNotEmpty()) {
                append("## LLM Models\n")
                append("- **Models**: ${modelsUsed.joinToString(", ")}\n")
                append("- **Providers**: ${providersUsed.joinToString(", ")}\n\n")
            }

            if (failedSubtasks.isNotEmpty()) {
                append("## Errors\n")
                failedSubtasks.take(3).forEach { subtask ->
                    append("- ${subtask.description}: ${subtask.errorMessage ?: "Unknown error"}\n")
                }
                append("\n")
            }
        }

        // Build prompt using PromptsService (loaded from database with variable substitution)
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_EXECUTION_SUMMARY,
            variables = mapOf("context" to contextData)
        )

        // Get model from config (uses WEAK operation type)
        val (modelId, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = taskId
        )

        // Call LLM (systemPrompt already contains instructions and context data)
        val llmResponse = llmClient.complete(
            provider = provider,
            model = modelId,
            messages = listOf(
                LLMMessage(role = "user", content = systemPrompt)
            ),
            temperature = 0.7,
            maxTokens = 1500,
            source = "generateExecutionSummary"
        )

        val summaryText = llmResponse.content

        // Save summary to chat messages
        DatabaseFactory.dbQuery {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = summaryText,
                metadata = gson.toJson(
                    mapOf(
                        "type" to "execution_summary",
                        "generated_at" to System.currentTimeMillis(),
                        "model" to modelId,
                        "provider" to provider,
                        "stats" to mapOf(
                            "total_steps" to subtasks.size,
                            "completed_steps" to completedSubtasks.size,
                            "failed_steps" to failedSubtasks.size,
                            "total_cost_usd" to totalCost,
                            "total_tokens" to (totalTokensIn + totalTokensOut)
                        ),
                        "changed_files" to changedFiles.map { changed ->
                            mapOf(
                                "file_path" to changed.filePath,
                                "added_lines" to changed.addedLines,
                                "removed_lines" to changed.removedLines,
                                "snapshot_id" to changed.snapshotId
                            )
                        },
                        "changed_files_count" to changedFiles.size
                    )
                )
            )
        }

        logger.info { "[AgentRouter] Execution summary generated successfully (${llmResponse.usage.totalTokens} tokens used)" }

        return summaryText
    }

    // ===== Private Helper Methods =====

    private data class ExecutionSummaryChangedFile(
        val filePath: String,
        val addedLines: Int,
        val removedLines: Int,
        val snapshotId: String?
    )

    private fun aggregateChangedFilesForSummary(subtasks: List<pl.jclab.refio.core.db.Subtask>): List<ExecutionSummaryChangedFile> {
        if (subtasks.isEmpty()) {
            return emptyList()
        }

        val rawEntries = mutableListOf<ExecutionSummaryChangedFile>()

        subtasks.forEach { subtask ->
            val rawResult = subtask.result ?: return@forEach
            try {
                val executionResult = gson.fromJson(
                    rawResult,
                    ToolExecutionResult::class.java
                )
                executionResult.outputs.forEach outputLoop@ { output ->
                    val path = extractChangedFilePath(output) ?: return@outputLoop
                    val metadata = output.result.metadata
                    val added = extractMetricInt(metadata, "added_lines")
                    val removed = extractMetricInt(metadata, "removed_lines")
                    rawEntries += ExecutionSummaryChangedFile(
                        filePath = path,
                        addedLines = added ?: 0,
                        removedLines = removed ?: 0,
                        snapshotId = subtask.snapshotIdBeforeWrite
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "[AgentRouter] Failed to parse execution result for subtask ${subtask.id} when building summary" }
            }
        }

        if (rawEntries.isEmpty()) {
            return emptyList()
        }

        return rawEntries
            .groupBy { it.filePath }
            .map { (path, entries) ->
                val firstWithSnapshot = entries.firstOrNull { !it.snapshotId.isNullOrBlank() }
                ExecutionSummaryChangedFile(
                    filePath = path,
                    addedLines = entries.sumOf { it.addedLines },
                    removedLines = entries.sumOf { it.removedLines },
                    snapshotId = firstWithSnapshot?.snapshotId
                )
            }
            .sortedBy { it.filePath }
    }

    private fun extractChangedFilePath(output: ToolCallOutput): String? {
        val metadataPath = output.result.metadata?.get("path") as? String
        if (!metadataPath.isNullOrBlank()) {
            return metadataPath
        }
        return output.result.affectedFiles.firstOrNull()
    }

    private fun extractMetricInt(metadata: Map<String, Any>?, key: String): Int? {
        val value = metadata?.get(key) ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}


