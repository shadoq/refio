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
private const val RECENT_WORK_BUDGET_TIER_1 = 12_000
private const val RECENT_WORK_BUDGET_TIER_2 = 8_000
private const val RECENT_WORK_BUDGET_TIER_3 = 5_000
private const val RECENT_WORK_BUDGET_TIER_4 = 3_500
private const val RECENT_WORK_BUDGET_TIER_5 = 3_000
private const val RECENT_WORK_BUDGET_TIER_6 = 2_500
private const val RECENT_WORK_FULL_LIMIT_TIER_1 = 10
private const val RECENT_WORK_FULL_LIMIT_TIER_2 = 8
private const val RECENT_WORK_FULL_LIMIT_TIER_3 = 6
private const val RECENT_WORK_FULL_LIMIT_TIER_4 = 5
private const val RECENT_WORK_FULL_LIMIT_DEFAULT = 4
private const val RECENT_WORK_DETAILED_LIMIT_TIER_1 = 10
private const val RECENT_WORK_DETAILED_LIMIT_TIER_2 = 8
private const val RECENT_WORK_DETAILED_LIMIT_TIER_3 = 6
private const val RECENT_WORK_DETAILED_LIMIT_TIER_4 = 5
private const val RECENT_WORK_DETAILED_LIMIT_TIER_5 = 4
private const val RECENT_WORK_DETAILED_LIMIT_DEFAULT = 3

