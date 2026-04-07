package pl.jclab.refio.core.services.turn

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.subagents.models.SubagentContextProfile
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMMessageMapper
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.services.PromptCache
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("TurnPromptBuilder")

/**
 * Builds prompts for turn-based execution.
 * Responsible for system prompt generation and message history formatting.
 */
class TurnPromptBuilder(
    private val promptsService: PromptsService,
    private val chatMessageRepository: ChatMessageRepository,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val contextService: ContextService?,
    private val workingMemoryService: pl.jclab.refio.core.services.context.WorkingMemoryService?,
    private val projectRoot: Path?,
    private val tokenEstimator: PromptTokenEstimator = PromptTokenEstimator(),
    private val promptCache: PromptCache? = null,
    private val sectionProviders: List<PromptSectionProvider> = emptyList()
) {
    class StructuredPromptBuilder {
        fun buildSystemPrompt(sections: List<PromptSection>): String {
            val stablePart = sections
                .filter { it.stable && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }
            val dynamicPart = sections
                .filter { !it.stable && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }

            return listOf(stablePart, dynamicPart)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }
    }

    companion object {
        private const val STICKY_REQUIREMENTS_MAX_CHARS = 4_000
        private const val REQUIREMENT_ENTRY_MAX_CHARS = 1_500
    }

    private val structuredPromptBuilder = StructuredPromptBuilder()

    /**
     * Get the last context decision trace from ContextService.
     * Available after buildPrompt() has been called.
     */
    fun getLastContextTrace(): ContextDecisionTrace? {
        return contextService?.lastContextTrace
    }

    /**
     * Build complete prompt for turn iteration.
     * Includes: system prompt + tool descriptions + context + conversation history.
     */
    suspend fun buildPrompt(
        taskId: String,
        mode: TaskMode,
        currentIteration: Int,
        maxIterations: Int,
        userContextRefs: List<ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        writeToolsExecutedInTurn: Int = 0
    ): TurnPrompt {
        // Build system prompt based on mode/profile
        val baseSystemPrompt = resolveSystemPrompt(
            mode = mode,
            taskId = taskId,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            writeToolsExecutedInTurn = writeToolsExecutedInTurn
        )

        // Resolve context profile for subagents
        val contextProfile = if (runProfile == TurnRunProfile.SUBAGENT) {
            profileOverrides?.contextProfile
        } else null

        val history = chatMessageRepository.findByTaskId(taskId)
        val stickyRequirements = buildStickyRequirementsBlock(history)
        val promptSections = mutableListOf(
            PromptSection("base_system_prompt", baseSystemPrompt, stable = true)
        )
        if (stickyRequirements.isNotBlank()) {
            promptSections += PromptSection(
                id = "task_requirements",
                content = """
<task_requirements>
$stickyRequirements
</task_requirements>
                """.trimIndent(),
                stable = false
            )
        }

        // Append sections from providers
        if (sectionProviders.isNotEmpty()) {
            val buildContext = PromptBuildContext(
                taskId = taskId,
                mode = mode,
                iteration = currentIteration,
                maxIterations = maxIterations,
                writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                profileOverrides = profileOverrides
            )
            for (provider in sectionProviders) {
                val section = provider.build(buildContext)
                if (!section.isNullOrBlank()) {
                    promptSections += PromptSection(
                        id = "provider_${provider.javaClass.simpleName}",
                        content = section,
                        stable = false
                    )
                }
            }
        }

        var systemPrompt = structuredPromptBuilder.buildSystemPrompt(promptSections)

        // Use ContextService for messages and project context (for PLAN and AGENT modes)
        if ((mode == TaskMode.PLAN || mode == TaskMode.AGENT) && contextService != null && projectRoot != null) {
            try {
                val lastUserMessage = history.lastOrNull { it.role == MessageRole.USER }?.content ?: ""

                val allContextRefs = contextService.collectAllUserContextRefs(taskId) + userContextRefs

                val turnResult = contextService.buildAgentTurnMessages(
                    taskId = taskId,
                    projectRoot = projectRoot,
                    project = null,
                    userContextRefs = allContextRefs,
                    query = lastUserMessage
                )

                // Apply context profile filtering for subagents
                var filteredContextPrompt = turnResult.projectContextPrompt
                var filteredMessages = turnResult.messages

                if (contextProfile != null) {
                    filteredContextPrompt = applyContextProfileToProjectContext(filteredContextPrompt, contextProfile)
                    if (!contextProfile.includeConversation) {
                        filteredMessages = filteredMessages.filter { it.role == "system" || it == filteredMessages.lastOrNull() }
                    }
                }

                // Add parent working memory summary if requested
                if (contextProfile?.includeParentSummary == true && workingMemoryService != null) {
                    val parentSummary = workingMemoryService.buildWorkingMemorySection(taskId, contextProfile.maxContextTokens ?: 2048)
                    if (parentSummary.isNotBlank()) {
                        systemPrompt = structuredPromptBuilder.buildSystemPrompt(
                            listOf(
                                PromptSection("existing", systemPrompt, stable = true),
                                PromptSection(
                                    "parent_working_memory",
                                    """
<parent_working_memory>
$parentSummary
</parent_working_memory>
                                    """.trimIndent(),
                                    stable = false
                                )
                            )
                        )
                    }
                }

                // Append project context to system prompt
                if (filteredContextPrompt.isNotBlank()) {
                    systemPrompt = structuredPromptBuilder.buildSystemPrompt(
                        listOf(
                            PromptSection("existing", systemPrompt, stable = true),
                            PromptSection(
                                "project_context",
                                """
<context>
$filteredContextPrompt
</context>
                                """.trimIndent(),
                                stable = false
                            )
                        )
                    )
                }

                // Apply maxContextTokens limit if set
                if (contextProfile?.maxContextTokens != null) {
                    val totalTokens = tokenEstimator.estimateString(systemPrompt) +
                        filteredMessages.sumOf { tokenEstimator.estimateString(it.content) }
                    if (totalTokens > contextProfile.maxContextTokens) {
                        val systemTokens = tokenEstimator.estimateString(systemPrompt)
                        val remainingBudget = contextProfile.maxContextTokens - systemTokens
                        if (remainingBudget > 0) {
                            filteredMessages = truncateMessagesToTokenBudget(filteredMessages, remainingBudget)
                        } else {
                            // Truncate system prompt to fit within budget
                            val maxChars = (contextProfile.maxContextTokens * 3.5).toInt()
                            systemPrompt = systemPrompt.take(maxChars)
                            filteredMessages = emptyList()
                        }
                    }
                }

                logger.info { "[BUILD_PROMPT] Using ContextService: ${filteredMessages.size} messages, context=${filteredContextPrompt.length} chars" +
                    if (contextProfile != null) ", contextProfile applied" else "" }

                return TurnPrompt(
                    systemPrompt = systemPrompt,
                    messages = filteredMessages
                )
            } catch (e: Exception) {
                logger.warn(e) { "[BUILD_PROMPT] Failed to use ContextService, falling back to direct: ${e.message}" }
            }
        }

        // Fallback: Direct message building for CHAT mode or when ContextService unavailable
        val fallbackToolNames = history
            .asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { it.toolCalls?.asSequence() ?: emptySequence() }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .associate { it.id to it.name }

        val messages = history.mapNotNull { msg ->
            when (msg.role) {
                MessageRole.USER -> LLMMessage(
                    role = "user",
                    content = msg.content
                )
                MessageRole.ASSISTANT -> {
                    val toolCallsText = if (!msg.toolCalls.isNullOrEmpty()) {
                        msg.toolCalls.joinToString("\n") { tc ->
                            "TOOL_CALL: ${tc.name}\nARGUMENTS: ${tc.arguments}"
                        }
                    } else null

                    val content = buildList {
                        if (msg.content.isNotBlank()) add(msg.content)
                        if (toolCallsText != null) add("\n\nTool calls:\n$toolCallsText")
                    }.joinToString("")

                    if (content.isNotBlank()) {
                        LLMMessage(role = "assistant", content = content)
                    } else null
                }
                MessageRole.TOOL -> {
                    val toolText = if (msg.isSummarized && msg.content.isNotBlank()) {
                        msg.content
                    } else {
                        val base = msg.content.ifBlank { msg.rawOutput ?: "(empty tool result)" }
                        if (base.length > 320) "${base.take(320)}..." else base
                    }
                    val toolName = msg.toolCallId?.let { fallbackToolNames[it] }
                    LLMMessageMapper.fromToolResult(msg, toolText, toolName)
                }
                MessageRole.SYSTEM -> LLMMessage(
                    role = "system",
                    content = msg.content
                )
            }
        }

        return TurnPrompt(
            systemPrompt = systemPrompt,
            messages = messages
        )
    }

    private fun buildStickyRequirementsBlock(history: List<ChatMessage>): String {
        val userMessages = history
            .filter { it.role == MessageRole.USER }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }

        if (userMessages.isEmpty()) return ""

        val entries = linkedSetOf<String>()

        userMessages.firstOrNull()?.let {
            entries += formatRequirementEntry("Original user request", it)
        }

        userMessages.lastOrNull()
            ?.takeIf { it != userMessages.firstOrNull() }
            ?.let { entries += formatRequirementEntry("Latest user clarification", it) }

        userMessages
            .drop(1)
            .dropLast(if (userMessages.size > 1) 1 else 0)
            .takeLast(2)
            .forEachIndexed { index, message ->
                entries += formatRequirementEntry("Additional user constraint ${index + 1}", message)
            }

        return entries.joinToString("\n\n").take(STICKY_REQUIREMENTS_MAX_CHARS)
    }

    private fun formatRequirementEntry(label: String, content: String): String {
        val normalized = content.replace(Regex("\\s+"), " ").trim()
        val clipped = if (normalized.length > REQUIREMENT_ENTRY_MAX_CHARS) {
            normalized.take(REQUIREMENT_ENTRY_MAX_CHARS) + "..."
        } else {
            normalized
        }
        return "$label:\n$clipped"
    }

    /**
     * System prompt for CHAT mode (no tools).
     */
    fun buildChatSystemPrompt(): String {
        return promptsService.getSystemPrompt(PromptType.SYSTEM_CHAT)
    }

    /**
     * System prompt for PLAN mode (read-only tools).
     */
    fun buildPlanSystemPrompt(
        mode: TaskMode,
        taskId: String,
        toolDescriptionsOverride: String? = null
    ): String {
        if (toolDescriptionsOverride != null) {
            return promptsService.getSystemPrompt(
                type = PromptType.SYSTEM_PLAN,
                variables = mapOf("tool_descriptions" to toolDescriptionsOverride)
            )
        }

        // Try to use cached static prefix
        if (promptCache != null) {
            val cached = promptCache.getOrBuildStaticPrefix(mode, taskId, tokenEstimator)
            if (cached.toolDescriptions.isNotBlank()) {
                logger.info { "[PLAN_PROMPT] Using ${if (cached.fromCache) "CACHED" else "NEW"} prompt (~${cached.tokenEstimate} tokens)" }
                return promptsService.getSystemPrompt(
                    type = PromptType.SYSTEM_PLAN,
                    variables = mapOf("tool_descriptions" to cached.toolDescriptions)
                )
            }
        }

        // Fallback: build directly
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(mode, taskId)

        logger.info { "[PLAN_PROMPT] Tool descriptions length=${toolDescriptions.length}" }
        if (toolDescriptions.isBlank()) {
            logger.error { "[PLAN_PROMPT] Tool descriptions are EMPTY! This will cause LLM to return error." }
        }

        return promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_PLAN,
            variables = mapOf("tool_descriptions" to toolDescriptions)
        )
    }

    /**
     * System prompt for AGENT mode (all tools).
     */
    fun buildAgentSystemPrompt(
        mode: TaskMode,
        taskId: String,
        currentIteration: Int = 0,
        maxIterations: Int,
        toolDescriptionsOverride: String? = null,
        writeToolsExecutedInTurn: Int = 0
    ): String {
        val toolDescriptions = if (toolDescriptionsOverride != null) {
            toolDescriptionsOverride
        } else if (promptCache != null) {
            val cached = promptCache.getOrBuildStaticPrefix(mode, taskId, tokenEstimator)
            if (cached.toolDescriptions.isNotBlank()) {
                logger.info { "[AGENT_PROMPT] Using ${if (cached.fromCache) "CACHED" else "NEW"} tools (~${cached.tokenEstimate} tokens)" }
                cached.toolDescriptions
            } else {
                toolDescriptionBuilder.getToolDescriptions(mode, taskId)
            }
        } else {
            toolDescriptionBuilder.getToolDescriptions(mode, taskId)
        }

        val iterationInfo = buildIterationInfo(currentIteration, maxIterations, writeToolsExecutedInTurn)

        val basePrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_AGENT,
            variables = mapOf("tool_descriptions" to toolDescriptions)
        )

        return if (iterationInfo.isNotEmpty()) {
            """
$basePrompt

$iterationInfo
            """.trimIndent()
        } else {
            basePrompt
        }
    }

    /**
     * System prompt for subagent (limited tools).
     */
    fun buildSubagentSystemPrompt(
        overrides: TurnProfileOverrides,
        mode: TaskMode,
        taskId: String,
        currentIteration: Int,
        maxIterations: Int,
        toolDescriptions: String,
        writeToolsExecutedInTurn: Int = 0
    ): String {
        val basePrompt = overrides.systemPromptOverride
            ?: buildAgentSystemPrompt(mode, taskId, currentIteration, maxIterations, toolDescriptions, writeToolsExecutedInTurn)
        val iterationInfo = buildIterationInfo(currentIteration, maxIterations, writeToolsExecutedInTurn)

        return buildString {
            appendLine(basePrompt)
            if (toolDescriptions.isNotBlank()) {
                appendLine()
                appendLine("<available_tools>")
                appendLine(toolDescriptions)
                appendLine("</available_tools>")
            }
            appendLine()
            appendLine("<tool_call_contract>")
            appendLine("When using tools, respond ONLY with JSON object:")
            appendLine("""{"actions":[{"tool":"exact_tool_name","arguments":{"param":"value"}}],"response":"","intent":"implementation|analysis|response"}""")
            appendLine("Use only tool names from <available_tools> and exact parameter names from schemas.")
            appendLine("""Optional field: "thinking":"short reasoning" when it adds value.""")
            appendLine("""Never wrap calls in helper tools like {"tool":"run", ...}.""")
            appendLine("""Never nest tool payloads like {"tool":"some_tool","arguments":{"tool":"other","arguments":{...}}}.""")
            appendLine("""If no tools are needed, respond with {"actions":[],"response":"your answer","intent":"response"}.""")
            appendLine("</tool_call_contract>")
            if (iterationInfo.isNotBlank()) {
                appendLine()
                appendLine(iterationInfo)
            }
        }.trim()
    }

    /**
     * Resolve system prompt based on mode and profile.
     */
    fun resolveSystemPrompt(
        mode: TaskMode,
        taskId: String,
        currentIteration: Int,
        maxIterations: Int,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        writeToolsExecutedInTurn: Int = 0
    ): String {
        val toolDescriptionsOverride = resolveToolDescriptionsForProfile(mode, taskId, profileOverrides)

        if (runProfile == TurnRunProfile.SUBAGENT && profileOverrides?.systemPromptOverride != null) {
            return buildSubagentSystemPrompt(
                overrides = profileOverrides,
                mode = mode,
                taskId = taskId,
                currentIteration = currentIteration,
                maxIterations = maxIterations,
                toolDescriptions = toolDescriptionsOverride.orEmpty(),
                writeToolsExecutedInTurn = writeToolsExecutedInTurn
            )
        }

        return when (mode) {
            TaskMode.CHAT -> buildChatSystemPrompt()
            TaskMode.PLAN -> buildPlanSystemPrompt(mode, taskId, toolDescriptionsOverride)
            TaskMode.AGENT -> buildAgentSystemPrompt(
                mode = mode,
                taskId = taskId,
                currentIteration = currentIteration,
                maxIterations = maxIterations,
                toolDescriptionsOverride = toolDescriptionsOverride,
                writeToolsExecutedInTurn = writeToolsExecutedInTurn
            )
        }
    }

    /**
     * Build iteration info with warnings about remaining loop budget.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildIterationInfo(current: Int, max: Int, _writeToolsExecutedInTurn: Int = 0): String {
        val remaining = max - current

        val warning = when {
            remaining <= 3 -> "⚠️ CRITICAL: Only $remaining iterations left! Prioritize essential actions and prepare to conclude."
            remaining <= 7 -> "⚠️ WARNING: $remaining iterations remaining. Plan efficiently and focus on core objectives."
            remaining <= 12 -> "Note: $remaining iterations remaining. Consider pacing your tool usage."
            else -> ""
        }

        return if (warning.isNotEmpty()) {
            """
<iteration_status>
Current iteration: $current / $max
${warning}
</iteration_status>
            """.trimIndent()
        } else {
            ""
        }
    }

    /**
     * Get conversation history size for metrics.
     */
    fun getHistorySize(taskId: String): Int {
        return try {
            chatMessageRepository.findByTaskId(taskId).size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Resolve tool descriptions for profile override.
     */
    fun resolveToolDescriptionsForProfile(
        mode: TaskMode,
        taskId: String,
        profileOverrides: TurnProfileOverrides?
    ): String? {
        if (profileOverrides == null) {
            return null
        }
        val filteredTools = resolveToolsForProfile(mode, taskId, profileOverrides)
        return toolDescriptionBuilder.getToolDescriptionsForTools(mode, filteredTools)
    }

    /**
     * Resolve tools available for profile.
     */
    fun resolveToolsForProfile(
        mode: TaskMode,
        taskId: String,
        profileOverrides: TurnProfileOverrides
    ): List<pl.jclab.refio.core.tools.base.Tool> {
        val baseTools = toolDescriptionBuilder.getToolsForMode(mode, taskId)
        return baseTools.filter { isToolAllowedByProfile(it.name, profileOverrides) }
    }

    private fun isToolAllowedByProfile(toolName: String, profileOverrides: TurnProfileOverrides): Boolean {
        val normalizedName = toolName.lowercase()
        val allowed = profileOverrides.allowedTools?.map { it.lowercase() }?.toSet()
        val disallowed = profileOverrides.disallowedTools?.map { it.lowercase() }?.toSet()

        if (allowed != null) {
            return normalizedName in allowed
        }
        if (disallowed != null) {
            return normalizedName !in disallowed
        }
        return true
    }

    /**
     * Applies context profile filtering to project context prompt.
     * Removes sections based on profile flags.
     */
    private fun applyContextProfileToProjectContext(
        contextPrompt: String,
        profile: SubagentContextProfile
    ): String {
        var result = contextPrompt

        if (!profile.includeFileTree) {
            // Remove file tree sections
            result = result.replace(Regex("<file_tree>.*?</file_tree>", RegexOption.DOT_MATCHES_ALL), "")
            result = result.replace(Regex("<project_structure>.*?</project_structure>", RegexOption.DOT_MATCHES_ALL), "")
        }

        if (!profile.includeRag) {
            // Remove RAG fragment sections
            result = result.replace(Regex("<rag_fragments>.*?</rag_fragments>", RegexOption.DOT_MATCHES_ALL), "")
            result = result.replace(Regex("<rag_context>.*?</rag_context>", RegexOption.DOT_MATCHES_ALL), "")
        }

        if (!profile.includeDependencies) {
            // Remove dependency sections
            result = result.replace(Regex("<dependencies>.*?</dependencies>", RegexOption.DOT_MATCHES_ALL), "")
            result = result.replace(Regex("<dependency_analysis>.*?</dependency_analysis>", RegexOption.DOT_MATCHES_ALL), "")
        }

        if (!profile.includeWorkingMemory) {
            // Remove working memory sections
            result = result.replace(Regex("<working_memory>.*?</working_memory>", RegexOption.DOT_MATCHES_ALL), "")
        }

        // Clean up excessive blank lines
        result = result.replace(Regex("\n{3,}"), "\n\n")

        return result.trim()
    }

    /**
     * Truncates messages to fit within a token budget, keeping the most recent messages.
     */
    private fun truncateMessagesToTokenBudget(
        messages: List<LLMMessage>,
        tokenBudget: Int
    ): List<LLMMessage> {
        if (messages.isEmpty()) return messages

        val result = mutableListOf<LLMMessage>()
        var usedTokens = 0

        // Work backwards from most recent messages
        for (msg in messages.reversed()) {
            val msgTokens = tokenEstimator.estimateString(msg.content)
            if (usedTokens + msgTokens <= tokenBudget) {
                result.add(0, msg)
                usedTokens += msgTokens
            } else {
                break
            }
        }

        return result
    }
}
