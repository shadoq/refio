package pl.jclab.refio.core.services

// Project type erased to Any? for platform independence (see ProjectHandle)
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.context.*
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path
import java.time.Instant

private val logger = dualLogger("ContextService")
private const val CONVERSATION_SUMMARY_METADATA_TYPE = ConversationContextBuilder.CONVERSATION_SUMMARY_METADATA_TYPE

// Context budget limits
private const val SMALL_CONTEXT_OVERFLOW_THRESHOLD_TOKENS = 12_000
private const val SMALL_CONTEXT_OVERFLOW_RATIO = 0.75

// RECENT_WORK limits
private const val RECENT_WORK_LAST_ENTRY_CHAR_BUFFER = 32256
private const val RECENT_WORK_LAST_ENTRY_TOKEN_BUFFER = RECENT_WORK_LAST_ENTRY_CHAR_BUFFER / 4

// CONVERSATION_HISTORY limits
private const val DEFAULT_CONVERSATION_HISTORY_LIMIT = 100
private const val CONTEXT_CONVERSATION_HISTORY_LIMIT = DEFAULT_CONVERSATION_HISTORY_LIMIT
private const val CONTEXT_INCLUDE_CONVERSATION_HISTORY = true

/**
 * Service for building context for LLM prompts.
 * Combines project analysis with current task state.
 */
