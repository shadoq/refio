package pl.jclab.refio.core.services.turn

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
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
import pl.jclab.refio.core.services.context.DiffCompressor
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
    private val sectionProviders: List<PromptSectionProvider> = emptyList(),
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val agentInboxRegistry: AgentInboxRegistry? = null
) {
    class StructuredPromptBuilder {
        /**
         * Render the prompt with stable sections first, then non-stable.
         * Single-string output for callers that don't need the cache boundary.
         */
        fun buildSystemPrompt(sections: List<PromptSection>): String {
            return render(sections).text
        }

        /**
         * Render the prompt and report the byte length of the stable prefix.
         *
         * Output shape: `<stable>\n\n<dynamic>` (or just `<stable>` / `<dynamic>`
         * if one side is empty). [Rendered.stablePrefixLength] is the offset where
         * the dynamic content begins — equivalently, the length of the cacheable
         * prefix. When everything is dynamic, returns 0 (no prefix to cache).
         * When everything is stable, returns the full text length.
         *
         * Used by [TurnPrompt.cacheableSystemLength] / [AnthropicAdapter] to wire
         * Anthropic `cache_control` markers.
         */
        fun render(sections: List<PromptSection>): Rendered {
            val stablePart = sections
                .filter { it.stable && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }
            val dynamicPart = sections
                .filter { !it.stable && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }

            return when {
                stablePart.isBlank() && dynamicPart.isBlank() -> Rendered("", 0)
                stablePart.isBlank() -> Rendered(dynamicPart, 0)
                dynamicPart.isBlank() -> Rendered(stablePart, stablePart.length)
                else -> Rendered("$stablePart\n\n$dynamicPart", stablePart.length)
            }
        }

        data class Rendered(val text: String, val stablePrefixLength: Int)
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
     * Get the last granular section token breakdown from ContextService.
     * Available after buildPrompt() has been called.
     * Keys match ContextSectionColorPalette (e.g. "recent_work", "key_components").
     */
    fun getLastSectionTokens(): Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>? {
        return contextService?.lastSectionTokens
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
        writeToolsExecutedInTurn: Int = 0,
        nativeToolsActive: Boolean = false,
        /** Stable A2A agent name (multi-agent). When set together with [sessionId], pending incoming requests are injected. */
        agentName: String? = null,
        /** Multi-agent session id. Used to look up the inbox in [AgentInboxRegistry]. */
        sessionId: String? = null,
    ): TurnPrompt {
        // Build system prompt based on mode/profile
        val baseSystemPrompt = resolveSystemPrompt(
            mode = mode,
            taskId = taskId,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            writeToolsExecutedInTurn = writeToolsExecutedInTurn,
            nativeToolsActive = nativeToolsActive
        )

        // Resolve context profile for subagents
        val contextProfile = if (runProfile == TurnRunProfile.SUBAGENT) {
            profileOverrides?.contextProfile
        } else null

        // Isolate subagent history: each subagent invocation tags its rows with its own
        // agentInstanceId (see SubagentRouter). Pass null for the parent run so it does
        // not see subagent intermediate steps either.
        val history = chatMessageRepository.findHistoryForInvocation(
            taskId,
            profileOverrides?.agentInstanceId
        )
        val stickyRequirements = buildStickyRequirementsBlock(history)
        val promptSections = mutableListOf(
            PromptSection("base_system_prompt", baseSystemPrompt, stable = true)
        )
        // Iteration warning (only emits when remaining <= 12) — marked stable=false so
        // it lands AFTER the stable prefix. The cacheable prefix (identity + tools +
        // family guidance) stays byte-stable as `iteration` increments, instead of
        // invalidating the prefix-cache the moment the warning kicks in.
        val iterationInfo = buildIterationInfo(currentIteration, maxIterations, writeToolsExecutedInTurn)
        if (iterationInfo.isNotBlank()) {
            promptSections += PromptSection(
                id = "iteration_status",
                content = iterationInfo,
                stable = false
            )
        }
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

        // Render once and capture the stable-prefix boundary. Subsequent appends
        // (working memory, project context, contextProfile truncation) only add
        // non-stable content after the boundary, so this captured length stays
        // accurate for the final prompt — it is the value passed to providers
        // that support prompt-prefix caching (currently the Anthropic adapter
        // via cache_control markers).
        val initialRender = structuredPromptBuilder.render(promptSections)
        var systemPrompt = initialRender.text
        var cacheableSystemLen = initialRender.stablePrefixLength

        // Static prefix tokens = everything we've assembled so far (system markdown + tool
        // descriptions + response contract + provider sections). Passed to ContextService so
        // the dynamic section budget (RECENT_WORK, CONVERSATION, ...) is sized AGAINST the
        // remaining window after the prefix, not against the full window. Before this, the
        // budget pretended the system prompt didn't exist, so an 18k prefix + 13k of sections
        // happily blew past a 16k Ollama window (see [CONTEXT_OVERFLOW] in OllamaAdapter).
        val staticPrefixTokens = pl.jclab.refio.core.services.PromptTokenEstimator.estimateBase(systemPrompt)

        // Use ContextService for messages and project context (for PLAN and AGENT modes)
        if ((mode == TaskMode.PLAN || mode == TaskMode.AGENT) && contextService != null && projectRoot != null) {
            try {
                val lastUserMessage = history.lastOrNull { it.role == MessageRole.USER }?.content ?: ""

                val allContextRefs = contextService.collectAllUserContextRefs(taskId) + userContextRefs

                val turnResult = contextService.buildAgentTurnMessages(
                    taskId = taskId,
                    projectRoot = projectRoot,
                    userContextRefs = allContextRefs,
                    query = lastUserMessage,
                    staticPrefixTokens = staticPrefixTokens,
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

                // Clamp cacheableSystemLen — if truncation cut into the stable
                // prefix, the boundary now points past the end of the string and
                // would be rejected by the Anthropic adapter.
                cacheableSystemLen = cacheableSystemLen.coerceAtMost(systemPrompt.length)

                logger.info { "[BUILD_PROMPT] Using ContextService: ${filteredMessages.size} messages, context=${filteredContextPrompt.length} chars" +
                    if (contextProfile != null) ", contextProfile applied" else "" }

                return TurnPrompt(
                    systemPrompt = systemPrompt,
                    messages = appendInboxMessage(filteredMessages, sessionId, agentName),
                    cacheableSystemLength = cacheableSystemLen.takeIf { it > 0 }
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
                        // Error results from TurnToolExecutor carry tool-provided
                        // `Recovery:` + `Next steps:` blocks that the agent MUST see to correct course.
                        when {
                            base.startsWith("Error:") -> base
                            base.length > 512 -> "${base.take(512)}..."
                            else -> base
                        }
                    }
                    // Compress fenced ```diff bodies the same way the ContextService path
                    // does — keeps this fallback in sync so a 600-line +diff doesn't ride
                    // through verbatim when ContextService is unavailable (CHAT-mode-style
                    // direct build, or unit-test setups without the full stack).
                    val compressed = DiffCompressor.compress(toolText, msg.subtaskId)
                    val toolName = msg.toolCallId?.let { fallbackToolNames[it] }
                    LLMMessageMapper.fromToolResult(msg, compressed, toolName)
                }
                MessageRole.SYSTEM -> LLMMessage(
                    role = "system",
                    content = msg.content
                )
            }
        }

        return TurnPrompt(
            systemPrompt = systemPrompt,
            messages = appendInboxMessage(messages, sessionId, agentName),
            cacheableSystemLength = cacheableSystemLen.takeIf { it > 0 }
        )
    }

    /**
     * Multi-agent A2A: if this agent has an inbox in [AgentInboxRegistry] with pending
     * requests from peers, append a system message describing them and instructing the
     * model to reply via `answer_message`. Idempotent — once the agent replies, the
     * inbox drops the request (see [pl.jclab.refio.core.agents.events.AgentMessageInbox]).
     */
    private fun appendInboxMessage(
        messages: List<LLMMessage>,
        sessionId: String?,
        agentName: String?
    ): List<LLMMessage> {
        if (sessionId == null || agentName == null) return messages
        val registry = agentInboxRegistry ?: return messages
        val inbox = registry.find(sessionId, agentName) ?: return messages
        val pending = inbox.snapshotPending()
        if (pending.isEmpty()) return messages
        val content = buildString {
            appendLine("You have pending incoming messages from other agents in this session.")
            appendLine("Reply using the `answer_message` tool with the matching requestId.")
            appendLine()
            pending.forEach { req ->
                val type = req.context["type"] ?: "message"
                appendLine("- from=${req.sourceAgentId}  type=$type  requestId=${req.id}")
                appendLine("  query: ${req.query}")
            }
        }
        return messages + LLMMessage(role = "system", content = content)
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
        toolDescriptionsOverride: String? = null,
        nativeToolsActive: Boolean = false,
        profileOverrides: TurnProfileOverrides? = null
    ): String {
        // Auto-detect compact mode based on model context size
        syncCompactMode(mode, taskId)

        val responseContract = resolveResponseContract(nativeToolsActive)
        val multiAgentSection = resolveMultiAgentSection(mode, taskId, profileOverrides)

        if (toolDescriptionsOverride != null) {
            return promptsService.getSystemPrompt(
                type = PromptType.SYSTEM_PLAN,
                variables = mapOf(
                    "tool_descriptions" to toolDescriptionsOverride,
                    "response_contract" to responseContract,
                    "multi_agent_section" to multiAgentSection
                )
            )
        }

        // Try to use cached static prefix
        if (promptCache != null) {
            val cached = promptCache.getOrBuildStaticPrefix(mode, taskId, tokenEstimator)
            if (cached.toolDescriptions.isNotBlank()) {
                logger.info { "[PLAN_PROMPT] Using ${if (cached.fromCache) "CACHED" else "NEW"} prompt (~${cached.tokenEstimate} tokens)" }
                return promptsService.getSystemPrompt(
                    type = PromptType.SYSTEM_PLAN,
                    variables = mapOf(
                        "tool_descriptions" to cached.toolDescriptions,
                        "response_contract" to responseContract,
                        "multi_agent_section" to multiAgentSection
                    )
                )
            }
        }

        // Fallback: build directly
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(mode, taskId)

        logger.info { "[PLAN_PROMPT] Tool descriptions length=${toolDescriptions.length}" }
        if (toolDescriptions.isBlank()) {
            logger.error { "[PLAN_PROMPT] Tool descriptions are EMPTY! This will cause LLM to return error." }
        }

        // Filter the When-to-use matrix by profile too — see buildAgentSystemPrompt.
        val toolSelectionMatrix = if (profileOverrides != null) {
            val filteredTools = resolveToolsForProfile(mode, taskId, profileOverrides)
            toolDescriptionBuilder.buildSelectionMatrix(filteredTools)
        } else {
            toolDescriptionBuilder.getToolSelectionMatrix(mode, taskId)
        }

        return promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_PLAN,
            variables = mapOf(
                "tool_descriptions" to toolDescriptions,
                "tool_selection_matrix" to toolSelectionMatrix,
                "response_contract" to responseContract,
                "multi_agent_section" to multiAgentSection
            )
        )
    }

    /**
     * Resolve which response-contract fragment to inject based on active tool-calling mode.
     * Native path gets a terse "use native tool_calls, reply in prose" contract;
     * JSON-in-text path gets the full envelope + examples.
     */
    private fun resolveResponseContract(nativeToolsActive: Boolean): String {
        val name = if (nativeToolsActive) "response-contract-native" else "response-contract-json"
        return promptsService.getFragment(name)
    }

    /**
     * Resolve the <multi_agent> section. Returns the full delegation-guidance block when the
     * caller can actually invoke subagents — empty string otherwise.
     *
     * Rationale: subagents (and profiles without `invoke_subagent`) don't need ~2500 tokens of
     * "when to delegate" rules — they can't delegate in the first place. Same for subagents
     * at depth>=1 where deeper delegation is discouraged.
     *
     * @param mode TaskMode (PLAN vs AGENT — different fragment files with slightly different tone)
     * @param taskId task id for mode-level tool resolution
     * @param profileOverrides subagent profile overrides (null for main run)
     */
    fun resolveMultiAgentSection(
        mode: TaskMode,
        taskId: String,
        profileOverrides: TurnProfileOverrides?
    ): String {
        if (!hasInvokeSubagentAvailable(mode, taskId, profileOverrides)) {
            return ""
        }
        val fragmentName = when (mode) {
            TaskMode.PLAN -> "multi-agent-plan"
            TaskMode.AGENT -> "multi-agent-agent"
            TaskMode.CHAT -> return ""
        }
        return promptsService.getFragment(fragmentName)
    }

    private fun hasInvokeSubagentAvailable(
        mode: TaskMode,
        taskId: String,
        profileOverrides: TurnProfileOverrides?
    ): Boolean {
        val tools = if (profileOverrides != null) {
            resolveToolsForProfile(mode, taskId, profileOverrides)
        } else {
            toolDescriptionBuilder.getToolsForMode(mode, taskId)
        }
        return tools.any { it.name.equals("invoke_subagent", ignoreCase = true) }
    }

    /**
     * System prompt for AGENT mode (all tools).
     *
     * `currentIteration`, `maxIterations`, `writeToolsExecutedInTurn` are accepted for
     * API symmetry with [buildPlanSystemPrompt] / [buildSubagentSystemPrompt] but
     * intentionally NOT used here — iteration-dependent content moved out of the
     * stable prompt prefix to preserve prompt-cache hit ratios across iterations.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildAgentSystemPrompt(
        mode: TaskMode,
        taskId: String,
        currentIteration: Int = 0,
        maxIterations: Int,
        toolDescriptionsOverride: String? = null,
        writeToolsExecutedInTurn: Int = 0,
        nativeToolsActive: Boolean = false,
        profileOverrides: TurnProfileOverrides? = null
    ): String {
        // Auto-detect compact mode based on model context size
        syncCompactMode(mode, taskId)

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

        // Filter the When-to-use matrix by profile too — otherwise subagents see all
        // ~29 tools in the system prompt even though only 4-6 are actually available
        // (their <available_tools> AND native tool_calls channel are both filtered).
        val toolSelectionMatrix = if (profileOverrides != null) {
            val filteredTools = resolveToolsForProfile(mode, taskId, profileOverrides)
            toolDescriptionBuilder.buildSelectionMatrix(filteredTools)
        } else {
            toolDescriptionBuilder.getToolSelectionMatrix(mode, taskId)
        }

        // Iteration info is intentionally NOT appended here — it changes every iteration
        // once remaining <= 12 (warning kicks in), which would invalidate the prompt-prefix
        // cache. [buildPrompt] injects it as a separate non-stable PromptSection that sits
        // after the stable prefix, so the cacheable portion (identity + rules + tools +
        // multi-agent guidance) stays byte-stable across turn iterations.
        return promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_AGENT,
            variables = mapOf(
                "tool_descriptions" to toolDescriptions,
                "tool_selection_matrix" to toolSelectionMatrix,
                "response_contract" to resolveResponseContract(nativeToolsActive),
                "multi_agent_section" to resolveMultiAgentSection(mode, taskId, profileOverrides)
            )
        )
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
        writeToolsExecutedInTurn: Int = 0,
        nativeToolsActive: Boolean = false
    ): String {
        val basePrompt = overrides.systemPromptOverride
            ?: buildAgentSystemPrompt(
                mode = mode,
                taskId = taskId,
                currentIteration = currentIteration,
                maxIterations = maxIterations,
                toolDescriptionsOverride = toolDescriptions,
                writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                nativeToolsActive = nativeToolsActive,
                profileOverrides = overrides
            )

        // Iteration info NOT appended here — see buildAgentSystemPrompt for why
        // (cache-prefix stability). [buildPrompt] injects it as a non-stable section
        // after the stable subagent prefix. The remaining content (tool_call_contract
        // + available_tools) is byte-stable for the subagent invocation.
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
            if (nativeToolsActive) {
                appendLine("Native function-calling is active for this subagent.")
                appendLine("Invoke tools through the provider's native tool_call / tool_use channel.")
                appendLine("Do not emit a JSON envelope in text content.")
                appendLine("Use only tool names from <available_tools> and exact parameter names from attached schemas.")
                appendLine("When finished, respond with plain text only.")
            } else {
                appendLine("When using tools, respond ONLY with JSON object:")
                appendLine("""{"actions":[{"tool":"exact_tool_name","arguments":{"param":"value"}}],"response":"","intent":"implementation|analysis|response"}""")
                appendLine("Use only tool names from <available_tools> and exact parameter names from schemas.")
                appendLine("""Optional field: "thinking":"short reasoning" when it adds value.""")
                appendLine("""Never wrap calls in helper tools like {"tool":"run", ...}.""")
                appendLine("""Never nest tool payloads like {"tool":"some_tool","arguments":{"tool":"other","arguments":{...}}}.""")
                appendLine("""If no tools are needed, respond with {"actions":[],"response":"your answer","intent":"response"}.""")
            }
            appendLine("</tool_call_contract>")
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
        writeToolsExecutedInTurn: Int = 0,
        nativeToolsActive: Boolean = false
    ): String {
        // Sync compact mode early — before resolving tool descriptions for any profile.
        // Without this, subagent tool descriptions are built with compactMode=false
        // even on small-context models (e.g. Ollama 32k), wasting ~1.5k tokens.
        syncCompactMode(mode, taskId)

        // Native function-calling: reuse the .md templates but substitute {{tool_descriptions}}
        // with a short note (schemas are attached via the API `tools` parameter) and select the
        // native response-contract fragment. All other guidance stays intact.
        if (nativeToolsActive && runProfile != TurnRunProfile.SUBAGENT) {
            val nativeNote = nativeToolsDescriptionOverride()
            return when (mode) {
                TaskMode.PLAN -> buildPlanSystemPrompt(
                    mode = mode,
                    taskId = taskId,
                    toolDescriptionsOverride = nativeNote,
                    nativeToolsActive = true,
                    profileOverrides = profileOverrides
                )
                TaskMode.AGENT -> buildAgentSystemPrompt(
                    mode = mode,
                    taskId = taskId,
                    currentIteration = currentIteration,
                    maxIterations = maxIterations,
                    toolDescriptionsOverride = nativeNote,
                    writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                    nativeToolsActive = true,
                    profileOverrides = profileOverrides
                )
                TaskMode.CHAT -> buildChatSystemPrompt()
            }
        }

        val toolDescriptionsOverride = if (nativeToolsActive) {
            // Subagent path with native tools: still goes through buildSubagentSystemPrompt, which
            // produces a <tool_call_contract> block — replace the tool list with a short note.
            "Tools are available via the native function-calling API. " +
                "Call tools using the standard tool_use mechanism. " +
                "When you have completed the task, respond with plain text — no JSON envelope."
        } else {
            resolveToolDescriptionsForProfile(mode, taskId, profileOverrides)
        }

        if (runProfile == TurnRunProfile.SUBAGENT && profileOverrides?.systemPromptOverride != null) {
            return buildSubagentSystemPrompt(
                overrides = profileOverrides,
                mode = mode,
                taskId = taskId,
                currentIteration = currentIteration,
                maxIterations = maxIterations,
                toolDescriptions = toolDescriptionsOverride.orEmpty(),
                writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                nativeToolsActive = nativeToolsActive
            )
        }

        return when (mode) {
            TaskMode.CHAT -> buildChatSystemPrompt()
            TaskMode.PLAN -> buildPlanSystemPrompt(
                mode = mode,
                taskId = taskId,
                toolDescriptionsOverride = toolDescriptionsOverride,
                nativeToolsActive = false,
                profileOverrides = profileOverrides
            )
            TaskMode.AGENT -> buildAgentSystemPrompt(
                mode = mode,
                taskId = taskId,
                currentIteration = currentIteration,
                maxIterations = maxIterations,
                toolDescriptionsOverride = toolDescriptionsOverride,
                writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                nativeToolsActive = false,
                profileOverrides = profileOverrides
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

    /**
     * Filter native tool schemas by subagent profile (allowedTools / disallowedTools).
     *
     * Without this, the model receives all mode-permitted schemas via the native
     * function-calling API and will happily emit tool_calls the harness then rejects
     * (e.g. api-documenter calling `think` when the definition lists only file ops).
     * The filtered list must match what the subagent's system prompt says is available.
     */
    fun filterNativeToolSchemasByProfile(
        schemas: List<pl.jclab.refio.core.tools.base.ToolSchema>,
        profileOverrides: TurnProfileOverrides?
    ): List<pl.jclab.refio.core.tools.base.ToolSchema> {
        if (profileOverrides == null) return schemas
        if (profileOverrides.allowedTools.isNullOrEmpty() && profileOverrides.disallowedTools.isNullOrEmpty()) {
            return schemas
        }
        return schemas.filter { isToolAllowedByProfile(it.name, profileOverrides) }
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
     * Sync compact mode on ToolDescriptionBuilder based on resolved context size.
     * When context is small (e.g. Ollama 32k), uses shorter tool descriptions to save tokens.
     */
    private fun syncCompactMode(mode: TaskMode, taskId: String) {
        if (configService == null) return
        val operation = pl.jclab.refio.core.api.ModelOperation.fromTaskMode(mode)
        toolDescriptionBuilder.compactMode = configService.isCompactPrompts(operation, taskId)
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
