package pl.jclab.refio.core.services.turn

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.TokenEstimator
import pl.jclab.refio.core.services.PromptCache
import pl.jclab.refio.services.logging.dualLogger
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
    private val tokenEstimator: TokenEstimator = TokenEstimator(),
    private val promptCache: PromptCache? = null
) {

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
        var systemPrompt = resolveSystemPrompt(
            mode = mode,
            taskId = taskId,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            writeToolsExecutedInTurn = writeToolsExecutedInTurn
        )

        // Use ContextService for messages and project context (for PLAN and AGENT modes)
        if ((mode == TaskMode.PLAN || mode == TaskMode.AGENT) && contextService != null && projectRoot != null) {
            try {
                val history = chatMessageRepository.findByTaskId(taskId)
                val lastUserMessage = history.lastOrNull { it.role == MessageRole.USER }?.content ?: ""

                val allContextRefs = contextService.collectAllUserContextRefs(taskId) + userContextRefs

                val turnResult = contextService.buildAgentTurnMessages(
                    taskId = taskId,
                    projectRoot = projectRoot,
                    project = null,
                    userContextRefs = allContextRefs,
                    query = lastUserMessage
                )

                // Append project context to system prompt
                if (turnResult.projectContextPrompt.isNotBlank()) {
                    systemPrompt = """
$systemPrompt

<context>
${turnResult.projectContextPrompt}
</context>
                    """.trimIndent()
                }

                logger.info { "[BUILD_PROMPT] Using ContextService: ${turnResult.messages.size} messages, context=${turnResult.projectContextPrompt.length} chars" }

                return TurnPrompt(
                    systemPrompt = systemPrompt,
                    messages = turnResult.messages
                )
            } catch (e: Exception) {
                logger.warn(e) { "[BUILD_PROMPT] Failed to use ContextService, falling back to direct: ${e.message}" }
            }
        }

        // Fallback: Direct message building for CHAT mode or when ContextService unavailable
        val history = chatMessageRepository.findByTaskId(taskId)

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
                    val content = "[Tool Result for ${msg.toolCallId}]\n$toolText"

                    LLMMessage(
                        role = "user",
                        content = content
                    )
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
            appendLine("""{"actions":[{"tool":"exact_tool_name","arguments":{"param":"value"}}],"response":"","thinking":"","intent":"implementation|analysis"}""")
            appendLine("Use only tool names from <available_tools> and exact parameter names from schemas.")
            appendLine("""Never wrap calls in helper tools like {"tool":"run", ...}.""")
            appendLine("""Never nest tool payloads like {"tool":"some_tool","arguments":{"tool":"other","arguments":{...}}}.""")
            appendLine("""If no tools are needed, respond with {"actions":[],"response":"final answer","thinking":"short reasoning","intent":"analysis"}.""")
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
     * Build iteration info "Iteration 3/25" with warnings.
     * Shows early write nudge when iteration >= 3 and no write tools executed yet (ADR-0044).
     */
    fun buildIterationInfo(current: Int, max: Int, writeToolsExecutedInTurn: Int = 0): String {
        val remaining = max - current

        val writeNudge = if (current >= 3 && writeToolsExecutedInTurn == 0) {
            "\n⚠️ You have not executed any WRITE tools yet. If this is an implementation task, proceed to writing NOW."
        } else ""

        val warning = when {
            remaining <= 3 -> "⚠️ CRITICAL: Only $remaining iterations left! Prioritize essential actions and prepare to conclude."
            remaining <= 7 -> "⚠️ WARNING: $remaining iterations remaining. Plan efficiently and focus on core objectives."
            remaining <= 12 -> "Note: $remaining iterations remaining. Consider pacing your tool usage."
            else -> ""
        }

        return if (warning.isNotEmpty() || writeNudge.isNotEmpty()) {
            """
<iteration_status>
Current iteration: $current / $max
${warning}${writeNudge}
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
}
