package pl.jclab.refio.core.services

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.ContextProviderExtras
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.mcp.*
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.analysis.FileAnalysis
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.context.*
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("ContextService")
private const val MAX_RAG_FRAGMENTS = 15
private const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"

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
 *
 * Based on ADR 0018: Context Building & Visualization System
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
    ragSearchService: RagSearchService? = null,
    ragSearchModel: String? = null,
    ragSearchProvider: String? = null
) {
    private val projectInstructionsLoader = ProjectInstructionsLoader()

    @Volatile
    private var ragSearchServiceRef: RagSearchService? = ragSearchService

    @Volatile
    private var ragSearchModelRef: String? = ragSearchModel

    @Volatile
    private var ragSearchProviderRef: String? = ragSearchProvider

    /**
     * Context layer cache for stable/accumulated context reuse across turns.
     */
    val contextLayerCache = ContextLayerCache()

    private data class McpToolCacheKey(
        val projectId: String,
        val serverId: String,
        val toolName: String,
        val query: String
    )

    private data class McpToolCacheEntry(
        val expiresAtMs: Long,
        val content: String,
        val isError: Boolean
    )

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

    private val mcpToolCache = ConcurrentHashMap<McpToolCacheKey, McpToolCacheEntry>()
    private val mcpToolCacheTtlMs = 30_000L

    constructor(
        projectAnalyzer: ProjectAnalyzerService,
        taskRepository: TaskRepository,
        chatMessageRepository: ChatMessageRepository,
        subtaskRepository: SubtaskRepository,
        fileAnalyzerService: FileAnalyzerService? = null,
        configService: ConfigService,
        ragSearchService: RagSearchService? = null,
        ragSearchModel: String? = null,
        ragSearchProvider: String? = null
    ) : this(
        projectAnalyzer = projectAnalyzer,
        taskRepository = taskRepository,
        chatMessageRepository = chatMessageRepository,
        subtaskRepository = subtaskRepository,
        fileAnalyzerService = fileAnalyzerService,
        configService = configService,
        workingMemoryService = null,
        conversationSummaryService = null,
        ragSearchService = ragSearchService,
        ragSearchModel = ragSearchModel,
        ragSearchProvider = ragSearchProvider
    )

    fun updateRagSearchConfig(service: RagSearchService?, model: String?, provider: String?) {
        ragSearchServiceRef = service
        ragSearchModelRef = model
        ragSearchProviderRef = provider
    }

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
        project: Project? = null,
        query: String? = null,
        userContextRefs: List<ContextReference> = emptyList()
    ): ProjectContextDTO {
        logger.info { "Building project context for task=$taskId" }

        // 1. Get or load project analysis (cached)
        val projectAnalysis = projectAnalyzer.analyzeProject(projectRoot, includeContent = false)

        // 2. Get task info
        val task = transaction { taskRepository.findById(taskId) }
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val dedupedUserContextRefs = deduplicateUserContextRefs(userContextRefs)
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

        // 4. Get conversation history (if requested)
        val rawConversationHistory = if (CONTEXT_INCLUDE_CONVERSATION_HISTORY) {
            val allMessages = transaction { chatMessageRepository.findByTaskId(taskId) }
            sliceConversationHistoryFromLastSummary(allMessages)
                .takeLast(CONTEXT_CONVERSATION_HISTORY_LIMIT * 2)  // Get more, then filter
        } else {
            emptyList()
        }

        // Preserve summary message at the beginning (if any) and filter remaining noise
        val hasLeadingSummary = rawConversationHistory.firstOrNull()?.let { isConversationSummary(it) } == true
        val filteredConversation = filterMeaningfulConversation(rawConversationHistory).let { filtered ->
            if (hasLeadingSummary) {
                val summaryMessage = rawConversationHistory.first()
                val withoutSummary = filtered.filterNot { it.id == summaryMessage.id }
                listOf(summaryMessage) + withoutSummary
            } else {
                filtered
            }
        }.takeLast(CONTEXT_CONVERSATION_HISTORY_LIMIT)

        // 6. Build previous subtasks data (PHASE 3)
        val (previousSubtaskSummaries, completedFiles) = buildPreviousSubtasksData(subtasks)

        // 6a. Build structured executed steps for RECENT_WORK (ADR 0041)
        val executedSteps = buildExecutedSteps(subtasks)

        // 7. Extract user requirements from task description (PHASE 2)
        val userRequirements = extractUserRequirements(task.name)

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

        // 8. Load hybrid RAG fragments (code + documentation)
        val ragFragments = loadRagFragments(
            projectRoot = projectRoot,
            query = query
        )
        val mcpResources = loadMcpResources(projectRoot, query)

        // 9. Resolve user context references (from @ mentions)
        val resolvedUserContext = if (dedupedUserContextRefs.isNotEmpty()) {
            logger.info { "[CONTEXT] Resolving ${dedupedUserContextRefs.size} user context reference(s)" }
            resolveAndConvertUserContextRefs(dedupedUserContextRefs, projectRoot, project, query)
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

            // Work history (from PHASE 3)
            completedFiles = completedFiles,
            previousSubtasks = previousSubtaskSummaries,
            executedSteps = executedSteps,

            // User requirements (extracted from task description - PHASE 2)
            userRequirements = userRequirements,

            // RAG (Retrieval-Augmented Generation) context - unified fragments
            ragFragments = ragFragments,

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

    private suspend fun loadMcpResources(projectRoot: Path, query: String?): List<MCPContextResourceDTO> {
        val projectId = ProjectIdGenerator.generate(projectRoot)
        val serverIds = MCPManager.getConnectedServers(projectId)
        if (serverIds.isEmpty()) {
            return emptyList()
        }

        val configsById = MCPManager.getAllServers(projectId).associateBy { it.id }
        val gson = pl.jclab.refio.core.utils.GsonInstance.gson
        val toolQuery = query?.trim().orEmpty()

        return serverIds.flatMap { serverId ->
            val connection = MCPManager.getConnection(projectId, serverId) ?: return@flatMap emptyList()
            val config = configsById[serverId]
            val outputs = mutableListOf<MCPContextResourceDTO>()

            if (connection.supportsResources()) {
                val resources = runCatching {
                    val cached = connection.getCachedResources()
                    if (cached.isNotEmpty()) cached else connection.refreshResources()
                }.getOrElse { error ->
                    logger.warn(error) { "[CONTEXT] Failed to load MCP resources for $serverId" }
                    emptyList()
                }

                outputs.addAll(
                    resources.map {
                        MCPContextResourceDTO(
                            serverId = serverId,
                            uri = it.uri,
                            name = it.name,
                            description = it.description,
                            mimeType = it.mimeType
                        )
                    }
                )
            } else {
                logger.debug { "[CONTEXT] MCP server $serverId does not support resources capability" }
            }

            val serverConfig = config
            val toolsExposure = serverConfig?.toolsExposureMode ?: MCPToolsExposureMode.TOOLS
            val shouldUseTools = serverConfig?.toolsEnabled == true && toolsExposure == MCPToolsExposureMode.CONTEXT
            if (shouldUseTools && serverConfig != null && connection.supportsTools()) {
                val tools = runCatching {
                    val cached = connection.getCachedTools()
                    if (cached.isNotEmpty()) cached else connection.refreshTools()
                }.getOrElse { error ->
                    logger.warn(error) { "[CONTEXT] Failed to load MCP tools for $serverId" }
                    emptyList()
                }

                val workflowConfig = serverConfig.toolWorkflow?.takeIf { it.steps.isNotEmpty() }
                if (workflowConfig != null) {
                    val workflowResult = MCPToolWorkflowExecutor.execute(
                        workflow = workflowConfig,
                        tools = tools,
                        config = serverConfig,
                        query = toolQuery,
                        gson = gson
                    ) { toolName, arguments ->
                        val cacheKey = McpToolCacheKey(
                            projectId = projectId,
                            serverId = serverId,
                            toolName = toolName,
                            query = toolQuery
                        )
                        val cached = getCachedMcpToolOutput(cacheKey)
                        if (cached != null) {
                            return@execute MCPToolCallResult(cached.content, cached.isError)
                        }

                        val result = runCatching {
                            connection.callTool(toolName, arguments)
                        }.getOrElse { error ->
                            val content = "Failed to execute tool $toolName: ${error.message}"
                            logger.warn(error) { "[CONTEXT] $content" }
                            val entry = McpToolCacheEntry(
                                expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                                content = content,
                                isError = true
                            )
                            putCachedMcpToolOutput(cacheKey, entry)
                            return@execute MCPToolCallResult(content, true)
                        }

                        val content = formatMcpToolResult(result)
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = result.isError
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        MCPToolCallResult(content, result.isError)
                    }

                    workflowResult.steps.forEach { step ->
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${step.toolName}",
                                name = "tool:${step.toolName}",
                                description = step.output,
                                mimeType = "text/plain"
                            )
                        )
                        logger.info { "[CONTEXT] MCP tool context added: server=$serverId tool=${step.toolName}" }
                    }

                    if (workflowResult.error != null) {
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "workflow:error",
                                name = "workflow:error",
                                description = workflowResult.error,
                                mimeType = "text/plain"
                            )
                        )
                    }

                    return@flatMap outputs
                }

                tools.forEach { toolDef ->
                    val cacheKey = McpToolCacheKey(
                        projectId = projectId,
                        serverId = serverId,
                        toolName = toolDef.name,
                        query = toolQuery
                    )
                    val cached = getCachedMcpToolOutput(cacheKey)
                    if (cached != null) {
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = cached.content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val argsResult = MCPToolArgumentResolver.buildArguments(toolQuery, toolDef, serverConfig, gson)
                    if (argsResult.isFailure) {
                        val error = argsResult.exceptionOrNull()
                        val content =
                            "Failed to parse tool arguments for ${toolDef.name}: ${error?.message ?: "unknown error"}"
                        if (error != null) {
                            logger.warn(error) { "[CONTEXT] $content" }
                        } else {
                            logger.warn { "[CONTEXT] $content" }
                        }
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = true
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val arguments = argsResult.getOrNull().orEmpty()
                    val result = runCatching {
                        connection.callTool(toolDef.name, arguments)
                    }.getOrElse { error ->
                        val content = "Failed to execute tool ${toolDef.name}: ${error.message}"
                        logger.warn(error) { "[CONTEXT] $content" }
                        val entry = McpToolCacheEntry(
                            expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                            content = content,
                            isError = true
                        )
                        putCachedMcpToolOutput(cacheKey, entry)
                        outputs.add(
                            MCPContextResourceDTO(
                                serverId = serverId,
                                uri = "tool:${toolDef.name}",
                                name = "tool:${toolDef.name}",
                                description = content,
                                mimeType = "text/plain"
                            )
                        )
                        return@forEach
                    }

                    val content = formatMcpToolResult(result)
                    val entry = McpToolCacheEntry(
                        expiresAtMs = System.currentTimeMillis() + mcpToolCacheTtlMs,
                        content = content,
                        isError = result.isError
                    )
                    putCachedMcpToolOutput(cacheKey, entry)
                    outputs.add(
                        MCPContextResourceDTO(
                            serverId = serverId,
                            uri = "tool:${toolDef.name}",
                            name = "tool:${toolDef.name}",
                            description = content,
                            mimeType = "text/plain"
                        )
                    )
                    logger.info { "[CONTEXT] MCP tool context added: server=$serverId tool=${toolDef.name}" }
                }
            }

            outputs
        }
    }

    private fun getCachedMcpToolOutput(key: McpToolCacheKey): McpToolCacheEntry? {
        val entry = mcpToolCache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            mcpToolCache.remove(key)
            return null
        }
        return entry
    }

    private fun putCachedMcpToolOutput(key: McpToolCacheKey, entry: McpToolCacheEntry) {
        mcpToolCache[key] = entry
    }

    private fun formatMcpToolResult(result: pl.jclab.refio.core.context.mcp.MCPToolResult): String {
        val output = result.content.mapNotNull { it.text }.joinToString("\n").trim()
        if (output.isNotBlank()) {
            return output
        }
        return if (result.isError) {
            "MCP tool returned an error with no content."
        } else {
            "MCP tool executed successfully."
        }
    }

    /**
     * Determines if RAG should be skipped for simple/meta questions.
     * Based on ADR 0017: Refaktoryzacja Context Service.
     *
     * @param query User query
     * @return true if RAG should be skipped, false otherwise
     */
    private fun shouldSkipRag(query: String?): Boolean {
        if (query.isNullOrBlank()) return true

        val queryLower = query.lowercase()

        // Meta questions that don't need code context
        val metaPatterns = listOf(
            "co wiesz", "what do you know",
            "opisz projekt", "describe project", "describe the project",
            "jaki to projekt", "what project", "what is this project",
            "podsumuj", "summarize",
            "struktura", "structure", "project structure",
            "technologie", "technologies", "tech stack",
            "architektura", "architecture",
            "co to za projekt", "what kind of project"
        )

        if (metaPatterns.any { queryLower.contains(it) }) {
            logger.info { "[CONTEXT] Skipping RAG - meta question detected: ${query.take(100)}" }
            return true
        }

        // Short questions are usually meta (unless they mention code/file)
        if (query.length < 30 &&
            !queryLower.contains("kod") &&
            !queryLower.contains("code") &&
            !queryLower.contains("file") &&
            !queryLower.contains("plik") &&
            !queryLower.contains("function") &&
            !queryLower.contains("funkcja") &&
            !queryLower.contains("class") &&
            !queryLower.contains("klasa")
        ) {
            logger.info { "[CONTEXT] Skipping RAG - short meta question: ${query}" }
            return true
        }

        return false
    }

    private suspend fun loadRagFragments(
        projectRoot: Path,
        query: String?,
    ): List<CodeFragmentDTO> {
        val searchService = ragSearchServiceRef ?: run {
            logger.info { "[CONTEXT] RAG search service not configured - skipping fragments" }
            return emptyList()
        }
        val model = ragSearchModelRef ?: run {
            logger.warn { "[CONTEXT] RAG search model is not configured - skipping fragments" }
            return emptyList()
        }
        if (ragSearchProviderRef.equals("ollama", ignoreCase = true)) {
            val endpoint = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
            val providerKey = "ollama:$endpoint"
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                val retryMs = EmbeddingCircuitBreaker.getCooldownRemaining(providerKey)
                logger.warn { "[CONTEXT] Skipping RAG fragments - Ollama unavailable (circuit OPEN, retry in ${retryMs}ms, endpoint=$endpoint)" }
                return emptyList()
            }
        }

        // Skip RAG for simple/meta questions (ADR 0017)
        if (shouldSkipRag(query)) {
            return emptyList()
        }

        val queryParts = listOfNotNull(query?.trim())
            .filter { it.isNotBlank() }
        if (queryParts.isEmpty()) {
            logger.info { "[CONTEXT] No RAG query data provided - skipping fragments" }
            return emptyList()
        }

        val combinedQuery = queryParts.joinToString("\n\n")
        val keywords = extractRagKeywords(queryParts)
        val hybridEnabled = configService.getTyped(ConfigKeys.RAG_SEARCH_HYBRID_ENABLED)

        return try {
            logger.info {
                "[CONTEXT] Running hybrid RAG search: query='${combinedQuery.take(120)}...', keywords=$keywords"
            }
            val config = RagSearchConfig(
                similarityThreshold = configService.getTyped(ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD),
                topK = MAX_RAG_FRAGMENTS,
                hybridSearch = hybridEnabled,
                keywords = keywords,
                semanticWeight = configService.getTyped(ConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT),
                includeContextChunks = configService.getTyped(ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)
            )
            val results = searchService.search(
                projectRoot = projectRoot.toString(),
                query = combinedQuery,
                model = model,
                config = config
            )

            val out = results.map { result ->
                CodeFragmentDTO(
                    filePath = result.filePath,
                    content = result.content,
                    startLine = result.startLine,
                    endLine = result.endLine,
                    similarity = result.similarity,
                    contentType = result.contentType.name
                )
            }

            logger.info { "Found the: ${out.size} fragments" }

            out
        } catch (e: Exception) {
            logger.warn(e) { "[CONTEXT] Hybrid RAG search failed (${e.message})" }
            emptyList()
        }
    }

    private fun extractRagKeywords(parts: List<String>): List<String> {
        if (parts.isEmpty()) {
            return emptyList()
        }

        val tokens = parts
            .flatMap { it.split(Regex("[^A-Za-z0-9_/\\\\-]+")) }
            .map { it.trim().lowercase() }
            .filter { it.length >= 4 }
            .map { it.take(64) }

        // Prioritize unique keywords while preserving order
        val seen = mutableSetOf<String>()
        val keywords = mutableListOf<String>()
        for (token in tokens) {
            if (seen.add(token)) {
                keywords.add(token)
            }
            if (keywords.size >= 12) {
                break
            }
        }

        return keywords
    }

    /**
     * Build formatted LLM context prompt from DTO
     * Returns structured prompt with XML-like tags
     *
     * ADR 0040 ORDER (2025-12-03):
     * 1. PROJECT CONTEXT FIRST - Agent must know the project before getting the task
     * 2. TASK & REQUIREMENTS - What needs to be done
     * 3. USER CONTEXT & RAG - Supporting information
     * 4. HISTORY - Previous work and conversation
     */
    /**
     * Build LLM context prompt (REFACTORED - ADR 0017).
     * Organized by TIER priority: Essential → Work → Supplementary → Reference
     */
    fun buildLLMContextPrompt(context: ProjectContextDTO): String {
        val budget = resolveContextBudget(context, modelOperation = null)
        val parts = mutableListOf<String>()
        val usage = mutableListOf<String>()
        val actualUsage = mutableMapOf<ContextSection, Int>()
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
            if (content.isBlank()) return

            val allowedBase = minOf(maxTokens, remainingTokens).coerceAtLeast(0)
            val allowed = (allowedBase + overflowTokensRemaining).coerceAtLeast(0)
            if (allowed <= 0) {
                logger.debug { "[CONTEXT_BUDGET] Skipping ${section.name} - no remaining budget" }
                return
            }

            var sectionContent = content
            var tokens = ContextTokenEstimator.estimateTokens(sectionContent)
            if (tokens <= 0) return

            var truncated = false
            if (tokens > allowed) {
                logger.debug {
                    "[CONTEXT_BUDGET] Section overflow ${section.name}: tokens=$tokens, allowed=$allowed"
                }
                sectionContent = truncateSectionToBudget(sectionContent, allowed)
                tokens = ContextTokenEstimator.estimateTokens(sectionContent)
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
            projectContextParts.add(buildCompactProjectOverview(context))
            projectContextParts.add(buildCurrentTaskSection(context))
            if (context.userRequirements.isNotEmpty()) {
                projectContextParts.add(buildUserRequirementsSection(context))
            }
            val projectContent = projectContextParts.joinToString("\n\n")
            stableParts.add(projectContent)
            addSection(ContextSection.PROJECT_CONTEXT, projectContent)

            // Project instructions (AGENTS.md, .refio/agent.md, .refio/rules/)
            if (!context.projectInstructions.isNullOrBlank()) {
                val instructionsContent = buildProjectInstructionsSection(context)
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
                referenceParts.add(buildSubtasksStatusSection(context))
            }
            if (context.keyComponents.isNotEmpty() && !context.semanticSummary.orEmpty().contains("<KEY_COMPONENTS>")) {
                referenceParts.add(buildKeyComponentsSection(context))
            }
            buildDependenciesSection(context)?.let { referenceParts.add(it) }

            // Language-specific analysis sections (only for projects that use these languages)
            buildTypeScriptAnalysisSection(context)?.let { referenceParts.add(it) }
            buildHtmlAnalysisSection(context)?.let { referenceParts.add(it) }
            buildCssAnalysisSection(context)?.let { referenceParts.add(it) }

            if (referenceParts.isNotEmpty()) {
                val referenceContent = referenceParts.joinToString("\n\n")
                stableParts.add(referenceContent)
                addSection(ContextSection.REFERENCE, referenceContent)
            }

            // Cache stable layer for reuse across turns
            if (taskId != null) {
                val stableContent = "<STABLE_CONTEXT>\n${stableParts.joinToString("\n\n")}\n</STABLE_CONTEXT>"
                val stableTokens = ContextTokenEstimator.estimateTokens(stableContent)
                contextLayerCache.putStableContext(taskId, stableContent, stableTokens)
            }
        }

        // === ACCUMULATED CONTEXT LAYER (grows across turns) ===
        // TIER 1.5: WORKING MEMORY
        if (taskId != null && workingMemoryService != null) {
            val workingBudget = minOf(budget.budgetFor(ContextSection.WORKING_MEMORY), remainingTokens / 4)
            if (workingBudget > 0) {
                val workingMemory = workingMemoryService.buildWorkingMemorySection(taskId, workingBudget)
                addSection(ContextSection.WORKING_MEMORY, workingMemory, workingBudget)
            }
        }

        // TIER 2: WORK CONTEXT
        if (context.completedFiles.isNotEmpty() || context.executedSteps.isNotEmpty()) {
            val recentBudget = minOf(budget.budgetFor(ContextSection.RECENT_WORK), remainingTokens)
            val recentBudgetWithBuffer = recentBudget + RECENT_WORK_LAST_ENTRY_TOKEN_BUFFER
            addSection(
                ContextSection.RECENT_WORK,
                buildRecentWorkSection(context, recentBudgetWithBuffer),
                recentBudgetWithBuffer
            )
        }

        // === EPHEMERAL CONTEXT LAYER (rebuilt every turn) ===
        val userContextParts = mutableListOf<String>()
        if (context.userContextRefs.isNotEmpty()) {
            userContextParts.add(buildUserContextSection(context))
        }
        if (context.mcpResources.isNotEmpty()) {
            userContextParts.add(buildMcpResourcesSection(context))
        }
        addSection(ContextSection.USER_CONTEXT, userContextParts.joinToString("\n\n"))
        val redistributedBudget = budget.redistributeUnused(actualUsage)

        // TIER 3: SUPPLEMENTARY CONTEXT
        if (context.ragFragments.isNotEmpty()) {
            val ragBudget = minOf(redistributedBudget.budgetFor(ContextSection.RAG_FRAGMENTS), remainingTokens)
            addSection(ContextSection.RAG_FRAGMENTS, buildRagFragmentsSection(context), ragBudget)
        }

        if (context.conversationHistory.isNotEmpty()) {
            val conversationBudget = minOf(redistributedBudget.budgetFor(ContextSection.CONVERSATION), remainingTokens)
            addSection(
                ContextSection.CONVERSATION,
                buildCompressedConversationSection(context, conversationBudget),
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

        return contextPrompt
    }

    private fun truncateSectionToBudget(content: String, maxTokens: Int): String {
        if (maxTokens <= 0 || content.isBlank()) return ""

        val wrappedSectionRegex = Regex("""^\s*<([A-Z_]+)>\s*([\s\S]*?)\s*</\1>\s*$""")
        val match = wrappedSectionRegex.matchEntire(content)
        if (match == null) {
            return ContextTokenEstimator.truncateToTokens(content, maxTokens)
        }

        val tag = match.groupValues[1]
        val innerContent = match.groupValues[2].trim()
        val wrapper = "<$tag>\n\n</$tag>"
        val wrapperTokens = ContextTokenEstimator.estimateTokens(wrapper)
        val innerBudget = (maxTokens - wrapperTokens).coerceAtLeast(1)
        val truncatedInner = ContextTokenEstimator.truncateToTokens(innerContent, innerBudget).trim()
        return "<$tag>\n$truncatedInner\n</$tag>"
    }

    private fun resolveContextBudget(
        context: ProjectContextDTO,
        modelOperation: ModelOperation?
    ): ContextBudget {
        val taskId = context.currentTask?.id
        val resolvedOperation = modelOperation ?: resolveModelOperationFromContext(context)
        return configService.getContextBudget(taskId, resolvedOperation)
    }

    private fun resolveModelOperationFromContext(context: ProjectContextDTO): ModelOperation? {
        val mode = context.executionMetadata.agentMode ?: return null
        return runCatching { ModelOperation.fromTaskMode(TaskMode.valueOf(mode)) }.getOrNull()
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
     * @param project IntelliJ Project instance (optional)
     * @param userContextRefs User-provided @ mentions
     * @param query Current user query for RAG
     * @return Pair of (projectContextPrompt, messages list)
     */
    suspend fun buildAgentTurnMessages(
        taskId: String,
        projectRoot: Path,
        project: Project? = null,
        userContextRefs: List<ContextReference> = emptyList(),
        query: String? = null
    ): AgentTurnMessagesResult {
        logger.info { "[AGENT_TURN] Building messages for task=$taskId, contextRefs=${userContextRefs.size}" }

        // 1. Load conversation history with filtering
        val taskMode = transaction { taskRepository.findById(taskId)?.mode }
        val modelOperation = taskMode?.let { ModelOperation.fromTaskMode(it) }
        val budget = configService.getContextBudget(taskId, modelOperation)
        val conversationBudget = budget.budgetFor(ContextSection.CONVERSATION)

        val allMessages = transaction { chatMessageRepository.findByTaskId(taskId) }
        val summarizedMessages = if (conversationSummaryService != null && conversationBudget > 0) {
            conversationSummaryService.ensureSummaryIfNeeded(taskId, allMessages, conversationBudget)
        } else {
            allMessages
        }

        val historyFromSummary = sliceConversationHistoryFromLastSummary(summarizedMessages)
        val filteredHistory = filterMeaningfulConversation(historyFromSummary)

        // 2. Convert to LLMMessage list
        val messages = filteredHistory.mapNotNull { msg ->
            convertChatMessageToLLMMessage(msg)
        }

        // 4. Build project context (with user context refs)
        val projectContextPrompt = try {
            val projectContext = buildProjectContext(
                projectRoot = projectRoot,
                taskId = taskId,
                project = project,
                query = query,
                userContextRefs = userContextRefs
            )
            buildLLMContextPrompt(projectContext)
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

    /**
     * Convert ChatMessage to LLMMessage for AgentTurnLoop.
     * Tool messages always use summarized/compact content.
     */
    private fun convertChatMessageToLLMMessage(
        msg: ChatMessage
    ): LLMMessage? {
        return when (msg.role) {
            MessageRole.USER -> LLMMessage(
                role = "user",
                content = msg.content
            )

            MessageRole.ASSISTANT -> {
                // If assistant has tool calls, append them to content
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
                val summarized = resolveToolConversationContent(
                    msg = msg,
                )
                val content = "[Tool Result for ${msg.toolCallId}]\n$summarized"

                LLMMessage(
                    role = "user",  // Tool results as user messages for compatibility
                    content = content
                )
            }

            MessageRole.SYSTEM -> {
                val isSummaryMessage = isConversationSummary(msg) ||
                    msg.metadata == "compaction" ||
                    msg.content.contains("<conversation_summary>")

                if (isSummaryMessage) {
                    LLMMessage(
                        role = "user",
                        content = "[Conversation summary context]\n${msg.content}"
                    )
                } else {
                    LLMMessage(
                        role = "system",
                        content = msg.content
                    )
                }
            }
        }
    }

    private fun resolveToolConversationContent(
        msg: ChatMessage
    ): String {
        val preferred = msg.content.takeIf { it.isNotBlank() }
            ?: msg.rawOutput?.takeIf { it.isNotBlank() }
            ?: "(empty tool result)"

        // Keep RECENT_WORK untouched; this is only for conversation history/messages.
        // If result was not summarized by tool pipeline, keep a compact fallback summary.
        return if (msg.isSummarized) {
            preferred
        } else {
            truncate(preferred, 320)
        }
    }

    /**
     * Collect all user context references from task history.
     * Used by AgentTurnLoop to gather @ mentions from previous messages.
     */
    fun collectAllUserContextRefs(taskId: String): List<ContextReference> {
        return collectUserContextRefs(taskId)
    }

    /**
     * Generate compact project summary optimized for small LLMs.
     */
    fun buildCompactProjectSummary(
        projectAnalysis: ProjectAnalysis,
        richReport: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?,
        maxTokens: Int = 4000
    ): String {
        val parts = mutableListOf<String>()

        parts.add(buildArchitectureSummary(projectAnalysis, richReport))
        buildKeyComponentsSummary(richReport)?.let { parts.add(it) }
        buildPatternsAndConventions(richReport)?.let { parts.add(it) }
        buildNavigationMap(richReport, projectAnalysis)?.let { parts.add(it) }

        return parts.joinToString("\n\n").take(maxTokens * 4)
    }

    private fun buildArchitectureSummary(
        analysis: ProjectAnalysis,
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String {
        val arch = rich?.architecture
        val style = arch?.style ?: "Unknown"
        val layers = arch?.layers?.map { it.name } ?: emptyList()
        val entryPoints = arch?.apiSurface?.entryPoints?.take(5).orEmpty()
        val layersLine = if (layers.isNotEmpty()) "Layers: ${layers.joinToString(" -> ")}" else ""

        val entryPointsLine = if (entryPoints.isNotEmpty()) "\nKey Entry Points: ${entryPoints.joinToString(", ")}" else ""

        return """
            <PROJECT_ARCHITECTURE>
            Style: $style
            Primary Language: ${analysis.primaryLanguage}
            $layersLine$entryPointsLine
            </PROJECT_ARCHITECTURE>
        """.trimIndent()
    }

    private fun buildKeyComponentsSummary(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String? {
        if (rich == null) return null

        val classes = rich.codeStructure.classes
        val controllers = classes.filter { cls -> cls.annotations.any { it.contains("Controller") } }
        val services = classes.filter { cls -> cls.annotations.any { it.contains("Service") } }
        val repositories = classes.filter { cls -> cls.annotations.any { it.contains("Repository") } }
        val models = classes.filter { cls ->
            cls.name.endsWith("DTO") || cls.name.endsWith("Entity") || cls.modifiers.contains("data")
        }

        // Also detect non-JVM key classes: decorators (@app.route), base classes, main modules
        val keyClasses = classes.filter { cls ->
            cls.annotations.any { a ->
                a.contains("route") || a.contains("endpoint") || a.contains("view") ||
                a.contains("Component") || a.contains("Injectable") || a.contains("Module")
            } || cls.methods.size >= 5
        }.filterNot { it in controllers || it in services || it in repositories || it in models }

        val hasContent = controllers.isNotEmpty() || services.isNotEmpty() ||
            repositories.isNotEmpty() || models.isNotEmpty() || keyClasses.isNotEmpty()
        if (!hasContent) return null

        val parts = mutableListOf<String>()
        parts.add("<KEY_COMPONENTS>")

        if (controllers.isNotEmpty()) {
            parts.add("## API Controllers")
            controllers.take(5).forEach { ctrl ->
                val methods = ctrl.methods.filter { it.modifiers.contains("public") }
                    .take(3)
                    .map { "${it.name}(${it.parameters.size} params) -> ${it.returnType ?: "void"}" }
                parts.add("- ${ctrl.name}: ${methods.joinToString(", ")}")
            }
        }

        if (services.isNotEmpty()) {
            parts.add("## Services")
            services.take(5).forEach { svc ->
                val doc = svc.documentation?.take(100) ?: "Business logic"
                parts.add("- ${svc.name}: $doc")
            }
        }

        if (repositories.isNotEmpty()) {
            parts.add("## Repositories")
            repositories.take(5).forEach { repo ->
                parts.add("- ${repo.name}: data access")
            }
        }

        if (models.isNotEmpty()) {
            parts.add("## Data Models")
            models.take(10).forEach { model ->
                val fields = model.fields.take(5).map { "${it.name}: ${it.type ?: "?"}" }
                val fieldText = if (fields.isNotEmpty()) "(${fields.joinToString(", ")})" else ""
                parts.add("- ${model.name}$fieldText")
            }
        }

        if (keyClasses.isNotEmpty()) {
            parts.add("## Key Classes")
            keyClasses.take(10).forEach { cls ->
                val methodNames = cls.methods.take(5).joinToString(", ") { it.name }
                val suffix = if (methodNames.isNotBlank()) ": $methodNames" else ""
                parts.add("- ${cls.name}$suffix")
            }
        }

        parts.add("</KEY_COMPONENTS>")
        return parts.joinToString("\n")
    }

    private fun buildPatternsAndConventions(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String? {
        if (rich == null) return null

        val patterns = rich.patterns
        // Deduplicate framework patterns by unique framework:pattern pairs
        val frameworkPatterns = patterns.frameworkPatterns
            .map { "${it.framework}:${it.pattern}" }
            .distinct()
            .take(5)
            .joinToString(", ")
        val naming = patterns.namingConventions

        val parts = mutableListOf<String>()
        parts.add("<PATTERNS>")
        if (frameworkPatterns.isNotBlank()) {
            parts.add("Framework patterns: $frameworkPatterns")
        }
        parts.add("Naming: class=${naming.classNaming}, method=${naming.methodNaming}")
        parts.add("</PATTERNS>")

        return parts.joinToString("\n")
    }

    // Removed buildExternalDependenciesList() — it listed import package prefixes (java, com, kotlinx)
    // which are not actual dependencies. Real dependencies are in buildDependenciesSection().

    private fun buildNavigationMap(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?,
        projectAnalysis: ProjectAnalysis
    ): String? {
        val structure = rich?.codeStructure ?: return null

        // Categorize packages/directories by purpose
        val packageMap = structure.packages.groupBy { pkg ->
            val name = pkg.name.lowercase()
            when {
                name.contains(".api") || name.contains(".controller") || name.contains("/api") ||
                    name.contains("/controllers") || name.contains("/routes") || name.contains("/views") ||
                    name.contains("/endpoints") -> "API / Routes"
                name.contains(".service") || name.contains("/services") || name.contains("/logic") ||
                    name.contains("/core") -> "Business Logic"
                name.contains(".repository") || name.contains(".db") || name.contains("/db") ||
                    name.contains("/database") || name.contains("/repositories") ||
                    name.contains("/models") || name.contains("/dao") -> "Data Access"
                name.contains(".model") || name.contains(".dto") || name.contains("/schemas") ||
                    name.contains("/entities") || name.contains("/types") -> "Models"
                name.contains(".config") || name.contains("/config") || name.contains("/settings") -> "Configuration"
                name.contains(".util") || name.contains("/utils") || name.contains("/helpers") ||
                    name.contains("/lib") || name.contains("/common") -> "Utilities"
                name.contains("/test") || name.contains("/tests") || name.contains("/spec") -> "Tests"
                name.contains("/ui") || name.contains("/components") || name.contains("/pages") ||
                    name.contains("/templates") || name.contains("/static") -> "UI / Frontend"
                name.contains("/scripts") || name.contains("/tools") || name.contains("/bin") -> "Scripts / Tools"
                name.contains("/docs") || name.contains("/documentation") -> "Documentation"
                else -> null
            }
        }

        // Filter out uncategorized and empty categories
        val categorized = packageMap.filterKeys { it != null }
        if (categorized.isEmpty()) {
            // Fall back to top-level directory structure from project analysis
            val topLevelDirs = projectAnalysis.structure.topLevelItems
                .filter { !it.startsWith(".") }
            if (topLevelDirs.isEmpty()) return null

            val parts = mutableListOf<String>()
            parts.add("<NAVIGATION_MAP>")
            parts.add("Top-level structure:")
            compactDirectoryList(topLevelDirs).forEach { parts.add("- $it") }
            parts.add("</NAVIGATION_MAP>")
            return parts.joinToString("\n")
        }

        val parts = mutableListOf<String>()
        parts.add("<NAVIGATION_MAP>")
        parts.add("Where to find what:")

        categorized.entries.sortedBy { it.key }.forEach { (category, packages) ->
            val names = packages.map { it.name.substringAfterLast('.').substringAfterLast('/') }
                .distinct().take(5)
            parts.add("- $category: ${names.joinToString(", ")}")
        }

        parts.add("</NAVIGATION_MAP>")
        return parts.joinToString("\n")
    }

    /**
     * Groups similar directory/file names by common prefix to reduce noise.
     * E.g., ["mimir_benchmark_20260130_143610", "mimir_benchmark_20260130_144914", "mimir_benchmark_20260310_103925"]
     * becomes ["mimir_benchmark_* (3 dirs)"]
     */
    private fun compactDirectoryList(items: List<String>): List<String> {
        if (items.size <= 10) {
            // Try grouping by common prefix (at least 4 chars) with numeric/date suffix
            val groups = mutableMapOf<String, MutableList<String>>()
            val standalone = mutableListOf<String>()

            for (item in items) {
                // Find prefix before numeric/date suffix pattern
                val prefixMatch = Regex("""^(.{4,?})[\d_.-]+\d{4,}.*$""").find(item)
                if (prefixMatch != null) {
                    groups.getOrPut(prefixMatch.groupValues[1]) { mutableListOf() }.add(item)
                } else {
                    standalone.add(item)
                }
            }

            val result = mutableListOf<String>()
            for ((prefix, members) in groups) {
                if (members.size >= 2) {
                    result.add("${prefix.trimEnd('_', '-', '.')}* (${members.size} items)")
                } else {
                    result.addAll(members)
                }
            }
            result.addAll(standalone)
            return result.take(12)
        }

        // Many items — group aggressively
        val byExtension = items.groupBy { item ->
            val dot = item.lastIndexOf('.')
            if (dot > 0) item.substring(dot) else ""
        }

        val result = mutableListOf<String>()
        for ((ext, members) in byExtension.entries.sortedByDescending { it.value.size }) {
            if (ext.isNotEmpty() && members.size > 3) {
                result.add("*$ext (${members.size} files)")
            } else {
                result.addAll(members.take(3))
                if (members.size > 3) result.add("... and ${members.size - 3} more")
            }
        }
        return result.take(12)
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

    private fun buildMcpResourcesSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<MCP_RESOURCES>")
        parts.add("External context from configured MCP servers (read-only in PLAN).")
        parts.add("")

        context.mcpResources.take(20).forEach { res ->
            parts.add("--- ${res.serverId} :: ${res.name} ---")
            parts.add("URI: ${res.uri}")
            res.description?.let { parts.add(it) }
            parts.add("")
        }

        parts.add("</MCP_RESOURCES>")
        return parts.joinToString("\n")
    }

    /**
     * Build compact project overview (ADR 0017).
     * Includes essential project info + code analysis summary.
     */
    private fun buildCompactProjectOverview(context: ProjectContextDTO): String {
        val projectName = context.metaData.projectName
        val lang = context.summary.mainLanguage
        val type = context.projectType
        val files = context.structure.totalFiles
        val tech = context.technologies.take(5).joinToString(", ")

        // Jedna linia podsumowania
        val summary = when {
            type.contains("Game") || tech.contains("Canvas") ->
                "Game/graphics project with $files files"

            type.contains("API") || type.contains("Backend") ->
                "Backend service with $files files"

            type.contains("Frontend") || type.contains("React") ->
                "Frontend application with $files files"

            else -> "$type with $files files"
        }

        // Code analysis summary (inline)
        val codeLines = mutableListOf<String>()
        context.codeAnalysis.kotlin.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Kotlin: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.java.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Java: $f files, ${m["classes"]} classes")
        }
        context.codeAnalysis.python.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Python: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.javascript.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("JS: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.typescript.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("TS: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.html.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("HTML: $f files")
        }
        context.codeAnalysis.css.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) {
                val classesCount = m["classes_count"] as? Int ?: 0
                val extra = if (classesCount > 0) ", $classesCount selectors" else ""
                codeLines.add("CSS: $f files$extra")
            }
        }
        val codeAnalysisSummary = if (codeLines.isNotEmpty()) "\nCode: ${codeLines.joinToString("; ")}" else ""

        // Architecture notes
        val archNotes = context.summary.architectureNotes?.let { "\nArchitecture: $it" } ?: ""

        // File types summary
        val fileTypesSummary = context.structure.fileTypes.entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString(", ") { ".${it.key}(${it.value})" }
        val fileTypesLine = if (fileTypesSummary.isNotBlank()) "\nFile types: $fileTypesSummary" else ""

        val frameworkSection = buildFrameworkAnalysisSection(context)

        return """
            |<PROJECT_CONTEXT>
            |$projectName: $summary
            |Stack: $lang | $tech
            |Complexity: ${context.summary.complexity}$codeAnalysisSummary$archNotes$fileTypesLine
            |</PROJECT_CONTEXT>
            |$frameworkSection
        """.trimMargin().trim()
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

    /**
     * Build user requirements section (NEW - PHASE 4).
     * Displays technologies, services, and notes extracted from task description.
     */
    private fun buildUserRequirementsSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<USER_REQUIREMENTS>")

        @Suppress("UNCHECKED_CAST")
        val technologies = context.userRequirements["technologies"] as? List<String>
        if (!technologies.isNullOrEmpty()) {
            parts.add("Required Technologies: ${technologies.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val services = context.userRequirements["services"] as? List<String>
        if (!services.isNullOrEmpty()) {
            parts.add("Required Services: ${services.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val notes = context.userRequirements["notes"] as? List<String>
        if (!notes.isNullOrEmpty()) {
            parts.add("Additional Notes:")
            notes.take(5).forEach { note -> parts.add("- $note") }
        }

        parts.add("</USER_REQUIREMENTS>")
        return parts.joinToString("\n")
    }

    /**
     * Build unified RAG fragments section (code + docs).
     */
    /**
     * Build RAG fragments section with metadata.
     * Refactored based on ADR 0017.
     */
    private fun buildRagFragmentsSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<RAG_FRAGMENTS>")

        context.ragFragments.take(MAX_RAG_FRAGMENTS).forEach { fragment ->
            // Enrich fragment with metadata
            val metadata = enrichFragmentWithMetadata(fragment)

            parts.add("")

            // Build fragment header with metadata
            val attrs = buildList {
                add("file=\"${fragment.filePath}\"")

                if (fragment.startLine != null && fragment.endLine != null) {
                    add("lines=\"${fragment.startLine}-${fragment.endLine}\"")
                }

                metadata["language"]?.let { add("lang=\"$it\"") }
                metadata["fileSize"]?.let { add("size=\"$it\"") }
                add("similarity=\"${String.format("%.2f", fragment.similarity)}\"")
                metadata["complexity"]?.let { add("complexity=\"$it\"") }
            }.joinToString(" ")

            parts.add("<fragment $attrs>")

            // Content with language hint
            val lang = metadata["language"] as? String ?: ""
            val langHint = when (lang.lowercase()) {
                "kotlin" -> "kotlin"
                "java" -> "java"
                "python" -> "python"
                "javascript", "typescript" -> "javascript"
                "html" -> "html"
                "css" -> "css"
                "json" -> "json"
                "yaml" -> "yaml"
                else -> ""
            }

            if (langHint.isNotEmpty()) {
                parts.add("```$langHint")
            } else {
                parts.add("```")
            }

            parts.add(fragment.content.trim())
            parts.add("```")
            parts.add("</fragment>")
        }

        parts.add("</RAG_FRAGMENTS>")
        return parts.joinToString("\n")
    }

    private fun buildCurrentTaskSection(context: ProjectContextDTO): String {
        val task = context.currentTask ?: return "<CURRENT_TASK>\nNo task information available\n</CURRENT_TASK>"

        val completedCount = context.subtasks.count { it.status == "SUCCESS" }
        val failedCount = context.subtasks.count { it.status == "FAILED" }
        val runningCount = context.subtasks.count { it.status == "RUNNING" }
        val pendingCount = context.subtasks.count { it.status == "PENDING" || it.status == "PLANNED" }

        val statusSummary = buildString {
            if (completedCount > 0) append("$completedCount completed")
            if (runningCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$runningCount running")
            }
            if (pendingCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$pendingCount pending")
            }
            if (failedCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$failedCount failed")
            }
        }

        val statusTag = when (task.status) {
            "SUCCESS" -> " [completed]"
            "RUNNING" -> " [in progress]"
            else -> ""
        }
        val subtasksLine = if (context.subtasks.isNotEmpty()) {
            "\nSubtasks: ${context.subtasks.size} total ($statusSummary)"
        } else ""

        return "<CURRENT_TASK>\n${task.description}$statusTag$subtasksLine\n</CURRENT_TASK>"
    }

    /**
     * Build subtasks status section (renamed from buildCompletedSubtasksSection - ADR 0040).
     * Shows ALL subtasks with their current status, not just completed ones.
     * Format: "description - STATUS" sorted by execution order.
     */
    private fun buildSubtasksStatusSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<SUBTASKS_STATUS>")

        // Sort by execution order (ascending)
        val sortedSubtasks = context.subtasks.sortedBy { it.order }

        // Build formatted lines: "N. description - STATUS"
        val formattedLines = sortedSubtasks.mapIndexed { index, subtask ->
            val statusSymbol = when (subtask.status) {
                "SUCCESS" -> "SUCCESS"
                "FAILED" -> "ERROR"
                "RUNNING" -> "RUNNING"
                "PENDING", "PLANNED", "NEW" -> "PENDING"
                else -> subtask.status
            }
            "${index + 1}. ${subtask.description} - $statusSymbol"
        }

        parts.addAll(formattedLines)
        parts.add("</SUBTASKS_STATUS>")
        return parts.joinToString("\n")
    }

    /**
     * Build compressed conversation section (ADR 0017).
     * More aggressive compression for smaller context.
     */
    private fun buildCompressedConversationSection(context: ProjectContextDTO, budgetTokens: Int): String {
        if (budgetTokens <= 0) return ""

        val history = context.conversationHistory
        if (history.isEmpty()) return ""

        val parts = mutableListOf<String>()
        parts.add("<CONVERSATION_HISTORY>")

        var tokensUsed = ContextTokenEstimator.estimateTokens(parts.joinToString("\n"))

        fun appendLine(line: String): Boolean {
            val tokens = ContextTokenEstimator.estimateTokens(line)
            if (tokensUsed + tokens > budgetTokens) return false
            parts.add(line)
            tokensUsed += tokens
            return true
        }

        val maxMessages = when {
            budgetTokens >= CONVERSATION_BUDGET_TIER_HIGH -> CONVERSATION_MAX_MESSAGES_HIGH
            budgetTokens >= CONVERSATION_BUDGET_TIER_MEDIUM -> CONVERSATION_MAX_MESSAGES_MEDIUM
            budgetTokens >= CONVERSATION_BUDGET_TIER_LOW -> CONVERSATION_MAX_MESSAGES_LOW
            else -> CONVERSATION_MAX_MESSAGES_DEFAULT
        }
        val perMessageTokens = maxOf(CONVERSATION_MIN_PER_MESSAGE_TOKENS, budgetTokens / maxMessages)

        val firstMessage = history.firstOrNull()
        val firstIsSummary = firstMessage?.metadata?.get("type") == CONVERSATION_SUMMARY_METADATA_TYPE

        if (firstIsSummary && firstMessage != null) {
            val summaryBudget = minOf((budgetTokens * 0.5).toInt(), budgetTokens)
            val summaryContent = ContextTokenEstimator.truncateToTokens(firstMessage.content.trim(), summaryBudget)
            appendLine("=== SUMMARY ===")
            appendLine(summaryContent)
            appendLine("")
        }

        val remaining = if (firstIsSummary) history.drop(1) else history
        val recentMessages = remaining.takeLast(maxMessages)
        for (msg in recentMessages) {
            val content = ContextTokenEstimator.truncateToTokens(msg.content.trim(), perMessageTokens)
            val line = "[${msg.role.uppercase()}]\n${content.trim()}\n"
            if (!appendLine(line)) break
        }

        parts.add("</CONVERSATION_HISTORY>")
        return parts.joinToString("\n")
    }

    /**
     * Build recent work section (REFACTORED - ADR 0017).
     * Podzielenie executed steps na: summary dla starszych kroków, pełne dane dla ostatnich N.
     * Uses RecentWorkConfig for configuration.
     *
     * @param context Project context DTO
     * @param budgetTokens Token budget for this section
     * @param config Configuration for recent work generation
     * @return Formatted recent work section
     */
    private fun buildRecentWorkSection(
        context: ProjectContextDTO,
        budgetTokens: Int,
        config: RecentWorkConfig = buildRecentWorkConfig()
    ): String {
        if (budgetTokens <= 0) return ""

        val executedSteps = context.executedSteps
        if (executedSteps.isEmpty() && context.completedFiles.isEmpty()) {
            return ""
        }

        val parts = mutableListOf<String>()
        parts.add("<RECENT_WORK>")

        val fullDataLimit = calculateFullDataLimit(executedSteps.size, budgetTokens, config.fullDataLimit)
        val detailedLimit = calculateDetailedLimit(budgetTokens)
        val compressionConfig = ToolResultCompressionConfig(
            detailedMaxChars = config.detailedMaxLength,
            summaryMaxChars = config.summaryMaxLength
        )

        val entries = buildAdaptiveRecentWork(
            steps = executedSteps,
            fullLimit = fullDataLimit,
            detailedLimit = detailedLimit,
            budgetTokens = budgetTokens,
            config = config,
            compressionConfig = compressionConfig
        )
        parts.addAll(entries)

        parts.add("</RECENT_WORK>")
        return parts.joinToString("\n")
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

    private fun buildAdaptiveRecentWork(
        steps: List<ExecutedStepDTO>,
        fullLimit: Int,
        detailedLimit: Int,
        budgetTokens: Int,
        config: RecentWorkConfig,
        compressionConfig: ToolResultCompressionConfig
    ): List<String> {
        if (steps.isEmpty()) return emptyList()

        val entries = mutableListOf<Pair<Int, String>>()  // (original index, entry)
        val reversedSteps = steps.asReversed()
        val detailedStart = fullLimit + detailedLimit

        // Strict budget handling: most recent tools first, with graceful fallback
        var tokensUsed = 0

        val candidates = reversedSteps.mapIndexed { index, step ->
            val baseLevel = when {
                index < fullLimit -> CompressionLevel.FULL
                index < detailedStart -> CompressionLevel.DETAILED
                else -> CompressionLevel.SUMMARY
            }
            Triple(index, step, baseLevel)
        }

        // Always keep the latest executed step uncompressed (FULL) for maximum fidelity.
        // This is intentionally allowed to exceed the regular section budget.
        val latest = candidates.firstOrNull()
        if (latest != null) {
            val (latestIndex, latestStep, _) = latest
            val latestEntry = formatToolOutput(latestStep, CompressionLevel.FULL, config, compressionConfig)
            entries.add(Pair(latestIndex, latestEntry))
            tokensUsed += ContextTokenEstimator.estimateTokens(latestEntry)
        }

        for ((index, step, baseLevel) in candidates.drop(1)) {
            val fallbackLevels = compressionFallbacks(baseLevel)
            var chosen: String? = null

            for (level in fallbackLevels) {
                val entry = formatToolOutput(step, level, config, compressionConfig)
                val entryTokens = ContextTokenEstimator.estimateTokens(entry)
                if (tokensUsed + entryTokens <= budgetTokens) {
                    tokensUsed += entryTokens
                    chosen = entry
                    break
                }
            }

            if (chosen != null) {
                entries.add(Pair(index, chosen))
            }

            if (tokensUsed >= budgetTokens) break
        }

        // Sort by original index (descending = most recent first) and extract entries
        return entries
            .sortedByDescending { it.first }
            .map { it.second }
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

    private fun formatToolOutput(
        step: ExecutedStepDTO,
        level: CompressionLevel,
        config: RecentWorkConfig,
        compressionConfig: ToolResultCompressionConfig
    ): String {
        val fileAttr = buildToolFileAttribute(step, config.includeMetadata)
        val content = ToolResultCompression.compress(step.result, step.summary, level, compressionConfig)
        val tagSuffix = if (fileAttr.isNotBlank()) " $fileAttr" else ""

        // Add compression level attribute (only show if not FULL)
        val compressionAttr = if (level != CompressionLevel.FULL) {
            " compressed=\"${level.name.lowercase()}\""
        } else {
            ""
        }

        // Add metadata: timestamp, params (truncated), summary
        val timestamp = step.timestamp.toString().take(19)  // ISO format, truncate milliseconds
        val paramsAttr = formatToolParamsAttribute(step.parameters)
        val summaryAttr = if (!step.summary.isNullOrBlank() && step.summary.length <= 100) {
            " summary=\"${step.summary.replace("\"", "'")}\""
        } else {
            ""
        }
        val subtaskIdAttr = " subtaskId=\"${step.subtaskId.replace("\"", "'")}\""

        return buildString {
            append("<tool name=\"")
            append(step.tool)
            append("\"")
            append(tagSuffix)
            append(compressionAttr)
            append(subtaskIdAttr)
            append(" timestamp=\"")
            append(timestamp)
            append("\"")
            if (paramsAttr.isNotBlank()) append(paramsAttr)
            if (summaryAttr.isNotBlank()) append(summaryAttr)
            append(">\n")
            append(wrapInMarkdownCodeBlock(content.ifBlank { "-" }))
            append("\n</tool>")
        }
    }

    private fun wrapInMarkdownCodeBlock(content: String): String {
        val fenceLength = maxOf(3, longestBacktickRun(content) + 1)
        val fence = "`".repeat(fenceLength)
        return buildString {
            append(fence)
            append("text\n")
            append(content)
            append("\n")
            append(fence)
        }
    }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0
        var current = 0
        for (char in text) {
            if (char == '`') {
                current += 1
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun formatToolParamsAttribute(
        parameters: Map<String, Any>,
        maxParams: Int = 5,
        maxValueLength: Int = 80,
        maxAttributeLength: Int = 320
    ): String {
        if (parameters.isEmpty()) return ""

        val visibleEntries = parameters.entries.take(maxParams)
        val paramsStr = visibleEntries.joinToString(",") { (key, value) ->
            val safeKey = sanitizeXmlAttributeValue(key)
            val safeValue = sanitizeXmlAttributeValue(truncateValue(value.toString(), maxValueLength))
            "$safeKey=$safeValue"
        }

        val withCountSuffix = if (parameters.size > maxParams) {
            "$paramsStr,+${parameters.size - maxParams}_more"
        } else {
            paramsStr
        }

        val trimmed = truncateValue(withCountSuffix, maxAttributeLength)
        return if (trimmed.isBlank()) "" else " params=\"$trimmed\""
    }

    private fun sanitizeXmlAttributeValue(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "'")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    private fun truncateValue(value: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        return if (value.length > maxLength) {
            "${value.take(maxLength)}..."
        } else {
            value
        }
    }

    private fun buildToolFileAttribute(step: ExecutedStepDTO, includeMetadata: Boolean): String {
        val filePath = step.file ?: return ""
        if (!includeMetadata) return "file=\"$filePath\""

        val path = Path.of(filePath)
        val size = try {
            val bytes = java.nio.file.Files.size(path)
            when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                else -> "${bytes / (1024 * 1024)}MB"
            }
        } catch (e: Exception) {
            "?"
        }
        val ext = path.fileName.toString().substringAfterLast('.', "").takeIf { it.isNotEmpty() } ?: "txt"
        return "file=\"$filePath\" size=\"$size\" type=\"$ext\""
    }

    /**
     * Clean subtask summary - remove JSON artifacts if present.
     */
    private fun cleanSubtaskSummary(subtask: String): String {
        // If subtask contains JSON-like content, extract just the description
        return if (subtask.contains("{\"toolsExecuted\"") || subtask.contains("{\"outputs\"")) {
            // Try to extract meaningful description before JSON
            val colonIndex = subtask.indexOf(": {")
            if (colonIndex > 0) {
                subtask.substring(0, colonIndex).trim().removePrefix("- ")
            } else {
                subtask.substringBefore("{").trim().removePrefix("- ")
            }
        } else {
            subtask
        }
    }

    /**
     * Format file metadata for display in context.
     * Based on ADR 0017: Refaktoryzacja Context Service.
     *
     * @param filePath Absolute or relative file path
     * @return Formatted string with file metadata: "path [ext, size, modified]"
     */
    private fun formatFileMetadata(filePath: String): String {
        return try {
            val path = Path.of(filePath)
            if (!java.nio.file.Files.exists(path)) {
                return filePath
            }

            val size = java.nio.file.Files.size(path)
            val sizeStr = when {
                size < 1024 -> "${size}B"
                size < 1024 * 1024 -> "${size / 1024}KB"
                else -> "${size / (1024 * 1024)}MB"
            }

            val ext = path.fileName.toString().substringAfterLast('.', "")
            val modified = java.nio.file.Files.getLastModifiedTime(path).toInstant()
            val modifiedStr = modified.toString().take(10) // YYYY-MM-DD

            "$filePath [$ext, $sizeStr, $modifiedStr]"
        } catch (e: Exception) {
            filePath // Fallback to plain path if any error occurs
        }
    }

    /**
     * Truncate text to specified length with ellipsis.
     * Based on ADR 0017.
     */
    /**
     * Intelligently truncate text, with special handling for code blocks.
     * Detects markdown code blocks and truncates them with summary instead of raw cut.
     */
    private fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) {
            return text
        }

        // Detect code blocks (``` ... ```)
        val codeBlockRegex = Regex("```[\\w]*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
        val hasCodeBlocks = codeBlockRegex.containsMatchIn(text)

        if (hasCodeBlocks) {
            val parts = mutableListOf<String>()
            var lastIndex = 0
            var totalLength = 0

            codeBlockRegex.findAll(text).forEach { match ->
                // Add text before code block
                val beforeCode = text.substring(lastIndex, match.range.first)
                if (beforeCode.isNotBlank()) {
                    val available = maxLength - totalLength
                    if (available > 0) {
                        val truncated = if (beforeCode.length > available) {
                            beforeCode.take(available) + "..."
                        } else {
                            beforeCode
                        }
                        parts.add(truncated)
                        totalLength += truncated.length
                    }
                }

                // Process code block
                val codeBlock = match.value
                val codeContent = match.groupValues[1]
                val lines = codeContent.lines()
                val available = maxLength - totalLength

                if (available > 50) {  // Minimum space for code preview
                    if (lines.size <= 10) {
                        // Short code block - include it fully if space allows
                        if (codeBlock.length <= available) {
                            parts.add(codeBlock)
                            totalLength += codeBlock.length
                        } else {
                            val previewLines = lines.take(5).joinToString("\n")
                            val preview = "```\n$previewLines\n... (${lines.size - 5} more lines)\n```"
                            parts.add(preview)
                            totalLength += preview.length
                        }
                    } else {
                        // Large code block - show summary
                        val previewLines = lines.take(5).joinToString("\n")
                        val language = match.value.removePrefix("```").substringBefore("\n")
                        val preview =
                            "```$language\n$previewLines\n... (${lines.size - 5} more lines, ${codeContent.length} chars total)\n```"
                        parts.add(preview)
                        totalLength += preview.length
                    }
                } else {
                    // Not enough space - add summary only
                    parts.add("[Code block: ${lines.size} lines, ${codeContent.length} chars]")
                    totalLength += 50
                }

                lastIndex = match.range.last + 1
            }

            // Add remaining text after last code block
            if (lastIndex < text.length) {
                val remaining = text.substring(lastIndex)
                val available = maxLength - totalLength
                if (available > 0 && remaining.isNotBlank()) {
                    val truncated = if (remaining.length > available) {
                        remaining.take(available) + "..."
                    } else {
                        remaining
                    }
                    parts.add(truncated)
                }
            }

            return parts.joinToString("")
        }

        // No code blocks - simple truncation
        return "${text.take(maxLength)}..."
    }

    /**
     * Detect programming language from file path.
     * Based on ADR 0017.
     */
    private fun detectLanguage(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "jsx" -> "React"
            "html", "htm" -> "HTML"
            "css", "scss", "sass", "less" -> "CSS"
            "md", "markdown" -> "Markdown"
            "json" -> "JSON"
            "xml" -> "XML"
            "yaml", "yml" -> "YAML"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "rs" -> "Rust"
            "go" -> "Go"
            "cpp", "cc", "cxx" -> "C++"
            "c", "h" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            else -> ext.uppercase().takeIf { it.isNotEmpty() } ?: "Unknown"
        }
    }

    /**
     * Estimate code complexity based on lines and nesting level.
     * Based on ADR 0017.
     */
    private fun estimateComplexity(content: String): String {
        val lines = content.lines().size
        val nestingLevel = content.count { it == '{' || it == '(' }

        return when {
            lines < 20 && nestingLevel < 5 -> "low"
            lines < 100 && nestingLevel < 20 -> "medium"
            else -> "high"
        }
    }

    /**
     * Enrich code fragment with metadata.
     * Based on ADR 0017.
     */
    private fun enrichFragmentWithMetadata(fragment: CodeFragmentDTO): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>()

        // File size and line count
        try {
            val path = Path.of(fragment.filePath)
            if (java.nio.file.Files.exists(path)) {
                val size = java.nio.file.Files.size(path)
                metadata["fileSize"] = when {
                    size < 1024 -> "${size}B"
                    size < 1024 * 1024 -> "${size / 1024}KB"
                    else -> "${size / (1024 * 1024)}MB"
                }

                val lines = java.nio.file.Files.readAllLines(path).size
                metadata["lineCount"] = lines

                val modified = java.nio.file.Files.getLastModifiedTime(path).toInstant()
                metadata["lastModified"] = modified.toString().take(10)
            }
        } catch (e: Exception) {
            // Ignore file metadata errors
        }

        // Language detection
        metadata["language"] = detectLanguage(fragment.filePath)

        // Complexity estimation
        metadata["complexity"] = estimateComplexity(fragment.content)

        return metadata
    }

    private fun formatParameters(params: Map<String, Any>, gson: com.google.gson.Gson): String {
        if (params.isEmpty()) return "-"
        val json = gson.toJson(params)
        return if (json.length > 1024) "${json.take(1024)}..." else json
    }

    private fun truncateForRecentWork(text: String?, limit: Int = 8192): String {
        if (text.isNullOrBlank()) return "-"
        return if (text.length > limit) "${text.take(limit)}..." else text
    }

    private fun buildTypeScriptAnalysisSection(context: ProjectContextDTO): String? {
        val ts = context.codeAnalysis.typescript
        val filesCount = ts["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<TYPESCRIPT_ANALYSIS>")
        parts.add("Files: $filesCount")

        val interfacesCount = ts["interfaces"] as? Int ?: 0
        val typesCount = ts["types"] as? Int ?: 0
        val classesCount = ts["classes"] as? Int ?: 0
        val functionsCount = ts["functions"] as? Int ?: 0

        if (interfacesCount > 0 || typesCount > 0) {
            parts.add("Interfaces: $interfacesCount, Types: $typesCount")
        }
        if (classesCount > 0) parts.add("Classes: $classesCount")
        if (functionsCount > 0) parts.add("Functions: $functionsCount")

        @Suppress("UNCHECKED_CAST")
        val interfaceNames = ts["interface_names"] as? List<String>
        if (!interfaceNames.isNullOrEmpty()) {
            parts.add("Key Interfaces: ${interfaceNames.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val typeNames = ts["type_names"] as? List<String>
        if (!typeNames.isNullOrEmpty()) {
            parts.add("Key Types: ${typeNames.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val decorators = ts["decorators"] as? List<String>
        if (!decorators.isNullOrEmpty()) {
            parts.add("Decorators: ${decorators.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val reactComponents = ts["react_components"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val reactHooks = ts["react_hooks"] as? Int ?: 0
        if (reactComponents > 0 || reactHooks > 0) {
            parts.add("React: $reactComponents components, $reactHooks hooks")
        }

        parts.add("</TYPESCRIPT_ANALYSIS>")
        return parts.joinToString("\n")
    }

    private fun buildHtmlAnalysisSection(context: ProjectContextDTO): String? {
        val html = context.codeAnalysis.html
        val filesCount = html["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<HTML_ANALYSIS>")
        parts.add("HTML Files: $filesCount")

        @Suppress("UNCHECKED_CAST")
        val pages = html["pages"] as? List<Map<String, Any>>
        if (!pages.isNullOrEmpty()) {
            parts.add("Pages:")
            val displayPages = if (pages.size > 10) pages.take(10) else pages
            displayPages.forEach { page ->
                val file = page["file"] as? String ?: "unknown"
                val title = page["title"] as? String
                val hasCanvas = page["has_canvas"] as? Boolean ?: false
                val hasWebgl = page["has_webgl"] as? Boolean ?: false
                val formsCount = page["forms_count"] as? Int

                val pageInfo = buildString {
                    append("  - $file")
                    if (title != null) append(" (\"$title\")")
                    if (hasCanvas) append(" [Canvas]")
                    if (hasWebgl) append(" [WebGL]")
                    if (formsCount != null && formsCount > 0) append(" [Forms: $formsCount]")
                }
                parts.add(pageInfo)
            }
            if (pages.size > 10) {
                parts.add("  ... and ${pages.size - 10} more pages")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val canvasGames = html["canvas_games"] as? List<String>
        if (!canvasGames.isNullOrEmpty()) {
            parts.add("Canvas Games Detected: ${canvasGames.take(5).joinToString(", ")}")
        }

        parts.add("</HTML_ANALYSIS>")
        return parts.joinToString("\n")
    }

    private fun buildCssAnalysisSection(context: ProjectContextDTO): String? {
        val css = context.codeAnalysis.css
        val filesCount = css["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<CSS_ANALYSIS>")
        parts.add("CSS Files: $filesCount")

        val classesCount = css["classes_count"] as? Int ?: 0
        val idsCount = css["ids_count"] as? Int ?: 0
        if (classesCount > 0 || idsCount > 0) {
            parts.add("Selectors: $classesCount classes, $idsCount IDs")
        }

        @Suppress("UNCHECKED_CAST")
        val variables = css["variables"] as? List<String>
        if (!variables.isNullOrEmpty()) {
            parts.add("CSS Variables: ${variables.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val animations = css["animations"] as? List<String>
        if (!animations.isNullOrEmpty()) {
            parts.add("Animations: ${animations.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val mediaQueries = css["media_queries"] as? List<String>
        if (!mediaQueries.isNullOrEmpty()) {
            parts.add("Media Queries: ${mediaQueries.take(5).joinToString(", ")}")
        }

        parts.add("</CSS_ANALYSIS>")
        return parts.joinToString("\n")
    }

    // ===========================
    // PHASE 2: New Helper Methods
    // ===========================

    /**
     * Filter conversation history to keep only meaningful exchanges.
     * Removes system messages, tool usage notifications, and very short messages.
     * Based on Python context_service.py lines 966-990
     */
    private fun filterMeaningfulConversation(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        return messages.filter { msg ->
            val content = msg.content.trim()
            content.length >= 10
        }
    }

    private fun sliceConversationHistoryFromLastSummary(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        return if (lastSummaryIndex >= 2) {
            messages.drop(lastSummaryIndex - 1)
        } else {
            messages
        }
    }

    private fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }

    /**
     * Extract user requirements from task description.
     * Parses lines like "Technologies: X, Y, Z" or "Services: A, B".
     * Based on Python context_service.py lines 1052-1085
     */
    private fun extractUserRequirements(description: String): Map<String, Any> {
        val requirements = mutableMapOf<String, Any>()
        if (description.isBlank()) return requirements

        val lines = description.lines().map { it.trim() }.filter { it.isNotBlank() }
        val tech = mutableListOf<String>()
        val services = mutableListOf<String>()
        val notes = mutableListOf<String>()

        for (line in lines) {
            val lower = line.lowercase()
            when {
                lower.startsWith("technologies:") || lower.contains(" technologies ") -> {
                    val value = if (":" in line) line.split(":", limit = 2)[1] else line
                    tech.addAll(value.split(Regex("[,;|]")).map { it.trim() }.filter { it.isNotBlank() })
                }

                lower.startsWith("services:") || lower.contains(" services ") -> {
                    val value = if (":" in line) line.split(":", limit = 2)[1] else line
                    services.addAll(value.split(Regex("[,;|]")).map { it.trim() }.filter { it.isNotBlank() })
                }

                lower.startsWith("use ") -> notes.add(line)
            }
        }

        if (tech.isNotEmpty()) requirements["technologies"] = tech.distinct().sorted()
        if (services.isNotEmpty()) requirements["services"] = services.distinct().sorted()
        if (notes.isNotEmpty()) requirements["notes"] = notes

        return requirements
    }

    /**
     * Build previous subtasks data for context.
     * Returns pair of (subtask summaries, completed file paths).
     * Based on Python context_service.py lines 1087-1105
     */
    private fun buildPreviousSubtasksData(
        subtasks: List<Subtask>,
        limit: Int = 10
    ): Pair<List<String>, List<String>> {
        val completedFiles = mutableSetOf<String>()
        val previousSubtasks = mutableSetOf<String>()

        val completed = subtasks.filter { it.status == TaskStatus.SUCCESS }
        val gson = pl.jclab.refio.core.utils.GsonInstance.gson

        for (prevSubtask in completed.takeLast(limit)) {
            val summary = prevSubtask.result ?: "No summary available."
            previousSubtasks.add("- ${prevSubtask.description}: $summary")

            // Extract file paths from tool arguments (try multiple field names)
            val filePaths = mutableSetOf<String>()

            // Try paramsJson first
            prevSubtask.paramsJson?.let { json ->
                try {
                    val params = gson.fromJson(json, Map::class.java)
                    // Try various common field names for file paths
                    val possibleKeys = listOf("path", "file_path", "file", "target", "source", "files")
                    for (key in possibleKeys) {
                        when (val value = params?.get(key)) {
                            is String -> filePaths.add(value)
                            is List<*> -> value.filterIsInstance<String>().forEach { filePaths.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }

            // Also try stepPlanJson (might contain file references)
            prevSubtask.stepPlanJson?.let { json ->
                try {
                    val plan = gson.fromJson(json, Map::class.java)
                    // Look for files in tool_calls
                    @Suppress("UNCHECKED_CAST")
                    val toolCalls = plan?.get("tool_calls") as? List<Map<*, *>>
                    toolCalls?.forEach { call ->
                        @Suppress("UNCHECKED_CAST")
                        val args = call["args"] as? Map<*, *>
                        val possibleKeys = listOf("path", "file_path", "file", "target", "source")
                        for (key in possibleKeys) {
                            when (val value = args?.get(key)) {
                                is String -> filePaths.add(value)
                                is List<*> -> value.filterIsInstance<String>().forEach { filePaths.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }

            completedFiles.addAll(filePaths)
        }

        return Pair(previousSubtasks.toList(), completedFiles.toList())
    }

    /**
     * Build structured executed steps for RECENT_WORK (ADR 0041).
     * Parses subtask result JSON to extract tool runs with parameters and outputs.
     */
    private fun buildExecutedSteps(
        subtasks: List<Subtask>,
        limit: Int = 10
    ): List<ExecutedStepDTO> {
        val gson = pl.jclab.refio.core.utils.GsonInstance.gson
        val completed = subtasks.filter { it.status == TaskStatus.SUCCESS }
        val executedSteps = mutableListOf<ExecutedStepDTO>()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type

        completed.takeLast(limit).forEach { prevSubtask ->
            val rawResult = prevSubtask.result ?: return@forEach
            var hasAddedSteps = false
            val fallbackParams = extractParamsFromSubtask(prevSubtask, gson)

            try {
                val normalized = normalizeResultJson(rawResult, gson)
                if (normalized != null) {
                    val parsed = gson.fromJson<Map<String, Any>>(normalized, mapType)
                    val outputs = parsed?.get("outputs") as? List<*>

                    if (!outputs.isNullOrEmpty()) {
                        outputs.mapNotNull { it as? Map<*, *> }.forEach { output ->
                            val tool = output["tool"] as? String ?: prevSubtask.kind.name
                            val outputParams = extractParamsFromOutput(output)
                            val paramsMap = toStringAnyMap(outputParams).ifEmpty { fallbackParams }
                            val resultMap = output["result"] as? Map<*, *>
                            val filePath = pickFilePath(
                                paramsMap = outputParams ?: fallbackParams,
                                resultMap = resultMap,
                                affectedFiles = output["affectedFiles"] as? List<*>
                            )
                            val resultText = extractResultText(resultMap, gson)
                            val summary = prevSubtask.summary  // Get summary from subtask
                            val timestamp = prevSubtask.completedAt ?: prevSubtask.updatedAt

                            executedSteps.add(
                                ExecutedStepDTO(
                                    subtaskId = prevSubtask.id,
                                    file = filePath,
                                    tool = tool,
                                    parameters = paramsMap,
                                    result = resultText,
                                    summary = summary,
                                    timestamp = Instant.ofEpochMilli(timestamp)
                                )
                            )
                            hasAddedSteps = true
                        }
                    } else {
                        logger.debug {
                            "[CONTEXT] No outputs found in parsed JSON, subtask=${prevSubtask.id}, " +
                                    "tool=${prevSubtask.kind.name}, normalized.startsWith('{')=${normalized.startsWith("{")}"
                        }
                    }
                } else {
                    logger.debug {
                        "[CONTEXT] normalizeResultJson returned null, subtask=${prevSubtask.id}, " +
                                "tool=${prevSubtask.kind.name}, rawResult.length=${rawResult.length}, " +
                                "starts with '{'=${
                                    rawResult.trim().startsWith("{")
                                }, starts with '\"'=${rawResult.trim().startsWith("\"")}"
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[CONTEXT] Failed to parse executed step from subtask ${prevSubtask.id}" }
            }

            // FALLBACK: If no steps were added from outputs, create a basic entry from subtask metadata
            if (!hasAddedSteps) {
                val timestamp = prevSubtask.completedAt ?: prevSubtask.updatedAt
                val summary = prevSubtask.summary ?: "Completed: ${prevSubtask.kind.name}"

                // Don't truncate - use full rawResult, but limit to reasonable size for display
                val resultText = if (rawResult.length > 16384) {
                    rawResult.take(16384) + "\n\n... [${rawResult.length - 16384} chars omitted] ..."
                } else {
                    rawResult
                }

                executedSteps.add(
                    ExecutedStepDTO(
                        subtaskId = prevSubtask.id,
                        file = null,
                        tool = prevSubtask.kind.name,
                        parameters = fallbackParams,
                        result = resultText,
                        summary = summary,
                        timestamp = Instant.ofEpochMilli(timestamp)
                    )
                )
                logger.debug { "[CONTEXT] Added fallback step for subtask ${prevSubtask.id}: ${prevSubtask.kind.name}" }
            }
        }

        // Keep only the most recent entries if outputs expanded the list beyond limit
        return executedSteps.takeLast(limit)
    }

    private fun normalizeResultJson(rawResult: String, gson: com.google.gson.Gson): String? {
        val trimmed = rawResult.trim()
        if (trimmed.startsWith("{")) {
            return trimmed
        }
        if (trimmed.startsWith("\"")) {
            val unquoted = try {
                gson.fromJson(trimmed, String::class.java).trim()
            } catch (e: Exception) {
                return null
            }
            return if (unquoted.startsWith("{")) unquoted else null
        }
        return null
    }

    private fun extractParamsFromOutput(output: Map<*, *>): Map<*, *>? {
        val direct = firstMapByKeys(
            source = output,
            keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
        )
        if (direct != null) return direct

        val toolCall = output["toolCall"] as? Map<*, *> ?: output["tool_call"] as? Map<*, *>
        return firstMapByKeys(
            source = toolCall,
            keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
        )
    }

    private fun extractParamsFromSubtask(subtask: Subtask, gson: com.google.gson.Gson): Map<String, Any> {
        val raw = subtask.paramsJson?.trim().orEmpty()
        if (raw.isBlank() || !raw.startsWith("{")) return emptyMap()

        return runCatching {
            val parsed = gson.fromJson<Map<String, Any>>(raw, object : TypeToken<Map<String, Any>>() {}.type)
            val nested = firstMapByKeys(
                source = parsed,
                keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
            )
            when {
                nested != null -> toStringAnyMap(nested)
                else -> toStringAnyMap(parsed)
            }
        }.getOrElse { emptyMap() }
    }

    private fun firstMapByKeys(source: Map<*, *>?, keys: List<String>): Map<*, *>? {
        if (source == null) return null
        for (key in keys) {
            val value = source[key]
            if (value is Map<*, *>) return value
        }
        return null
    }

    private fun toStringAnyMap(raw: Map<*, *>?): Map<String, Any> {
        if (raw == null) return emptyMap()
        val result = mutableMapOf<String, Any>()
        for ((key, value) in raw.entries) {
            val k = key as? String ?: continue
            if (value != null) result[k] = value
        }
        return result
    }

    private fun pickFilePath(
        paramsMap: Map<*, *>?,
        resultMap: Map<*, *>?,
        affectedFiles: List<*>?
    ): String? {
        val metadataPath = (resultMap?.get("metadata") as? Map<*, *>)?.get("path") as? String
        val candidates = listOfNotNull(
            paramsMap?.get("path") as? String,
            paramsMap?.get("file_path") as? String,
            paramsMap?.get("file") as? String,
            paramsMap?.get("target") as? String,
            paramsMap?.get("source") as? String,
            metadataPath,
            affectedFiles?.firstOrNull() as? String
        )
        return candidates.firstOrNull()
    }

    private fun extractResultText(resultMap: Map<*, *>?, gson: com.google.gson.Gson): String {
        if (resultMap == null) return "-"
        val output = resultMap["output"] as? String
        val error = resultMap["error"] as? String
        val message = resultMap["message"] as? String

        return output ?: error ?: message ?: gson.toJson(resultMap)
    }

    /**
     * Summarize file changes by type and importance.
     * Groups files by extension for concise display.
     * Based on Python context_service.py lines 1107-1132
     */
    private fun summarizeFileChanges(completedFiles: List<String>): String {
        if (completedFiles.isEmpty()) return ""

        val byType = mutableMapOf<String, MutableList<String>>()

        for (filePath in completedFiles) {
            try {
                val path = Path.of(filePath)
                val ext = path.fileName.toString().substringAfterLast('.', "no-ext").lowercase()
                val name = path.fileName.toString()
                byType.getOrPut(ext) { mutableListOf() }.add(name)
            } catch (e: Exception) {
                byType.getOrPut("unknown") { mutableListOf() }.add(filePath)
            }
        }

        val summaryParts = byType.map { (ext, files) ->
            if (files.size > 2) {
                "$ext: ${files.size} files"
            } else {
                val fileNames = files.map { if (it.length > 25) "${it.take(25)}..." else it }
                "$ext: ${fileNames.joinToString(", ")}"
            }
        }.take(4)

        return summaryParts.joinToString(" | ")
    }

    // ===========================
    // USER CONTEXT RESOLUTION (SINGLE SOURCE OF TRUTH)
    // ===========================

    /**
     * Resolve and convert user context references to DTOs.
     * This is called internally by buildProjectContext().
     *
     * @param refs Raw user context references from PromptInputPanel
     * @param projectRoot Project root path
     * @param project IntelliJ Project instance (optional)
     * @return List of ResolvedContextDTO ready for LLM
     */
    private suspend fun resolveAndConvertUserContextRefs(
        refs: List<ContextReference>,
        projectRoot: Path,
        project: Project?,
        currentQuery: String?
    ): List<ResolvedContextDTO> {
        val resolved = resolveUserContextReferences(refs, projectRoot, project, currentQuery)

        return resolved.mapNotNull { ref ->
            val content = ref.content
            if (content.isNullOrBlank()) {
                logger.warn { "[CONTEXT] Skipping empty context ref: ${ref.displayName}" }
                null
            } else {
                ResolvedContextDTO(
                    type = ref.type.name,
                    providerId = ref.metadata["providerId"] as? String,
                    path = ref.path,
                    displayName = ref.displayName,
                    content = content,
                    sizeBytes = ref.sizeBytes,
                    estimatedTokens = ref.estimatedTokens
                )
            }
        }
    }

    private fun deduplicateUserContextRefs(refs: List<ContextReference>): List<ContextReference> {
        if (refs.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<ContextReference>()
        refs.forEach { ref ->
            val providerId = ref.metadata["providerId"]?.toString() ?: ""
            val key = "${ref.type}:${ref.path}:$providerId"
            if (seen.add(key)) {
                deduped.add(ref)
            } else {
                logger.debug { "[CONTEXT_DEBUG] Deduplicated user context ref: $key" }
            }
        }
        return deduped
    }

    /**
     * Resolve user-provided context references (@file, @folder, @selection, etc.)
     * using ContextProviderRegistry as SINGLE SOURCE OF TRUTH.
     *
     * INTERNAL: This is now private. Use buildProjectContext() with userContextRefs parameter.
     *
     * Flow:
     * 1. For each ContextReference, determine provider ID
     * 2. Get provider from ContextProviderRegistry
     * 3. Call provider.getContextItems()
     * 4. Update ref.content with results
     *
     * @param refs User context references from PromptInputPanel
     * @param projectRoot Project root path for PathSandbox validation
     * @param project IntelliJ Project instance (nullable for core-only usage)
     * @return List of resolved references with loaded content
     */
    private suspend fun resolveUserContextReferences(
        refs: List<ContextReference>,
        projectRoot: Path,
        project: Project? = null,
        currentQuery: String? = null
    ): List<ContextReference> = withContext(Dispatchers.IO) {
        logger.info { "[CONTEXT] Resolving ${refs.size} user context reference(s)" }

        val pathSandbox = PathSandbox(projectRoot)

        refs.map { ref ->
            try {
                when (ref.type) {
                    ContextType.PROVIDER -> {
                        // Modern flow: direct provider reference
                        resolveProviderReference(ref, projectRoot, project, pathSandbox, currentQuery)
                    }
                    // Legacy types: map to providers for backwards compatibility
                    ContextType.FILE -> {
                        resolveLegacyFileReference(ref, projectRoot, project, pathSandbox)
                    }

                    ContextType.FOLDER -> {
                        resolveLegacyFolderReference(ref, projectRoot, project, pathSandbox)
                    }

                    ContextType.SELECTION -> {
                        // Selection already has content, just validate size
                        ref.copy(
                            estimatedTokens = (ref.content?.length ?: 0) / 4
                        )
                    }

                    ContextType.OPEN -> {
                        resolveLegacyOpenReference(ref, projectRoot, project, pathSandbox)
                    }

                    ContextType.RULES -> {
                        resolveLegacyRulesReference(ref, projectRoot, project, pathSandbox)
                    }

                    ContextType.DOCS -> {
                        resolveDocsReference(ref, projectRoot, project, currentQuery)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "[CONTEXT] Failed to resolve reference: ${ref.displayName}" }
                ref.copy(
                    content = "Error: ${e.message}",
                    estimatedTokens = 10
                )
            }
        }
    }

    /**
     * Resolve modern PROVIDER type reference using ContextProviderRegistry.
     */
    private suspend fun resolveProviderReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        pathSandbox: PathSandbox,
        currentQuery: String?
    ): ContextReference {
        val providerId = ref.metadata["providerId"] as? String
        if (providerId == null) {
            logger.warn { "[CONTEXT] Provider reference missing providerId in metadata" }
            return ref.copy(
                content = "Error: Provider ID not found in metadata",
                estimatedTokens = 10
            )
        }

        val provider = ContextProviderRegistry.getProvider(providerId)
        if (provider == null) {
            logger.warn { "[CONTEXT] Provider not found: $providerId" }
            return ref.copy(
                content = "Error: Provider not found: $providerId",
                estimatedTokens = 10
            )
        }

        logger.debug { "[CONTEXT] Calling provider: $providerId with query: ${ref.path}" }

        val fullInput = if (providerId == "docs") {
            currentQuery ?: ref.path
        } else {
            ref.path
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = fullInput,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            logger.debug { "[CONTEXT] Provider $providerId returned no items" }
            return ref.copy(
                content = "No items returned from provider: $providerId",
                estimatedTokens = 10
            )
        }

        // Concatenate all context items
        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.name} ---")
                appendLine(item.content)
            }
        }

        val fileAnalysisSummary = if (providerId == "file") {
            runCatching {
                val resolvedPath = pathSandbox.resolve(ref.path)
                analyzeAndSummarizeFile(projectRoot, resolvedPath)
            }.getOrNull()
        } else {
            null
        }

        val enrichedContent = buildString {
            if (!fileAnalysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(fileAnalysisSummary)
                appendLine()
            }
            append(content)
        }.trim()

        val sizeBytes = enrichedContent.length.toLong()
        val estimatedTokens = enrichedContent.length / 4

        logger.info { "[CONTEXT] Provider $providerId returned ${contextItems.size} item(s), ${enrichedContent.length} chars (analysis=${!fileAnalysisSummary.isNullOrBlank()})" }

        return ref.copy(
            content = enrichedContent,
            sizeBytes = sizeBytes,
            estimatedTokens = estimatedTokens
        )
    }

    private suspend fun resolveDocsReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        currentQuery: String?
    ): ContextReference {
        val provider = ContextProviderRegistry.getProvider("docs")
        if (provider == null) {
            logger.warn { "[CONTEXT] DocsContextProvider not registered" }
            return ref.copy(
                content = "Documentation provider is not available.",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = currentQuery ?: ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "No documentation content found for: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.description} ---")
                appendLine(item.content)
            }
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = content.length / 4
        )
    }

    /**
     * Resolve legacy FILE type by mapping to FileContextProvider.
     */
    private suspend fun resolveLegacyFileReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy FILE reference: ${ref.path}" }

        val provider = ContextProviderRegistry.getProvider("file")
        if (provider == null) {
            logger.error { "[CONTEXT] FileContextProvider not registered!" }
            return ref.copy(
                content = "Error: FileContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: File not found or not readable: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val item = contextItems.first()
        val analysisSummary = runCatching {
            val resolvedPath = pathSandbox.resolve(ref.path)
            analyzeAndSummarizeFile(projectRoot, resolvedPath)
        }.getOrNull()

        val content = buildString {
            if (!analysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(analysisSummary)
                appendLine()
            }
            appendLine("### File Content")
            appendLine(item.content)
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = content.length / 4
        )
    }

    /**
     * Resolve legacy FOLDER type by mapping to FolderContextProvider.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyFolderReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy FOLDER reference: ${ref.path}" }

        val provider = ContextProviderRegistry.getProvider("folder")
        if (provider == null) {
            logger.error { "[CONTEXT] FolderContextProvider not registered!" }
            return ref.copy(
                content = "Error: FolderContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: Folder not found: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val item = contextItems.first()
        return ref.copy(
            content = item.content,
            sizeBytes = item.content.length.toLong(),
            estimatedTokens = item.content.length / 4
        )
    }

    /**
     * Resolve legacy OPEN type by mapping to OpenFilesContextProvider.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyOpenReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy OPEN reference" }

        val provider = ContextProviderRegistry.getProvider("open_files")
        if (provider == null) {
            logger.error { "[CONTEXT] OpenFilesContextProvider not registered!" }
            return ref.copy(
                content = "Error: OpenFilesContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = "",
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = "",
            extras = extras
        )

        if (contextItems.isEmpty()) {
            logger.debug { "[CONTEXT] No open files found in editor" }
            return ref.copy(
                content = "No open files",
                estimatedTokens = 5
            )
        }

        logger.info { "[CONTEXT] Found ${contextItems.size} open files" }

        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.description} ---")
                appendLine(item.content)
            }
        }

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = content.length / 4
        )
    }

    /**
     * Resolve legacy RULES type by reading Agents.md or specified file.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyRulesReference(
        ref: ContextReference,
        projectRoot: Path,
        project: Project?,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy RULES reference" }

        val rulesPath = if (ref.path.isNotEmpty() && ref.path != "Agents.md") {
            ref.path
        } else {
            "Agents.md"
        }

        // Use FileContextProvider to read rules file
        val provider = ContextProviderRegistry.getProvider("file")
        if (provider == null) {
            logger.error { "[CONTEXT] FileContextProvider not registered!" }
            return ref.copy(
                content = "Error: FileContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = project,
            fullInput = rulesPath,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = rulesPath,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: Rules file not found: $rulesPath",
                estimatedTokens = 10
            )
        }

        val rulesContent = contextItems.first().content
        val analysisSummary = runCatching {
            val resolvedPath = pathSandbox.resolve(rulesPath)
            analyzeAndSummarizeFile(projectRoot, resolvedPath)
        }.getOrNull()

        val content = buildString {
            if (!analysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(analysisSummary)
                appendLine()
            }
            appendLine("### File Content")
            appendLine(rulesContent)
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = content.length / 4
        )
    }

    /**
     * Format resolved context references as string for LLM prompt.
     *
     * @param refs Resolved context references (with content loaded)
     * @return Formatted string with headers and content
     */
    fun formatContextReferencesForLLM(refs: List<ContextReference>): String {
        if (refs.isEmpty()) return ""

        val parts = mutableListOf<String>()

        refs.forEach { ref ->
            when (ref.type) {
                ContextType.FILE, ContextType.PROVIDER -> {
                    parts.add("=== ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Content not loaded")
                    parts.add("=== End of ${ref.displayName} ===")
                }

                ContextType.FOLDER -> {
                    parts.add("=== Folder: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Content not loaded")
                    parts.add("=== End of folder listing ===")
                }

                ContextType.SELECTION -> {
                    parts.add("=== Current Selection ===")
                    parts.add(ref.content ?: "Error: No selection")
                    parts.add("=== End of selection ===")
                }

                ContextType.OPEN -> {
                    parts.add("=== Open Files ===")
                    parts.add(ref.content ?: "Error: Could not get open files")
                    parts.add("=== End of open files ===")
                }

                ContextType.RULES -> {
                    parts.add("=== Rules: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Rules not loaded")
                    parts.add("=== End of rules ===")
                }

                ContextType.DOCS -> {
                    parts.add("=== Documentation: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Documentation not available")
                    parts.add("=== End of documentation ===")
                }
            }
        }

        return parts.joinToString("\n\n")
    }

    private suspend fun analyzeAndSummarizeFile(projectRoot: Path, absolutePath: Path): String? {
        val analyzer = fileAnalyzerService ?: return null
        return try {
            val analysis = analyzer.analyze(projectRoot, absolutePath)
            buildAnalysisSummary(analysis)
        } catch (e: Exception) {
            logger.warn(e) { "[CONTEXT] File analysis failed for $absolutePath" }
            null
        }
    }

    private fun buildAnalysisSummary(analysis: FileAnalysis): String {
        val builder = StringBuilder()
        builder.appendLine("Path: ${analysis.filePath}")
        builder.appendLine("Language: ${analysis.language ?: "unknown"}")

        if (analysis.language == "html" && !analysis.codeElements.documentation.isNullOrBlank()) {
            builder.appendLine(analysis.codeElements.documentation)
        }

        if (analysis.codeElements.classes.isNotEmpty()) {
            builder.appendLine("Classes:")
            analysis.codeElements.classes.take(5).forEach {
                builder.appendLine("  - ${it.name} (${it.startLine}-${it.endLine})")
            }
        }

        if (analysis.codeElements.functions.isNotEmpty()) {
            builder.appendLine("Functions:")
            analysis.codeElements.functions.take(5).forEach {
                builder.appendLine("  - ${it.name} (${it.startLine}-${it.endLine})")
            }
        }

        if (analysis.codeElements.frameworks.isNotEmpty()) {
            builder.appendLine("Frameworks: ${analysis.codeElements.frameworks.joinToString()}")
        }

        return builder.toString().trim()
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
        if (llmPrompt.isBlank()) {
            logger.debug { "[CONTEXT_TOKENS] Empty LLM prompt, skipping section token calculation" }
            return emptyMap()
        }

        // Section patterns that are expected in the generated prompt
        val sectionPatterns = listOf(
            "PROJECT_CONTEXT" to "project_overview",
            "PROJECT_INSTRUCTIONS" to "project_instructions",
            "CURRENT_TASK" to "current_task",
            "USER_REQUIREMENTS" to "user_requirements",
            "USER_PROVIDED_CONTEXT" to "user_context",
            "WORKING_MEMORY" to "working_memory",
            "MCP_RESOURCES" to "mcp_resources",
            "RAG_FRAGMENTS" to "rag_fragments",
            "CONVERSATION_HISTORY" to "conversation",
            "RECENT_WORK" to "recent_work",
            "SUBTASKS_STATUS" to "subtasks",
            "KEY_COMPONENTS" to "key_components",
            "PROJECT_DEPENDENCIES" to "dependencies",
            "PROJECT_ARCHITECTURE" to "architecture",
            "FRAMEWORK_ANALYSIS" to "framework_analysis",
            "TYPESCRIPT_ANALYSIS" to "typescript_analysis",
            "HTML_ANALYSIS" to "html_analysis",
            "CSS_ANALYSIS" to "css_analysis",
            "PATTERNS" to "patterns",
            "NAVIGATION_MAP" to "navigation_map",
            "CODE_ANALYSIS" to "code_analysis"
        )

        val sectionNames = mapOf(
            "project_overview" to "Project Context",
            "project_instructions" to "Project Instructions",
            "current_task" to "Current Task",
            "user_requirements" to "User Requirements",
            "user_context" to "User Context",
            "working_memory" to "Working Memory",
            "mcp_resources" to "MCP Resources",
            "rag_fragments" to "RAG Fragments",
            "conversation" to "Conversation History",
            "recent_work" to "Recent Work",
            "subtasks" to "Subtasks",
            "key_components" to "Key Components",
            "dependencies" to "Dependencies",
            "architecture" to "Architecture",
            "framework_analysis" to "Framework Analysis",
            "typescript_analysis" to "TypeScript Analysis",
            "html_analysis" to "HTML Analysis",
            "css_analysis" to "CSS Analysis",
            "patterns" to "Patterns",
            "navigation_map" to "Navigation Map",
            "code_analysis" to "Code Analysis"
        )

        // Parse explicit tagged sections from the final generated prompt.
        // Robust to truncated sections where closing tag was cut by token budget.
        // IMPORTANT: parse each section independently (from the whole prompt),
        // because prompt section order is not guaranteed to match sectionPatterns order.
        val parsedContents = mutableMapOf<String, Pair<String, Boolean>>() // key -> (content, hasClosingTag)

        for ((tag, key) in sectionPatterns) {
            val openTag = "<$tag>"
            val closeTag = "</$tag>"

            val openIndex = findTagAtLineStart(llmPrompt, openTag, 0)
            if (openIndex == -1) continue

            val contentStart = openIndex + openTag.length
            val closeIndex = findTagAtLineStart(llmPrompt, closeTag, contentStart)
            val nextSectionIndex = findNextSectionStart(llmPrompt, sectionPatterns, contentStart)

            val hasClosingTag = closeIndex != -1 && (nextSectionIndex == null || closeIndex <= nextSectionIndex)
            val contentEnd = when {
                hasClosingTag -> closeIndex
                nextSectionIndex != null -> nextSectionIndex
                else -> llmPrompt.length
            }

            if (contentEnd < contentStart) continue

            val content = llmPrompt.substring(contentStart, contentEnd)
            parsedContents[key] = content to hasClosingTag
        }

        val totalPromptChars = llmPrompt.length.coerceAtLeast(1)
        val result = mutableMapOf<String, ContextSectionTokenInfo>()

        // Process parsed sections only (no fallback estimation).
        for ((key, parsed) in parsedContents) {
            val (content, hasClosingTag) = parsed
            val tagName = sectionPatterns.firstOrNull { it.second == key }?.first ?: key
            val openTag = "<$tagName>"
            val closeTag = "</$tagName>"
            val sectionChars = content.length + openTag.length + if (hasClosingTag) closeTag.length else 0
            val tokens = (sectionChars / 4).toInt().coerceAtLeast(1)

            result[key] = ContextSectionTokenInfo(
                name = sectionNames[key] ?: key,
                tokens = tokens,
                chars = sectionChars,
                percentage = (sectionChars.toDouble() / totalPromptChars * 100)
            )
        }

        return result
    }

    private fun findNextSectionStart(
        prompt: String,
        sectionPatterns: List<Pair<String, String>>,
        fromIndex: Int
    ): Int? {
        var nextIndex: Int? = null
        for ((tag, _) in sectionPatterns) {
            val candidate = findTagAtLineStart(prompt, "<$tag>", fromIndex)
            if (candidate != -1 && (nextIndex == null || candidate < nextIndex)) {
                nextIndex = candidate
            }
        }
        return nextIndex
    }

    private fun findTagAtLineStart(prompt: String, tag: String, fromIndex: Int): Int {
        var index = prompt.indexOf(tag, fromIndex.coerceAtLeast(0))
        while (index != -1) {
            val lineStart = prompt.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            if (prompt.substring(lineStart, index).isBlank()) {
                return index
            }
            index = prompt.indexOf(tag, index + 1)
        }
        return -1
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

    /**
     * Collect user context references from chat message history.
     * Extracts all @mention context refs from user messages for given task.
     *
     * @param taskId Task ID to collect context refs for
     * @return List of context references from user messages
     */
    fun collectUserContextRefs(taskId: String): List<ContextReference> {
        val refs = mutableListOf<ContextReference>()
        val history = chatMessageRepository.findByTaskId(taskId)

        history.filter { it.role == pl.jclab.refio.core.db.MessageRole.USER }.forEach { message ->
            val metadata = pl.jclab.refio.api.models.UserContextMetadata.fromJson(message.metadata)
            if (metadata != null && metadata.contextRefs.isNotEmpty()) {
                refs.addAll(metadata.contextRefs)
            }
        }

        return refs
    }
}

/**
 * Result of building agent turn messages.
 * Contains all data needed by AgentTurnLoop to build the LLM prompt.
 */
data class AgentTurnMessagesResult(
    /** Conversation messages ready for LLM (filtered and formatted) */
    val messages: List<LLMMessage>,
    /** Project context prompt (project analysis, RAG, user @ mentions) */
    val projectContextPrompt: String,
    /** Size of conversation history before filtering */
    val historySize: Int
)




