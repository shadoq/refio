package pl.jclab.refio.core.services

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.llm.JsonParseException
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import java.util.*

private val logger = dualLogger("PlanningService")
private const val LEGACY_PROJECT_ID = "legacy_unknown"
private const val LEGACY_PROJECT_PATH = "unknown"

/**
 * Planning service for Plan/Agent mode - FULL implementation from Python.
 *
 * Flow:
 * 1. Validate task and mode
 * 2. Build context using ContextService (single source of truth)
 * 3. Call LLM for planning with JSON structured output
 * 4. Parse plan and create subtasks
 * 5. Return plan with subtasks
 *
 * REFACTORED:
 * - Single createPlan() method with stream parameter (no duplicate code)
 * - Context building delegated entirely to ContextService
 * - Removed fetchConversationHistory/buildFullContext (ContextService handles this)
 */
class PlanningService(
    private val taskRepository: TaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val subtaskRepository: SubtaskRepository,
    private val configService: ConfigService,
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val toolRegistry: ToolRegistry? = null,
    private val toolPermissionsService: ToolPermissionsService? = null,
    private val contextService: ContextService? = null,
    private val projectRoot: java.nio.file.Path? = null,
) {
    private val fallbackProjectId: String =
        projectRoot?.let { ProjectIdGenerator.generate(it) } ?: LEGACY_PROJECT_ID
    private val fallbackProjectPath: String =
        projectRoot?.toAbsolutePath()?.normalize()?.toString() ?: LEGACY_PROJECT_PATH

    companion object {
        const val MAX_INPUT_LENGTH = 8192
        const val MAX_CONTEXT_REFS = 20
        const val CONVERSATION_HISTORY_LIMIT = 100
    }

    /**
     * Create execution plan for a task (RFC 0032: unified streaming/non-streaming).
     *
     * - Always uses streamComplete() internally for consistency
     * - If stream=true and onChunk provided, callback is invoked with each chunk
     * - Always returns PlanningResponse (streaming is presentation, not API change)
     *
     * @param taskId Task ID to create plan for
     * @param request Planning request with user input and parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Planning response with plan, steps, subtasks, and cost info
     * @throws IllegalArgumentException If task not found or mode is not PLAN/AGENT
     * @throws Exception On LLM API errors or planning failures
     */
    suspend fun createPlan(
        taskId: String,
        request: PlanningRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): PlanningResponse {
        val requestId = UUID.randomUUID().toString()
        logger.info { "[PLANNING] Starting plan creation: taskId=$taskId, requestId=$requestId, stream=$stream" }

        // 1. Get or create task (creates if not exists)
        val task = getOrCreateTask(taskId, request.input, TaskMode.PLAN)

        // 2. Sanitize input
        val sanitizedInput = sanitizeInput(request.input)
        if (sanitizedInput.length > MAX_INPUT_LENGTH) {
            throw IllegalArgumentException("Input too long: max $MAX_INPUT_LENGTH chars")
        }

        // 3. Update task status to RUNNING
        if (task.status == TaskStatus.NEW || task.status == TaskStatus.PENDING) {
            taskRepository.update(id = task.id, status = TaskStatus.RUNNING)
        }

        // 4. Save user message
        val userMessage = chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.USER,
            content = sanitizedInput,
            metadata = null
        )

        logger.info { "[PLANNING] User message saved: id=${userMessage.id}" }

        // 5. Get model and provider (already resolved by CoreApiRouter in SessionManager)
        val (model, provider) = if (!request.model.isNullOrBlank() && !request.provider.isNullOrBlank()) {
            logger.info { "[PLANNING] Using explicit model from request: ${request.model} (provider=${request.provider})" }
            Pair(request.model, request.provider)
        } else {
            val operation = ModelOperation.fromTaskMode(task.mode)
            val (m, p) = configService.getModel(operation = operation, taskId = task.id)
            logger.info { "[PLANNING] Using model from config: $m (provider=$p)" }
            Pair(m, p)
        }

        logger.info { "[PLANNING] Using model=$model, provider=$provider" }

        // 6. Build context using ContextService (SINGLE SOURCE OF TRUTH)
        val context = buildContextWithContextService(task, request.contextRefs, sanitizedInput)

        logger.info { "[PLANNING] Context built: ${context.length} chars" }

        // 7. Build prompts (with task-mode-specific tool list from ToolRegistry, filtered by permissions)
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(task.mode, task.id)
        val validToolNames = toolDescriptionBuilder.getValidToolNames(task.mode, task.id)

        val systemPromptType = when (task.mode) {
            TaskMode.PLAN -> PromptType.SYSTEM_PLAN
            TaskMode.AGENT -> PromptType.SYSTEM_AGENT
            TaskMode.CHAT -> PromptType.SYSTEM_CHAT
        }

        val systemPrompt = promptsService.getSystemPrompt(
            type = systemPromptType,
            variables = mapOf(
                "tool_descriptions" to toolDescriptions,
                "valid_tool_names" to validToolNames
                // Context is now passed separately
            )
        )
        val userPrompt = buildUserPrompt(sanitizedInput)

        // Read UI state from config table (single source of truth)
        val thinkingEnabled = configService.get(ConfigKeys.GENERAL_THINKING_ENABLED.key)?.toBoolean() ?: false
        val noEgressEnabled = configService.get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key)?.toBoolean() ?: false

        // Build messages - context is passed separately via contextContent parameter
        // This ensures proper order: [system] System, [user] Context, [user] User prompt
        val messages = listOf(LLMMessage(role = "user", content = userPrompt))

        logger.info { "[PLANNING] UI state: thinking=$thinkingEnabled, noEgress=$noEgressEnabled, messagesCount=${messages.size}, contextAdded=${context.isNotBlank()}" }

        // 8. RFC 0032: Use unified complete() with stream flag
        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId),
            temperature = 0.7,
            responseFormat = mapOf("type" to "json_object"),
            thinking = thinkingEnabled,
            noEgressEnabled = noEgressEnabled,
            stream = stream,
            onChunk = if (stream) onChunk else null,
            taskId = taskId,
            subtaskId = null,
            source = "Planner",
            contextContent = context.takeIf { it.isNotBlank() }
        )

        val fullContent = response.content
        val finalUsage = response.usage
        val finalCost = response.cost

        logger.info {
            "[PLANNING] LLM response complete: tokens_in=${finalUsage.inputTokens}, " +
            "tokens_out=${finalUsage.outputTokens}, cost=\$${String.format("%.4f", finalCost)}"
        }
        val planningResponse = processLLMResponse(
            task = task,
            content = fullContent,
            requestId = requestId,
            model = model,
            provider = provider,
            tokensIn = finalUsage.inputTokens,
            tokensOut = finalUsage.outputTokens,
            cost = finalCost,
            interactive = request.interactive
        )

        logger.info { "[PLANNING] Plan creation complete" }

        return planningResponse
    }

    // ========================================================================
    // LLM Response Processing (shared logic)
    // ========================================================================

    /**
     * Process LLM response: parse JSON, save message, create subtasks, update metrics.
     * Shared by both streaming and non-streaming modes.
     */
    private fun processLLMResponse(
        task: Task,
        content: String,
        requestId: String,
        model: String,
        provider: String,
        tokensIn: Int,
        tokensOut: Int,
        cost: Double,
        interactive: Boolean
    ): PlanningResponse {
        // 1. Parse plan from JSON response
        val planData = parsePlanJson(content)

        // 2. Save assistant response with metrics
        val planText = formatPlanResponse(planData)
        chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.ASSISTANT,
            content = planText,
            metadata = gson.toJson(mapOf(
                "request_id" to requestId,
                "model" to model,
                "provider" to provider,
                "tokens_in" to tokensIn,
                "tokens_out" to tokensOut,
                "cost_usd" to cost,
                "subtasks_count" to (planData["subtasks"] as? List<*>)?.size
            )),
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            cost = cost
        )

        logger.info { "[PLANNING] Plan saved to chat history" }

        // 3. Create subtasks from plan
        val subtasks = createSubtasksFromPlan(
            task = task,
            planData = planData,
            interactive = interactive,
            llmModel = model,
            llmProvider = provider
        )

        logger.info { "[PLANNING] Created ${subtasks.size} subtasks" }

        // 4. Update task metrics
        taskRepository.incrementMetrics(
            id = task.id,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            costUsd = cost
        )
        logger.info { "[PLANNING] Incremented task metrics: +$tokensIn/$tokensOut tokens, +\$${String.format("%.4f", cost)}" }

        // 5. Update task status to SUCCESS (plan created)
        taskRepository.update(id = task.id, status = TaskStatus.SUCCESS)

        // 6. Build response
        return PlanningResponse(
            plan = planData["plan"]?.toString() ?: "",
            subtasks = subtasks.map { subtaskToResponse(it) },
            costs = PlanCost(
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                usdEst = cost
            ),
            modelUsed = model,
            providerUsed = provider
        )
    }

    // ========================================================================
    // Validation and preparation
    // ========================================================================

    private fun getOrCreateTask(taskId: String, taskName: String, mode: TaskMode): Task {
        val existingTask = taskRepository.findById(taskId)
        if (existingTask != null) {
            if (existingTask.mode != TaskMode.PLAN && existingTask.mode != TaskMode.AGENT) {
                throw IllegalArgumentException(
                    "Planning requires mode PLAN or AGENT, got ${existingTask.mode}"
                )
            }
            return existingTask
        }

        // Task not found - create new one with provided ID and parameters
        logger.info { "[PLANNING] Task not found ($taskId), creating new task: id=$taskId, name='$taskName', mode=$mode" }

        val truncatedName = if (taskName.length > 250) taskName.take(250) + "..." else taskName
        return taskRepository.create(
            id = taskId,
            name = truncatedName,
            mode = mode,
            readOnly = mode == TaskMode.PLAN,
            executionMode = ExecutionMode.INTERACTIVE,
            projectId = fallbackProjectId,
            projectPath = fallbackProjectPath
        )
    }

    private fun sanitizeInput(text: String): String {
        var sanitized = text

        // Remove dangerous patterns
        val dangerousPatterns = listOf(
            "ignore previous instructions",
            "disregard all previous",
            "</system>",
            "<|im_end|>"
        )

        dangerousPatterns.forEach { pattern ->
            sanitized = sanitized.replace(pattern, "[REDACTED]", ignoreCase = true)
        }

        return sanitized
    }

    // ========================================================================
    // Context building (delegated to ContextService)
    // ========================================================================

    /**
     * Build context using ContextService as SINGLE SOURCE OF TRUTH.
     *
     * ContextService handles:
     * - Project analysis
     * - Conversation history
     * - Previous subtasks
     * - Previous tool outputs
     * - User requirements extraction
     *
     * This method additionally handles:
     * - User-provided context references (@file, @folder, etc.)
     */
    private suspend fun buildContextWithContextService(
        task: Task,
        contextRefs: List<ContextReference>,
        ragUserPrompt: String? = null
    ): String {
        // Build project context using ContextService (including user context refs)
        // ContextService now handles resolution of user context internally
        if (contextService != null && projectRoot != null) {
            logger.info { "[PLANNING] Building project context via ContextService for task ${task.id}, contextRefs=${contextRefs.size}" }

            try {
                val projectContext = contextService.buildProjectContext(
                    projectRoot = projectRoot,
                    taskId = task.id,
                    query = ragUserPrompt,
                    userContextRefs = contextRefs
                )

                val contextPrompt = contextService.buildLLMContextPrompt(projectContext)
                logger.info { "[PLANNING] Built context: ${contextPrompt.length} chars" }
                return contextPrompt
            } catch (e: Exception) {
                logger.error(e) { "[PLANNING] Failed to build project context: ${e.message}" }
                return ""  // Continue without project context rather than failing
            }
        } else {
            if (contextService == null) {
                logger.debug { "[PLANNING] ContextService not available, skipping project context" }
            }
            if (projectRoot == null) {
                logger.debug { "[PLANNING] Project root not available, skipping project context" }
            }
            return ""
        }
    }

    private fun buildUserPrompt(request: String): String {
        val parts = mutableListOf<String>()

        parts.add("User request:\n$request")
        parts.add("")
        parts.add("Create a detailed execution plan as JSON.")

        return parts.joinToString("\n")
    }

    // ========================================================================
    // LLM response parsing
    // ========================================================================

    private fun parsePlanJson(content: String): Map<String, Any> {
        try {
            // Use universal JSON extractor with normalization for different response schemas
            val planData = JsonExtractor.extractAndParsePlanningResponse(content)

            logger.info { "[PLANNING] Plan parsed successfully: ${(planData["subtasks"] as? List<*>)?.size ?: 0} subtasks" }

            return planData
        } catch (e: JsonParseException) {
            logger.error { "[PLANNING] Failed to extract/parse/normalize JSON: ${e.message}" }
            logger.error { "[PLANNING] Full content:\n$content" }
            throw IllegalArgumentException("LLM returned invalid JSON: ${e.message}", e)
        } catch (e: Exception) {
            logger.error { "[PLANNING] Unexpected error while parsing JSON: ${e.message}" }
            logger.error { "[PLANNING] Content preview: ${content.take(500)}..." }
            throw IllegalArgumentException("Failed to parse LLM response: ${e.message}", e)
        }
    }

    private fun formatPlanResponse(planData: Map<String, Any>): String {
        val plan = planData["plan"]?.toString() ?: ""
        val subtasks = (planData["subtasks"] as? List<*>) ?: emptyList<Any>()

        if (subtasks.isEmpty()) {
            return plan
        }

        val formatted = StringBuilder(plan)
        formatted.append("\n\n## Plan Steps:\n\n")

        subtasks.forEachIndexed { index, subtask ->
            val step = subtask as? Map<*, *>
            formatted.append("**${index + 1}. ${step?.get("name") ?: "Step ${index + 1}"}**\n")
            formatted.append("   - Description: ${step?.get("description") ?: ""}\n")
            formatted.append("   - Kind: ${step?.get("kind") ?: ""}\n")
            formatted.append("   - Parameters: ${step?.get("paramsJson") ?: ""}\n")
            formatted.append("\n")
        }

        return formatted.toString()
    }

    // ========================================================================
    // Subtask creation
    // ========================================================================

    private fun createSubtasksFromPlan(
        task: Task,
        planData: Map<String, Any>,
        interactive: Boolean,
        llmModel: String,
        llmProvider: String
    ): List<Subtask> {
        val subtasks = mutableListOf<Subtask>()
        val subtasksList = (planData["subtasks"] as? List<*>) ?: emptyList<Any>()
        var skippedCount = 0

        // Get list of valid tool names for this mode (filtered by permissions + mode)
        val validToolNames = toolDescriptionBuilder.getValidToolNames(task.mode, task.id)
            .split(", ")
            .map { it.trim() }
            .toSet()

        logger.info { "[PLANNING] Valid tools for ${task.mode} mode: $validToolNames" }

        // Get the maximum order_index for this task to support continuation
        val maxOrderIndex = subtaskRepository.getMaxOrderIndex(task.id)
        val startingIndex = maxOrderIndex ?: 0  // Sequential: first batch starts at 0, so index+1 gives 1,2,3...
        logger.info { "[PLANNING] Max order_index for task ${task.id}: $maxOrderIndex, starting new subtasks from: ${startingIndex + 1}" }

        subtasksList.forEachIndexed { index, step ->
            val stepMap = step as? Map<*, *> ?: return@forEachIndexed

            // Map tool names (snake_case) to SubtaskKind enum (SCREAMING_SNAKE_CASE)
            val toolName = stepMap["kind"]?.toString() ?: "plan_step"

            // CRITICAL: Validate tool is available for this mode
            if (toolName != "plan_step" && toolName !in validToolNames) {
                logger.warn { "[PLANNING] Skipping step with unavailable tool '$toolName' - not in valid tools for ${task.mode} mode." }
                skippedCount++
                return@forEachIndexed
            }

            // Double-check permission (for ASK/ON tools)
            if (toolPermissionsService != null && toolName != "plan_step") {
                val permission = toolPermissionsService.getPermission(toolName, task.mode, task.id)
                if (permission == PermissionLevel.OFF) {
                    logger.warn { "[PLANNING] Skipping step with disabled tool '$toolName' (permission=OFF for ${task.mode} mode)." }
                    skippedCount++
                    return@forEachIndexed
                }
            }

            // Dynamic tool → SubtaskKind mapping via ToolRegistry
            val kind = toolRegistry?.toSubtaskKind(toolName) ?: SubtaskKind.PLAN_STEP

            val toolArgs = stepMap["tool_args"] as? Map<*, *>

            // Store tool_args as SUGGESTIONS for StepPlanner
            val planStructure = mapOf(
                "intent" to (stepMap["description"]?.toString() ?: "Execute step"),
                "tool_type" to toolName,
                "suggested_params" to (toolArgs ?: emptyMap<String, Any>()),
                "description" to (stepMap["description"]?.toString() ?: ""),
                "estimated_duration_ms" to 5000
            )

            val paramsJson = gson.toJson(planStructure)
            val orderIndex = startingIndex + index + 1  // Sequential numbering: 1, 2, 3...

            val subtask = subtaskRepository.create(
                taskId = task.id,
                orderIndex = orderIndex,
                kind = kind,
                description = stepMap["description"]?.toString() ?: "",
                paramsJson = paramsJson,
                requiresApproval = interactive,
                llmModel = llmModel,
                llmProvider = llmProvider
            )

            subtasks.add(subtask)
        }

        if (skippedCount > 0) {
            logger.warn { "[PLANNING] Skipped $skippedCount steps with disabled tools" }
        }
        logger.info { "[PLANNING] Created ${subtasks.size} subtasks with intent (dynamic planning)" }

        return subtasks
    }

    private fun subtaskToResponse(subtask: Subtask): SubtaskResponse {
        return SubtaskResponse(
            id = subtask.id,
            taskId = subtask.taskId,
            orderIndex = subtask.orderIndex,
            kind = subtask.kind.name,
            status = subtask.status.name,
            approvalStatus = subtask.approvalStatus.name,
            requiresApproval = subtask.requiresApproval,
            approvedByUser = subtask.approvalStatus == ApprovalStatus.APPROVED,
            description = subtask.description,
            paramsJson = subtask.paramsJson,
            stepPlanJson = subtask.stepPlanJson,
            summary = subtask.summary,
            result = subtask.result,
            startedAt = subtask.startedAt,
            finishedAt = subtask.completedAt,
            errorCode = null,
            errorMessage = subtask.errorMessage,
            tokensIn = subtask.inputTokens,
            tokensOut = subtask.outputTokens,
            costUsd = subtask.costUsd,
            latencyMs = subtask.latencyMs,
            model = subtask.llmModel,
            provider = subtask.llmProvider,
            resultSummary = subtask.summary ?: subtask.result?.take(500),
            createdAt = subtask.createdAt,
            updatedAt = subtask.updatedAt,
            completedAt = subtask.completedAt
        )
    }
}
