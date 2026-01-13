package pl.jclab.refio.core.services

import com.intellij.openapi.project.Project
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.core.context.ContextProviderExtras
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.analysis.FileAnalysis
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.PlanningService.Companion.CONVERSATION_HISTORY_LIMIT
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.EmbeddingCircuitBreaker
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPToolCallResult
import pl.jclab.refio.core.context.mcp.MCPToolArgumentResolver
import pl.jclab.refio.core.context.mcp.MCPToolWorkflowExecutor
import pl.jclab.refio.core.context.mcp.MCPToolsExposureMode
import pl.jclab.refio.core.models.context.MCPContextResourceDTO
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import com.google.gson.reflect.TypeToken
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.Volatile

private val logger = dualLogger("ContextService")
private const val MAX_RAG_FRAGMENTS = 15
private const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"

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
    ragSearchService: RagSearchService? = null,
    ragSearchModel: String? = null,
    ragSearchProvider: String? = null
) {
    @Volatile
    private var ragSearchServiceRef: RagSearchService? = ragSearchService

    @Volatile
    private var ragSearchModelRef: String? = ragSearchModel

    @Volatile
    private var ragSearchProviderRef: String? = ragSearchProvider

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

    private val mcpToolCache = ConcurrentHashMap<McpToolCacheKey, McpToolCacheEntry>()
    private val mcpToolCacheTtlMs = 30_000L

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
     * @param userContextRefs User-provided context references from @ mentions (raw, unresolved)
     * @param project IntelliJ Project instance (optional, for IDE-specific resolution)
     * @param query Latest user prompt to seed hybrid RAG search
     * @param ragStepDescription Current step description/intent for hybrid RAG search
     */
    suspend fun buildProjectContext(
        projectRoot: Path,
        taskId: String,
        project: Project? = null,
        includeConversationHistory: Boolean = true,
        conversationHistoryLimit: Int = CONVERSATION_HISTORY_LIMIT,
        query: String? = null,
        userContextRefs: List<ContextReference> = emptyList(),
        trackOperation: Boolean = true
    ): ProjectContextDTO {
        logger.info { "Building project context for task=$taskId" }

        val contextToken = if (trackOperation) {
            GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("Project context"))
        } else {
            null
        }
        try {
            // 1. Get or load project analysis (cached)
            val analysisToken = if (trackOperation) {
                GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("Project analysis"))
            } else {
                null
            }
            val projectAnalysis = try {
                projectAnalyzer.analyzeProject(projectRoot, includeContent = false)
            } finally {
                if (analysisToken != null) {
                    GlobalMetrics.endOperation(analysisToken)
                }
            }

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
            val rawConversationHistory = if (includeConversationHistory) {
                val historyToken = if (trackOperation) {
                    GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("Conversation history"))
                } else {
                    null
                }
                try {
                    val allMessages = transaction { chatMessageRepository.findByTaskId(taskId) }
                    sliceConversationHistoryFromLastSummary(allMessages)
                        .take(conversationHistoryLimit * 2)  // Get more, then filter
                } finally {
                    if (historyToken != null) {
                        GlobalMetrics.endOperation(historyToken)
                    }
                }
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
        }.take(conversationHistoryLimit)

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
                content = msg.content,
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
            val ragToken = if (trackOperation) {
                GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("RAG search"))
            } else {
                null
            }
            val ragFragments = try {
                loadRagFragments(
                    projectRoot = projectRoot,
                    query = query
                )
            } finally {
                if (ragToken != null) {
                    GlobalMetrics.endOperation(ragToken)
                }
            }

            val mcpToken = if (trackOperation) {
                GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("MCP resources"))
            } else {
                null
            }
            val mcpResources = try {
                loadMcpResources(projectRoot, query)
            } finally {
                if (mcpToken != null) {
                    GlobalMetrics.endOperation(mcpToken)
                }
            }

        // 9. Resolve user context references (from @ mentions)
            val resolvedUserContext = if (dedupedUserContextRefs.isNotEmpty()) {
                logger.info { "[CONTEXT] Resolving ${dedupedUserContextRefs.size} user context reference(s)" }
                val resolveToken = if (trackOperation) {
                    GlobalMetrics.beginOperation(OperationInfo.ContextBuilding("Resolve @refs"))
                } else {
                    null
                }
                try {
                    resolveAndConvertUserContextRefs(dedupedUserContextRefs, projectRoot, project, query)
                } finally {
                    if (resolveToken != null) {
                        GlobalMetrics.endOperation(resolveToken)
                    }
                }
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
            files = emptyList(),  // Not included in default analysis

            // Task and subtasks
            currentTask = currentTask,
            subtasks = subtaskDTOs,
            subtaskContext = null,
            taskContext = null,

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

            // Multi-agent support (not used yet)
            agents = emptyList(),
            agentInfo = emptyList(),
            coordinationStrategy = null,
            agentConfig = emptyMap(),
            toolConfig = emptyMap(),

            // Additional context
            availableTools = emptyList(),
            templateReference = emptyMap(),
            mcpResources = mcpResources,

            // Context generation metadata
            contextGeneratedAt = Instant.now(),
            analyzerVersion = "kotlin-v1.0",
            domainAnalysis = projectAnalysis.domainAnalysis.domainScores,
            semanticMetaData = emptyMap(),
            workflowPatterns = emptyMap(),
            llmContext = emptyMap(),
            semanticSummary = semanticSummary,

            // Error information
            error = null,
            sectionTokens = null
        )

            val sectionTokens = calculateContextSectionTokens(projectContext, "")
            return projectContext.copy(sectionTokens = sectionTokens)
        } finally {
            if (contextToken != null) {
                GlobalMetrics.endOperation(contextToken)
            }
        }
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
                        val content = "Failed to parse tool arguments for ${toolDef.name}: ${error?.message ?: "unknown error"}"
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
            val endpoint = configService.getOllamaEndpoint()
            val providerKey = "ollama:$endpoint"
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                val retryMs = EmbeddingCircuitBreaker.getCooldownRemaining(providerKey)
                logger.warn { "[CONTEXT] Skipping RAG fragments - Ollama unavailable (circuit OPEN, retry in ${retryMs}ms, endpoint=$endpoint)" }
                return emptyList()
            }
        }

        val queryParts = listOfNotNull(query?.trim())
            .filter { it.isNotBlank() }
        if (queryParts.isEmpty()) {
            logger.info { "[CONTEXT] No RAG query data provided - skipping fragments" }
            return emptyList()
        }

        val combinedQuery = queryParts.joinToString("\n\n")
        val keywords = extractRagKeywords(queryParts)
        val hybridEnabled = configService.getRagSearchHybridEnabled()

        return try {
            logger.info {
                "[CONTEXT] Running hybrid RAG search: query='${combinedQuery.take(120)}...', keywords=$keywords"
            }
            val config = RagSearchConfig(
                similarityThreshold = configService.getRagSearchSimilarityThreshold(),
                topK = MAX_RAG_FRAGMENTS,
                hybridSearch = hybridEnabled,
                keywords = keywords,
                semanticWeight = configService.getRagSearchSemanticWeight(),
                includeContextChunks = configService.getRagSearchIncludeContextChunks()
            )
            val results = searchService.search(
                projectRoot = projectRoot.toString(),
                query = combinedQuery,
                model = model,
                config = config
            )

            val out=results.map { result ->
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
    fun buildLLMContextPrompt(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()

        // ========== SECTION 1: PROJECT CONTEXT (Agent needs to know the project first) ==========

        // 1. PROJECT OVERVIEW - What kind of project is this?
        parts.add(buildProjectOverview(context))

        // 1a. SEMANTIC SUMMARY - Compact summary for small LLMs
        context.semanticSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            parts.add(summary)
        }

        // 2. KEY COMPONENTS - Main files and architecture
        if (context.keyComponents.isNotEmpty()) {
            parts.add(buildKeyComponentsSection(context))
        }

        // 3. CODE ANALYSIS - Project structure (classes, functions, patterns)
        if (hasCodeAnalysis(context.codeAnalysis)) {
            parts.add(buildCodeAnalysisSection(context))
        }

        // 3a. TypeScript Analysis
        if (hasTypeScriptAnalysis(context.codeAnalysis)) {
            parts.add(buildTypeScriptAnalysisSection(context))
        }

        // 3b. HTML Analysis
        if (hasHtmlAnalysis(context.codeAnalysis)) {
            parts.add(buildHtmlAnalysisSection(context))
        }

        // 3c. CSS Analysis
        if (hasCssAnalysis(context.codeAnalysis)) {
            parts.add(buildCssAnalysisSection(context))
        }

        // 4. DEPENDENCIES - Project dependencies
        if (context.dependencies.python.isNotEmpty() || context.dependencies.javascript.isNotEmpty()) {
            parts.add(buildDependenciesSection(context))
        }

        // ========== SECTION 2: TASK & REQUIREMENTS (What needs to be done) ==========

        // 5. CURRENT TASK - The task to complete
        parts.add(buildCurrentTaskSection(context))

        // 6. USER REQUIREMENTS - Explicit requirements from task description
        if (context.userRequirements.isNotEmpty()) {
            parts.add(buildUserRequirementsSection(context))
        }

        // ========== SECTION 3: SUPPORTING CONTEXT (User-provided and RAG) ==========

        // 7. USER-PROVIDED CONTEXT - @mentions (files, selections, etc.)
        if (context.userContextRefs.isNotEmpty()) {
            parts.add(buildUserContextSection(context))
        }

        // 7a. MCP RESOURCES - external context from MCP servers
        if (context.mcpResources.isNotEmpty()) {
            parts.add(buildMcpResourcesSection(context))
        }

        // 8. RAG FRAGMENTS - Semantically similar code/docs
        if (context.ragFragments.isNotEmpty()) {
            parts.add(buildRagFragmentsSection(context))
        }

        // ========== SECTION 4: HISTORY (Previous work and conversation) ==========

        // 10. CONVERSATION HISTORY - Recent interaction context
        if (context.conversationHistory.isNotEmpty()) {
            parts.add(buildConversationHistorySection(context))
        }

        // 11. SUBTASKS STATUS - Current state of all subtasks
        if (context.subtasks.isNotEmpty()) {
            parts.add(buildSubtasksStatusSection(context))
        }

        // 13. RECENT WORK - Completed files and subtask summaries
        if (context.completedFiles.isNotEmpty() || context.previousSubtasks.isNotEmpty() || context.executedSteps.isNotEmpty()) {
            parts.add(buildRecentWorkSection(context))
        }

        val contextPrompt = parts.joinToString("\n\n")

        logger.info { "Generated context prompt: ${contextPrompt.length} chars" }

        return contextPrompt
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
        parts.add(buildKeyComponentsSummary(richReport))
        parts.add(buildPatternsAndConventions(richReport))
        parts.add(buildExternalDependenciesList(richReport))
        parts.add(buildNavigationMap(richReport))

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

        return """
            <PROJECT_ARCHITECTURE>
            Style: $style
            Primary Language: ${analysis.primaryLanguage}
            $layersLine
            Key Entry Points: ${if (entryPoints.isNotEmpty()) entryPoints.joinToString(", ") else "N/A"}
            </PROJECT_ARCHITECTURE>
        """.trimIndent()
    }

    private fun buildKeyComponentsSummary(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String {
        if (rich == null) return "<KEY_COMPONENTS>No detailed analysis available</KEY_COMPONENTS>"

        val classes = rich.codeStructure.classes
        val controllers = classes.filter { cls -> cls.annotations.any { it.contains("Controller") } }
        val services = classes.filter { cls -> cls.annotations.any { it.contains("Service") } }
        val repositories = classes.filter { cls -> cls.annotations.any { it.contains("Repository") } }
        val models = classes.filter { cls ->
            cls.name.endsWith("DTO") || cls.name.endsWith("Entity") || cls.modifiers.contains("data")
        }

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

        parts.add("</KEY_COMPONENTS>")
        return parts.joinToString("\n")
    }

    private fun buildPatternsAndConventions(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String {
        if (rich == null) return "<PATTERNS>No detailed analysis available</PATTERNS>"

        val patterns = rich.patterns
        val frameworkPatterns = patterns.frameworkPatterns.take(5).joinToString(", ") {
            "${it.framework}:${it.pattern}"
        }
        val naming = patterns.namingConventions

        return """
            <PATTERNS>
            Framework patterns: ${if (frameworkPatterns.isNotBlank()) frameworkPatterns else "N/A"}
            Naming: class=${naming.classNaming}, method=${naming.methodNaming}
            </PATTERNS>
        """.trimIndent()
    }

    private fun buildExternalDependenciesList(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String {
        if (rich == null) return "<EXTERNAL_DEPENDENCIES>No detailed analysis available</EXTERNAL_DEPENDENCIES>"
        val deps = rich.dependencies.externalDependencies.take(10).map { "- ${it.name} (usage: ${it.usageCount})" }
        val body = if (deps.isNotEmpty()) deps.joinToString("\n") else "- None detected"
        return "<EXTERNAL_DEPENDENCIES>\n$body\n</EXTERNAL_DEPENDENCIES>"
    }

    private fun buildNavigationMap(
        rich: pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport?
    ): String {
        val structure = rich?.codeStructure ?: return "<NAVIGATION_MAP>No detailed analysis available</NAVIGATION_MAP>"

        val packageMap = structure.packages.groupBy { pkg ->
            when {
                pkg.name.contains(".api") || pkg.name.contains(".controller") -> "API"
                pkg.name.contains(".service") -> "Business Logic"
                pkg.name.contains(".repository") || pkg.name.contains(".db") -> "Data Access"
                pkg.name.contains(".model") || pkg.name.contains(".dto") -> "Models"
                pkg.name.contains(".config") -> "Configuration"
                pkg.name.contains(".util") -> "Utilities"
                else -> "Other"
            }
        }

        val parts = mutableListOf<String>()
        parts.add("<NAVIGATION_MAP>")
        parts.add("Where to find what:")

        packageMap.entries.sortedBy { it.key }.forEach { (category, packages) ->
            val names = packages.map { it.name.substringAfterLast('.') }.distinct().take(5)
            parts.add("- $category: ${names.joinToString(", ")}")
        }

        parts.add("</NAVIGATION_MAP>")
        return parts.joinToString("\n")
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

    private fun buildProjectOverview(context: ProjectContextDTO): String {
        val projectName = context.metaData.projectName
        val osName = System.getProperty("os.name")?.ifBlank { "Unknown OS" } ?: "Unknown OS"
        val currentDate = runCatching { LocalDate.now().toString() }.getOrElse { "Unknown date" }

        // Build architecture description from key components
        val architecture = if (context.keyComponents.isNotEmpty()) {
            "Architecture: Key components: ${context.keyComponents.take(5).joinToString(", ")}"
        } else if (context.summary.architectureNotes != null) {
            "Architecture: ${context.summary.architectureNotes}"
        } else {
            ""
        }

        // Build file types summary
        val fileTypesSummary = context.structure.fileTypes.entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString(", ") { ".${it.key}(${it.value})" }

        return """
            |<PROJECT_OVERVIEW>
            |Project: $projectName
            |Type: ${context.projectType} | Language: ${context.summary.mainLanguage} | Complexity: ${context.summary.complexity}
            |Runtime: $osName | Date: $currentDate
            |Files: ${context.structure.totalFiles}
            |Technologies: ${context.technologies.joinToString(", ")}
            |${if (architecture.isNotBlank()) "$architecture\n" else ""}|File Types: $fileTypesSummary
            |</PROJECT_OVERVIEW>
        """.trimMargin()
    }

    private fun buildDependenciesSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<PROJECT_DEPENDENCIES>")

        if (context.dependencies.python.isNotEmpty()) {
            parts.add(
                "Python: ${
                    context.dependencies.python.take(15).joinToString(", ")
                }${if (context.dependencies.python.size > 15) " (+${context.dependencies.python.size - 15} more)" else ""}"
            )
        }

        if (context.dependencies.javascript.isNotEmpty()) {
            parts.add(
                "JavaScript: ${
                    context.dependencies.javascript.take(15).joinToString(", ")
                }${if (context.dependencies.javascript.size > 15) " (+${context.dependencies.javascript.size - 15} more)" else ""}"
            )
        }

        parts.add("</PROJECT_DEPENDENCIES>")
        return parts.joinToString("\n")
    }

    private fun buildCodeAnalysisSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<CODE_ANALYSIS>")

        context.codeAnalysis.kotlin.let { kotlin ->
            if (kotlin["files"] as? Int ?: 0 > 0) {
                parts.add("Kotlin: ${kotlin["files"]} files, ${kotlin["classes"]} classes, ${kotlin["functions"]} functions")
            }
        }

        context.codeAnalysis.java.let { java ->
            if (java["files"] as? Int ?: 0 > 0) {
                parts.add("Java: ${java["files"]} files, ${java["classes"]} classes")
            }
        }

        context.codeAnalysis.python.let { python ->
            if (python["files"] as? Int ?: 0 > 0) {
                parts.add("Python: ${python["files"]} files, ${python["classes"]} classes, ${python["functions"]} functions")
            }
        }

        context.codeAnalysis.javascript.let { js ->
            if (js["files"] as? Int ?: 0 > 0) {
                parts.add("JavaScript: ${js["files"]} files, ${js["classes"]} classes, ${js["functions"]} functions")
            }
        }

        parts.add("</CODE_ANALYSIS>")
        return parts.joinToString("\n")
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
    private fun buildRagFragmentsSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<RAG_FRAGMENTS>")

        context.ragFragments.take(MAX_RAG_FRAGMENTS).forEach { fragment ->
            parts.add("")
            val label = if (fragment.contentType.equals("DOCUMENTATION", ignoreCase = true)) {
                "Source"
            } else {
                "File"
            }
            parts.add("$label: ${fragment.filePath}")
            if (fragment.startLine != null && fragment.endLine != null) {
                parts.add("Lines: ${fragment.startLine}-${fragment.endLine}")
            }
            parts.add("Type: ${fragment.contentType}")
            parts.add("Similarity: ${String.format("%.2f", fragment.similarity)}")
            parts.add("```")
            parts.add(fragment.content.trim())
            parts.add("```")
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

        return """
            |<CURRENT_TASK>
            |${task.description}${if (task.status == "SUCCESS") " [completed]" else if (task.status == "RUNNING") " [in progress]" else ""}
            |${task.description}
            |
            |${if (context.subtasks.isNotEmpty()) "Subtasks: ${context.subtasks.size} total ($statusSummary)" else ""}
            |</CURRENT_TASK>
        """.trimMargin()
    }

    /**
     * Build subtasks status section (renamed from buildCompletedSubtasksSection - ADR 0040).
     * Shows ALL subtasks with their current status, not just completed ones.
     */
    private fun buildSubtasksStatusSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<SUBTASKS_STATUS>")

        // Group subtasks by status for better readability
        val completed = context.subtasks.filter { it.status == "SUCCESS" }
        val failed = context.subtasks.filter { it.status == "FAILED" }
        val running = context.subtasks.filter { it.status == "RUNNING" }
        val pending = context.subtasks.filter { it.status in listOf("PENDING", "PLANNED", "NEW") }

        if (completed.isNotEmpty()) {
            parts.add("Completed (${completed.size}):")
            completed.forEach { parts.add("  ✓ ${it.description}") }
        }
        if (running.isNotEmpty()) {
            parts.add("Running (${running.size}):")
            running.forEach { parts.add("  ⏳ ${it.description}") }
        }
        if (pending.isNotEmpty()) {
            parts.add("Pending (${pending.size}):")
            pending.forEach { parts.add("  ○ ${it.description}") }
        }
        if (failed.isNotEmpty()) {
            parts.add("Failed (${failed.size}):")
            failed.forEach { parts.add("  ✗ ${it.description}") }
        }

        parts.add("</SUBTASKS_STATUS>")
        return parts.joinToString("\n")
    }

    private fun buildConversationHistorySection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<CONVERSATION_CONTEXT>")

        val history = context.conversationHistory
        val firstMessage = history.firstOrNull()
        val firstIsSummary = firstMessage?.metadata?.get("type") == CONVERSATION_SUMMARY_METADATA_TYPE

        if (firstIsSummary && firstMessage != null) {
            val summaryContent = firstMessage.content.take(4096).trim()
            parts.add("=== PREVIOUS CONVERSATION SUMMARY ===")
            parts.add(summaryContent)
            parts.add("")
            parts.add("=== RECENT MESSAGES ===")

            history.drop(1).takeLast(15).forEach { msg ->
                val content = msg.content.take(4096)
                parts.add("[${msg.role.uppercase()}]\n${content.trim()}\n")
            }
        } else {
            history.takeLast(15).forEach { msg ->
                val content = msg.content.take(4096)
                parts.add("[${msg.role.uppercase()}]\n${content.trim()}\n")
            }
        }

        parts.add("</CONVERSATION_CONTEXT>")
        return parts.joinToString("\n")
    }

    /**
     * Build recent work section (IMPROVED - PHASE 3 + NEW).
     * Uses summarizeFileChanges() for concise file grouping.
     * NEW: Includes full outputs from DATA_PRODUCING tools (search, grep, read).
     */
    private fun buildRecentWorkSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()

        // 1. Group recent file changes by extension using summarizeFileChanges()
        if (context.completedFiles.isNotEmpty()) {
            val fileSummary = summarizeFileChanges(context.completedFiles.take(25))  // ADR 0040: increased from 15
            if (fileSummary.isNotBlank()) {
                parts.add("<RECENT_FILE_CHANGES>")
                parts.add(fileSummary)
                parts.add("</RECENT_FILE_CHANGES>")
                parts.add("")
            }
        }

        val executedSteps = context.executedSteps.takeLast(1)

        // 2. Structured recent work (executed steps)
        if (executedSteps.isNotEmpty()) {
            val gson = pl.jclab.refio.core.utils.GsonInstance.gson
            parts.add("<RECENT_WORK>")
            executedSteps.forEach { step ->
                val fileLabel = step.file?.let { filePath ->
                    val name = runCatching { Path.of(filePath).fileName.toString() }.getOrElse { filePath }
                    "$name ($filePath)"
                } ?: "n/a"

                parts.add("File: $fileLabel")
                parts.add("Tool: ${step.tool}")
                parts.add("Parameters: ${formatParameters(step.parameters, gson)}")
                parts.add("Result: ```\n${truncateForRecentWork(step.result)}\n```")
                parts.add("")
            }
            parts.add("</RECENT_WORK>")
        } else if (context.previousSubtasks.isNotEmpty()) {
            // 2. Fallback: Recent work summary (last completed subtasks) - ADR 0040: increased limit from 150 to 300
            parts.add("<RECENT_WORK>")
            context.previousSubtasks.takeLast(10).forEach { subtask ->
                // Clean up JSON if present in subtask summary
                val cleanSummary = cleanSubtaskSummary(subtask)
                parts.add("- ${cleanSummary.take(300)}${if (cleanSummary.length > 300) "..." else ""}")
            }
            parts.add("</RECENT_WORK>")
        }

        return parts.joinToString("\n")
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

    private fun formatParameters(params: Map<String, Any>, gson: com.google.gson.Gson): String {
        if (params.isEmpty()) return "-"
        val json = gson.toJson(params)
        return if (json.length > 1024) "${json.take(1024)}..." else json
    }

    private fun truncateForRecentWork(text: String?, limit: Int = 8192): String {
        if (text.isNullOrBlank()) return "-"
        return if (text.length > limit) "${text.take(limit)}..." else text
    }

    private fun hasCodeAnalysis(codeAnalysis: CodeAnalysisDTO): Boolean {
        return (codeAnalysis.kotlin["files"] as? Int ?: 0) > 0 ||
                (codeAnalysis.java["files"] as? Int ?: 0) > 0 ||
                (codeAnalysis.python["files"] as? Int ?: 0) > 0 ||
                (codeAnalysis.javascript["files"] as? Int ?: 0) > 0
    }

    private fun hasTypeScriptAnalysis(codeAnalysis: CodeAnalysisDTO): Boolean {
        return (codeAnalysis.typescript["files"] as? Int ?: 0) > 0
    }

    private fun hasHtmlAnalysis(codeAnalysis: CodeAnalysisDTO): Boolean {
        return (codeAnalysis.html["files"] as? Int ?: 0) > 0
    }

    private fun hasCssAnalysis(codeAnalysis: CodeAnalysisDTO): Boolean {
        return (codeAnalysis.css["files"] as? Int ?: 0) > 0
    }

    private fun buildTypeScriptAnalysisSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<TYPESCRIPT_ANALYSIS>")

        val ts = context.codeAnalysis.typescript
        val filesCount = ts["files"] as? Int ?: 0
        val interfacesCount = ts["interfaces"] as? Int ?: 0
        val typesCount = ts["types"] as? Int ?: 0
        val classesCount = ts["classes"] as? Int ?: 0
        val functionsCount = ts["functions"] as? Int ?: 0

        parts.add("Files: $filesCount")
        if (interfacesCount > 0 || typesCount > 0) {
            parts.add("Interfaces: $interfacesCount, Types: $typesCount")
        }
        if (classesCount > 0) {
            parts.add("Classes: $classesCount")
        }
        if (functionsCount > 0) {
            parts.add("Functions: $functionsCount")
        }

        // Add sample interface/type names if available
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

        parts.add("</TYPESCRIPT_ANALYSIS>")
        return parts.joinToString("\n")
    }

    private fun buildHtmlAnalysisSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<HTML_ANALYSIS>")

        val html = context.codeAnalysis.html
        val filesCount = html["files"] as? Int ?: 0
        parts.add("HTML Files: $filesCount")

        @Suppress("UNCHECKED_CAST")
        val pages = html["pages"] as? List<Map<String, Any>>
        if (!pages.isNullOrEmpty()) {
            parts.add("")
            parts.add("Pages:")

            val toProcess = if (pages.size > 10) {
                pages.take(5).union(pages.takeLast(5))
            } else {
                pages
            }

            toProcess.take(5).union(pages.takeLast(5)).forEach { page ->
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

                @Suppress("UNCHECKED_CAST")
                val gameIndicators = page["game_indicators"] as? List<String>
                if (!gameIndicators.isNullOrEmpty()) {
                    parts.add("    Game indicators: ${gameIndicators.joinToString(", ")}")
                }

                @Suppress("UNCHECKED_CAST")
                val canvasIds = page["canvas_ids"] as? List<String>
                if (!canvasIds.isNullOrEmpty()) {
                    parts.add("    Canvas IDs: ${canvasIds.joinToString(", ")}")
                }
            }

            if (pages.size > 10) {
                parts.add("  ... and ${pages.size - 10} more pages")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val canvasGames = html["canvas_games"] as? List<String>
        if (!canvasGames.isNullOrEmpty()) {
            parts.add("")
            parts.add("Canvas Games Detected: ${canvasGames.size}")
            parts.add("  ${canvasGames.take(5).joinToString(", ")}")
            if (canvasGames.size > 5) {
                parts.add("  ... and ${canvasGames.size - 5} more")
            }
        }

        parts.add("</HTML_ANALYSIS>")
        return parts.joinToString("\n")
    }

    private fun buildCssAnalysisSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<CSS_ANALYSIS>")

        val css = context.codeAnalysis.css
        val filesCount = css["files"] as? Int ?: 0
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
            if (variables.size > 10) {
                parts.add("  ... and ${variables.size - 10} more")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val animations = css["animations"] as? List<String>
        if (!animations.isNullOrEmpty()) {
            parts.add("Animations: ${animations.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val mediaQueries = css["media_queries"] as? List<String>
        if (!mediaQueries.isNullOrEmpty()) {
            parts.add("Media Queries: ${mediaQueries.take(3).joinToString(", ")}")
            if (mediaQueries.size > 3) {
                parts.add("  ... and ${mediaQueries.size - 3} more")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val classes = css["classes"] as? List<String>
        if (!classes.isNullOrEmpty() && classesCount > 0) {
            parts.add("")
            parts.add("Common Classes: ${classes.take(15).joinToString(", ")}")
            if (classes.size > 15) {
                parts.add("  ... and ${classes.size - 15} more")
            }
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
        val skipPhrases = listOf(
            "task started",
            "communication completed",
            "beginning autonomous execution"
        )

        return messages.filter { msg ->
            val content = msg.content.trim()
            content.length >= 20 &&
                    !skipPhrases.any { content.contains(it, ignoreCase = true) } &&
                    !content.startsWith("**Tool used:**") &&
                    !content.startsWith("**Summary:**")
        }
    }

    private fun sliceConversationHistoryFromLastSummary(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        return if (lastSummaryIndex >= 0) {
            messages.drop(lastSummaryIndex)
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
            try {
                val parsed = gson.fromJson<Map<String, Any>>(rawResult, mapType) ?: return@forEach
                val outputs = parsed["outputs"] as? List<*>
                if (outputs.isNullOrEmpty()) return@forEach

                outputs.mapNotNull { it as? Map<*, *> }.forEach { output ->
                    val tool = output["tool"] as? String ?: prevSubtask.kind.name
                    val paramsMap = toStringAnyMap(output["params"] as? Map<*, *>)
                    val resultMap = output["result"] as? Map<*, *>
                    val filePath = pickFilePath(
                        paramsMap = output["params"] as? Map<*, *>,
                        resultMap = resultMap,
                        affectedFiles = output["affectedFiles"] as? List<*>
                    )
                    val resultText = extractResultText(resultMap, gson)
                    val timestamp = prevSubtask.completedAt ?: prevSubtask.updatedAt

                    executedSteps.add(
                        ExecutedStepDTO(
                            file = filePath,
                            tool = tool,
                            parameters = paramsMap,
                            result = resultText,
                            timestamp = Instant.ofEpochMilli(timestamp)
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "[CONTEXT] Failed to parse executed step from subtask ${prevSubtask.id}" }
            }
        }

        // Keep only the most recent entries if outputs expanded the list beyond limit
        return executedSteps.takeLast(limit)
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
                        resolveProviderReference(ref, projectRoot, project, pathSandbox)
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
        pathSandbox: PathSandbox
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
    fun calculateContextSectionTokens(
        context: ProjectContextDTO,
        llmPrompt: String
    ): Map<String, ContextSectionTokenInfo> {
        val sections = mutableMapOf<String, Int>()  // section -> chars

        // Project Overview section
        val projectOverviewChars = listOf(
            context.workspace.path,
            context.projectType,
            context.summary.mainLanguage,
            context.summary.complexity,
            context.technologies.joinToString(", ")
        ).sumOf { it.length }
        sections["project_overview"] = projectOverviewChars

        // Dependencies section
        val depsChars = context.dependencies.python.sumOf { it.length } +
                context.dependencies.javascript.sumOf { it.length }
        sections["dependencies"] = depsChars

        // Code Analysis section
        val codeAnalysisChars = context.codeAnalysis.kotlin.toString().length +
                context.codeAnalysis.java.toString().length +
                context.codeAnalysis.python.toString().length +
                context.codeAnalysis.typescript.toString().length
        sections["code_analysis"] = codeAnalysisChars.coerceAtLeast(100)

        // Semantic summary section
        val semanticSummaryChars = context.semanticSummary?.length ?: 0
        if (semanticSummaryChars > 0) {
            sections["semantic_summary"] = semanticSummaryChars
        }

        // Current Task section
        val taskChars = (context.currentTask?.name?.length ?: 0) +
                (context.currentTask?.description?.length ?: 0)
        sections["current_task"] = taskChars

        // Subtasks section
        val subtasksChars = context.subtasks.sumOf { it.description.length }
        sections["subtasks"] = subtasksChars

        // Conversation History section
        val conversationChars = context.conversationHistory.sumOf { it.content.length }
        sections["conversation"] = conversationChars

        // RAG fragments section
        val ragChars = context.ragFragments.sumOf { it.content.length }
        sections["rag_fragments"] = ragChars

        // User Context section
        val userContextChars = context.userContextRefs.sumOf { it.content.length }
        sections["user_context"] = userContextChars

        // MCP Resources section
        val mcpChars = context.mcpResources.sumOf { (it.description ?: "").length + it.name.length }
        sections["mcp_resources"] = mcpChars

        // Recent Work section
        val recentWorkChars = context.completedFiles.sumOf { it.length } +
                context.previousSubtasks.sumOf { it.length }
        sections["recent_work"] = recentWorkChars

        // Calculate total chars from all sections
        val totalSectionChars = sections.values.sum().coerceAtLeast(1)

        // Section display names
        val sectionNames = mapOf(
            "project_overview" to "Project Overview",
            "dependencies" to "Dependencies",
            "code_analysis" to "Code Analysis",
            "semantic_summary" to "Semantic Summary",
            "current_task" to "Current Task",
            "subtasks" to "Subtasks",
            "conversation" to "Conversation History",
            "rag_fragments" to "RAG Fragments",
            "user_context" to "User Context (@mentions)",
            "mcp_resources" to "MCP Resources",
            "tool_outputs" to "Previous Tool Outputs",
            "recent_work" to "Recent Work"
        )

        // Convert to ContextSectionTokenInfo with percentages
        return sections.mapValues { (key, chars) ->
            val tokens = chars / 4
            val percentage = (chars.toDouble() / totalSectionChars * 100)

            ContextSectionTokenInfo(
                name = sectionNames[key] ?: key,
                tokens = tokens,
                chars = chars,
                percentage = percentage
            )
        }
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

                    refWithoutAt == "open" || refWithoutAt.startsWith("open:") -> {
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
