package pl.jclab.refio.core.api.routers

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMMessageMapper
import pl.jclab.refio.core.llm.TokenEstimator
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.ProjectAnalyzerService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("ProjectContextRouter")

/**
 * Router for project context operations (context panel, prompt preview).
 */
class ProjectContextRouter(
    private val contextService: ContextService?,
    private val projectRoot: Path?,
    private val ideProject: Any?,
    private val taskRepository: TaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: pl.jclab.refio.core.prompts.ToolDescriptionBuilder,
    private val projectAnalyzer: ProjectAnalyzerService?,
    private val richProjectAnalysisEngine: RichProjectAnalysisEngine?,
    /**
     * Prompt section providers shared with TurnPromptBuilder so that the
     * Context panel preview shows exactly the same system prompt additions
     * (e.g. <system_environment>, <agent_plans>) that the runtime prompt has.
     * Pass emptyList() to preserve the legacy stripped preview.
     */
    private val promptSectionProviders: List<pl.jclab.refio.core.services.turn.PromptSectionProvider> = emptyList()
) : Router {

    override suspend fun initialize() {
        logger.info { "[ProjectContextRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[ProjectContextRouter] Shutting down" }
    }

    /**
     * Get project context (for UI visualization).
     */
    suspend fun getProjectContext(
        taskId: String,
        userInput: String? = null,
        contextRefs: List<ContextReference> = emptyList()
    ): ProjectContextResponse {
        logger.debug { "Getting project context for task=$taskId" }

        if (contextService == null || projectRoot == null) {
            throw IllegalStateException("Context service not available - projectRoot required")
        }

        try {
            val task = taskRepository.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: $taskId")

            val chatHistory = chatMessageRepository.findByTaskId(taskId)
            val pendingUserInput = userInput?.takeIf { it.isNotBlank() }
            val effectiveQuery = pendingUserInput
                ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content

            val userContextRefs = contextService.collectAllUserContextRefs(taskId) + contextRefs
            val context = contextService.buildProjectContext(
                projectRoot = projectRoot,
                taskId = taskId,
                project = ideProject,
                query = effectiveQuery,
                userContextRefs = userContextRefs
            )

            val promptContext = if (task.mode == TaskMode.AGENT || task.mode == TaskMode.CHAT) {
                context.copy(conversationHistory = emptyList())
            } else {
                context
            }
            val llmPrompt = contextService.buildLLMContextPrompt(context = promptContext)

            val contextSectionTokens = contextService.calculateContextSectionTokens(promptContext, llmPrompt)
            val runtimePreview = buildRuntimePromptPreview(
                task = task,
                chatHistory = chatHistory,
                pendingUserInput = pendingUserInput,
                contextPrompt = llmPrompt,
                userContextRefs = userContextRefs,
                contextSectionTokens = contextSectionTokens
            )
            val auxiliaryPreview = buildAuxiliaryPromptPreview(task.mode)
            val combinedPreview = buildString {
                append(runtimePreview.previewText)
                if (auxiliaryPreview.previewText.isNotBlank()) {
                    append("\n\n")
                    append(auxiliaryPreview.previewText)
                }
            }

            val updatedContext = context.copy(sectionTokens = runtimePreview.sectionTokens)

            return mapToProjectContextResponse(
                context = updatedContext,
                llmPrompt = llmPrompt,
                llmPreviewPrompt = combinedPreview,
                activeLlmPreviewPrompt = runtimePreview.previewText,
                auxiliaryPreviewPrompt = auxiliaryPreview.previewText,
                activeEstimatedTokens = runtimePreview.activeEstimatedTokens,
                auxiliaryEstimatedTokens = auxiliaryPreview.estimatedTokens,
                combinedEstimatedTokens = runtimePreview.activeEstimatedTokens + auxiliaryPreview.estimatedTokens
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project context" }
            throw e
        }
    }

    /**
     * Generate a lightweight project analysis summary.
     */
    suspend fun getProjectAnalysisSummary(): String {
        if (projectRoot == null || richProjectAnalysisEngine == null) {
            return "Unknown project type"
        }

        return try {
            val report = richProjectAnalysisEngine.analyzeProject(projectRoot)

            buildString {
                append("Project: ${report.architecture.style ?: "Unknown architecture"}\n")

                val topLanguages = report.statistics.linesByLanguage
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(", ") { "${it.key} (${it.value} lines)" }
                append("Languages: $topLanguages\n")

                if (report.technologies.frameworks.isNotEmpty()) {
                    val frameworks = report.technologies.frameworks
                        .take(3)
                        .joinToString(", ") { it.name }
                    append("Frameworks: $frameworks\n")
                }

                append("Files: ${report.statistics.totalFiles}, ")
                append("Classes: ${report.codeStructure.classes.size}, ")
                append("Packages: ${report.codeStructure.packages.size}\n")

                if (report.architecture.layers.isNotEmpty()) {
                    val layers = report.architecture.layers
                        .take(3)
                        .joinToString(", ") { it.name }
                    append("Layers: $layers")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate project analysis summary, using fallback" }
            "Project at $projectRoot"
        }
    }

    // ===== Private helpers =====

    private data class PromptPreviewEntry(
        val source: String,
        val role: String,
        val content: String,
        val estimatedTokens: Int
    )

    private data class RuntimePromptPreview(
        val previewText: String,
        val activeEstimatedTokens: Int,
        val sectionTokens: Map<String, ContextSectionTokenInfo>
    )

    private data class AuxiliaryPromptPreview(
        val previewText: String,
        val estimatedTokens: Int
    )

    private suspend fun buildRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        userContextRefs: List<ContextReference>,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        return when (task.mode) {
            TaskMode.CHAT -> buildChatRuntimePromptPreview(chatHistory, pendingUserInput, contextPrompt, contextSectionTokens)
            TaskMode.PLAN -> buildPlanRuntimePromptPreview(task, chatHistory, pendingUserInput, contextPrompt, contextSectionTokens)
            TaskMode.AGENT -> buildAgentRuntimePromptPreview(task, chatHistory, pendingUserInput, contextPrompt, userContextRefs, contextSectionTokens)
        }
    }

    private suspend fun buildChatRuntimePromptPreview(
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val basePrompt = promptsService.getSystemPrompt(PromptType.SYSTEM_CHAT)
        val systemPrompt = appendProviderSections(basePrompt, TaskMode.CHAT, taskId = "", iteration = 0)
        val messages = chatHistory
            .map { msg -> LLMMessage(role = msg.role.name.lowercase(), content = msg.content) }
            .toMutableList()
        appendPendingUserMessage(messages, pendingUserInput)

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = systemPrompt,
            contextContent = contextPrompt.takeIf { it.isNotBlank() },
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = systemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = contextPrompt.isNotBlank()
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.CHAT,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(previewText = preview, activeEstimatedTokens = activeTokens, sectionTokens = sectionTokens)
    }

    private suspend fun buildPlanRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(TaskMode.PLAN, task.id)
        val validToolNames = toolDescriptionBuilder.getValidToolNames(TaskMode.PLAN, task.id)
        val basePrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_PLAN,
            variables = mapOf("tool_descriptions" to toolDescriptions, "valid_tool_names" to validToolNames)
        )
        val systemPrompt = appendProviderSections(basePrompt, TaskMode.PLAN, task.id, iteration = 0)

        val requestText = pendingUserInput
            ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val userPrompt = buildString {
            appendLine("User request:")
            appendLine(requestText)
            appendLine()
            appendLine("Create a detailed execution plan as JSON.")
        }.trim()
        val messages = listOf(LLMMessage(role = "user", content = userPrompt))

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = systemPrompt,
            contextContent = contextPrompt.takeIf { it.isNotBlank() },
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = systemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = contextPrompt.isNotBlank()
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.PLAN,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(previewText = preview, activeEstimatedTokens = activeTokens, sectionTokens = sectionTokens)
    }

    private suspend fun buildAgentRuntimePromptPreview(
        task: Task,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        contextPrompt: String,
        userContextRefs: List<ContextReference>,
        contextSectionTokens: Map<String, ContextSectionTokenInfo>
    ): RuntimePromptPreview {
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(TaskMode.AGENT, task.id)
        val baseSystemPromptRaw = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_AGENT,
            variables = mapOf("tool_descriptions" to toolDescriptions)
        )
        // Apply the same section providers used by TurnPromptBuilder in runtime
        // so the preview reflects the real prompt (including <system_environment>).
        val baseSystemPrompt = appendProviderSections(baseSystemPromptRaw, TaskMode.AGENT, task.id, iteration = 0)

        val runtimeSystemPrompt = if (contextPrompt.isNotBlank()) {
            "$baseSystemPrompt\n\n<context>\n$contextPrompt\n</context>"
        } else {
            baseSystemPrompt
        }

        val query = pendingUserInput ?: chatHistory.lastOrNull { it.role == MessageRole.USER }?.content
        val messages = buildAgentMessagesForPreview(task.id, chatHistory, pendingUserInput, userContextRefs, query)

        val preparedPayload = LLMClient.prepareRequestPayload(
            messages = messages,
            systemPrompt = runtimeSystemPrompt,
            contextContent = null,
            systemMessages = emptyList()
        )
        val activeTokens = preparedPayload.estimatedInputTokens

        val sectionTokens = buildActiveSectionTokens(
            baseContextSections = contextSectionTokens,
            activeTokens = activeTokens,
            systemPromptForBreakdown = baseSystemPrompt,
            systemMessages = emptyList(),
            messages = messages,
            hasInjectedContext = false
        )

        val preview = renderActiveRequestPreview(
            mode = TaskMode.AGENT,
            systemMessages = preparedPayload.systemMessages,
            messages = preparedPayload.messages
        )

        return RuntimePromptPreview(previewText = preview, activeEstimatedTokens = activeTokens, sectionTokens = sectionTokens)
    }

    private suspend fun buildAgentMessagesForPreview(
        taskId: String,
        chatHistory: List<ChatMessage>,
        pendingUserInput: String?,
        userContextRefs: List<ContextReference>,
        query: String?
    ): List<LLMMessage> {
        if (contextService != null && projectRoot != null) {
            return try {
                val turnMessages = contextService.buildAgentTurnMessages(
                    taskId = taskId, projectRoot = projectRoot, project = null,
                    userContextRefs = userContextRefs, query = query
                ).messages.toMutableList()
                appendPendingUserMessage(turnMessages, pendingUserInput)
                turnMessages
            } catch (e: Exception) {
                logger.warn(e) { "[CONTEXT_PREVIEW] Failed to build agent messages via ContextService, using fallback" }
                buildAgentMessagesFallback(chatHistory, pendingUserInput)
            }
        }
        return buildAgentMessagesFallback(chatHistory, pendingUserInput)
    }

    private fun buildAgentMessagesFallback(chatHistory: List<ChatMessage>, pendingUserInput: String?): List<LLMMessage> {
        val lastToolIndex = chatHistory.indexOfLast { it.role == MessageRole.TOOL }
        val toolNameByCallId = chatHistory
            .asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { it.toolCalls?.asSequence() ?: emptySequence() }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .associate { it.id to it.name }

        val messages = chatHistory.mapIndexedNotNull { index, msg ->
            when (msg.role) {
                MessageRole.USER -> LLMMessage(role = "user", content = msg.content)
                MessageRole.ASSISTANT -> {
                    val toolCallsText = if (!msg.toolCalls.isNullOrEmpty()) {
                        msg.toolCalls.joinToString("\n") { tc -> "TOOL_CALL: ${tc.name}\nARGUMENTS: ${tc.arguments}" }
                    } else null

                    val content = buildString {
                        if (msg.content.isNotBlank()) append(msg.content)
                        if (!toolCallsText.isNullOrBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("Tool calls:\n")
                            append(toolCallsText)
                        }
                    }
                    if (content.isNotBlank()) LLMMessage(role = "assistant", content = content) else null
                }
                MessageRole.TOOL -> {
                    val body = if (index == lastToolIndex && msg.isSummarized) {
                        msg.rawOutput ?: msg.content
                    } else {
                        msg.content
                    }
                    val toolName = msg.toolCallId?.let { toolNameByCallId[it] }
                    LLMMessageMapper.fromToolResult(msg, body, toolName)
                }
                MessageRole.SYSTEM -> LLMMessage(role = "system", content = msg.content)
            }
        }.toMutableList()
        appendPendingUserMessage(messages, pendingUserInput)
        return messages
    }

    private fun appendPendingUserMessage(messages: MutableList<LLMMessage>, pendingUserInput: String?) {
        if (pendingUserInput.isNullOrBlank()) return
        val lastUser = messages.lastOrNull { it.role == "user" }?.content
        if (lastUser == pendingUserInput) return
        messages.add(LLMMessage(role = "user", content = pendingUserInput))
    }

    private fun estimateMessageWithOverheadTokens(content: String): Int {
        return ((content.length + 10) / 4).coerceAtLeast(1)
    }

    private fun buildActiveSectionTokens(
        baseContextSections: Map<String, ContextSectionTokenInfo>,
        activeTokens: Int,
        systemPromptForBreakdown: String?,
        systemMessages: List<String>,
        messages: List<LLMMessage>,
        hasInjectedContext: Boolean
    ): Map<String, ContextSectionTokenInfo> {
        val sections = linkedMapOf<String, Pair<String, Int>>()
        baseContextSections.forEach { (key, info) -> sections[key] = info.name to info.tokens }

        fun addTokens(key: String, name: String, tokens: Int) {
            if (tokens <= 0) return
            val existing = sections[key]
            sections[key] = if (existing == null) name to tokens else existing.first to (existing.second + tokens)
        }

        val systemPromptTokens = systemPromptForBreakdown?.let { TokenEstimator.estimateTokens(it) } ?: 0
        addTokens("system_prompt", "System Prompt", systemPromptTokens)

        val systemMessagesTokens = systemMessages.sumOf { estimateMessageWithOverheadTokens(it) }
        addTokens("system_messages", "System Messages", systemMessagesTokens)

        val userTokens = messages.filter { it.role == "user" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val assistantTokens = messages.filter { it.role == "assistant" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val systemRoleTokens = messages.filter { it.role == "system" }.sumOf { estimateMessageWithOverheadTokens(it.content) }
        val otherTokens = messages.filter { it.role !in setOf("user", "assistant", "system") }.sumOf { estimateMessageWithOverheadTokens(it.content) }

        addTokens("messages_user", "User Messages", userTokens)
        addTokens("messages_assistant", "Assistant Messages", assistantTokens)
        addTokens("messages_system", "System Role Messages", systemRoleTokens)
        addTokens("messages_other", "Other Role Messages", otherTokens)

        if (hasInjectedContext) {
            addTokens("context_injection_overhead", "Context Injection Overhead", 10)
        }

        val normalizedSections = if (activeTokens > 0) {
            val subtotal = sections.values.sumOf { it.second }
            if (subtotal > activeTokens && subtotal > 0) {
                val scale = activeTokens.toDouble() / subtotal.toDouble()
                sections.mapValues { (_, value) -> value.first to (value.second * scale).toInt().coerceAtLeast(1) }.toMutableMap()
            } else {
                sections.toMutableMap()
            }
        } else {
            sections.toMutableMap()
        }

        val subtotal = normalizedSections.values.sumOf { it.second }
        val residual = (activeTokens - subtotal).coerceAtLeast(0)
        if (residual > 0) {
            val existing = normalizedSections["request_overhead"]
            normalizedSections["request_overhead"] = if (existing == null) "Request Overhead" to residual else existing.first to (existing.second + residual)
        }

        val denominator = activeTokens.coerceAtLeast(1).toDouble()
        return normalizedSections.mapValues { (_, value) ->
            val (name, tokens) = value
            ContextSectionTokenInfo(name = name, tokens = tokens, chars = tokens * 4, percentage = (tokens / denominator) * 100.0)
        }
    }

    private fun renderActiveRequestPreview(mode: TaskMode, systemMessages: List<String>, messages: List<LLMMessage>): String {
        return buildString {
            appendLine("Mode: ${mode.name}")
            appendLine()
            appendLine("SYSTEM MESSAGES (${systemMessages.size}):")
            if (systemMessages.isEmpty()) {
                appendLine("(none)")
            } else {
                systemMessages.forEachIndexed { index, message ->
                    appendLine("[SYSTEM ${index + 1}]")
                    appendLine(message)
                    appendLine()
                }
            }
            appendLine()
            appendLine("MESSAGES (${messages.size}):")
            if (messages.isEmpty()) {
                appendLine("(none)")
            } else {
                messages.forEachIndexed { index, msg ->
                    appendLine("[MESSAGE ${index + 1}] role=${msg.role}")
                    appendLine(msg.content)
                    appendLine()
                }
            }
            if (isNotEmpty() && last() == '\n') setLength(length - 1)
        }
    }

    private fun buildAuxiliaryPromptPreview(mode: TaskMode): AuxiliaryPromptPreview {
        val activeType = when (mode) {
            TaskMode.CHAT -> PromptType.SYSTEM_CHAT
            TaskMode.PLAN -> PromptType.SYSTEM_PLAN
            TaskMode.AGENT -> PromptType.SYSTEM_AGENT
        }

        val auxiliaryEntries = PromptType.SYSTEM_PROMPT_TYPES
            .filter { it != activeType }
            .sortedBy { it.name }
            .mapNotNull { type ->
                val content = runCatching { promptsService.getSystemPrompt(type) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val role = if (type.name.endsWith("_USER")) "user_template" else "system"
                PromptPreviewEntry(source = type.name, role = role, content = content, estimatedTokens = TokenEstimator.estimateTokens(content))
            }

        if (auxiliaryEntries.isEmpty()) return AuxiliaryPromptPreview(previewText = "", estimatedTokens = 0)

        val tokens = auxiliaryEntries.sumOf { it.estimatedTokens }
        val preview = buildString {
            appendLine("<AUXILIARY_LLM_PROMPTS>")
            appendLine("<NOTE>")
            appendLine("These prompts are used by tools/summarizers/workflow helpers and are not sent in a single active request.")
            appendLine("</NOTE>")
            appendLine()
            auxiliaryEntries.forEachIndexed { index, prompt ->
                appendLine("""<prompt index="${index + 1}" source="${prompt.source}" role="${prompt.role}" tokens="${prompt.estimatedTokens}">""")
                appendLine(prompt.content)
                appendLine("</prompt>")
                appendLine()
            }
            appendLine("</AUXILIARY_LLM_PROMPTS>")
        }.trim()

        return AuxiliaryPromptPreview(previewText = preview, estimatedTokens = tokens)
    }

    private suspend fun mapToProjectContextResponse(
        context: ProjectContextDTO,
        llmPrompt: String,
        llmPreviewPrompt: String,
        activeLlmPreviewPrompt: String,
        auxiliaryPreviewPrompt: String,
        activeEstimatedTokens: Int,
        auxiliaryEstimatedTokens: Int,
        combinedEstimatedTokens: Int
    ): ProjectContextResponse {
        val projectAnalysis = projectAnalyzer?.analyzeProject(projectRoot!!, includeContent = false)

        val conversationDTOs = context.conversationHistory.map { msg ->
            ConversationMessageDTO(
                id = msg.id, role = msg.role, content = msg.content, createdAt = msg.createdAt,
                processingTime = msg.processingTime, inputTokens = msg.inputTokens, outputTokens = msg.outputTokens,
                cost = msg.cost, modelId = msg.modelId, metadata = msg.metadata
            )
        }

        val userContextRefDTOs = context.userContextRefs.map { ref ->
            UserContextRefDTO(
                type = ref.type, providerId = ref.providerId, path = ref.path,
                displayName = ref.displayName, content = ref.content,
                sizeBytes = ref.sizeBytes, estimatedTokens = ref.estimatedTokens
            )
        }

        val taskRequirementsPrompt = extractSectionFromPrompt(llmPrompt, "TASK_REQUIREMENTS")
        val recentWorkPrompt = extractSectionFromPrompt(llmPrompt, "RECENT_WORK")

        return ProjectContextResponse(
            projectPath = context.workspace.path,
            projectType = context.projectType,
            technologies = context.technologies,
            technologyVersions = context.technologyVersions,
            infrastructure = projectAnalysis?.infrastructure ?: emptyList(),
            primaryLanguage = projectAnalysis?.primaryLanguage ?: "Unknown",
            mainLanguage = context.summary.mainLanguage,
            complexity = context.summary.complexity,
            totalFiles = context.structure.totalFiles,
            fileTypes = context.structure.fileTypes,
            keyComponents = context.keyComponents,
            dependencies = mapOf("python" to context.dependencies.python, "javascript" to context.dependencies.javascript),
            codeAnalysis = mapOf(
                "kotlin" to context.codeAnalysis.kotlin, "java" to context.codeAnalysis.java,
                "python" to context.codeAnalysis.python, "javascript" to context.codeAnalysis.javascript,
                "typescript" to context.codeAnalysis.typescript, "html" to context.codeAnalysis.html,
                "css" to context.codeAnalysis.css
            ),
            currentTask = context.currentTask,
            subtasks = context.subtasks,
            executedSteps = context.executedSteps,
            completedFiles = context.completedFiles,
            llmContextPrompt = llmPreviewPrompt,
            analyzedAt = context.contextGeneratedAt.toEpochMilli(),
            contextBuiltAt = context.contextGeneratedAt.toEpochMilli(),
            userRequirements = context.userRequirements,
            ragFragments = context.ragFragments,
            mcpResources = context.mcpResources.map {
                MCPResourceResponse(serverId = it.serverId, uri = it.uri, name = it.name, description = it.description, mimeType = it.mimeType)
            },
            userContextRefs = userContextRefDTOs,
            conversationHistory = conversationDTOs,
            previousSubtasks = context.executedSteps.map { it.displayContent },
            domainAnalysis = context.domainAnalysis,
            directoryCount = context.structure.directoryCount,
            maxDepth = context.structure.maxDepth,
            contextSectionTokens = context.sectionTokens ?: emptyMap(),
            totalEstimatedTokens = activeEstimatedTokens,
            activeEstimatedTokens = activeEstimatedTokens,
            auxiliaryEstimatedTokens = auxiliaryEstimatedTokens,
            combinedEstimatedTokens = combinedEstimatedTokens,
            semanticSummary = context.semanticSummary,
            projectInstructions = context.projectInstructions,
            taskRequirementsPrompt = taskRequirementsPrompt,
            recentWorkPrompt = recentWorkPrompt,
            activeLlmRequestPrompt = activeLlmPreviewPrompt,
            auxiliaryPromptsPreview = auxiliaryPreviewPrompt
        )
    }

    private fun extractSectionFromPrompt(prompt: String, sectionTag: String): String? {
        val openTag = "<$sectionTag>"
        val closeTag = "</$sectionTag>"
        val openIndex = findTagAtLineStart(prompt, openTag, 0)
        if (openIndex == -1) return null

        val contentStart = openIndex + openTag.length
        val closeIndex = findTagAtLineStart(prompt, closeTag, contentStart)
        val nextSectionIndex = findNextKnownSectionStart(prompt, contentStart)
        val hasClosingTag = closeIndex != -1 && (nextSectionIndex == null || closeIndex <= nextSectionIndex)
        val contentEnd = when {
            hasClosingTag -> closeIndex
            nextSectionIndex != null -> nextSectionIndex
            else -> prompt.length
        }
        if (contentEnd < contentStart) return null
        return prompt.substring(contentStart, contentEnd).trim()
    }

    private fun findNextKnownSectionStart(prompt: String, fromIndex: Int): Int? {
        val knownSectionTags = listOf(
            "PROJECT_CONTEXT", "CURRENT_TASK", "USER_REQUIREMENTS", "USER_PROVIDED_CONTEXT",
            "WORKING_MEMORY", "MCP_RESOURCES", "RAG_FRAGMENTS", "CONVERSATION_HISTORY",
            "RECENT_WORK", "SUBTASKS_STATUS", "KEY_COMPONENTS", "PROJECT_DEPENDENCIES", "CODE_ANALYSIS"
        )
        var nextIndex: Int? = null
        for (tag in knownSectionTags) {
            val candidate = findTagAtLineStart(prompt, "<$tag>", fromIndex)
            if (candidate != -1 && (nextIndex == null || candidate < nextIndex)) nextIndex = candidate
        }
        return nextIndex
    }

    private fun findTagAtLineStart(prompt: String, tag: String, fromIndex: Int): Int {
        var index = prompt.indexOf(tag, fromIndex.coerceAtLeast(0))
        while (index != -1) {
            val lineStart = prompt.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            if (prompt.substring(lineStart, index).isBlank()) return index
            index = prompt.indexOf(tag, index + 1)
        }
        return -1
    }

    /**
     * Append sections contributed by [promptSectionProviders] to [basePrompt],
     * mirroring what TurnPromptBuilder does at runtime. Returns the original
     * prompt unchanged when the provider list is empty or all providers decline.
     */
    private suspend fun appendProviderSections(
        basePrompt: String,
        mode: TaskMode,
        taskId: String,
        iteration: Int
    ): String {
        if (promptSectionProviders.isEmpty()) return basePrompt
        val buildContext = pl.jclab.refio.core.services.turn.PromptBuildContext(
            taskId = taskId,
            mode = mode,
            iteration = iteration,
            maxIterations = 25,
            writeToolsExecutedInTurn = 0,
            profileOverrides = null
        )
        val extras = buildString {
            for (provider in promptSectionProviders) {
                val section = try {
                    provider.build(buildContext)
                } catch (e: Exception) {
                    logger.debug { "[CONTEXT_PREVIEW] Provider ${provider.javaClass.simpleName} failed: ${e.message}" }
                    null
                }
                if (!section.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(section)
                }
            }
        }
        return if (extras.isBlank()) basePrompt else "$basePrompt\n\n$extras"
    }
}