class ContextService(
    private val projectAnalyzer: ProjectAnalyzerService,
    private val taskRepository: TaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val subtaskRepository: SubtaskRepository,
    private val fileAnalyzerService: FileAnalyzerService? = null,
    private val configService: ConfigService,
    private val workingMemoryService: WorkingMemoryService? = null,
    private val conversationSummaryService: ConversationSummaryService? = null,
    /**
     * Opaque platform project handle (IntelliJ Project or null for CLI). Propagated
     * to IDE-specific context providers via [ContextProviderExtras]. Injected once
     * at construction because it is stable for the lifetime of a [ContextService]
     * (CoreApiRouter is per-project).
     */
    private val platformProject: Any? = null,
) {
    private val projectInstructionsLoader = ProjectInstructionsLoader()
    private val mcpContextLoader = McpContextLoader()
    private val projectContextSummarizer = ProjectContextSummarizer()
    private val conversationContextBuilder = ConversationContextBuilder()
    private val taskContextExtractor = TaskContextExtractor()
    private val contextReferenceResolver = ContextReferenceResolver(fileAnalyzerService, configService, chatMessageRepository, platformProject)
    private val pruner: ContextPruner = ContextPruner(configService)
    private val formatter: ContextFormatter = ContextFormatter(configService)

    /**
     * Context layer cache for stable/accumulated context reuse across turns.
     */
    val contextLayerCache = ContextLayerCache()

    /**
     * Last context decision trace from buildLLMContextPrompt().
     * Available after prompt building for PromptSnapshot construction.
     */
    @Volatile
    var lastContextTrace: pl.jclab.refio.core.services.turn.ContextDecisionTrace? = null
        private set

    /**
     * Conversation messages the last prompt build had to leave out, and older tool steps it
     * omitted. Two losses that the section trace above cannot show, because they happen INSIDE a
     * section that was included: the section fits, its oldest contents do not.
     */
    val lastConversationDropped: Int get() = formatter.lastConversationDropped
    val lastStepsDropped: Int get() = formatter.lastStepsDropped

    /**
     * Last granular section token breakdown from buildLLMContextPrompt().
     * Parsed from XML tags in the generated prompt — maps UI-friendly keys
     * (e.g. "recent_work", "key_components") to token info.
     * Used by AgentTurnLoop to populate PromptSnapshot.sectionTokens.
     */
    @Volatile
    var lastSectionTokens: Map<String, ContextSectionTokenInfo>? = null
        private set

    constructor(
        projectAnalyzer: ProjectAnalyzerService,
        taskRepository: TaskRepository,
        chatMessageRepository: ChatMessageRepository,
        subtaskRepository: SubtaskRepository,
        fileAnalyzerService: FileAnalyzerService? = null,
        configService: ConfigService,
    ) : this(
        projectAnalyzer = projectAnalyzer,
        taskRepository = taskRepository,
        chatMessageRepository = chatMessageRepository,
        subtaskRepository = subtaskRepository,
        fileAnalyzerService = fileAnalyzerService,
        configService = configService,
        workingMemoryService = null,
        conversationSummaryService = null,
    )

    /**
     * Build comprehensive project context for LLM using rich DTOs.
     * REFACTORED to use new ProjectContextDTO from models/context package.
     *
     * This is the MAIN PUBLIC METHOD for building context.
     * All context resolution (including user @ mentions) happens internally.
     *
     * Context build rules are fixed and shared across all call-sites.
     */
    suspend fun buildProjectContext(
        projectRoot: Path,
        taskId: String,
        query: String? = null,
        userContextRefs: List<ContextReference> = emptyList(),
        /**
         * The agent run the context is for: null for the parent run, a subagent's invocation id for
         * that subagent. Recent work, working memory, conversation history and the current task are
         * then built from that run's own steps and messages only.
         */
        agentInstanceId: String? = null,
        /** The run's conversation when the caller has already read it; null reads it here. */
        threadMessages: List<ChatMessage>? = null,
        /** False for a UI preview: MCP tool output only from cache, no call to the server. */
        allowMcpToolCalls: Boolean = true,
    ): ProjectContextDTO {
        logger.info { "Building project context for task=$taskId" }

        // 1. Get or load project analysis (cached)
        val projectAnalysis = projectAnalyzer.analyzeProject(projectRoot, includeContent = false)

        // 2. Get task info
        val task = transaction { taskRepository.findById(taskId) }
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val dedupedUserContextRefs = contextReferenceResolver.deduplicateUserContextRefs(userContextRefs)
        if (dedupedUserContextRefs.isNotEmpty()) {
            logger.info {
                "[CONTEXT_DEBUG] Received ${dedupedUserContextRefs.size} user context ref(s): ${
                    dedupedUserContextRefs.map { "${it.type}:${it.path}" }
                }"
            }
        } else {
            logger.info { "[CONTEXT_DEBUG] No user context references provided for task=$taskId" }
        }

        // 4. Get subtasks: files changed anywhere in the task stay visible, but recent work and
        // working memory come from this run's own steps. A subagent's internal steps are its own
        // business; the parent gets its report through the invoke_subagent result instead.
        val taskSubtasks = transaction { subtaskRepository.findByTaskId(taskId) }
        val subtasks = taskSubtasks.filter { it.agentInstanceId == agentInstanceId }
        val memoryScope = WorkingMemoryService.scopeKey(taskId, agentInstanceId)

        // 4a. Lazily rebuild WORKING_MEMORY from persisted subtasks. Working memory lives
        // in-memory only, so after a plugin/app restart (or when the user reopens an old
        // conversation) the in-memory map is empty even though the underlying data —
        // subtask params + result — is still in the database. hasEntries() guards against
        // re-running the extraction on every turn within a live session.
        workingMemoryService?.rebuildFromSubtasks(taskId = memoryScope, subtasks = subtasks)

        // A subagent's task is the prompt it was started with, not the parent's request.
        val threadMessages = threadMessages
            ?: transaction { chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId) }
        val taskDescription = if (agentInstanceId == null) {
            task.name
        } else {
            threadMessages.firstOrNull { it.role == MessageRole.USER }?.content ?: task.name
        }

        // 4. Get conversation history (if requested)
        val rawConversationHistory = if (CONTEXT_INCLUDE_CONVERSATION_HISTORY) {
            conversationContextBuilder.sliceConversationHistoryFromLastSummary(threadMessages)
                .takeLast(CONTEXT_CONVERSATION_HISTORY_LIMIT * 2)  // Get more, then filter
        } else {
            emptyList()
        }

        // Preserve summary message at the beginning (if any) and filter remaining noise
        val hasLeadingSummary = rawConversationHistory.firstOrNull()?.let { conversationContextBuilder.isConversationSummary(it) } == true
        val filteredConversation = conversationContextBuilder.filterMeaningfulConversation(rawConversationHistory).let { filtered ->
            if (hasLeadingSummary) {
                val summaryMessage = rawConversationHistory.first()
                val withoutSummary = filtered.filterNot { it.id == summaryMessage.id }
                listOf(summaryMessage) + withoutSummary
            } else {
                filtered
            }
        }.takeLast(CONTEXT_CONVERSATION_HISTORY_LIMIT)

        // 6. Build completed files data
        val completedFiles = taskContextExtractor.buildCompletedFiles(taskSubtasks)

        // 6a. Build structured executed steps for RECENT_WORK
        val executedSteps = taskContextExtractor.buildExecutedSteps(subtasks)

        // 7. Extract user requirements from task description
        val userRequirements = taskContextExtractor.extractUserRequirements(taskDescription)

        // 8. Build rich DTOs
        val metaData = MetaDataDTO(
            projectId = null,  // Not available in Task entity
            projectName = projectRoot.fileName.toString(),
            projectDescription = null,
            fileCount = projectAnalysis.structure.totalFiles,
            complexity = projectAnalysis.summary.complexity,
            mainLanguage = projectAnalysis.summary.mainLanguage,
            lastAnalysis = Instant.ofEpochMilli(projectAnalysis.analyzedAt)
        )

        val summary = SummaryDTO(
            projectType = projectAnalysis.projectType,
            complexity = projectAnalysis.summary.complexity,
            mainLanguage = projectAnalysis.summary.mainLanguage,
            architectureNotes = projectAnalysis.summary.architectureNotes,
            fileCount = projectAnalysis.structure.totalFiles,
            semanticDescription = projectAnalysis.summary.semanticDescription,
            keyCapabilities = projectAnalysis.summary.keyCapabilities,
            entryPoints = projectAnalysis.summary.entryPoints
        )

        val semanticSummary = buildCompactProjectSummary(
            projectAnalysis = projectAnalysis,
            richReport = projectAnalysis.richReport,
            maxTokens = 4000
        )

        val structure = StructureDTO(
            totalFiles = projectAnalysis.structure.totalFiles,
            fileTypes = projectAnalysis.structure.fileTypes,
            topLevelItems = projectAnalysis.structure.topLevelItems,
            directoryCount = projectAnalysis.structure.directoryCount,
            maxDepth = projectAnalysis.structure.maxDepth
        )

        val dependencies = DependenciesDTO(
            python = projectAnalysis.dependencies.python,
            javascript = projectAnalysis.dependencies.javascript,
            typescript = projectAnalysis.dependencies.typescript,
            kotlin = projectAnalysis.dependencies.kotlin,
            java = projectAnalysis.dependencies.java,
            cpp = projectAnalysis.dependencies.cpp,
            packageManagers = projectAnalysis.dependencies.packageManagers,
            configFiles = projectAnalysis.dependencies.configFiles
        )

        val codeAnalysis = CodeAnalysisDTO(
            javascript = projectAnalysis.codeAnalysis.javascript,
            python = projectAnalysis.codeAnalysis.python,
            html = projectAnalysis.codeAnalysis.html,
            css = projectAnalysis.codeAnalysis.css,
            typescript = projectAnalysis.codeAnalysis.typescript,
            kotlin = projectAnalysis.codeAnalysis.kotlin,
            java = projectAnalysis.codeAnalysis.java
        )

        val workspace = WorkspaceDTO(
            path = projectRoot.toString(),
            taskId = taskId,
            projectId = null,
            projectName = projectRoot.fileName.toString()
        )

        val executionMetadata = ExecutionMetadataDTO(
            executionTimestamp = Instant.now(),
            workspacePath = projectRoot.toString(),
            agentMode = task.mode.name,
            interactiveMode = true,
            executionMode = null
        )

        val currentTask = CurrentTaskDTO(
            id = task.id,
            name = taskDescription,
            description = taskDescription,  // Task doesn't have separate description field
            status = task.status.name,
            priority = null,
            executionMode = null,
            context = emptyMap()
        )

        val subtaskDTOs = subtasks.map {
            SubtaskDTO(
                id = it.id,
                name = it.description,
                description = it.description,
                status = it.status.name,
                order = it.orderIndex,
                agentType = null,
                stepType = it.kind.name,
                requiresConfirmation = true,
                expectedOutcome = null,
                tool = null,
                toolArgs = emptyMap()
            )
        }

        // Parse conversation history with full metadata
        val gson = pl.jclab.refio.core.utils.GsonInstance.gson
        val conversationDTOs = filteredConversation.map { msg ->
            val metadata: Map<String, Any?>? = msg.metadata?.let { json ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(json, Map::class.java) as? Map<String, Any?>
                } catch (e: Exception) {
                    null
                }
            }

            ConversationMessageDTO(
                id = msg.id,
                role = msg.role.name.lowercase(),
                content = if (msg.role == MessageRole.TOOL) {
                    resolveToolConversationContent(
                        msg = msg,
                    )
                } else {
                    msg.content
                },
                createdAt = Instant.ofEpochMilli(msg.createdAt),
                processingTime = (metadata?.get("processing_time") as? Number)?.toDouble(),
                inputTokens = (metadata?.get("tokens_in") as? Number)?.toInt(),
                outputTokens = (metadata?.get("tokens_out") as? Number)?.toInt(),
                cost = (metadata?.get("cost_usd") as? Number)?.toDouble(),
                modelId = metadata?.get("model_id") as? String,
                metadata = metadata
            )
        }

        val mcpResources = mcpContextLoader.loadMcpResources(projectRoot, query, allowMcpToolCalls)

        // 9. Resolve user context references (from @ mentions)
        val resolvedUserContext = if (dedupedUserContextRefs.isNotEmpty()) {
            logger.info { "[CONTEXT] Resolving ${dedupedUserContextRefs.size} user context reference(s)" }
            contextReferenceResolver.resolveAndConvertUserContextRefs(dedupedUserContextRefs, projectRoot, query)
        } else {
            emptyList()
        }

        // 10. Return rich ProjectContextDTO
        val projectContext = ProjectContextDTO(
            // Core DTOs
            metaData = metaData,
            summary = summary,
            structure = structure,
            dependencies = dependencies,
            codeAnalysis = codeAnalysis,
            workspace = workspace,
            executionMetadata = executionMetadata,

            // Project characteristics
            projectType = projectAnalysis.projectType,
            technologies = projectAnalysis.technologies,
            technologyVersions = projectAnalysis.technologyVersions,
            keyComponents = projectAnalysis.keyComponents,

            // Task and subtasks
            currentTask = currentTask,
            subtasks = subtaskDTOs,
            agentInstanceId = agentInstanceId,

            // Conversation history (filtered!)
            conversationHistory = conversationDTOs,

            // Work history
            completedFiles = completedFiles,
            executedSteps = executedSteps,

            // User requirements (extracted from task description)
            userRequirements = userRequirements,

            // User-provided context (from @ mentions)
            userContextRefs = resolvedUserContext,

            // Project instructions (AGENTS.md, .refio/agent.md, .refio/rules/)
            projectInstructions = loadProjectInstructions(projectRoot),

            // Additional context
            mcpResources = mcpResources,

            // Context generation metadata
            contextGeneratedAt = Instant.now(),
            analyzerVersion = "kotlin-v1.0",
            domainAnalysis = projectAnalysis.domainAnalysis.domainScores,
            semanticSummary = semanticSummary,

            // Framework analysis
            frameworkAnalysis = projectAnalysis.frameworkAnalysis,

            // Error information
            error = null,
            sectionTokens = null
        )

        return projectContext
    }


    /**
     * Build formatted LLM context prompt from DTO
     * Returns structured prompt with XML-like tags
     *
     * Section order (2025-12-03):
     * 1. PROJECT CONTEXT FIRST - Agent must know the project before getting the task
     * 2. TASK & REQUIREMENTS - What needs to be done
     * 3. USER CONTEXT - Supporting information
     * 4. HISTORY - Previous work and conversation
     */
    /**
     * Build LLM context prompt.
     * Organized by TIER priority: Essential → Work → Supplementary → Reference
     */
    fun buildLLMContextPrompt(context: ProjectContextDTO, staticPrefixTokens: Int = 0, modelId: String? = null): String =
        buildLLMContext(context, staticPrefixTokens, modelId, trailingState = false).prompt

    /**
     * Same as [buildLLMContextPrompt], optionally split for [ConfigKeys.AGENT_PROMPT_LAYOUT] = trailing:
     * with [trailingState] WORKING_MEMORY and RECENT_WORK are returned in [LLMContextParts.trailingState]
     * instead of the prompt (they still count against the budget, they are still sent), and
     * CONVERSATION_HISTORY is not built, because the caller sends the message list that carries the
     * same conversation.
     */
    fun buildLLMContext(
        context: ProjectContextDTO,
        staticPrefixTokens: Int = 0,
        modelId: String? = null,
        trailingState: Boolean = false
    ): LLMContextParts {
        formatter.resetDropCounters()
        val budget = pruner.resolveContextBudget(context, modelOperation = null, staticPrefixTokens = staticPrefixTokens)
        val parts = mutableListOf<String>()
        val stateParts = mutableListOf<String>()
        val workStateTarget = if (trailingState) stateParts else parts
        val usage = mutableListOf<String>()
        val actualUsage = mutableMapOf<ContextSection, Int>()
        val traceRecords = mutableListOf<pl.jclab.refio.core.services.turn.ContextSectionRecord>()
        var remainingTokens = budget.totalTokens
        // Soft headroom for small-context setups (e.g., ~8k providers) so context is not over-truncated.
        // Kept disabled for large budgets to avoid hitting model limits.
        val overflowTokensInitial = if (budget.totalTokens < SMALL_CONTEXT_OVERFLOW_THRESHOLD_TOKENS) {
            (budget.totalTokens * SMALL_CONTEXT_OVERFLOW_RATIO).toInt().coerceAtLeast(0)
        } else {
            0
        }
        var overflowTokensRemaining = overflowTokensInitial

        fun addSection(
            section: ContextSection,
            content: String,
            maxTokens: Int = budget.budgetFor(section),
            target: MutableList<String> = parts
        ) {
            if (content.isBlank()) {
                traceRecords.add(pl.jclab.refio.core.services.turn.ContextSectionRecord(
                    section = section,
                    priority = section.defaultPriority,
                    included = false,
                    estimatedTokens = 0,
                    dropReason = pl.jclab.refio.core.services.turn.DropReason.EMPTY_CONTENT
                ))
                return
            }

            val estimatedTokens = ContextTokenEstimator.estimateTokens(content, modelId)
            val allowedBase = minOf(maxTokens, remainingTokens).coerceAtLeast(0)
            val allowed = (allowedBase + overflowTokensRemaining).coerceAtLeast(0)
            if (allowed <= 0) {
                logger.debug { "[CONTEXT_BUDGET] Skipping ${section.name} - no remaining budget" }
                traceRecords.add(pl.jclab.refio.core.services.turn.ContextSectionRecord(
                    section = section,
                    priority = section.defaultPriority,
                    included = false,
                    estimatedTokens = estimatedTokens,
                    dropReason = pl.jclab.refio.core.services.turn.DropReason.BUDGET_EXCEEDED
                ))
                return
            }

            var sectionContent = content
            var tokens = estimatedTokens
            if (tokens <= 0) return

            var truncated = false
            if (tokens > allowed) {
                logger.debug {
                    "[CONTEXT_BUDGET] Section overflow ${section.name}: tokens=$tokens, allowed=$allowed"
                }
                sectionContent = pruner.truncateSectionToBudget(sectionContent, allowed, modelId)
                tokens = ContextTokenEstimator.estimateTokens(sectionContent, modelId)
                truncated = true
            }

            if (tokens <= 0 || sectionContent.isBlank()) return

            target.add(sectionContent)
            actualUsage[section] = (actualUsage[section] ?: 0) + tokens
            if (tokens <= remainingTokens) {
                remainingTokens = (remainingTokens - tokens).coerceAtLeast(0)
            } else {
                val overflowUsed = tokens - remainingTokens
                remainingTokens = 0
                overflowTokensRemaining = (overflowTokensRemaining - overflowUsed).coerceAtLeast(0)
            }
            val overflowMarker = if (truncated) " (truncated)" else ""
            usage.add("${section.name}=${tokens}/${maxTokens}$overflowMarker")

            traceRecords.add(pl.jclab.refio.core.services.turn.ContextSectionRecord(
                section = section,
                priority = section.defaultPriority,
                included = true,
                estimatedTokens = estimatedTokens,
                actualTokens = tokens,
                dropReason = if (truncated) pl.jclab.refio.core.services.turn.DropReason.TRUNCATED else null
            ))
        }

        val taskId = context.currentTask?.id
        // The stable layer holds the current task, which differs per agent run, so parent and each
        // subagent cache their own copy; working memory is kept per run the same way.
        val runScope = taskId?.let { WorkingMemoryService.scopeKey(it, context.agentInstanceId) }

        // === STABLE CONTEXT LAYER (cached, invalidated on project file change) ===
        val stableCacheHit = runScope?.let { contextLayerCache.getStableContext(it) }
        if (stableCacheHit != null) {
            parts.add(stableCacheHit.content)
            val tokens = stableCacheHit.tokensUsed
            remainingTokens = (remainingTokens - tokens).coerceAtLeast(0)
            if (stableCacheHit.sectionUsage.isNotEmpty()) {
                actualUsage.putAll(stableCacheHit.sectionUsage)
            } else {
                actualUsage[ContextSection.PROJECT_CONTEXT] = tokens
            }
            if (stableCacheHit.traceRecords.isNotEmpty()) {
                traceRecords.addAll(stableCacheHit.traceRecords)
            }
            usage.add("STABLE_CONTEXT=$tokens (cached)")
            logger.debug { "[CONTEXT_BUDGET] Using cached STABLE layer: $tokens tokens" }
        } else {
            val stableParts = mutableListOf<String>()

            // TIER 1: ESSENTIAL CONTEXT
            val projectContextParts = mutableListOf<String>()
            projectContextParts.add(formatter.buildCompactProjectOverview(context))
            projectContextParts.add(formatter.buildCurrentTaskSection(context))
            if (context.userRequirements.isNotEmpty()) {
                projectContextParts.add(formatter.buildUserRequirementsSection(context))
            }
            val projectContent = projectContextParts.joinToString("\n\n")
            stableParts.add(projectContent)
            addSection(ContextSection.PROJECT_CONTEXT, projectContent)

            // Project instructions (AGENTS.md, .refio/agent.md, .refio/rules/)
            if (!context.projectInstructions.isNullOrBlank()) {
                val instructionsContent = formatter.buildProjectInstructionsSection(context)
                stableParts.add(instructionsContent)
                addSection(ContextSection.PROJECT_INSTRUCTIONS, instructionsContent)
            }

            // TIER 4: REFERENCE CONTEXT (stable)
            val referenceParts = mutableListOf<String>()

            // Semantic project summary (architecture, key components, patterns, navigation)
            if (!context.semanticSummary.isNullOrBlank()) {
                referenceParts.add(context.semanticSummary)
            }

            if (context.subtasks.isNotEmpty()) {
                referenceParts.add(formatter.buildSubtasksStatusSection(context))
            }
            if (context.keyComponents.isNotEmpty() && !context.semanticSummary.orEmpty().contains("<KEY_COMPONENTS>")) {
                referenceParts.add(formatter.buildKeyComponentsSection(context))
            }
            formatter.buildDependenciesSection(context)?.let { referenceParts.add(it) }

            // Language-specific analysis sections (only for projects that use these languages)
            formatter.buildTypeScriptAnalysisSection(context)?.let { referenceParts.add(it) }
            formatter.buildHtmlAnalysisSection(context)?.let { referenceParts.add(it) }
            formatter.buildCssAnalysisSection(context)?.let { referenceParts.add(it) }

            if (referenceParts.isNotEmpty()) {
                val referenceContent = referenceParts.joinToString("\n\n")
                stableParts.add(referenceContent)
                addSection(ContextSection.REFERENCE, referenceContent)
            }

            // Cache stable layer for reuse across turns (including per-section usage and trace records)
            if (runScope != null) {
                val stableContent = "<STABLE_CONTEXT>\n${stableParts.joinToString("\n\n")}\n</STABLE_CONTEXT>"
                val stableTokens = ContextTokenEstimator.estimateTokens(stableContent, modelId)
                contextLayerCache.putStableContext(
                    taskId = runScope,
                    content = stableContent,
                    tokensUsed = stableTokens,
                    sectionUsage = actualUsage.toMap(),
                    traceRecords = traceRecords.toList()
                )
            }
        }

        // === EARLY REDISTRIBUTION (Bug 2C fix) ===
        // Previously `redistributeUnused` was called AFTER WORKING_MEMORY and RECENT_WORK
        // had already been added, which meant:
        //   - RECENT_WORK never benefited from unused budget from STABLE_CONTEXT /
        //     PROJECT_CONTEXT / REFERENCE sections (they often leave 10–30k tokens on
        //     the table because project context is small relative to total budget)
        //   - The redistribution flowed only into CONVERSATION / USER_CONTEXT,
        //     which in turn couldn't use it because of other caps (Bug 2B).
        //
        // New flow: redistribute the unused stable-layer budget BEFORE adding the
        // accumulated layer, so WORKING_MEMORY and RECENT_WORK see an expanded
        // budget. The redistribution function targets these two sections (see
        // `ContextBudget.redistributeUnused` priority list) so this is a natural fit.
        val budgetAfterStable = budget.redistributeUnused(actualUsage)

        // === ACCUMULATED CONTEXT LAYER (grows across turns) ===
        // TIER 1.5: WORKING MEMORY
        // Bug 2D fix: drop the `remainingTokens / 4` throttle. Previously this hard-
        // capped WORKING_MEMORY at a quarter of whatever was left even if its own
        // section budget was much larger, silently starving the section on large
        // context windows. The section has its own per-entry head+tail truncation
        // (fitLineWithHeadTailTruncation) so it cannot over-use its allotment.
        //
        // Duplication fix: pass the set of subtaskIds that will also be rendered in
        // RECENT_WORK so WORKING_MEMORY can suppress their outputExcerpt. Before this
        // the same tool-call head appeared in both sections, wasting tokens and
        // confusing the model.
        if (runScope != null && workingMemoryService != null) {
            val workingBudget = minOf(
                budgetAfterStable.budgetFor(ContextSection.WORKING_MEMORY),
                remainingTokens
            )
            if (workingBudget > 0) {
                val recentWorkSubtaskIds = context.executedSteps.map { it.subtaskId }.toSet()
                val workingMemory = workingMemoryService.buildWorkingMemorySection(
                    taskId = runScope,
                    maxTokens = workingBudget,
                    skipExcerptForOriginIds = recentWorkSubtaskIds
                )
                addSection(ContextSection.WORKING_MEMORY, workingMemory, workingBudget, workStateTarget)
            }
        }

        // TIER 2: WORK CONTEXT
        if (context.completedFiles.isNotEmpty() || context.executedSteps.isNotEmpty()) {
            val recentBudget = minOf(
                budgetAfterStable.budgetFor(ContextSection.RECENT_WORK),
                remainingTokens
            )
            val recentBudgetWithBuffer = recentBudget + RECENT_WORK_LAST_ENTRY_TOKEN_BUFFER
            addSection(
                ContextSection.RECENT_WORK,
                formatter.buildRecentWorkSection(context, recentBudgetWithBuffer),
                recentBudgetWithBuffer,
                workStateTarget
            )
        }

        // === EPHEMERAL CONTEXT LAYER (rebuilt every turn) ===
        val userContextParts = mutableListOf<String>()
        if (context.userContextRefs.isNotEmpty()) {
            userContextParts.add(formatter.buildUserContextSection(context))
        }
        if (context.mcpResources.isNotEmpty()) {
            userContextParts.add(formatter.buildMcpResourcesSection(context))
        }
        addSection(ContextSection.USER_CONTEXT, userContextParts.joinToString("\n\n"))
        // Second redistribution pass: now that accumulated + ephemeral layers have
        // reported their actual usage, any budget still unused is pushed into
        // CONVERSATION so they can benefit from slack.
        val redistributedBudget = budgetAfterStable.redistributeUnused(actualUsage)

        if (!trailingState && context.conversationHistory.isNotEmpty()) {
            val conversationBudget = minOf(redistributedBudget.budgetFor(ContextSection.CONVERSATION), remainingTokens)
            addSection(
                ContextSection.CONVERSATION,
                formatter.buildCompressedConversationSection(context, conversationBudget),
                conversationBudget
            )
        }

        val contextPrompt = parts.joinToString("\n\n")
        val stateText = stateParts.joinToString("\n\n")

        val baseUsed = budget.totalTokens - remainingTokens
        val overflowUsed = overflowTokensInitial - overflowTokensRemaining
        logger.info {
            "[CONTEXT_BUDGET] total=${budget.totalTokens}, used=${baseUsed + overflowUsed}, " +
                    "remaining=$remainingTokens, overflow_remaining=$overflowTokensRemaining, sections=${
                        usage.joinToString(
                            ", "
                        )
                    }"
        }

        val contextTrace = pl.jclab.refio.core.services.turn.ContextDecisionTrace(
            sections = traceRecords,
            totalBudget = budget.totalTokens,
            totalUsed = baseUsed + overflowUsed
        )
        lastContextTrace = contextTrace

        // Parse XML tags from the built prompt for granular section token breakdown
        val sectionTokens = PromptSectionTokenReport.parsePromptSectionTokens(
            if (stateText.isBlank()) contextPrompt else "$contextPrompt\n\n$stateText"
        )
        lastSectionTokens = sectionTokens

        return LLMContextParts(
            prompt = contextPrompt,
            trailingState = stateText,
            contextTrace = contextTrace,
            sectionTokens = sectionTokens,
            conversationDropped = formatter.lastConversationDropped,
            stepsDropped = formatter.lastStepsDropped
        )
    }

    // ===========================
    // AGENT TURN LOOP INTEGRATION
    // ===========================

    /**
     * Build messages list for AgentTurnLoop.
     *
     * This method provides a simplified interface for AgentTurnLoop:
     * - Loads and filters conversation history
     * - Uses summarized/compact tool results in conversation history
     * - Resolves user context references
     * - Returns ready-to-use LLMMessage list
     *
     * @param taskId Task ID
     * @param projectRoot Project root path
     * @param userContextRefs User-provided @ mentions
     * @param query Current user query
     * @return Pair of (projectContextPrompt, messages list)
     */
    suspend fun buildAgentTurnMessages(
        taskId: String,
        projectRoot: Path,
        userContextRefs: List<ContextReference> = emptyList(),
        query: String? = null,
        /**
         * Estimated tokens consumed by the static system prompt prefix (system markdown +
         * tool descriptions + response contract + provider sections). Passed through to the
         * context budget resolver so dynamic sections fit alongside the prefix inside the
         * model's context window. Default 0 keeps callers that don't track the prefix size
         * (CLI standalone bootstrap, legacy tests) working without behavior change.
         */
        staticPrefixTokens: Int = 0,
        /**
         * When false, skip building the project-context prompt (the expensive second
         * [buildProjectContext] call) and return an empty `projectContextPrompt`. The context
         * panel's runtime-prompt preview consumes only `.messages` and discards the prompt, so
         * for that path the rebuild is pure waste — it ran a full project-context build on every
         * UI refresh, doubling the builds per `getProjectContext`. The real turn loop
         * (TurnPromptBuilder) keeps the default `true` and is unaffected.
         */
        includeProjectContext: Boolean = true,
        /**
         * Resolved model id for model-aware token estimation of section budgets.
         * Default null keeps the flat-base ratio for callers without model context.
         */
        modelId: String? = null,
        /**
         * Invocation id selecting which conversation thread to load. Null loads the main (parent)
         * thread and excludes every subagent's intermediate steps; a subagent's own id loads only
         * that subagent's rows. Default null keeps callers that render the parent conversation
         * (context-panel preview, standalone bootstrap) unchanged.
         */
        agentInstanceId: String? = null,
        /**
         * When false, never compact the conversation for this build. Compaction spends a real
         * weak-model call and persists a SYSTEM summary row into the user's conversation, so a
         * read-only render of "what would be sent" (the context panel's runtime-prompt preview)
         * must not trigger it - otherwise an idle user refreshing that panel pays for summaries
         * and finds them appearing in a conversation nobody advanced. The turn loop keeps the
         * default `true`.
         */
        allowSummarization: Boolean = true,
        /**
         * True for [ConfigKeys.AGENT_PROMPT_LAYOUT] = trailing: the work state comes back in
         * [AgentTurnMessagesResult.trailingState] instead of [AgentTurnMessagesResult.projectContextPrompt],
         * and the duplicated CONVERSATION_HISTORY section is not built (see [buildLLMContext]).
         */
        trailingState: Boolean = false,
        /** The run's conversation when the caller has already read it this iteration; null reads it here. */
        history: List<ChatMessage>? = null,
    ): AgentTurnMessagesResult {
        logger.info {
            "[AGENT_TURN] Building messages for task=$taskId, contextRefs=${userContextRefs.size}, " +
                "staticPrefixTokens=$staticPrefixTokens"
        }

        // 1. Load conversation history with filtering
        val taskMode = transaction { taskRepository.findById(taskId)?.mode }
        val modelOperation = taskMode?.let { ModelOperation.fromTaskMode(it) }
        val budget = configService.getContextBudget(taskId, modelOperation, staticPrefixTokens)
        val conversationBudget = budget.budgetFor(ContextSection.CONVERSATION)

        // Load only the caller's own thread: the parent run (null id) never sees a subagent's
        // intermediate steps, and a subagent never sees the parent conversation.
        val allMessages = history
            ?: transaction { chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId) }
        val summarizedMessages = if (conversationSummaryService != null && conversationBudget > 0 && allowSummarization) {
            // Pass the same resolver that convertChatMessageToLLMMessage uses below, so the
            // summarizer's token estimate reflects the rendered prompt (TOOL bodies truncated
            // to 1024 chars when not summarized) instead of raw stored content. Without this,
            // one large `read_file` tool result can fake a 24k-token conversation and trigger
            // premature summarization after 2-3 turns.
            // Fail-soft: the summarizer calls a weak model over the network, and an outage there
            // (provider down, no-egress, rate limit) used to propagate out of buildAgentTurnMessages.
            // TurnPromptBuilder catches that and falls back to raw message building, so a failure to
            // COMPACT the history silently cost the turn its entire project context. Running on the
            // uncompacted history is the strictly better failure.
            try {
                conversationSummaryService.ensureSummaryIfNeeded(
                    taskId = taskId,
                    messages = allMessages,
                    maxTokens = conversationBudget,
                    contentResolver = { msg ->
                        if (msg.role == MessageRole.TOOL) resolveToolConversationContent(msg) else msg.content
                    },
                    agentInstanceId = agentInstanceId
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) {
                    "[AGENT_TURN] Conversation compaction failed for task=$taskId, continuing on the " +
                        "uncompacted history: ${e.message}"
                }
                allMessages
            }
        } else {
            allMessages
        }

        val historyFromSummary = conversationContextBuilder.sliceConversationHistoryFromLastSummary(summarizedMessages)
        val filteredHistory = conversationContextBuilder.filterMeaningfulConversation(historyFromSummary)

        // 2. Convert to LLMMessage list. Build tool-name lookup from the full
        // (pre-filter) history so TOOL results can reference their originating
        // assistant tool call by name even after filtering dropped some rows.
        val toolNameByCallId = conversationContextBuilder.buildToolNameByCallId(allMessages)
        val messages = filteredHistory.mapNotNull { msg ->
            conversationContextBuilder.convertChatMessageToLLMMessage(
                msg,
                ::resolveToolConversationContent,
                toolNameByCallId
            )
        }

        // 4. Build project context (with user context refs). Skipped when the caller only needs
        // the message list (the context-panel preview discards projectContextPrompt) — this
        // avoids a redundant full buildProjectContext on every UI refresh.
        val contextParts = if (!includeProjectContext) {
            LLMContextParts(prompt = "", trailingState = "")
        } else try {
            val projectContext = buildProjectContext(
                projectRoot = projectRoot,
                taskId = taskId,
                query = query,
                userContextRefs = userContextRefs,
                agentInstanceId = agentInstanceId,
                // The whole thread as it stands after any compaction above: the summarizer hands
                // back a fresh read when it added a summary, the same list otherwise.
                threadMessages = summarizedMessages
            )
            buildLLMContext(projectContext, staticPrefixTokens, modelId, trailingState)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[AGENT_TURN] Failed to build project context: ${e.message}" }
            LLMContextParts(prompt = "", trailingState = "")  // Continue without project context if it fails
        }

        logger.info {
            "[AGENT_TURN] Built ${messages.size} messages, contextLength=${contextParts.prompt.length}, " +
                "trailingStateLength=${contextParts.trailingState.length}"
        }

        return AgentTurnMessagesResult(
            messages = messages,
            projectContextPrompt = contextParts.prompt,
            historySize = filteredHistory.size,
            trailingState = contextParts.trailingState,
            contextTrace = contextParts.contextTrace,
            sectionTokens = contextParts.sectionTokens,
            conversationDropped = contextParts.conversationDropped,
            stepsDropped = contextParts.stepsDropped
        )
    }

    private fun resolveToolConversationContent(
        msg: ChatMessage
    ): String {
        val preferred = msg.content.takeIf { it.isNotBlank() }
            ?: msg.rawOutput?.takeIf { it.isNotBlank() }
            ?: "(empty tool result)"

        // The summarizer pipeline (ToolResultSummarizer + TurnToolExecutor) is the
        // single point that decides what a tool result looks like in conversation
        // history. If msg.isSummarized is true, msg.content already IS the canonical
        // summary chosen for that tool / context type — we trust it as-is here, no
        // ad-hoc truncation. Conversation budget pressure is handled separately by
        // the context budget allocator (which can drop or compact whole entries),
        // not by silently mangling the tail of an individual tool result.
        //
        // For non-summarized messages we still apply a tight cap, because those are
        // either tiny by definition or fall through from a path that did not run the
        // summarizer at all and we cannot inflate the budget unboundedly.
        val base = if (msg.isSummarized) {
            preferred
        } else {
            ToolOutputFormatting.truncate(preferred, 1024)
        }
        // Even for "summarized" tool messages the body may contain a fenced ```diff
        // block carrying the entire generated file (advance_code_editing pure-create).
        // RECENT_WORK already applies DiffCompressor; without this the same diff also
        // ships verbatim through the conversation messages path, doubling the cost on
        // every follow-up turn after a write tool. The compressor is a no-op when
        // there is no diff fence.
        return DiffCompressor.compress(base, msg.subtaskId)
    }

    /**
     * Collect all user context references from task history.
     * Used by AgentTurnLoop to gather @ mentions from previous messages.
     */
    fun collectAllUserContextRefs(taskId: String): List<ContextReference> {
        return contextReferenceResolver.collectAllUserContextRefs(taskId)
    }

    /**
     * Generate compact project summary optimized for small LLMs.
     */
    fun buildCompactProjectSummary(
        projectAnalysis: ProjectAnalysis,
        richReport: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?,
        maxTokens: Int = 4000
    ): String {
        return projectContextSummarizer.buildCompactProjectSummary(projectAnalysis, richReport, maxTokens)
    }

    private fun loadProjectInstructions(projectRoot: Path): String? {
        val loaded = projectInstructionsLoader.load(projectRoot)
        if (loaded.isEmpty) return null

        val parts = mutableListOf<String>()

        // Instruction files (AGENTS.md, .refio/agent.md)
        for (inst in loaded.instructions) {
            parts.add("## ${inst.source}\n${inst.content}")
        }

        // Conditional rules that matched
        for (rule in loaded.rules) {
            val header = if (rule.description.isNotBlank()) "## Rule: ${rule.name} (${rule.description})" else "## Rule: ${rule.name}"
            parts.add("$header\n${rule.content}")
        }

        return parts.joinToString("\n\n")
    }

    // ===========================
    // USER CONTEXT RESOLUTION — delegated to ContextReferenceResolver
    // ===========================

    /**
     * Format resolved context references as string for LLM prompt.
     *
     * @param refs Resolved context references (with content loaded)
     * @return Formatted string with headers and content
     */
    fun formatContextReferencesForLLM(refs: List<ContextReference>): String {
        return contextReferenceResolver.formatContextReferencesForLLM(refs)
    }


    /**
     * Calculate token usage per context section for visualization.
     * Estimates tokens as chars / 4 (rough approximation).
     *
     * @param context ProjectContextDTO with all context data
     * @param llmPrompt Generated LLM prompt string
     * @return Map of section key to token info
     */
    /**
     * Calculate context section tokens by parsing the actual generated LLM prompt.
     *
     * Only sections explicitly present in the prompt are counted.
     *
     * @param _context Project context DTO (kept for API compatibility)
     * @param llmPrompt The actual LLM prompt generated by buildLLMContextPrompt()
     * @return Map of section key to token info (chars, tokens, percentage)
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateContextSectionTokens(
        _context: ProjectContextDTO,
        llmPrompt: String
    ): Map<String, ContextSectionTokenInfo> {
        return PromptSectionTokenReport.parsePromptSectionTokens(llmPrompt)
    }

    companion object {
        /**
         * Convert legacy string-based context references to ContextReference DTOs.
         *
         * Supports two formats:
         * 1. Legacy format: "@file:path", "@folder:path", "@selection:content", "@docs:url", "@open", "@rules"
         * 2. Provider format: "@providerId:query" (e.g., "@codebase:search query")
         *
         * This is the SINGLE SOURCE OF TRUTH for string-to-ContextReference conversion.
         * Previously duplicated in ChatService and PlanningService.
         *
         * @param refs List of string-based context references
         * @return List of ContextReference DTOs
         */
        fun convertStringRefsToContextReferences(refs: List<String>): List<ContextReference> {
            return refs.mapNotNull { ref ->
                val trimmed = ref.trim()
                if (!trimmed.startsWith("@")) {
                    logger.warn { "[CONTEXT] Invalid context reference format (missing @): $trimmed" }
                    return@mapNotNull null
                }

                // Remove @ prefix
                val refWithoutAt = trimmed.removePrefix("@")

                // Check for legacy formats first
                when {
                    refWithoutAt.startsWith("file:") -> {
                        val path = refWithoutAt.removePrefix("file:").trim()
                        ContextReference.file(
                            path = path,
                            displayName = path.substringAfterLast('/')
                        )
                    }

                    refWithoutAt.startsWith("folder:") -> {
                        val path = refWithoutAt.removePrefix("folder:").trim()
                        ContextReference.folder(path = path)
                    }

                    refWithoutAt.startsWith("selection:") -> {
                        val content = refWithoutAt.removePrefix("selection:").trim()
                        ContextReference.selection(
                            content = content,
                            fileName = "selection"
                        )
                    }

                    refWithoutAt == "open" ||
                            refWithoutAt == "open_file" ||
                            refWithoutAt == "open_files" ||
                            refWithoutAt.startsWith("open:") ||
                            refWithoutAt.startsWith("open_file:") ||
                            refWithoutAt.startsWith("open_files:") -> {
                        ContextReference.openFiles()
                    }

                    refWithoutAt.startsWith("docs:") -> {
                        val url = refWithoutAt.removePrefix("docs:").trim()
                        ContextReference.docs(url = url)
                    }

                    refWithoutAt == "rules" || refWithoutAt.startsWith("rules:") -> {
                        val path = if (refWithoutAt.contains(":")) {
                            refWithoutAt.removePrefix("rules:").trim()
                        } else {
                            "Agents.md"
                        }
                        ContextReference.rules(path = path)
                    }
                    // Check if this is a registered provider
                    else -> {
                        // Try to parse as "@providerId:query" or "@providerId"
                        val colonIndex = refWithoutAt.indexOf(':')
                        val providerId = if (colonIndex > 0) {
                            refWithoutAt.substring(0, colonIndex)
                        } else {
                            refWithoutAt
                        }
                        val query = if (colonIndex > 0) {
                            refWithoutAt.substring(colonIndex + 1).trim()
                        } else {
                            ""
                        }

                        // Check if provider exists
                        val provider = ContextProviderRegistry.getProvider(providerId)
                        if (provider != null) {
                            ContextReference.provider(
                                providerId = providerId,
                                query = query,
                                displayName = provider.description.displayTitle
                            )
                        } else {
                            logger.warn { "[CONTEXT] Unknown context reference format: $trimmed" }
                            null
                        }
                    }
                }
            }
        }
    }
}

