package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.llm.JsonParseException
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.api.PlanDecisionInfo
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.ToolCallSpec
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo

private val logger = dualLogger("StepPlanner")

/**
 * StepPlanner - Generates execution plans for subtasks with dynamic parameter generation
 *
 * Responsibilities:
 * - Parse subtask intent from params_json
 * - Load context from previous subtask results
 * - Read current file state for code_editing operations
 * - Call LLM to generate exact tool parameters based on runtime state
 * - Validate plans against task constraints
 * - Estimate execution time
 *
 * Architecture:
 * Uses intelligent planning - generates tool_args dynamically based on:
 * - Subtask intent (high-level description)
 * - Current file content (read via tools)
 * - Previous subtask results (for context)
 * - Task mode and constraints
 */
class StepPlanner(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val toolRegistry: ToolRegistry,
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val configService: ConfigService,
    private val toolPermissionsService: ToolPermissionsService? = null,
    private val contextService: ContextService? = null,
    private val projectRoot: java.nio.file.Path? = null
) {
    private val gson = pl.jclab.refio.core.utils.GsonInstance.gson

    /**
     * Result from step plan generation
     */
    data class StepPlanResult(
        val toolCall: ToolCallSpec,
        val planDecision: PlanDecisionInfo,
        val metrics: pl.jclab.refio.core.db.MessageMetrics?
    )

    /**
     * Generate execution plan for subtask with dynamic parameter generation
     * (RFC 0032: unified streaming/non-streaming).
     *
     * - Always uses streamComplete() internally for consistency
     * - If stream=true and onChunk provided, callback is invoked with each chunk
     * - Always returns StepPlanResult (streaming is presentation, not API change)
     *
     * @param taskId Parent task ID for context
     * @param subtaskId Subtask to generate plan for
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return StepPlanResult with tool call and decision info
     */
    suspend fun generatePlan(
        taskId: String,
        subtaskId: String,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): StepPlanResult {
        logger.info { "[PLANNER] Generating plan: task=$taskId, subtask=$subtaskId, stream=$stream" }

        // 1. Fetch and validate task/subtask
        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")
        val subtask = subtaskRepository.findById(subtaskId)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

        if (subtask.taskId != taskId) {
            throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
        }

        val totalSteps = subtaskRepository.countByTaskId(taskId).toInt().coerceAtLeast(1)
        val planToken = GlobalMetrics.beginOperation(
            OperationInfo.StepPlanning(subtask.orderIndex, totalSteps)
        )

        try {
            // 2. Parse params and extract suggested values
            val params = parseParamsJson(subtask.paramsJson)
            val intent = params["intent"]?.toString() ?: subtask.description
            val originalSuggestedTool = params["tool_type"]?.toString()?.ifBlank { null }
                ?: subtask.kind.name.lowercase()

            @Suppress("UNCHECKED_CAST")
            val suggestedParams = params["suggested_params"] as? Map<String, Any> ?: emptyMap()

            logger.info { "[PLANNER] Suggested: tool=$originalSuggestedTool, intent='$intent'" }

            val allowedTools = getAllowedToolsForTask(task)
            if (allowedTools.isEmpty()) {
                skipSubtaskDueToMissingTools(subtask, task, "No tools available for ${task.mode} mode.")
            }
            val allowedToolNames = allowedTools.map { it.name.lowercase() }.toSet()

            // Apply tool preference: prefer multi_line_editor over advance_code_editing for existing files
            val optimizedSuggestedTool = optimizeToolSelection(
                suggestedTool = originalSuggestedTool,
                suggestedParams = suggestedParams,
                allowedToolNames = allowedToolNames
            )
            if (optimizedSuggestedTool != originalSuggestedTool) {
                logger.info { "[PLANNER] Tool optimized: $originalSuggestedTool → $optimizedSuggestedTool (file exists, using cheaper tool)" }
            }

            val suggestionResolution = resolveSuggestedTool(optimizedSuggestedTool, subtask, allowedTools)
                ?: skipSubtaskDueToMissingTools(
                    subtask,
                    task,
                    "No fallback tool available for suggestion '$originalSuggestedTool'."
                )
            val effectiveSuggestedTool = suggestionResolution.effectiveTool

            if (suggestionResolution.wasAdjusted) {
                logger.warn {
                    "[PLANNER] Suggested tool '$originalSuggestedTool' unavailable. Falling back to '$effectiveSuggestedTool'."
                }
            }

            // 3. Build system prompt
            val toolDescriptions = toolDescriptionBuilder.getToolDescriptionsForTools(task.mode, allowedTools)
            val validToolNames = toolDescriptionBuilder.getValidToolNamesForTools(allowedTools)

            val systemPrompt = promptsService.getSystemPrompt(
                type = PromptType.SYSTEM_STEP_PLANNER,
                variables = mapOf(
                    "tool_descriptions" to toolDescriptions,
                    "valid_tool_names" to validToolNames,
                    "suggested_tool_name" to effectiveSuggestedTool,
                    "os_info" to getOperatingSystemInfo()
                )
            )

            // 4. Build context for LLM
            val userContext = buildStepContext(
                task = task,
                subtask = subtask,
                intent = intent,
                suggestedTool = effectiveSuggestedTool,
                suggestedParams = suggestedParams,
                originalSuggestedTool = originalSuggestedTool,
                toolDescriptions = toolDescriptions,
                validToolNames = validToolNames
            )

            // 5. Get model/provider configuration
            val (model, provider) = configService.getModel(
                operation = ModelOperation.PLAN,
                taskId = task.id
            )
            logger.info { "[PLANNER] Using model=$model, provider=$provider" }

            // 6. Read UI state
            val thinkingEnabled = configService.get(ConfigKeys.GENERAL_THINKING_ENABLED.key)?.toBoolean() ?: false
            val noEgressEnabled = configService.get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key)?.toBoolean() ?: false

            // 7. RFC 0032: Use unified complete() with stream flag
            val startTime = System.currentTimeMillis()

            val response = llmClient.complete(
                provider = provider,
                model = model,
                messages = listOf(
                    LLMMessage(role = "user", content = userContext)
                ),
                systemPrompt = systemPrompt,
                maxTokens = configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, task.id),
                temperature = 0.3,
                responseFormat = mapOf("type" to "json_object"),
                thinking = thinkingEnabled,
                noEgressEnabled = noEgressEnabled,
                stream = stream,
                onChunk = if (stream) onChunk else null,
                taskId = task.id,
                subtaskId = subtask.id,
                source = "StepPlanner"
            )

            val endTime = System.currentTimeMillis()
            val finalUsage = response.usage
            val finalCost = response.cost

            logger.info {
                "[PLANNER] LLM complete: tokens_in=${finalUsage.inputTokens}, " +
                        "tokens_out=${finalUsage.outputTokens}, cost=\$${String.format("%.4f", finalCost)}"
            }

            // Process response and return result
            val result = processLLMResponse(
                task = task,
                content = response.content,
                intent = intent,
                suggestedTool = originalSuggestedTool,
                suggestedParams = suggestedParams,
                allowedToolNames = allowedToolNames,
                model = model,
                provider = provider,
                tokensIn = finalUsage.inputTokens,
                tokensOut = finalUsage.outputTokens,
                cost = finalCost,
                latencyMs = (endTime - startTime).toInt(),
                startTime = startTime,
                endTime = endTime
            )

            logger.info { "[PLANNER] Plan generation complete" }

            return StepPlanResult(
                toolCall = result.toolCall,
                planDecision = result.decision,
                metrics = result.metrics
            )
        } finally {
            GlobalMetrics.endOperation(planToken)
        }
    }

    // ========================================================================
    // Context Building (shared logic)
    // ========================================================================

    /**
     * Build context string for step planning LLM call.
     * Consolidates logic from both generatePlan and generatePlanStream.
     */
    private suspend fun buildStepContext(
        task: Task,
        subtask: Subtask,
        intent: String,
        suggestedTool: String,
        suggestedParams: Map<String, Any>,
        originalSuggestedTool: String?,
        toolDescriptions: String,
        validToolNames: String
    ): String {
        val contextParts = mutableListOf<String>()
        val taskGoal = task.name

        contextParts.add("**Task Goal:** $taskGoal")
        contextParts.add("")
        contextParts.add("**Step Intent:** $intent")
        contextParts.add("**Suggested Tool:** $suggestedTool")

        if (!originalSuggestedTool.isNullOrBlank() && originalSuggestedTool != suggestedTool) {
            contextParts.add("")
            contextParts.add("**Tool Override:** Requested tool '$originalSuggestedTool' is unavailable. Use '$suggestedTool' or another allowed tool.")
        }

        if (suggestedParams.isNotEmpty()) {
            contextParts.add("")
            contextParts.add("<Suggested Parameters>")
            contextParts.add(gson.toJson(suggestedParams))
            contextParts.add("</Suggested Parameters>")
        }

        contextParts.add("")
        contextParts.add("<Available Tools>")
        contextParts.add(toolDescriptions)
        contextParts.add("</Available Tools>")
        contextParts.add("")
        contextParts.add("<Valid Tool Names>")
        contextParts.add(validToolNames)
        contextParts.add("</Valid Tool Names>")

        // Add project context (includes RAG)
        if (contextService != null && projectRoot != null) {
            try {
                logger.info { "[PLANNER] Loading project context with RAG" }
                val projectContext = contextService.buildProjectContext(
                    projectRoot = projectRoot,
                    taskId = task.id,
                    query = intent
                )
                val contextPrompt = contextService.buildLLMContextPrompt(projectContext)
                contextParts.add("")
                contextParts.add("<Project Context>")
                contextParts.add(contextPrompt)
                contextParts.add("</Project Context>")

                logger.info { "[PLANNER] Added project context: ${contextPrompt.length} chars" }
            } catch (e: Exception) {
                logger.warn(e) { "[PLANNER] Failed to load project context: ${e.message}" }
            }
        }

        // Add previous results
        val previousResults = loadPreviousResults(task, subtask.orderIndex)
        if (previousResults.isNotBlank()) {
            contextParts.add("")
            contextParts.add("<Previous Step Results>")
            contextParts.add(previousResults)
            contextParts.add("</Previous Step Results>")
        }

        // For code_editing: read actual file content
        val filePath = suggestedParams["path"]?.toString()
        if (suggestedTool == "code_editing" && filePath != null) {
            try {
                val fileContent = readFileContent(filePath)
                contextParts.add("")
                contextParts.add("<Current File Content> File path: $filePath")
                contextParts.add("```")
                contextParts.add(fileContent)
                contextParts.add("```")
                contextParts.add("</Current File Content>")
                logger.info { "[PLANNER] Added file content: $filePath (${fileContent.length} chars)" }
            } catch (e: Exception) {
                logger.warn(e) { "[PLANNER] Failed to read file $filePath: ${e.message}" }
            }
        }

        return contextParts.joinToString("\n")
    }

    // ========================================================================
    // LLM Response Processing (shared logic)
    // ========================================================================

    /**
     * Result from LLM response processing.
     */
    private data class ProcessedPlanResult(
        val toolCall: ToolCallSpec,
        val decision: PlanDecisionInfo,
        val metrics: pl.jclab.refio.core.db.MessageMetrics?
    )

    /**
     * Process LLM response: parse JSON, create tool call, build decision info.
     * Shared by both streaming and non-streaming modes.
     */
    private fun processLLMResponse(
        task: Task,
        content: String,
        intent: String,
        suggestedTool: String,
        suggestedParams: Map<String, Any>,
        allowedToolNames: Set<String>,
        model: String,
        provider: String,
        tokensIn: Int,
        tokensOut: Int,
        cost: Double,
        latencyMs: Int,
        startTime: Long,
        endTime: Long
    ): ProcessedPlanResult {
        // Parse JSON response using universal JsonExtractor
        if (content.isBlank()) {
            throw IllegalStateException("LLM returned empty response")
        }

        val jsonResponse = try {
            JsonExtractor.extractAndParse(content)
        } catch (e: JsonParseException) {
            logger.error { "[PLANNER] Failed to extract/parse JSON: ${e.message}" }
            logger.error { "[PLANNER] Content preview: ${content.take(500)}" }
            throw IllegalStateException("LLM returned invalid JSON: ${e.message}", e)
        } catch (e: Exception) {
            logger.error { "[PLANNER] Unexpected error parsing JSON: ${e.message}" }
            throw IllegalStateException("Failed to parse LLM response: ${e.message}", e)
        }

        val selectedTool = extractToolName(jsonResponse)
            ?: run {
                logger.error { "[PLANNER] LLM response missing tool field. Content preview: ${content.take(500)}" }
                throw IllegalStateException("LLM response missing 'tool' field")
            }

        val normalizedSelectedTool = selectedTool.lowercase()
        if (!allowedToolNames.contains(normalizedSelectedTool)) {
            throw IllegalStateException("Tool '$selectedTool' is not registered or enabled for this task.")
        }

        @Suppress("UNCHECKED_CAST")
        val selectedParams = extractToolArgs(jsonResponse)
            ?: run {
                logger.error { "[PLANNER] LLM response missing args field. Content preview: ${content.take(500)}" }
                throw IllegalStateException("LLM response missing 'args' field")
            }

        // Extract reasoning (if LLM provided it)
        val reasoning = jsonResponse["reasoning"]?.toString()

        logger.info { "[PLANNER] Parsed: tool=$selectedTool, args keys=${selectedParams.keys}" }

        // Validate tool permissions
        if (toolPermissionsService != null) {
            val permission = toolPermissionsService.getPermission(selectedTool, task.mode, task.id)
            if (permission == PermissionLevel.OFF) {
                throw SecurityException("Tool '$selectedTool' has permission=OFF for ${task.mode} mode")
            }
        }

        // Update task metrics
        taskRepository.incrementMetrics(
            id = task.id,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            costUsd = cost
        )

        // Create tool call spec
        val toolCall = ToolCallSpec(
            name = selectedTool,
            params = selectedParams,
            expectedOutput = null
        )

        // Create decision info (suggested vs selected)
        val decision = PlanDecisionInfo.from(
            intent = intent,
            suggestedTool = suggestedTool,
            suggestedParams = suggestedParams,
            selectedTool = selectedTool,
            selectedParams = selectedParams,
            reasoning = reasoning
        )

        if (decision.wasModified) {
            logger.info { "[PLANNER] LLM modified plan: $suggestedTool -> $selectedTool (reasoning: ${reasoning ?: "none"})" }
        }

        // Create metrics
        val metrics = pl.jclab.refio.core.db.MessageMetrics.fromLLMResponse(
            model = model,
            provider = provider,
            inputTokens = tokensIn,
            outputTokens = tokensOut,
            costUsd = cost,
            latencyMs = latencyMs,
            startedAt = startTime,
            completedAt = endTime
        )

        return ProcessedPlanResult(
            toolCall = toolCall,
            decision = decision,
            metrics = metrics
        )
    }

    private fun extractToolName(jsonResponse: Map<String, Any?>): String? {
        val primary = jsonResponse["tool"]?.toString()
        if (!primary.isNullOrBlank()) {
            return primary
        }

        val fallback = jsonResponse["kind"]?.toString()
            ?: jsonResponse["tool_name"]?.toString()
            ?: jsonResponse["toolName"]?.toString()
        if (!fallback.isNullOrBlank()) {
            logger.warn { "[PLANNER] LLM response missing 'tool' field, using fallback '$fallback'." }
            return fallback
        }

        val nested = (jsonResponse["tool_call"] as? Map<*, *>)?.get("name")?.toString()
        if (!nested.isNullOrBlank()) {
            logger.warn { "[PLANNER] LLM response missing 'tool' field, using tool_call.name '$nested'." }
            return nested
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractToolArgs(jsonResponse: Map<String, Any?>): Map<String, Any>? {
        val primary = jsonResponse["args"] as? Map<String, Any>
        if (primary != null) {
            return primary
        }

        val fallback = jsonResponse["tool_args"] as? Map<String, Any>
            ?: jsonResponse["params"] as? Map<String, Any>
            ?: jsonResponse["arguments"] as? Map<String, Any>
        if (fallback != null) {
            logger.warn { "[PLANNER] LLM response missing 'args' field, using fallback args field." }
            return fallback
        }

        val nested = (jsonResponse["tool_call"] as? Map<*, *>)?.get("args") as? Map<String, Any>
        if (nested != null) {
            logger.warn { "[PLANNER] LLM response missing 'args' field, using tool_call.args." }
            return nested
        }

        return null
    }


    /**
     * Parse params_json string to map.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseParamsJson(paramsJson: String?): Map<String, Any> {
        if (paramsJson.isNullOrBlank()) {
            return emptyMap()
        }

        return try {
            @Suppress("UNCHECKED_CAST") gson.fromJson(paramsJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error(e) { "[PLANNER] Failed to parse params_json: $paramsJson" }
            emptyMap()
        }
    }

    /**
     * Load results from previous completed subtasks for context.
     */
    private fun loadPreviousResults(task: Task, currentOrderIndex: Int): String {
        val previousSubtasks = subtaskRepository.findByTaskId(task.id).filter { it.orderIndex < currentOrderIndex }
            .filter { it.status == TaskStatus.SUCCESS }.sortedBy { it.orderIndex }.take(1)

        if (previousSubtasks.isEmpty()) {
            return ""
        }

        val results = previousSubtasks.mapIndexed { index, subtask ->
            val resultText = formatToolExecutionResult(subtask.result)
            "Step ${index + 1} (${subtask.kind.name}):\n$resultText"
        }

        logger.info { "[PLANNER] Loaded ${previousSubtasks.size} previous results for context" }
        return results.joinToString("\n\n")
    }

    /**
     * Format ToolExecutionResult JSON into readable text for LLM.
     * Extracts actual outputs from tool executions instead of showing raw JSON.
     */
    private fun formatToolExecutionResult(resultJson: String?): String {
        if (resultJson.isNullOrBlank()) {
            return "No output"
        }

        return try {
            val executionResult = gson.fromJson(resultJson, ToolExecutionResult::class.java)

            if (executionResult.outputs.isEmpty()) {
                return "No tool outputs"
            }

            executionResult.outputs.joinToString("\n\n") { output ->
                buildString {
                    append("Tool: ${output.tool}\n")

                    if (output.result.success) {
                        append("Status: Success\n")
                        if (!output.result.output.isNullOrBlank()) {
                            append("Output:\n${output.result.output}")
                        } else {
                            append("Output: (no content)")
                        }

                        // Include affected files if any
                        if (output.result.affectedFiles.isNotEmpty()) {
                            append("\nAffected files: ${output.result.affectedFiles.joinToString(", ")}")
                        }
                    } else {
                        append("Status: Failed\n")
                        append("Error: ${output.result.error ?: "Unknown error"}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[PLANNER] Failed to parse result JSON, using raw text" }
            resultJson
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Read file content by executing read_file tool.
     */
    private suspend fun readFileContent(filePath: String): String {
        logger.info { "[PLANNER] Reading file content: $filePath" }

        val readFileTool =
            toolRegistry.getTool("read_file") ?: throw IllegalStateException("read_file tool not found in registry")

        val result = readFileTool.execute(mapOf("path" to filePath))

        if (!result.success) {
            throw IllegalStateException("Failed to read file $filePath: ${result.output}")
        }

        return result.output ?: throw IllegalStateException("Tool returned null output for file: $filePath")
    }


    /**
     * Validate execution plan against task constraints.
     *
     * Checks:
     * - All tools exist in registry
     * - Tools are allowed for task mode (READ_ONLY tools for CHAT/PLAN, all for AGENT)
     * - No WRITE tools if task.read_only=True
     * - Parameters match tool schemas (future: implement full validation)
     *
     * @param plan Execution plan to validate
     * @param task Parent task for constraint checking
     * @throws IllegalArgumentException if plan is invalid
     */
    private fun validatePlan(plan: ExecutionPlan, task: Task) {
        logger.info { "[PLANNER] Validating plan for task ${task.id} (mode=${task.mode}, readOnly=${task.readOnly})" }

        for ((index, toolSpec) in plan.tools.withIndex()) {
            // 1. Check tool exists
            val tool = toolRegistry.getTool(toolSpec.name)
                ?: throw IllegalArgumentException("Tool not found: ${toolSpec.name}")

            // 2. Check tool mode is allowed for task mode
            if (!isToolAllowedForTaskMode(tool, task.mode)) {
                throw IllegalArgumentException(
                    "Tool ${toolSpec.name} (${tool.mode}) not allowed for task mode ${task.mode}. " + "CHAT and PLAN modes can only use READ_ONLY tools."
                )
            }

            // 3. Check read-only constraint
            if (task.readOnly && tool.mode == ToolMode.WRITE) {
                throw IllegalArgumentException(
                    "Tool ${toolSpec.name} is a WRITE tool but task is read-only. " + "Remove read-only flag or use READ_ONLY tools only."
                )
            }

            // 4. Validate parameters (basic check - tool will do full validation on execute)
            try {
                tool.validateParams(toolSpec.params)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Tool ${toolSpec.name} parameter validation failed: ${e.message}"
                )
            }

            logger.debug { "[PLANNER] Tool #$index validated: ${toolSpec.name}" }
        }

        logger.info { "[PLANNER] Plan validated successfully: ${plan.tools.size} tool(s)" }
    }

    /**
     * Check if tool is allowed for given task mode.
     *
     * Rules:
     * - CHAT mode: Only READ_ONLY tools
     * - PLAN mode: Only READ_ONLY tools
     * - AGENT mode: All tools (READ_ONLY and WRITE)
     */
    private fun isToolAllowedForTaskMode(tool: Tool, taskMode: TaskMode): Boolean {
        return when (taskMode) {
            TaskMode.CHAT, TaskMode.PLAN -> tool.mode == ToolMode.READ_ONLY
            TaskMode.AGENT -> true // All tools allowed
        }
    }

    private fun getAllowedToolsForTask(task: Task): List<Tool> {
        val baseTools = if (toolPermissionsService != null) {
            toolRegistry.getAvailableTools(task.mode, toolPermissionsService, task.id)
        } else {
            when (task.mode) {
                TaskMode.CHAT, TaskMode.PLAN -> toolRegistry.getReadOnlyTools()
                TaskMode.AGENT -> toolRegistry.getAllTools()
            }
        }

        val filtered = if (task.readOnly) {
            baseTools.filter { it.mode == ToolMode.READ_ONLY }
        } else {
            baseTools
        }

        return filtered.sortedBy { it.name }
    }

    /**
     * Optimize tool selection based on file existence.
     * Prefers multi_line_editor over advance_code_editing for existing files (3x cheaper).
     *
     * @param suggestedTool Original suggested tool name
     * @param suggestedParams Parameters including file path
     * @param allowedToolNames Set of allowed tool names for this task
     * @return Optimized tool name (may be same as original)
     */
    private fun optimizeToolSelection(
        suggestedTool: String?,
        suggestedParams: Map<String, Any>,
        allowedToolNames: Set<String>
    ): String? {
        if (suggestedTool == null) return null

        val normalizedTool = suggestedTool.lowercase()

        // Only optimize if advance_code_editing is suggested and multi_line_editor is available
        if (normalizedTool != "advance_code_editing") return suggestedTool
        if ("multi_line_editor" !in allowedToolNames) return suggestedTool

        // Check if file exists - if yes, prefer multi_line_editor
        val path = suggestedParams["path"] as? String ?: return suggestedTool

        // Use projectRoot to check file existence
        if (projectRoot == null) return suggestedTool

        val filePath = try {
            projectRoot.resolve(path)
        } catch (e: Exception) {
            logger.debug { "[PLANNER] Cannot resolve path '$path': ${e.message}" }
            return suggestedTool
        }

        return if (java.nio.file.Files.exists(filePath) && java.nio.file.Files.isRegularFile(filePath)) {
            // File exists - prefer multi_line_editor (3x cheaper)
            "multi_line_editor"
        } else {
            // File doesn't exist - keep advance_code_editing for creation
            suggestedTool
        }
    }

    private fun resolveSuggestedTool(
        originalSuggestedTool: String?,
        subtask: Subtask,
        allowedTools: List<Tool>
    ): ToolSuggestionResolution? {
        val normalized = originalSuggestedTool?.trim()?.lowercase().orEmpty()
        val exactMatch = allowedTools.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        if (exactMatch != null) {
            return ToolSuggestionResolution(effectiveTool = exactMatch.name, wasAdjusted = false)
        }

        val fallback = selectFallbackTool(shouldPreferWriteTool(subtask), allowedTools) ?: return null
        val adjusted = normalized.isNotEmpty()

        return ToolSuggestionResolution(effectiveTool = fallback.name, wasAdjusted = adjusted)
    }

    private fun shouldPreferWriteTool(subtask: Subtask): Boolean {
        return when (subtask.kind) {
            SubtaskKind.CODE_EDITING,
            SubtaskKind.ADVANCE_CODE_EDITING,
            SubtaskKind.CREATE_NEW_FILE,
            SubtaskKind.MULTI_EDIT,
            SubtaskKind.RUN_TERMINAL_COMMAND -> true

            else -> false
        }
    }

    private fun selectFallbackTool(preferWrite: Boolean, allowedTools: List<Tool>): Tool? {
        if (allowedTools.isEmpty()) {
            return null
        }

        if (preferWrite) {
            val writeTool = allowedTools.firstOrNull { it.mode == ToolMode.WRITE }
            if (writeTool != null) {
                return writeTool
            }
        }

        return allowedTools.first()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun skipSubtaskDueToMissingTools(subtask: Subtask, _task: Task, reason: String): Nothing {
        logger.warn { "[PLANNER] $reason" }
        subtaskRepository.updateStatus(subtask.id, TaskStatus.CANCELED)
        subtaskRepository.updateResult(
            id = subtask.id,
            result = null,
            errorMessage = reason
        )
        throw IllegalStateException(reason)
    }

    private data class ToolSuggestionResolution(
        val effectiveTool: String,
        val wasAdjusted: Boolean
    )

    /**
     * Get operating system information for tool command generation.
     * Returns OS name and recommended shell/command syntax.
     */
    private fun getOperatingSystemInfo(): String {
        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val osArch = System.getProperty("os.arch")

        return when {
            osName.contains("Windows", ignoreCase = true) -> {
                "Operating System: Windows $osVersion ($osArch). " +
                        "Use PowerShell syntax for terminal commands (e.g., Get-ChildItem, Select-String). " +
                        "Do NOT use Unix commands like ls, grep, sed, find."
            }

            osName.contains("Mac", ignoreCase = true) || osName.contains("Darwin", ignoreCase = true) -> {
                "Operating System: macOS $osVersion ($osArch). " +
                        "Use Unix/Bash syntax for terminal commands (ls, grep, find, etc.)."
            }

            osName.contains("Linux", ignoreCase = true) -> {
                "Operating System: Linux $osVersion ($osArch). " +
                        "Use Unix/Bash syntax for terminal commands (ls, grep, find, etc.)."
            }

            else -> {
                "Operating System: $osName $osVersion ($osArch). " +
                        "Determine appropriate command syntax based on the OS."
            }
        }
    }
}

/**
 * Execution plan for a subtask.
 */
data class ExecutionPlan(
    /**
     * List of tool calls to execute in order
     */
    val tools: List<ToolCallSpec>,

    /**
     * Human-readable description of what this plan does
     */
    val description: String,

    /**
     * Estimated execution time in milliseconds
     */
    val estimatedDurationMs: Int,

    /**
     * List of subtask IDs this depends on (must complete first)
     */
    val dependencies: List<String>,

    /**
     * LLM metrics from plan generation call (US-027)
     */
    val llmMetrics: pl.jclab.refio.core.db.MessageMetrics? = null,

    /**
     * Decision details: what was suggested vs what LLM selected
     */
    val planDecision: PlanDecisionInfo? = null
)

/**
 * Tool call specification.
 */
data class ToolCallSpec(
    /**
     * Tool name (must exist in ToolRegistry)
     */
    val name: String,

    /**
     * Tool parameters
     */
    val params: Map<String, Any>,

    /**
     * Expected output description (optional, for planning)
     */
    val expectedOutput: String?
)
