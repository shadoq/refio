package pl.jclab.refio.core.services

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.models.api.SummarizeResponse
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.TokenEstimator
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID

private val logger = dualLogger("ChatService")
private const val MAX_CONTEXT_REFS = 50
private const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"
private const val LEGACY_PROJECT_ID = "legacy_unknown"
private const val LEGACY_PROJECT_PATH = "unknown"

/**
 * Service for handling chat interactions with LLM.
 *
 * Based on Python implementation in agent/core/api/v1/chat.py
 *
 * Flow:
 * 1. Validate task exists and mode is CHAT
 * 2. Update task status to RUNNING if NEW
 * 3. Save user message to database
 * 4. Build conversation context from history (includes project context)
 * 5. Call LLM adapter
 * 6. Save assistant response to database
 * 7. Update task status to SUCCESS
 * 8. Return response
 */
class ChatService(
    private val taskRepository: TaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val configService: ConfigService,
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val contextService: ContextService? = null,
    private val projectRoot: java.nio.file.Path? = null,
    private val ideProject: Any? = null
) {
    private val fallbackProjectId: String =
        projectRoot?.let { ProjectIdGenerator.generate(it) } ?: LEGACY_PROJECT_ID
    private val fallbackProjectPath: String =
        projectRoot?.toAbsolutePath()?.normalize()?.toString() ?: LEGACY_PROJECT_PATH

    /**
     * Process chat request and get LLM response.
     *
     * RFC 0032: Unified streaming/non-streaming method.
     * - Always uses streamComplete() internally for consistency
     * - If stream=true and onChunk provided, callback is invoked with each chunk
     * - Always returns ChatResponse (streaming is presentation, not API change)
     *
     * @param request Chat request with task ID, input message, and parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Chat response with assistant message and metadata
     * @throws IllegalArgumentException If task not found or mode is not CHAT
     * @throws Exception On LLM API errors
     */
    suspend fun chat(
        request: ChatRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ChatResponse {
        val startTime = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        logger.info {
            "[CHAT_SERVICE] Starting chat request: requestId=$requestId, taskId=${request.taskId}"
        }
        logger.debug {
            "[CHAT_SERVICE] Request: mode=${request.mode}, inputLength=${request.input.length} chars, " +
                    "contextRefsCount=${request.contextRefs.size}, model=${request.params.model}, " +
                    "provider=${request.params.provider}, temperature=${request.params.temperature}"
        }

        // Validate mode
        if (request.mode != TaskMode.CHAT) {
            throw IllegalArgumentException(
                "Chat endpoint only supports CHAT mode, got: ${request.mode}"
            )
        }

        // Load existing task or create new one
        val task = taskRepository.findById(request.taskId)
            ?: taskRepository.create(
                name = "Chat Session",
                mode = TaskMode.CHAT,
                projectId = fallbackProjectId,
                projectPath = fallbackProjectPath,
                readOnly = configService.getTyped(ConfigKeys.READ_ONLY_MODE)
            ).also {
                logger.info { "[CHAT_SERVICE] Created new task: ${it.id} (requested: ${request.taskId})" }
            }

        logger.info { "[CHAT_SERVICE] Using task: ${request.taskId}, status=${task.status}" }

        // Update task status to RUNNING if NEW
        if (task.status == TaskStatus.NEW) {
            taskRepository.update(id = task.id, status = TaskStatus.RUNNING)
            logger.info { "[CHAT_SERVICE] Updated task status: NEW -> RUNNING" }
        }

        // Get model and provider (before context building for logging)
        val (model, provider) = if (request.params.model != null && request.params.provider != null) {
            logger.info {
                "[CHAT_SERVICE] Using explicit model from request: ${request.params.model} " +
                        "(provider=${request.params.provider})"
            }
            Pair(request.params.model, request.params.provider)
        } else {
            val operation = ModelOperation.fromTaskMode(request.mode)
            val (m, p) = configService.getModel(operation = operation, taskId = task.id)
            logger.info { "[CHAT_SERVICE] Using model from config: $m (provider=$p)" }
            Pair(m, p)
        }

        if (request.contextRefs.size > MAX_CONTEXT_REFS) {
            throw IllegalArgumentException(
                "Context limit exceeded: ${request.contextRefs.size} (max $MAX_CONTEXT_REFS)"
            )
        }

        val userContextMetadata = buildUserContextMetadata(request.contextRefs)
        logger.info { "[CHAT_SERVICE] Built context metadata: contextRefs=${request.contextRefs.size}, metadata=${userContextMetadata?.take(200)}" }

        // Save user message (with context if provided - persists context in conversation)
        val userMessage = chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.USER,
            content = request.input,
            metadata = userContextMetadata
        )
        logger.info { "[CHAT_SERVICE] Saved user message: id=${userMessage.id}, metadata=${userMessage.metadata?.take(200)}" }

        // Load full conversation history once for both LLM context and cumulative references
        val chatHistory = chatMessageRepository.findByTaskId(task.id)
        logger.info { "[CHAT_SERVICE] Loaded ${chatHistory.size} messages for context building" }

        val cumulativeContextRefs = buildCumulativeContext(chatHistory)
        logger.info { "[CHAT_SERVICE] Cumulative context references from history: ${cumulativeContextRefs.size}" }

        // IMPORTANT: Add current request context refs (not yet persisted in history)
        // This ensures @open_files and other fresh context is included in LLM prompt
        val allContextRefs = cumulativeContextRefs + request.contextRefs
        logger.info { "[CHAT_SERVICE] Total context refs (history + current): ${allContextRefs.size} (current: ${request.contextRefs.size})" }

        // Build conversation context from history
        var messages = buildLlmMessages(chatHistory)
        logger.info { "[CHAT_SERVICE] Built context with ${messages.size} messages" }

        val contextPrompt = if (contextService != null && projectRoot != null) {
            try {
                logger.info { "[CHAT_SERVICE] Building project context with ${allContextRefs.size} total reference(s)" }
                val projectContext = contextService.buildProjectContext(
                    projectRoot = projectRoot,
                    taskId = task.id,
                    project = ideProject,
                    query = request.input,
                    userContextRefs = allContextRefs  // Use combined refs (history + current request)
                )
                contextService.buildLLMContextPrompt(projectContext)
            } catch (e: Exception) {
                logger.error(e) { "[CHAT_SERVICE] Failed to build project context for system prompt" }
                ""
            }
        } else {
            ""
        }

        // Build system prompt WITHOUT context (policies only)
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_CHAT,
            variables = emptyMap()  // Context is now passed separately
        )

        val autoOptimizePercentage = configService.getTyped(ConfigKeys.AUTO_OPTIMIZE_PERCENTAGE)
        if (autoOptimizePercentage > 0) {
            val modelMaxContext = TokenEstimator.getMaxContextForModel(model, provider, configService)
            val configuredLimit = configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, task.id)
            val effectiveMaxContext = minOf(modelMaxContext, configuredLimit)
            val threshold = (effectiveMaxContext * autoOptimizePercentage) / 100
            val estimatedTokens = TokenEstimator.estimateRequestTokens(messages, systemPrompt)

            if (estimatedTokens >= threshold) {
                logger.info {
                    "[CHAT_SERVICE] Auto-optimize triggered: estimatedTokens=$estimatedTokens, " +
                            "threshold=$threshold (maxContext=$effectiveMaxContext, percent=$autoOptimizePercentage)"
                }

                val candidates = chatHistory.filterNot { it.id == userMessage.id }
                if (hasMessagesToSummarize(candidates)) {
                    summarizeConversationForAutoOptimize(task.id, userMessage.id)

                    val refreshedHistory = chatMessageRepository.findByTaskId(task.id)
                    val optimizedHistory = buildHistoryFromLastSummary(refreshedHistory, userMessage.id)
                    messages = buildLlmMessages(optimizedHistory)

                    val optimizedTokens = TokenEstimator.estimateRequestTokens(messages, systemPrompt)
                    logger.info {
                        "[CHAT_SERVICE] Auto-optimize completed: messages=${messages.size}, " +
                                "estimatedTokens=$optimizedTokens"
                    }
                } else {
                    logger.info { "[CHAT_SERVICE] Auto-optimize skipped: no messages to summarize" }
                }
            }
        }

        // Read UI state from config table (single source of truth)
        // UI state is global plugin state, not task-specific (saved by SessionManager)
        val thinkingEnabled = configService.get(ConfigService.KEY_UI_THINKING_ENABLED)?.toBoolean() ?: false
        val noEgressEnabled = configService.get(ConfigService.KEY_UI_NO_EGRESS_ENABLED)?.toBoolean() ?: false

        // Call LLM
        // Context is passed separately via contextContent parameter to ensure proper message order:
        // [system] System prompt, [user] Context, [user] User prompt (for simple queries)
        // [system] System, [user] Context1, [user] Prompt1, [assistant] Answer1, [user] Context2, [user] Prompt2 (with history)
        logger.info {
            "[CHAT_SERVICE] Calling LLM: provider=$provider, model=$model, messagesCount=${messages.size}, " +
                    "contextAdded=${contextPrompt.isNotBlank()}, thinking=$thinkingEnabled, noEgress=$noEgressEnabled, stream=$stream"
        }
        logger.debug {
            "[CHAT_SERVICE] LLM parameters: maxTokens=${request.params.maxTokens}, " +
                    "temperature=${request.params.temperature ?: 0.7}"
        }

        // RFC 0032: Use unified complete() with stream flag
        val response = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = request.params.maxTokens,
                temperature = request.params.temperature ?: 0.7,
                thinking = thinkingEnabled,
                noEgressEnabled = noEgressEnabled,
                stream = stream,
                onChunk = if (stream) onChunk else null,
                taskId = task.id,
                subtaskId = null,
                source = "Chat",
                contextContent = contextPrompt.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            logger.error(e) { "[CHAT_SERVICE] LLM API error: ${e.message}" }
            taskRepository.update(id = task.id, status = TaskStatus.FAILED)
            logger.info { "[CHAT_SERVICE] Task ${task.id} marked as FAILED due to LLM error" }
            throw Exception("LLM API call failed: ${e.message}", e)
        }

        val responseContent = response.content
        val finalUsage = response.usage
        val finalCost = response.cost
        val wasCancelled = response.finishReason == "cancelled"

        if (wasCancelled) {
            logger.warn {
                "[CHAT_SERVICE] Response was cancelled - saving partial content (${responseContent.length} chars)"
            }
        } else {
            logger.info {
                "[CHAT_SERVICE] LLM response received: tokensIn=${finalUsage.inputTokens}, " +
                        "tokensOut=${finalUsage.outputTokens}, cost=$${String.format("%.4f", finalCost)}"
            }
        }

        // Log response content (truncated)
        val contentPreview = if (responseContent.length > 300) {
            responseContent.substring(0, 300) + "..."
        } else {
            responseContent
        }
        logger.debug { "[CHAT_SERVICE] Response content preview: $contentPreview" }

        // Calculate latency for metrics
        val endTime = System.currentTimeMillis()
        val latencyMs = (endTime - startTime).toInt()

        // Create metrics using MessageMetrics (US-027)
        val metrics = MessageMetrics.fromLLMResponse(
            model = model,
            provider = provider,
            inputTokens = finalUsage.inputTokens,
            outputTokens = finalUsage.outputTokens,
            costUsd = finalCost,
            latencyMs = latencyMs,
            startedAt = startTime,
            completedAt = endTime
        )

        val metricsJson = MessageMetrics.toJson(metrics)
        logger.info { "[US-027] Created metrics: $metrics" }
        logger.info { "[US-027] Metrics JSON length: ${metricsJson.length} chars" }

        // Save assistant response with metrics in metadata AND columns (Bug #4)
        val assistantMessage = chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.ASSISTANT,
            content = responseContent,
            metadata = metricsJson,
            tokensIn = finalUsage.inputTokens,
            tokensOut = finalUsage.outputTokens,
            cost = finalCost
        )
        logger.info { "[CHAT_SERVICE] Saved assistant message: id=${assistantMessage.id}, tokens=${finalUsage.inputTokens}/${finalUsage.outputTokens}, cost=$${String.format("%.4f", finalCost)}" }

        // Update task metrics (increment with response costs)
        taskRepository.incrementMetrics(
            id = task.id,
            tokensIn = finalUsage.inputTokens,
            tokensOut = finalUsage.outputTokens,
            costUsd = finalCost
        )
        logger.info { "[CHAT_SERVICE] Incremented task metrics: +${finalUsage.inputTokens}/${finalUsage.outputTokens} tokens, +$${String.format("%.4f", finalCost)}" }

        // Update task status based on cancellation
        val finalStatus = if (wasCancelled) TaskStatus.CANCELED else TaskStatus.SUCCESS
        taskRepository.update(id = task.id, status = finalStatus)
        logger.info { "[CHAT_SERVICE] Task ${task.id} marked as $finalStatus" }

        // Log completion metrics
        logger.info {
            "[CHAT_SERVICE] Chat completed: taskId=${task.id}, tokensIn=${finalUsage.inputTokens}, " +
                    "tokensOut=${finalUsage.outputTokens}, latency=${latencyMs}ms"
        }

        // Build response
        return ChatResponse(
            schemaVersion = "1.0",
            requestId = requestId,
            taskId = task.id,
            messageId = assistantMessage.id,
            output = responseContent,
            costs = ChatCosts(
                tokensIn = finalUsage.inputTokens,
                tokensOut = finalUsage.outputTokens,
                usdEst = finalCost
            ),
            toolCalls = emptyList(),  // No tools in CHAT mode
            diffSummary = null,  // No file changes in CHAT mode
            errorCode = null
        )
    }

    /**
     * Summarize conversation since the last summary and persist the result as a system message.
     */
    suspend fun summarizeConversation(taskId: String): SummarizeResponse {
        return summarizeConversation(taskId, null)
    }

    suspend fun summarizeConversation(
        taskId: String,
        streamCallback: StreamCallback? = null
    ): SummarizeResponse {
        logger.info { "[CHAT_SERVICE] Summarizing conversation for task=$taskId" }

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val allMessages = chatMessageRepository.findByTaskId(taskId)
        if (allMessages.isEmpty()) {
            throw IllegalArgumentException("Conversation is too short to summarize")
        }

        val lastSummaryIndex = findLastSummaryIndex(allMessages)
        val messagesToSummarize = if (lastSummaryIndex >= 0) {
            allMessages.drop(lastSummaryIndex + 1)
        } else {
            allMessages
        }

        if (messagesToSummarize.isEmpty()) {
            throw IllegalArgumentException("No new messages since last summary")
        }

        val meaningfulCount = messagesToSummarize.count { !isConversationSummary(it) }

        val conversationText = buildString {
            messagesToSummarize.forEach { msg ->
                appendLine("${msg.role.name.uppercase()}: ${msg.content}")
                appendLine()
            }
        }

        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_CONVERSATION_SUMMARY,
            variables = mapOf("conversation" to conversationText)
        )

        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = task.id
        )

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(LLMMessage(role = "user", content = systemPrompt)),
            temperature = 0.3,
            source = "summarizeConversation",
            taskId = task.id,
            subtaskId = null,
            stream = streamCallback != null,
            onChunk = streamCallback
        )

        val summaryIndex = allMessages.count { isConversationSummary(it) } + 1
        val metadataJson = gson.toJson(
            mapOf(
                "type" to CONVERSATION_SUMMARY_METADATA_TYPE,
                "summarized_count" to meaningfulCount,
                "summary_index" to summaryIndex,
                "timestamp" to System.currentTimeMillis(),
                "first_message_id" to messagesToSummarize.first().id,
                "last_message_id" to messagesToSummarize.last().id
            )
        )

        val summaryContent = buildString {
            append("**🧠 Conversation summary (${meaningfulCount} messages):**\n\n")
            append(response.content.trim())
        }

        val summaryMessage = chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.SYSTEM,
            content = summaryContent,
            metadata = metadataJson,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            cost = response.cost
        )

        logger.info {
            "[CHAT_SERVICE] Saved conversation summary messageId=${summaryMessage.id}, " +
                    "index=$summaryIndex, count=$meaningfulCount"
        }

        return SummarizeResponse(
            summaryMessageId = summaryMessage.id,
            summarizedCount = meaningfulCount,
            summaryIndex = summaryIndex,
            content = response.content
        )
    }

    /**
     * Generate a short session title using a weak model.
     */
    suspend fun generateSessionTitle(taskId: String, userMessage: String): String {
        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val cleaned = userMessage.trim().replace(Regex("\\s+"), " ").take(200)
        val prompt = buildString {
            appendLine("Generate a short 4-5 word title for this conversation.")
            appendLine("Respond with ONLY the title (no quotes, no punctuation).")
            appendLine()
            appendLine("User message:")
            appendLine(cleaned)
        }

        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = task.id
        )

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(LLMMessage(role = "user", content = prompt)),
            temperature = 0.2,
            source = "generateSessionTitle",
            taskId = task.id,
            subtaskId = null
        )

        return response.content.trim()
    }

    private fun buildLlmMessages(history: List<ChatMessage>): List<LLMMessage> =
        history.map { msg -> LLMMessage(role = msg.role.name.lowercase(), content = msg.content) }

    private fun buildCumulativeContext(history: List<ChatMessage>): List<ContextReference> {
        val refs = mutableListOf<ContextReference>()

        history.filter { it.role == MessageRole.USER }.forEach { message ->
            val metadata = UserContextMetadata.fromJson(message.metadata)
            if (metadata != null && metadata.contextRefs.isNotEmpty()) {
                refs.addAll(metadata.contextRefs)
            }
        }

        return refs
    }

    private fun buildUserContextMetadata(refs: List<ContextReference>): String? {
        if (refs.isEmpty()) return null
        val summary = buildContextSummary(refs.size)
        return UserContextMetadata.toJson(refs, summary)
    }

    private fun buildContextSummary(count: Int): String =
        if (count == 1) "Added 1 file" else "Added $count files"

    private fun hasMessagesToSummarize(messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) {
            return false
        }
        val lastSummaryIndex = findLastSummaryIndex(messages)
        val messagesToSummarize = if (lastSummaryIndex >= 0) {
            messages.drop(lastSummaryIndex + 1)
        } else {
            messages
        }
        return messagesToSummarize.isNotEmpty()
    }

    private fun buildHistoryFromLastSummary(
        messages: List<ChatMessage>,
        requiredMessageId: String
    ): List<ChatMessage> {
        val lastSummaryIndex = findLastSummaryIndex(messages)
        if (lastSummaryIndex < 0) {
            return messages
        }

        val summaryMessage = messages[lastSummaryIndex]
        val afterSummary = messages.drop(lastSummaryIndex + 1)
        val result = ArrayList<ChatMessage>(afterSummary.size + 2)
        result.add(summaryMessage)
        result.addAll(afterSummary)

        if (result.none { it.id == requiredMessageId }) {
            val required = messages.firstOrNull { it.id == requiredMessageId }
            if (required != null) {
                result.add(required)
            }
        }

        return result
    }

    private suspend fun summarizeConversationForAutoOptimize(
        taskId: String,
        excludeMessageId: String
    ): SummarizeResponse {
        logger.info { "[CHAT_SERVICE] Auto-optimize summarization for task=$taskId" }

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val allMessages = chatMessageRepository.findByTaskId(taskId)
        val filteredMessages = allMessages.filterNot { it.id == excludeMessageId }
        if (filteredMessages.isEmpty()) {
            throw IllegalArgumentException("Conversation is too short to summarize")
        }

        val lastSummaryIndex = findLastSummaryIndex(filteredMessages)
        val messagesToSummarize = if (lastSummaryIndex >= 0) {
            filteredMessages.drop(lastSummaryIndex + 1)
        } else {
            filteredMessages
        }

        if (messagesToSummarize.isEmpty()) {
            throw IllegalArgumentException("No new messages since last summary")
        }

        val meaningfulCount = messagesToSummarize.count { !isConversationSummary(it) }
        val conversationText = buildString {
            messagesToSummarize.forEach { msg ->
                appendLine("${msg.role.name.uppercase()}: ${msg.content}")
                appendLine()
            }
        }

        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_CONVERSATION_SUMMARY,
            variables = mapOf("conversation" to conversationText)
        )

        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = task.id
        )

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(LLMMessage(role = "user", content = systemPrompt)),
            temperature = 0.3,
            source = "summarizeConversation",
            taskId = task.id,
            subtaskId = null
        )

        val summaryIndex = allMessages.count { isConversationSummary(it) } + 1
        val metadataJson = gson.toJson(
            mapOf(
                "type" to CONVERSATION_SUMMARY_METADATA_TYPE,
                "summarized_count" to meaningfulCount,
                "summary_index" to summaryIndex,
                "timestamp" to System.currentTimeMillis(),
                "first_message_id" to messagesToSummarize.first().id,
                "last_message_id" to messagesToSummarize.last().id
            )
        )

        val summaryContent = buildString {
            append("**Ð«õÿ Conversation summary (${meaningfulCount} messages):**\n\n")
            append(response.content.trim())
        }

        val summaryMessage = chatMessageRepository.create(
            taskId = task.id,
            role = MessageRole.SYSTEM,
            content = summaryContent,
            metadata = metadataJson,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            cost = response.cost
        )

        logger.info {
            "[CHAT_SERVICE] Saved conversation summary (auto-optimize) messageId=${summaryMessage.id}, " +
                    "index=$summaryIndex, count=$meaningfulCount"
        }

        return SummarizeResponse(
            summaryMessageId = summaryMessage.id,
            summarizedCount = meaningfulCount,
            summaryIndex = summaryIndex,
            content = response.content
        )
    }

    private fun findLastSummaryIndex(messages: List<ChatMessage>): Int =
        messages.indexOfLast { isConversationSummary(it) }

    private fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }
}