/**
 * Result of building agent turn messages.
 * Contains all data needed by AgentTurnLoop to build the LLM prompt.
 */
data class AgentTurnMessagesResult(
    /** Conversation messages ready for LLM (filtered and formatted) */
    val messages: List<LLMMessage>,
    /** Project context prompt (project analysis, user @ mentions) */
    val projectContextPrompt: String,
    /** Size of conversation history before filtering */
    val historySize: Int,
    /** Work state (WORKING_MEMORY, RECENT_WORK) for a trailing harness message; empty in the default layout */
    val trailingState: String = "",
    /** Context decision trace produced by this call (avoids reading shared mutable lastContextTrace) */
    val contextTrace: pl.jclab.refio.core.services.turn.ContextDecisionTrace? = null,
    /** Granular section token breakdown produced by this call */
    val sectionTokens: Map<String, ContextSectionTokenInfo>? = null,
    /** Conversation messages omitted to fit the section budget */
    val conversationDropped: Int = 0,
    /** Older tool steps omitted from RECENT_WORK to fit the section budget */
    val stepsDropped: Int = 0
)

/** Context prompt for the system prompt plus, in the trailing layout, the work state kept out of it. */
data class LLMContextParts(
    val prompt: String,
    val trailingState: String,
    val contextTrace: pl.jclab.refio.core.services.turn.ContextDecisionTrace? = null,
    val sectionTokens: Map<String, ContextSectionTokenInfo>? = null,
    val conversationDropped: Int = 0,
    val stepsDropped: Int = 0
)