// CONVERSATION_HISTORY limits
private const val DEFAULT_CONVERSATION_HISTORY_LIMIT = 100
private const val CONTEXT_CONVERSATION_HISTORY_LIMIT = DEFAULT_CONVERSATION_HISTORY_LIMIT
private const val CONTEXT_INCLUDE_CONVERSATION_HISTORY = true
private const val CONVERSATION_BUDGET_TIER_HIGH = 5_000
private const val CONVERSATION_BUDGET_TIER_MEDIUM = 3_500
private const val CONVERSATION_BUDGET_TIER_LOW = 2_000
private const val CONVERSATION_MAX_MESSAGES_HIGH = 100
private const val CONVERSATION_MAX_MESSAGES_MEDIUM = 75
private const val CONVERSATION_MAX_MESSAGES_LOW = 50
private const val CONVERSATION_MAX_MESSAGES_DEFAULT = 25
private const val CONVERSATION_MIN_PER_MESSAGE_TOKENS = 128

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
    var lastContextTrace: pl.jclab.refio.core.services.turn.ContextDecisionTrace? = null
        private set

    /**
     * Last granular section token breakdown from buildLLMContextPrompt().
     * Parsed from XML tags in the generated prompt — maps UI-friendly keys
     * (e.g. "recent_work", "key_components") to token info.
     * Used by AgentTurnLoop to populate PromptSnapshot.sectionTokens.
     */
    var lastSectionTokens: Map<String, ContextSectionTokenInfo>? = null
        private set

    /**
     * Configuration for RECENT_WORK section generation.
     * Refaktoryzacja Context Service.
     */
    private data class RecentWorkConfig(
        val fullDataLimit: Int = 2,           // Ile ostatnich narzędzi ma pełne dane
        val detailedMaxLength: Int = 800,     // Max długość szczegółowego skrótu
        val summaryMaxLength: Int = 300,      // Max długość podsumowania
        val includeMetadata: Boolean = true   // Czy dołączać metadane plików
    )

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
        userContextRefs: List<ContextReference> = emptyList()
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

        // 4. Get subtasks
        val subtasks = transaction { subtaskRepository.findByTaskId(taskId) }

        // 4a. Lazily rebuild WORKING_MEMORY from persisted subtasks. Working memory lives
        // in-memory only, so after a plugin/app restart (or when the user reopens an old
        // conversation) the in-memory map is empty even though the underlying data —
        // subtask params + result — is still in the database. hasEntries() guards against
        // re-running the extraction on every turn within a live session.
        workingMemoryService?.rebuildFromSubtasks(taskId = taskId, subtasks = subtasks)

        // 4. Get conversation history (if requested)
        val rawConversationHistory = if (CONTEXT_INCLUDE_CONVERSATION_HISTORY) {
            val allMessages = transaction { chatMessageRepository.findByTaskId(taskId) }
            conversationContextBuilder.sliceConversationHistoryFromLastSummary(allMessages)
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
        val completedFiles = taskContextExtractor.buildCompletedFiles(subtasks)

        // 6a. Build structured executed steps for RECENT_WORK
        val executedSteps = taskContextExtractor.buildExecutedSteps(subtasks)

        // 7. Extract user requirements from task description
        val userRequirements = taskContextExtractor.extractUserRequirements(task.name)

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
            name = task.name,
            description = task.name,  // Task doesn't have separate description field
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

        val mcpResources = mcpContextLoader.loadMcpResources(projectRoot, query)

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
    fun buildLLMContextPrompt(context: ProjectContextDTO, staticPrefixTokens: Int = 0, modelId: String? = null): String {
        val budget = pruner.resolveContextBudget(context, modelOperation = null, staticPrefixTokens = staticPrefixTokens)
        val parts = mutableListOf<String>()
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

        fun addSection(section: ContextSection, content: String, maxTokens: Int = budget.budgetFor(section)) {
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

            parts.add(sectionContent)
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

        // === STABLE CONTEXT LAYER (cached, invalidated on project file change) ===
        val stableCacheHit = taskId?.let { contextLayerCache.getStableContext(it) }
        if (stableCacheHit != null) {
            parts.add(stableCacheHit.content)
            val tokens = stableCacheHit.tokensUsed
            remainingTokens = (remainingTokens - tokens).coerceAtLeast(0)
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

            // Cache stable layer for reuse across turns
            if (taskId != null) {
                val stableContent = "<STABLE_CONTEXT>\n${stableParts.joinToString("\n\n")}\n</STABLE_CONTEXT>"
                val stableTokens = ContextTokenEstimator.estimateTokens(stableContent, modelId)
                contextLayerCache.putStableContext(taskId, stableContent, stableTokens)
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
        if (taskId != null && workingMemoryService != null) {
            val workingBudget = minOf(
                budgetAfterStable.budgetFor(ContextSection.WORKING_MEMORY),
                remainingTokens
            )
            if (workingBudget > 0) {
                val recentWorkSubtaskIds = context.executedSteps.map { it.subtaskId }.toSet()
                val workingMemory = workingMemoryService.buildWorkingMemorySection(
                    taskId = taskId,
                    maxTokens = workingBudget,
                    skipExcerptForOriginIds = recentWorkSubtaskIds
                )
                addSection(ContextSection.WORKING_MEMORY, workingMemory, workingBudget)
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
                recentBudgetWithBuffer
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

        if (context.conversationHistory.isNotEmpty()) {
            val conversationBudget = minOf(redistributedBudget.budgetFor(ContextSection.CONVERSATION), remainingTokens)
            addSection(
                ContextSection.CONVERSATION,
                formatter.buildCompressedConversationSection(context, conversationBudget),
                conversationBudget
            )
        }

        val contextPrompt = parts.joinToString("\n\n")

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

        lastContextTrace = pl.jclab.refio.core.services.turn.ContextDecisionTrace(
            sections = traceRecords,
            totalBudget = budget.totalTokens,
            totalUsed = baseUsed + overflowUsed
        )

        // Parse XML tags from the built prompt for granular section token breakdown
        lastSectionTokens = PromptSectionTokenReport.parsePromptSectionTokens(contextPrompt)

        return contextPrompt
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
        val allMessages = transaction { chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId) }
        val summarizedMessages = if (conversationSummaryService != null && conversationBudget > 0) {
            // Pass the same resolver that convertChatMessageToLLMMessage uses below, so the
            // summarizer's token estimate reflects the rendered prompt (TOOL bodies truncated
            // to 1024 chars when not summarized) instead of raw stored content. Without this,
            // one large `read_file` tool result can fake a 24k-token conversation and trigger
            // premature summarization after 2-3 turns.
            conversationSummaryService.ensureSummaryIfNeeded(
                taskId = taskId,
                messages = allMessages,
                maxTokens = conversationBudget,
                contentResolver = { msg ->
                    if (msg.role == MessageRole.TOOL) resolveToolConversationContent(msg) else msg.content
                },
                agentInstanceId = agentInstanceId
            )
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
        val projectContextPrompt = if (!includeProjectContext) {
            ""
        } else try {
            val projectContext = buildProjectContext(
                projectRoot = projectRoot,
                taskId = taskId,
                query = query,
                userContextRefs = userContextRefs
            )
            buildLLMContextPrompt(projectContext, staticPrefixTokens, modelId)
        } catch (e: Exception) {
            logger.warn(e) { "[AGENT_TURN] Failed to build project context: ${e.message}" }
            ""  // Continue without project context if it fails
        }

        logger.info { "[AGENT_TURN] Built ${messages.size} messages, contextLength=${projectContextPrompt.length}" }

        return AgentTurnMessagesResult(
            messages = messages,
            projectContextPrompt = projectContextPrompt,
            historySize = filteredHistory.size
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

    /**
     * Build user-provided context section (from @ mentions)
     */
    private fun buildUserContextSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<USER_PROVIDED_CONTEXT>")
        parts.add("The user has provided the following additional context:")
        parts.add("")

        context.userContextRefs.forEach { ref ->
            val header = when {
                ref.providerId == "file" -> "File: ${ref.path}"
                ref.providerId == "selection" -> "Selection: ${ref.displayName}"
                ref.providerId != null -> "${ref.providerId}: ${ref.displayName}"
                else -> ref.displayName
            }

            parts.add("--- $header ---")
            parts.add(ref.content)
            parts.add("")
        }

        parts.add("</USER_PROVIDED_CONTEXT>")
        return parts.joinToString("\n")
    }

    private fun buildFrameworkAnalysisSection(context: ProjectContextDTO): String {
        val fa = context.frameworkAnalysis ?: return ""
        if (fa.frameworks.isEmpty()) return ""

        val parts = mutableListOf<String>()
        parts.add("<FRAMEWORK_ANALYSIS>")

        // Detected frameworks with conventions
        val detected = fa.frameworks.joinToString(", ") { fw ->
            val conventionSuffix = fa.conventions
                .firstOrNull { it.startsWith(fw.name) }
                ?.substringAfter(": ", "")
                ?.let { " ($it)" } ?: ""
            val versionSuffix = fw.version?.let { " $it" } ?: ""
            "${fw.name}${versionSuffix}${conventionSuffix}"
        }
        parts.add("Detected: $detected")

        // Layers with example files
        if (fa.layers.isNotEmpty()) {
            parts.add("Layers:")
            for (layer in fa.layers) {
                val examples = layer.exampleFiles.take(3).joinToString(", ") {
                    it.substringAfterLast("/")
                }
                parts.add("- ${layer.name}: $examples")
            }
        }

        // Endpoints (if any)
        if (fa.endpoints.isNotEmpty()) {
            parts.add("Endpoints: ${fa.endpoints.take(5).joinToString(", ")}")
        }

        // Config files (if any)
        if (fa.configFiles.isNotEmpty()) {
            parts.add("Config: ${fa.configFiles.take(5).joinToString(", ")}")
        }

        parts.add("</FRAMEWORK_ANALYSIS>")
        return parts.joinToString("\n")
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

    private fun buildProjectInstructionsSection(context: ProjectContextDTO): String {
        return "<PROJECT_INSTRUCTIONS>\n${context.projectInstructions}\n</PROJECT_INSTRUCTIONS>"
    }

    private fun buildDependenciesSection(context: ProjectContextDTO): String? {
        val depLines = mutableListOf<String>()

        fun addDeps(label: String, deps: List<String>) {
            if (deps.isNotEmpty()) {
                val shown = deps.take(15).joinToString(", ")
                val more = if (deps.size > 15) " (+${deps.size - 15} more)" else ""
                depLines.add("$label: $shown$more")
            }
        }

        addDeps("Kotlin/Java", (context.dependencies.kotlin + context.dependencies.java).distinct())
        addDeps("Python", context.dependencies.python)
        addDeps("JavaScript", context.dependencies.javascript)
        addDeps("TypeScript", context.dependencies.typescript.filter { it !in context.dependencies.javascript })
        addDeps("C/C++", context.dependencies.cpp)

        if (depLines.isEmpty()) return null

        if (context.dependencies.packageManagers.isNotEmpty()) {
            depLines.add("Package managers: ${context.dependencies.packageManagers.joinToString(", ")}")
        }

        return "<PROJECT_DEPENDENCIES>\n${depLines.joinToString("\n")}\n</PROJECT_DEPENDENCIES>"
    }

    private fun buildKeyComponentsSection(context: ProjectContextDTO): String {
        return """
            |<KEY_COMPONENTS>
            |${context.keyComponents.joinToString("\n") { "- $it" }}
            |</KEY_COMPONENTS>
        """.trimMargin()
    }

    private fun buildRecentWorkConfig(): RecentWorkConfig {
        val summaryMax = configService.getTyped(ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH)
        val detailedMax = (summaryMax * 5).coerceAtLeast(1024)

        return RecentWorkConfig(
            fullDataLimit = configService.getTyped(ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT),
            detailedMaxLength = detailedMax,
            summaryMaxLength = summaryMax,
            includeMetadata = true
        )
    }

    private fun calculateFullDataLimit(stepsCount: Int, budgetTokens: Int, baseLimit: Int): Int {
        if (stepsCount <= 0) return 0
        val safeBase = baseLimit.coerceAtLeast(1)
        val budgetLimit = when {
            budgetTokens >= RECENT_WORK_BUDGET_TIER_1 -> RECENT_WORK_FULL_LIMIT_TIER_1
            budgetTokens >= RECENT_WORK_BUDGET_TIER_2 -> RECENT_WORK_FULL_LIMIT_TIER_2
            budgetTokens >= RECENT_WORK_BUDGET_TIER_3 -> RECENT_WORK_FULL_LIMIT_TIER_3
            budgetTokens >= RECENT_WORK_BUDGET_TIER_5 -> RECENT_WORK_FULL_LIMIT_TIER_4
            else -> RECENT_WORK_FULL_LIMIT_DEFAULT
        }
        val effective = minOf(safeBase, budgetLimit)
        return minOf(stepsCount, effective)
    }

    private fun calculateDetailedLimit(budgetTokens: Int): Int = when {
        budgetTokens >= RECENT_WORK_BUDGET_TIER_1 -> RECENT_WORK_DETAILED_LIMIT_TIER_1
        budgetTokens >= RECENT_WORK_BUDGET_TIER_2 -> RECENT_WORK_DETAILED_LIMIT_TIER_2
        budgetTokens >= RECENT_WORK_BUDGET_TIER_3 -> RECENT_WORK_DETAILED_LIMIT_TIER_3
        budgetTokens >= RECENT_WORK_BUDGET_TIER_4 -> RECENT_WORK_DETAILED_LIMIT_TIER_4
        budgetTokens >= RECENT_WORK_BUDGET_TIER_6 -> RECENT_WORK_DETAILED_LIMIT_TIER_5
        else -> RECENT_WORK_DETAILED_LIMIT_DEFAULT
    }

    private fun compressionFallbacks(baseLevel: CompressionLevel): List<CompressionLevel> = when (baseLevel) {
        CompressionLevel.FULL -> listOf(
            CompressionLevel.FULL,
            CompressionLevel.DETAILED,
            CompressionLevel.SUMMARY
        )

        CompressionLevel.DETAILED -> listOf(
            CompressionLevel.DETAILED,
            CompressionLevel.SUMMARY
        )

        CompressionLevel.SUMMARY -> listOf(
            CompressionLevel.SUMMARY
        )
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
    val historySize: Int
)



