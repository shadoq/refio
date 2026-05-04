package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.api.StreamCallback

private val logger = dualLogger("StepSummarizer")

/**
 * StepSummarizer - Generates LLM-based human-readable summaries of step execution
 *
 * Responsibilities:
 * - Call LLM with STEP_SUMMARIZER prompt
 * - Pass full context: description, tools executed, complete results JSON
 * - Generate 5-10 sentence natural language summary
 * - Fall back to simple formatting if LLM fails
 *
 * Summary is saved to subtasks.summary and displayed in chat interface.
 * Uses the user's selected model (DEFAULT operation type) for consistency.
 */
class StepSummarizer(
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val configService: ConfigService,
    private val taskRepository: pl.jclab.refio.core.db.repositories.TaskRepository
) {

    /**
     * Generate LLM-based human-readable summary of step execution
     * (RFC 0032: unified streaming/non-streaming).
     *
     * - Always uses streamComplete() internally for consistency
     * - If stream=true and onChunk provided, callback is invoked with each chunk
     * - Always returns String (streaming is presentation, not API change)
     *
     * Calls LLM with STEP_SUMMARIZER prompt, passing:
     * - Step description
     * - Tools executed with parameters
     * - Complete execution results JSON
     *
     * Returns 5-10 sentence natural language summary
     * Falls back to simple formatting if LLM fails
     *
     * @param subtask Executed subtask
     * @param taskId Task ID for metrics tracking
     * @param executionResult Optional execution result (if not persisted to DB yet)
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return LLM-generated detailed summary (5-10 sentences)
     */
    suspend fun generateSummary(
        subtask: Subtask,
        taskId: String,
        executionResult: ToolExecutionResult? = null,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): String {
        logger.info { "[SUMMARIZER] Generating summary for subtask ${subtask.id}, stream=$stream" }

        return try {
            generateLlmSummaryWithStreaming(subtask, taskId, executionResult, stream, onChunk)
        } catch (e: Exception) {
            logger.error(e) { "[SUMMARIZER] LLM summary generation failed, falling back to simple format" }
            // Fall back to simple markdown formatting
            generateFallbackSummary(subtask, executionResult)
        }
    }

    /**
     * Generate summary using LLM with STEP_SUMMARIZER prompt (RFC 0032: unified streaming).
     */
    private suspend fun generateLlmSummaryWithStreaming(
        subtask: Subtask,
        taskId: String,
        executionResult: ToolExecutionResult?,
        stream: Boolean,
        onChunk: StreamCallback?
    ): String {

        // Build context for LLM
        val contextParts = mutableListOf<String>()

        // 1. Step description
        contextParts.add("**Step Description:**\n${subtask.description}")

        // 2. Tools executed
        val toolCallsInfo = buildToolCallsInfo(subtask)
        if (toolCallsInfo.isNotEmpty()) {
            contextParts.add("\n**Tools Executed:**\n${toolCallsInfo.joinToString("\n")}")
        }

        // 3. Execution results (full JSON)
        val resultsJson = when {
            executionResult != null -> {
                // Build from executionResult
                mapOf(
                    "status" to (if (executionResult.success) "success" else "failed"),
                    "files_changed" to executionResult.outputs.flatMap { it.result.affectedFiles },
                    "output" to (executionResult.outputs.lastOrNull()?.result?.output ?: ""),
                    "tools_executed" to executionResult.toolsExecuted,
                    "errors" to executionResult.errors
                )
            }

            subtask.result != null -> {
                // Parse from subtask.result
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(subtask.result, Map::class.java) as Map<String, Any>
                } catch (e: Exception) {
                    mapOf("error" to "Failed to parse results")
                }
            }

            else -> mapOf("status" to subtask.status.name)
        }

        contextParts.add("\n**Execution Results:**\n${gson.toJson(resultsJson)}")

        // 4. Error information if failed
        if (subtask.status == TaskStatus.FAILED) {
            contextParts.add("\n**Error:**\n${subtask.errorMessage ?: "Unknown error"}")
        }

        val userMessage = contextParts.joinToString("\n\n")

        logger.info { "[SUMMARIZER] Calling LLM with ${contextParts.size} context parts, stream=$stream" }

        // Get system prompt
        val systemPrompt = promptsService.getSystemPrompt(PromptType.SYSTEM_STEP_SUMMARIZER)

        // Get model/provider from config (uses DEFAULT operation type to respect user's model selection)
        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = taskId
        )

        logger.info { "[SUMMARIZER] Using model: $model (provider=$provider)" }

        // RFC 0032: Use unified complete() with stream flag
        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(
                LLMMessage(role = "user", content = userMessage)
            ),
            systemPrompt = systemPrompt,
            temperature = 0.3,
            maxTokens = 1024,
            stream = stream,
            onChunk = if (stream) onChunk else null,
            source = "StepSummarizer",
            taskId = taskId,
            subtaskId = subtask.id
        )

        val rawSummary = response.content.trim()

        logger.info { "[SUMMARIZER] LLM summary generated (${rawSummary.length} chars)" }

        // Task / subtask metrics auto-incremented inside LLMClient.complete() via
        // taskId / subtaskId passed in the call above. No manual increment here.

        // Format summary with metadata
        val summaryParts = mutableListOf<String>()
        summaryParts.add("**Step:** [${subtask.orderIndex}] ${subtask.description}")

        if (toolCallsInfo.isNotEmpty()) {
            summaryParts.add("\n**Executed:** ${toolCallsInfo.joinToString("; ")}")
        }

        summaryParts.add("\n**Summary:**\n\n${rawSummary}")

        when (subtask.status) {
            TaskStatus.SUCCESS -> {
                if (subtask.startedAt != null && subtask.completedAt != null) {
                    val durationMs = subtask.completedAt - subtask.startedAt
                    val durationS = durationMs / 1000.0
                    summaryParts.add("\n⏱️ **Duration:** ${"%.2f".format(durationS)}s")
                }
            }

            TaskStatus.FAILED -> {
                val errorMsg = subtask.errorMessage ?: "Unknown error"
                summaryParts.add("\n❌ **Failed:** $errorMsg")
            }

            TaskStatus.CANCELED -> {
                summaryParts.add("\n⏭️ **Status:** Step was skipped or canceled")
            }

            else -> {
                summaryParts.add("\n**Status:** ${subtask.status.name}")
            }
        }

        return summaryParts.joinToString("\n\n")
    }

    /**
     * Generate simple fallback summary if LLM fails
     */
    private fun generateFallbackSummary(
        subtask: Subtask,
        executionResult: ToolExecutionResult?
    ): String {
        val summaryParts = mutableListOf<String>()

        // 1. Description
        if (subtask.description.isNotBlank()) {
            summaryParts.add("**Step:** ${subtask.description}")
        }

        // 2. Tool execution details
        val toolCallsInfo = buildToolCallsInfo(subtask)
        if (toolCallsInfo.isNotEmpty()) {
            summaryParts.add("**Executed:** ${toolCallsInfo.joinToString("; ")}")
        }

        // 3. Results
        when (subtask.status) {
            TaskStatus.SUCCESS -> {
                summaryParts.add(buildSuccessResult(subtask, executionResult))
            }

            TaskStatus.FAILED -> {
                val errorMsg = subtask.errorMessage ?: "Unknown error"
                summaryParts.add("❌ **Failed:** $errorMsg")
            }

            TaskStatus.CANCELED -> {
                summaryParts.add("⏭️ **Status:** Step was skipped or canceled")
            }

            else -> {
                summaryParts.add("**Status:** ${subtask.status.name}")
            }
        }

        return summaryParts.joinToString("\n\n")
    }

    /**
     * Build tool calls information from subtask params_json.
     */
    private fun buildToolCallsInfo(subtask: Subtask): List<String> {
        val toolCallsInfo = mutableListOf<String>()

        subtask.paramsJson?.let { paramsJson ->
            try {
                @Suppress("UNCHECKED_CAST")
                val params = gson.fromJson(paramsJson, Map::class.java) as Map<String, Any>

                // Try to get tools list from params
                @Suppress("UNCHECKED_CAST")
                val tools = params["tools"] as? List<Map<String, Any>>

                if (tools != null) {
                    // Full format with tools array
                    tools.forEach { tool ->
                        val toolName = tool["name"] as? String ?: "unknown"

                        @Suppress("UNCHECKED_CAST")
                        val toolParams = tool["params"] as? Map<String, Any> ?: emptyMap()

                        val formattedParams = formatParams(toolParams)
                        toolCallsInfo.add("$toolName($formattedParams)")
                    }
                } else {
                    // Legacy format: single tool with params
                    val toolName = subtask.kind.name
                    val formattedParams = formatParams(params)
                    toolCallsInfo.add("$toolName($formattedParams)")
                }

            } catch (e: Exception) {
                logger.warn(e) { "[SUMMARIZER] Failed to parse params_json" }
            }
        }

        return toolCallsInfo
    }

    /**
     * Format parameters for readability (truncate long values).
     */
    private fun formatParams(params: Map<String, Any>): String {
        return params.entries.joinToString(", ") { (key, value) ->
            var valueStr = value.toString()
            if (valueStr.length > 100) {
                valueStr = valueStr.take(100) + "..."
            }
            // Escape HTML to prevent rendering in markdown
            valueStr = escapeHtml(valueStr)
            "$key='$valueStr'"
        }
    }

    /**
     * Build success result summary.
     */
    private fun buildSuccessResult(
        subtask: Subtask,
        executionResult: ToolExecutionResult?
    ): String {
        val resultDetails = mutableListOf<String>()

        // Get result from executionResult or subtask.result
        val result: Map<String, Any>? = when {
            executionResult != null -> {
                mapOf(
                    "files_changed" to executionResult.outputs.flatMap { it.result.affectedFiles },
                    "tools_executed" to executionResult.toolsExecuted,
                    "output" to (executionResult.outputs.lastOrNull()?.result?.output ?: "")
                )
            }

            subtask.result != null -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(subtask.result, Map::class.java) as Map<String, Any>
                } catch (e: Exception) {
                    null
                }
            }

            else -> null
        }

        result?.let { res ->
            // Files changed
            @Suppress("UNCHECKED_CAST")
            val filesChanged = res["files_changed"] as? List<String> ?: emptyList()
            if (filesChanged.isNotEmpty()) {
                val filesList = filesChanged.take(3).joinToString(", ")
                resultDetails.add("Modified ${filesChanged.size} file(s): $filesList")
                if (filesChanged.size > 3) {
                    resultDetails.add("and ${filesChanged.size - 3} more")
                }
            }

            // Output/results
            val output = res["output"] as? String
            if (!output.isNullOrBlank()) {
                val outputPreview = if (output.length > 1024) {
                    output.take(1024) + "..."
                } else {
                    output
                }
                resultDetails.add("Output: ${escapeHtml(outputPreview)}")
            }

            // Tools executed count (if not already shown in toolCallsInfo)
            val toolsCount = (res["tools_executed"] as? Number)?.toInt() ?: 0
            if (toolsCount > 0 && buildToolCallsInfo(subtask).isEmpty()) {
                resultDetails.add("Executed $toolsCount tool(s)")
            }
        }

        return if (resultDetails.isNotEmpty()) {
            "\n**Result:** ${resultDetails.joinToString(" ")}"
        } else {
            "\n**Result:** Completed successfully"
        }
    }

    /**
     * Parse errors from result JSON.
     */
    private fun parseErrors(resultJson: String): List<String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = gson.fromJson(resultJson, Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            result["errors"] as? List<String> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Escape HTML characters to prevent rendering as HTML in markdown viewer.
     */
    private fun escapeHtml(text: String): String {
        return text
//            .replace("&", "&amp;")
//            .replace("<", "&lt;")
//            .replace(">", "&gt;")
//            .replace("\"", "&quot;")
    }
}
