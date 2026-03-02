package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.context.ContextItem
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentInfo
import pl.jclab.refio.core.subagents.models.SubagentResult
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("SubagentRouter")

/**
 * Router dla operacji na subagentach.
 *
 * Odpowiedzialności:
 * - API do listowania i pobierania subagentów
 * - API do wywoływania subagentów
 * - CRUD operacje na definicjach subagentów
 * - Integracja z CoreApiRouter
 */
class SubagentRouter(
    private val projectRoot: Path,
    private val toolRegistry: ToolRegistry,
    private val configService: ConfigService,
    private val llmClient: LLMClient,
    private val toolPermissionsService: ToolPermissionsService,
    private val chatMessageRepository: ChatMessageRepository,
    private val contextService: pl.jclab.refio.core.services.ContextService?,
    private val ideProject: com.intellij.openapi.project.Project?,
    private val runTurnCallback: (suspend (TurnRequest, pl.jclab.refio.core.api.StreamCallback?) -> TurnResult)? = null
) : Router {
    private val invocationRegex = Regex("^!([a-zA-Z0-9_-]+)(?:\\s+([\\s\\S]+))?$")

    private val parser = SubagentParser()
    private val registry = SubagentRegistry(projectRoot, parser)
    private val toolFilter = SubagentToolFilter(toolPermissionsService)
    private val executor = SubagentExecutor(
        llmClient = llmClient,
        toolRegistry = toolRegistry,
        toolFilter = toolFilter,
        configService = configService,
        chatMessageRepository = chatMessageRepository,
        contextService = contextService,
        projectRoot = projectRoot,
        ideProject = ideProject
    )

    init {
        // Auto-initialize registry on construction
        logger.info { "[SubagentRouter] Auto-initializing on construction..." }
        registry.refresh()
        logger.info { "[SubagentRouter] Initialized with ${registry.size()} subagents" }
    }

    override suspend fun initialize() {
        // No-op: initialization happens in init block for eager loading
        logger.info { "[SubagentRouter] initialize() called (already initialized in constructor)" }
    }

    override suspend fun shutdown() {
        logger.info { "[SubagentRouter] Shutting down" }
        registry.clear()
    }

    // ===== Query Operations =====

    /**
     * Lista wszystkich dostępnych subagentów.
     *
     * @param includeDisabled Czy uwzględnić wyłączone subagenty (dla panelu administracyjnego)
     */
    fun listSubagents(includeDisabled: Boolean = false): List<SubagentInfo> {
        val overrides = configService.getBuiltinSubagentEnabledOverrides()
        val all = registry.getAllSubagents(includeDisabled = true)
            .map { applyBuiltinEnabledOverride(it, overrides) }
        val visible = if (includeDisabled) all else all.filter { it.enabled }
        return visible.map { it.toInfo() }
    }

    /**
     * Pobiera szczegóły subagenta.
     */
    fun getSubagent(name: String): SubagentDefinition? {
        val definition = registry.getSubagent(name) ?: return null
        return applyBuiltinEnabledOverride(definition, configService.getBuiltinSubagentEnabledOverrides())
    }

    /**
     * Sprawdza czy subagent istnieje.
     */
    fun exists(name: String): Boolean {
        return getSubagent(name) != null
    }

    /**
     * Wymusza odświeżenie cache subagentów.
     */
    fun refresh() {
        registry.refresh()
    }

    /**
     * Wyszukuje subagentów pasujących do słów kluczowych.
     * Używane do auto-delegacji.
     */
    fun findByKeywords(keywords: List<String>): List<SubagentInfo> {
        if (keywords.isEmpty()) return emptyList()

        val overrides = configService.getBuiltinSubagentEnabledOverrides()
        val enabled = registry.getAllSubagents(includeDisabled = true)
            .map { applyBuiltinEnabledOverride(it, overrides) }
            .filter { it.enabled }

        val keywordsLower = keywords.map { it.lowercase() }
        val matched = enabled.filter { agent ->
            val agentText = "${agent.name} ${agent.description}".lowercase()
            keywordsLower.any { keyword -> agentText.contains(keyword) }
        }

        return matched.map { it.toInfo() }
    }

    // ===== Execution =====

    /**
     * Wywołuje subagenta.
     *
     * @param taskId ID taska w którym wywoływany jest subagent
     * @param name Nazwa subagenta
     * @param prompt Prompt użytkownika
     * @param contextItems Opcjonalne elementy kontekstu (legacy - deprecated, używaj contextRefs)
     * @param contextRefs Context references (rekomendowane - używane przez ContextService)
     * @param stream Czy streamować odpowiedź
     * @param onChunk Callback dla streamingu
     * @param parentModel Model z głównej konwersacji (dla "inherit")
     * @return Wynik wykonania subagenta
     * @throws SubagentNotFoundException Jeśli subagent nie istnieje
     * @throws SubagentDisabledException Jeśli subagent jest wyłączony
     */
    suspend fun invoke(
        taskId: String,
        name: String,
        prompt: String,
        contextItems: List<ContextItem> = emptyList(),
        contextRefs: List<pl.jclab.refio.api.models.ContextReference> = emptyList(),
        stream: Boolean = false,
        onChunk: ((StreamChunk) -> Unit)? = null,
        parentModel: String? = null
    ): SubagentResult {
        val startTime = System.currentTimeMillis()
        val definition = getSubagent(name)
            ?: throw SubagentNotFoundException("Subagent not found: $name")

        if (!definition.enabled) {
            throw SubagentDisabledException("Subagent is disabled: $name")
        }

        logger.info { "[SubagentRouter] Invoking subagent via AgentTurnLoop: $name" }
        val (resolvedModel, resolvedProvider) = definition.resolveModel(configService, parentModel)

        if (runTurnCallback != null) {
            val request = TurnRequest(
                taskId = taskId,
                userInput = prompt,
                mode = TaskMode.AGENT,
                executionMode = ExecutionMode.AUTO,
                model = resolvedModel,
                provider = resolvedProvider,
                userContextRefs = contextRefs,
                runProfile = TurnRunProfile.SUBAGENT,
                profileOverrides = TurnProfileOverrides(
                    subagentName = name,
                    systemPromptOverride = definition.systemPrompt,
                    allowedTools = definition.allowedTools,
                    disallowedTools = definition.disallowedTools,
                    modelOverride = resolvedModel,
                    providerOverride = resolvedProvider,
                    maxIterationsOverride = definition.maxSteps,
                    depth = 0,
                    subagentChain = emptyList()
                )
            )

            val streamCallback = if (stream && onChunk != null) {
                { chunk: StreamChunk -> onChunk(chunk) }
            } else {
                null
            }

            val result = runTurnCallback.invoke(request, streamCallback)
            return SubagentResult(
                success = result.success,
                response = result.response,
                toolsUsed = emptyList(),
                tokensUsed = result.tokensIn + result.tokensOut,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf(
                    "run_profile" to TurnRunProfile.SUBAGENT.name,
                    "model" to resolvedModel,
                    "provider" to resolvedProvider
                )
            )
        }

        logger.warn { "[SubagentRouter] Falling back to legacy SubagentExecutor (runTurnCallback unavailable)" }
        return executor.execute(
            taskId = taskId,
            definition = definition,
            userPrompt = prompt,
            contextItems = contextItems,
            contextRefs = contextRefs,
            stream = stream,
            onChunk = onChunk,
            parentModel = parentModel
        )
    }

    /**
     * Sprawdza czy wiadomość jest wywołaniem subagenta (prefix !).
     * Sprawdza wyłącznie składnię komendy.
     */
    fun parseSubagentCommand(message: String): Pair<String, String>? {
        val match = invocationRegex.find(message.trim()) ?: return null
        val subagentName = match.groupValues[1].trim().lowercase()
        val prompt = match.groupValues.getOrNull(2)?.trim().orEmpty()
        return Pair(subagentName, prompt)
    }

    /**
     * Sprawdza czy wiadomość jest wywołaniem subagenta (prefix !)
     * i waliduje dostępność subagenta.
     *
     * @param message Wiadomość użytkownika
     * @return Pair(nazwa_subagenta, prompt) lub null jeśli nie jest wywołaniem
     */
    fun parseSubagentInvocation(message: String): Pair<String, String>? {
        val command = parseSubagentCommand(message) ?: return null
        val (subagentName, providedPrompt) = command

        // Sprawdź czy subagent istnieje
        // Check if subagent exists and is enabled
        val definition = getSubagent(subagentName)
        if (definition == null || !definition.enabled) {
            return null
        }

        val prompt = if (providedPrompt.isNotBlank()) {
            providedPrompt
        } else {
            logger.info { "[SubagentRouter] Invocation without prompt for '$subagentName' - using default objective" }
            buildDefaultInvocationPrompt(definition)
        }

        return Pair(subagentName, prompt)
    }

    private fun buildDefaultInvocationPrompt(definition: SubagentDefinition): String {
        val trimmedDescription = definition.description.trim().ifBlank { "specialized analysis" }
        return buildString {
            append("Run your role as '")
            append(definition.name)
            append("' (")
            append(trimmedDescription)
            append(") on the current project. ")
            append("Use your available tools to inspect relevant files and provide concrete findings with file references.")
        }
    }

    // ===== CRUD Operations =====

    /**
     * Tworzy nowego subagenta.
     *
     * @param name Nazwa subagenta
     * @param description Opis
     * @param systemPrompt System prompt
     * @param allowedTools Lista dozwolonych narzędzi (null = inherit)
     * @param model Model do użycia
     * @param scope Gdzie zapisać (PROJECT lub USER)
     * @return Utworzona definicja
     * @throws SubagentAlreadyExistsException Jeśli subagent już istnieje
     */
    fun createSubagent(
        name: String,
        description: String,
        systemPrompt: String,
        allowedTools: List<String>? = null,
        model: String = "default",
        scope: SubagentScope = SubagentScope.PROJECT,
        enabled: Boolean = true,
        priority: Int = 0
    ): SubagentDefinition {
        val definition = SubagentDefinition(
            name = name.lowercase().replace(" ", "-"),
            description = description,
            systemPrompt = systemPrompt,
            allowedTools = allowedTools,
            model = model,
            enabled = enabled,
            priority = priority,
            scope = scope
        )

        return registry.createSubagent(definition, scope)
    }

    /**
     * Aktualizuje istniejącego subagenta.
     *
     * @param name Nazwa subagenta do zaktualizowania
     * @param description Nowy opis (null = bez zmian)
     * @param systemPrompt Nowy system prompt (null = bez zmian)
     * @param allowedTools Nowa lista narzędzi (null = bez zmian)
     * @param model Nowy model (null = bez zmian)
     * @param enabled Czy aktywny (null = bez zmian)
     * @param priority Nowy priorytet (null = bez zmian)
     * @return Zaktualizowana definicja
     * @throws SubagentNotFoundException Jeśli subagent nie istnieje
     */
    fun updateSubagent(
        name: String,
        description: String? = null,
        systemPrompt: String? = null,
        allowedTools: List<String>? = null,
        model: String? = null,
        enabled: Boolean? = null,
        priority: Int? = null
    ): SubagentDefinition {
        val existing = registry.getSubagent(name)
            ?: throw SubagentNotFoundException("Subagent not found: $name")

        if (existing.scope == SubagentScope.BUILTIN) {
            val hasOtherUpdates = description != null ||
                systemPrompt != null ||
                allowedTools != null ||
                model != null ||
                priority != null

            if (hasOtherUpdates) {
                throw IllegalArgumentException("Cannot update builtin subagent fields other than enabled")
            }

            if (enabled == null) {
                return getSubagent(name) ?: existing
            }

            configService.setBuiltinSubagentEnabledOverride(existing.name, enabled)
            return existing.copy(enabled = enabled)
        }

        val updated = existing.copy(
            description = description ?: existing.description,
            systemPrompt = systemPrompt ?: existing.systemPrompt,
            allowedTools = allowedTools ?: existing.allowedTools,
            model = model ?: existing.model,
            enabled = enabled ?: existing.enabled,
            priority = priority ?: existing.priority
        )

        return registry.updateSubagent(updated)
    }

    /**
     * Usuwa subagenta.
     *
     * @param name Nazwa subagenta
     * @return true jeśli usunięto
     * @throws SubagentNotFoundException Jeśli subagent nie istnieje
     * @throws IllegalArgumentException Jeśli subagent jest wbudowany
     */
    fun deleteSubagent(name: String): Boolean {
        return registry.deleteSubagent(name)
    }

    // ===== Helpers =====

    /**
     * Pobiera ścieżkę do katalogu subagentów projektu.
     */
    fun getProjectAgentsDir(): Path = registry.projectAgentsDir

    /**
     * Pobiera ścieżkę do katalogu subagentów użytkownika.
     */
    fun getUserAgentsDir(): Path = registry.userAgentsDir

    /**
     * Konwertuje SubagentDefinition na SubagentInfo.
     */
    private fun SubagentDefinition.toInfo() = SubagentInfo(
        name = name,
        description = description,
        tools = allowedTools,
        model = model,
        enabled = enabled,
        scope = scope.name.lowercase(),
        priority = priority
    )

    private fun applyBuiltinEnabledOverride(
        definition: SubagentDefinition,
        overrides: Map<String, Boolean>
    ): SubagentDefinition {
        if (definition.scope != SubagentScope.BUILTIN) return definition
        val enabledOverride = overrides[definition.name.lowercase()] ?: return definition
        return definition.copy(enabled = enabledOverride)
    }
}
