package pl.jclab.refio.core.api.routers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
// Project type erased to Any? for platform independence (see ProjectHandle)
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.ToolExecutionResult
import pl.jclab.refio.core.services.ToolCallOutput
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("AgentRouter")

/**
 * Router for agent execution operations.
 * Handles agent execution and monitoring.
 *
 * This router is responsible for:
 * - Running PLAN/AGENT turns via AgentTurnLoop
 * - Generating post-run execution summaries
 *
 * @property taskRepository Task management repository
 * @property subtaskRepository Subtask storage repository
 * @property chatMessageRepository Chat message storage (for approval messages)
 * @property configService Configuration service
 * @property llmClient LLM client
 * @property promptsService Prompts service
 * @property contextService Context service (for execution summaries)
 * @property projectRoot Project root path (for execution summaries)
 */
class AgentRouter(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val configService: ConfigService,
    private val llmClient: pl.jclab.refio.core.llm.LLMClient,
    private val promptsService: pl.jclab.refio.core.services.PromptsService,
    private val contextService: ContextService?,
    private val projectRoot: Path?,
    private val toolDescriptionBuilder: pl.jclab.refio.core.prompts.ToolDescriptionBuilder,
    private val agentTurnLoop: pl.jclab.refio.core.services.AgentTurnLoop? = null
) : Router {

    private val gson = pl.jclab.refio.core.utils.GsonInstance.gson

    override suspend fun initialize() {
        logger.info { "[AgentRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[AgentRouter] Shutting down" }
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
            taskId = taskId
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
            source = "generateExecutionSummary",
            taskId = taskId
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

    // ===== Turn-Loop Execution =====

    /**
     * Run a single turn with turn-loop pattern (Codex CLI-style).
     */
    suspend fun runTurn(
        request: TurnRequest,
        streamCallback: StreamCallback? = null,
        listener: TurnEventListener? = null
    ): TurnResult {
        val turnLoop = agentTurnLoop
            ?: throw IllegalStateException("AgentTurnLoop not available - toolRegistry is not configured")

        return turnLoop.runTurn(
            taskId = request.taskId,
            userInput = request.userInput,
            mode = request.mode,
            executionMode = request.executionMode,
            listener = listener,
            streamCallback = streamCallback,
            model = request.model,
            provider = request.provider,
            userContextRefs = request.userContextRefs,
            runProfile = request.runProfile,
            profileOverrides = request.profileOverrides,
            emitSessionId = request.emitSessionId,
            emitSourceAgentId = request.emitSourceAgentId,
            agentName = request.agentName
        )
    }

    /**
     * Continue a turn after user provides additional input (for INTERACTIVE mode).
     */
    suspend fun continueTurn(
        taskId: String,
        mode: pl.jclab.refio.core.db.TaskMode,
        executionMode: pl.jclab.refio.core.db.ExecutionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): TurnResult {
        val turnLoop = agentTurnLoop
            ?: throw IllegalStateException("AgentTurnLoop not available - toolRegistry is not configured")

        return turnLoop.continueTurn(
            taskId = taskId,
            mode = mode,
            executionMode = executionMode,
            listener = null,
            streamCallback = if (stream && onChunk != null) {
                { chunk -> onChunk(chunk) }
            } else null
        )
    }
}


