package pl.jclab.refio.core.subagents

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.context.ContextItem
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.ToolCall
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentExecutionMode
import pl.jclab.refio.core.subagents.models.SubagentResult
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("SubagentExecutor")

/**
 * Executor subagentów.
 *
 * Odpowiedzialności:
 * - Budowanie izolowanego kontekstu dla subagenta
 * - Filtrowanie narzędzi wg definicji
 * - Wywołanie LLM z custom system prompt
 * - Obsługa tool execution loop
 * - Zbieranie statystyk (tokeny, czas, narzędzia)
 */
@Deprecated(
    message = "Use AgentTurnLoop with TurnRunProfile.SUBAGENT instead. See ADR-0029.",
    replaceWith = ReplaceWith("AgentTurnLoop.runTurn(..., runProfile = TurnRunProfile.SUBAGENT)")
)
class SubagentExecutor(
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry,
    private val toolFilter: SubagentToolFilter,
    private val configService: ConfigService,
    private val chatMessageRepository: ChatMessageRepository? = null,
    private val contextService: pl.jclab.refio.core.services.ContextService? = null,
    private val projectRoot: java.nio.file.Path? = null,
    private val ideProject: com.intellij.openapi.project.Project? = null
) {
    private val gson = Gson()

    companion object {
        /**
         * Maksymalna liczba iteracji tool execution loop.
         */
        const val MAX_TOOL_ITERATIONS = 10
    }

    /**
     * Wykonuje subagenta z podanym promptem.
     *
     * @param taskId ID taska nadrzędnego
     * @param definition Definicja subagenta
     * @param userPrompt Prompt użytkownika
     * @param contextItems Opcjonalne elementy kontekstu (legacy - deprecated)
     * @param contextRefs User context references (używane przez ContextService)
     * @param stream Czy streamować odpowiedź
     * @param onChunk Callback dla streamingu
     * @param parentModel Model z głównej konwersacji (dla "inherit")
     * @return Wynik wykonania subagenta
     */
    suspend fun execute(
        taskId: String,
        definition: SubagentDefinition,
        userPrompt: String,
        contextItems: List<ContextItem> = emptyList(),
        contextRefs: List<pl.jclab.refio.api.models.ContextReference> = emptyList(),
        stream: Boolean = false,
        onChunk: ((StreamChunk) -> Unit)? = null,
        parentModel: String? = null
    ): SubagentResult {
        logger.warn { "[SubagentExecutor] Deprecated path in use. Migrate to AgentTurnLoop with TurnRunProfile.SUBAGENT." }
        val startTime = System.currentTimeMillis()
        logger.info { "[SubagentExecutor] Invoking ${definition.name}" }

        return when (definition.executionMode) {
            SubagentExecutionMode.SINGLE_SHOT -> executeSingleShot(
                taskId = taskId,
                definition = definition,
                userPrompt = userPrompt,
                contextItems = contextItems,
                contextRefs = contextRefs,
                stream = stream,
                onChunk = onChunk,
                parentModel = parentModel,
                startTime = startTime
            )
            SubagentExecutionMode.MULTI_STEP -> executeMultiStep(
                taskId = taskId,
                definition = definition,
                userPrompt = userPrompt,
                contextItems = contextItems,
                contextRefs = contextRefs,
                stream = stream,
                onChunk = onChunk,
                parentModel = parentModel,
                startTime = startTime
            )
        }
    }

    /**
     * Wykonanie single-shot: jeden cykl LLM z możliwością wywołania narzędzi.
     */
    private suspend fun executeSingleShot(
        taskId: String,
        definition: SubagentDefinition,
        userPrompt: String,
        contextItems: List<ContextItem>,
        contextRefs: List<pl.jclab.refio.api.models.ContextReference>,
        stream: Boolean,
        onChunk: ((StreamChunk) -> Unit)?,
        parentModel: String?,
        startTime: Long
    ): SubagentResult {
        // 1. Rozwiąż model
        val (modelId, provider) = definition.resolveModel(configService, parentModel)
        logger.debug { "[SubagentExecutor] Using model: $provider/$modelId" }

        // 2. Filtruj dostępne narzędzia
        val allTools = toolRegistry.getAllTools()
        val availableTools = toolFilter.filterTools(allTools, definition)
        logger.debug { "[SubagentExecutor] Available tools: ${availableTools.map { it.name }}" }

        // 3. Zbuduj opisy narzędzi dla LLM
        val toolDescriptions = if (availableTools.isNotEmpty()) {
            buildToolDescriptions(availableTools)
        } else ""

        // 4. Zbuduj system prompt
        val fullSystemPrompt = buildSystemPrompt(definition, toolDescriptions)

        // 5. Zbuduj wiadomości z użyciem ContextService (context added as final user message)
        val messages = buildMessagesWithContext(taskId, userPrompt, contextItems, contextRefs)

        // 6. Wykonaj pętlę LLM z narzędziami
        val toolsUsed = mutableListOf<String>()
        var totalTokensIn = 0
        var totalTokensOut = 0
        var finalResponse = ""
        var iteration = 0

        val currentMessages = messages.toMutableList()

        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++
            logger.debug { "[SubagentExecutor] LLM iteration $iteration" }

            // Wywołaj LLM
            val response = llmClient.complete(
                provider = provider,
                model = modelId,
                messages = currentMessages,
                systemPrompt = fullSystemPrompt,
                stream = stream && iteration == 1, // Stream tylko pierwszy raz
                onChunk = if (iteration == 1) onChunk else null,
                source = "Subagent:${definition.name}"
            )

            totalTokensIn += response.usage.inputTokens
            totalTokensOut += response.usage.outputTokens

            // Sprawdź czy są tool calls
            val toolCallsJson = extractToolCalls(response.content)
            if (toolCallsJson.isEmpty()) {
                // Brak tool calls - zakończ
                finalResponse = response.content
                break
            }

            // Wykonaj narzędzia
            val toolExecutor = ToolExecutor(
                toolRegistry = toolRegistry,
                mode = TaskMode.AGENT
            )

            for (toolCallData in toolCallsJson) {
                val toolName = toolCallData["name"] as? String ?: continue
                @Suppress("UNCHECKED_CAST")
                val params = toolCallData["parameters"] as? Map<String, Any> ?: emptyMap()

                // Sprawdź czy narzędzie jest dozwolone
                if (!toolFilter.isToolAllowed(toolName, definition)) {
                    logger.warn { "[SubagentExecutor] Tool $toolName not allowed for ${definition.name}" }
                    continue
                }

                toolsUsed.add(toolName)

                try {
                    val result = toolExecutor.executeTool(ToolCall(toolName, params))

                    // Dodaj wynik narzędzia do kontekstu
                    currentMessages.add(LLMMessage(
                        role = "assistant",
                        content = response.content
                    ))
                    currentMessages.add(LLMMessage(
                        role = "user",
                        content = buildToolResultMessage(toolName, result.success, result.output, result.error)
                    ))
                } catch (e: Exception) {
                    logger.error(e) { "[SubagentExecutor] Tool $toolName failed: ${e.message}" }
                    currentMessages.add(LLMMessage(
                        role = "assistant",
                        content = response.content
                    ))
                    currentMessages.add(LLMMessage(
                        role = "user",
                        content = buildToolResultMessage(toolName, false, null, e.message)
                    ))
                }
            }

            // Jeśli to ostatnia iteracja, pobierz finalną odpowiedź
            if (iteration == MAX_TOOL_ITERATIONS) {
                val finalLlmResponse = llmClient.complete(
                    provider = provider,
                    model = modelId,
                    messages = currentMessages,
                    systemPrompt = fullSystemPrompt,
                    source = "Subagent:${definition.name}"
                )
                finalResponse = finalLlmResponse.content
                totalTokensIn += finalLlmResponse.usage.inputTokens
                totalTokensOut += finalLlmResponse.usage.outputTokens
            }
        }

        val durationMs = System.currentTimeMillis() - startTime

        // Zapisz do historii
        saveToHistory(taskId, definition.name, userPrompt, finalResponse, totalTokensIn, totalTokensOut)

        logger.info {
            "[SubagentExecutor] ${definition.name} completed in ${durationMs}ms, " +
            "tools: ${toolsUsed.size}, tokens: $totalTokensIn/$totalTokensOut"
        }

        return SubagentResult(
            success = true,
            response = finalResponse,
            toolsUsed = toolsUsed.distinct(),
            tokensUsed = totalTokensIn + totalTokensOut,
            durationMs = durationMs
        )
    }

    /**
     * Wykonanie multi-step: własna pętla wykonania z generowaniem planu.
     * (Uproszczona wersja - w przyszłości można rozszerzyć o pełne planowanie)
     */
    private suspend fun executeMultiStep(
        taskId: String,
        definition: SubagentDefinition,
        userPrompt: String,
        contextItems: List<ContextItem>,
        contextRefs: List<pl.jclab.refio.api.models.ContextReference>,
        stream: Boolean,
        onChunk: ((StreamChunk) -> Unit)?,
        parentModel: String?,
        startTime: Long
    ): SubagentResult {
        // Na razie delegujemy do single-shot z większą liczbą iteracji
        // W przyszłości można dodać pełne generowanie planu i wykonywanie kroków
        logger.info { "[SubagentExecutor] Multi-step mode for ${definition.name} (maxSteps=${definition.maxSteps})" }

        return executeSingleShot(
            taskId = taskId,
            definition = definition.copy(executionMode = SubagentExecutionMode.SINGLE_SHOT),
            userPrompt = userPrompt,
            contextItems = contextItems,
            contextRefs = contextRefs,
            stream = stream,
            onChunk = onChunk,
            parentModel = parentModel,
            startTime = startTime
        )
    }

    /**
     * Buduje pełny system prompt dla subagenta.
     */
    private fun buildSystemPrompt(definition: SubagentDefinition, toolDescriptions: String): String {
        val sb = StringBuilder()
        sb.appendLine(definition.systemPrompt)

        if (toolDescriptions.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("## Available Tools")
            sb.appendLine()
            sb.appendLine(toolDescriptions)
            sb.appendLine()
            sb.appendLine("""
                To use a tool, respond with a JSON object in this format:
                ```json
                {"tool_calls": [{"name": "tool_name", "parameters": {...}}]}
                ```

                After receiving tool results, provide your final analysis based on the gathered information.
            """.trimIndent())
        }

        return sb.toString()
    }

    /**
     * Buduje listę wiadomości dla LLM używając ContextService.
     *
     * Jeśli ContextService jest dostępny, używa go do budowania pełnego kontekstu projektu.
     * W przeciwnym razie wkleja contextItems bezpośrednio (fallback).
     *
     * Context jest dodawany jako druga user message (po pytaniu użytkownika) dla lepszej retention
     * zgodnie z NVIDIA RULER findings o recency bias w modelach LLM.
     *
     * @return Lista LLMMessage zawierająca: 1) user prompt, 2) context (jeśli dostępny)
     */
    private suspend fun buildMessagesWithContext(
        taskId: String,
        userPrompt: String,
        contextItems: List<ContextItem>,
        contextRefs: List<pl.jclab.refio.api.models.ContextReference>
    ): List<LLMMessage> {
        val messages = mutableListOf<LLMMessage>()
        var contextPrompt: String?

        // Użyj ContextService jeśli dostępny (rekomendowane)
        if (contextService != null && projectRoot != null) {
            try {
                logger.info { "[SubagentExecutor] Building project context using ContextService with ${contextRefs.size} references" }

                val projectContext = contextService.buildProjectContext(
                    projectRoot = projectRoot,
                    taskId = taskId,
                    project = ideProject,
                    query = userPrompt,
                    userContextRefs = contextRefs
                )

                contextPrompt = contextService.buildLLMContextPrompt(projectContext)

                if (contextPrompt.isNotBlank()) {
                    logger.info { "[SubagentExecutor] Built ContextService prompt: ${contextPrompt?.length ?: 0} chars" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[SubagentExecutor] Failed to build context using ContextService, falling back to legacy contextItems" }
                // Fallback do legacy implementation
                contextPrompt = buildLegacyContextPrompt(contextItems)
            }
        } else {
            // Fallback: użyj legacy contextItems (wklejanie bezpośrednie)
            logger.info { "[SubagentExecutor] ContextService not available, using legacy contextItems (${contextItems.size} items)" }
            contextPrompt = buildLegacyContextPrompt(contextItems)
        }

        // Dodaj główny prompt jako pierwsza user message
        messages.add(LLMMessage(
            role = "user",
            content = userPrompt
        ))

        // Dodaj context jako druga user message (AFTER user question) dla lepszej retention
        // To maksymalizuje recency bias - context jest najbliżej odpowiedzi modelu
        if (!contextPrompt.isNullOrBlank()) {
            messages.add(LLMMessage(
                role = "user",
                content = contextPrompt
            ))
        }

        return messages
    }

    /**
     * Legacy method: buduje prompt z contextItems.
     * Używane tylko gdy ContextService nie jest dostępny.
     */
    private fun buildLegacyContextPrompt(contextItems: List<ContextItem>): String? {
        if (contextItems.isEmpty()) return null

        val contextContent = contextItems.joinToString("\n\n") { item ->
            "## ${item.name}\n```\n${item.content}\n```"
        }
        return "Context:\n$contextContent"
    }

    /**
     * Wyodrębnia tool calls z odpowiedzi LLM.
     */
    private fun extractToolCalls(content: String): List<Map<String, Any>> {
        try {
            // Szukaj JSON z tool_calls
            val jsonPattern = Regex("\\{\\s*\"tool_calls\"\\s*:\\s*\\[.+?\\]\\s*\\}", RegexOption.DOT_MATCHES_ALL)
            val match = jsonPattern.find(content) ?: return emptyList()

            val jsonStr = match.value
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val parsed: Map<String, Any> = gson.fromJson(jsonStr, type)

            @Suppress("UNCHECKED_CAST")
            return parsed["tool_calls"] as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) {
            logger.debug { "[SubagentExecutor] No valid tool_calls found in response" }
            return emptyList()
        }
    }

    /**
     * Buduje wiadomość z wynikiem narzędzia.
     */
    private fun buildToolResultMessage(
        toolName: String,
        success: Boolean,
        output: String?,
        error: String?
    ): String {
        return if (success) {
            "Tool result for $toolName:\n```\n${output ?: "(no output)"}\n```"
        } else {
            "Tool $toolName failed: ${error ?: "Unknown error"}"
        }
    }

    /**
     * Zapisuje wywołanie subagenta do historii chatu.
     */
    private fun saveToHistory(
        taskId: String,
        subagentName: String,
        userPrompt: String,
        response: String,
        tokensIn: Int,
        tokensOut: Int
    ) {
        if (chatMessageRepository == null) return

        try {
            // Zapisz wiadomość użytkownika
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.USER,
                content = "!$subagentName $userPrompt",
                metadata = """{"subagent": "$subagentName", "type": "invocation"}""",
                tokensIn = null,
                tokensOut = null
            )

            // Zapisz odpowiedź subagenta
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = response,
                metadata = """{"subagent": "$subagentName", "type": "response"}""",
                tokensIn = tokensIn,
                tokensOut = tokensOut
            )
        } catch (e: Exception) {
            logger.warn(e) { "[SubagentExecutor] Failed to save to history" }
        }
    }

    /**
     * Buduje opisy narzędzi dla LLM.
     */
    private fun buildToolDescriptions(tools: List<Tool>): String {
        return tools.mapIndexed { index, tool ->
            val schema = tool.getParameterSchema()
            buildSingleToolDescription(index + 1, tool.name, tool.description, schema)
        }.joinToString("\n\n")
    }

    /**
     * Buduje opis pojedynczego narzędzia.
     */
    private fun buildSingleToolDescription(
        number: Int,
        name: String,
        description: String,
        schema: Map<String, Any>
    ): String {
        val sb = StringBuilder()
        sb.append("$number. **$name** - $description\n")

        @Suppress("UNCHECKED_CAST")
        val properties = (schema["properties"] as? Map<String, Map<String, Any>>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>) ?: emptyList()

        if (properties.isNotEmpty()) {
            properties.forEach { (paramName, paramSchema) ->
                val paramType = paramSchema["type"]?.toString() ?: "any"
                val paramDesc = paramSchema["description"]?.toString() ?: ""
                val isRequired = paramName in required
                val requiredLabel = if (isRequired) "Required" else "Optional"

                sb.append("   - $requiredLabel: \"$paramName\" ($paramType)")
                if (paramDesc.isNotBlank()) {
                    sb.append(" - $paramDesc")
                }
                sb.append("\n")
            }
        }

        return sb.toString()
    }
}
