package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import pl.jclab.refio.core.api.CoreApiRouter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Observability ViewModel — RAG, context, logs, debug, API logs, file browser, content viewer, help.
 * Extracted from TuiViewModel to separate observability/tooling state from chat/session concerns.
 */
class TuiObservabilityViewModel(
    val scope: CoroutineScope,
    internal val getRouter: () -> CoreApiRouter?,
    internal val getTaskId: () -> String?,
    internal val projectPath: Path,
    internal val addSystemMessageFn: (String) -> Unit,
    internal val insertContextFn: (String) -> Unit
) {
    // --- RAG ---
    val _ragIndexingProgress = MutableStateFlow(-1.0)
    val _ragIndexingStatus = MutableStateFlow("")
    val _ragIndexedFiles = MutableStateFlow<List<TuiRagFile>>(emptyList())
    val _ragSelectedFileIndex = MutableStateFlow(0)
    val _ragSearchQuery = MutableStateFlow("")
    val _ragSearchResults = MutableStateFlow<List<String>>(emptyList())

    // --- Context ---
    val _contextSections = MutableStateFlow<List<TuiContextSection>>(emptyList())
    val _contextMaxTokens = MutableStateFlow(128_000)
    val _selectedContextIndex = MutableStateFlow(0)
    val _contextDetailVisible = MutableStateFlow(false)
    val _contextDetailScrollOffset = MutableStateFlow(0)

    // --- Logs ---
    val _logs = MutableStateFlow<List<TuiLogEntry>>(emptyList())
    val _logsPaused = MutableStateFlow(false)
    val _selectedLogIndex = MutableStateFlow(0)
    val _logDetailVisible = MutableStateFlow(false)
    val _logsFilter = MutableStateFlow<String?>(null)

    // --- API Logs ---
    val _apiLogs = MutableStateFlow<List<TuiApiLogEntry>>(emptyList())
    val _apiLogsFilter = MutableStateFlow<String?>(null)
    val _selectedApiLogIndex = MutableStateFlow(0)
    val _apiLogDetailVisible = MutableStateFlow(false)
    val _apiLogDetailScrollOffset = MutableStateFlow(0)

    // --- Debug ---
    val _debugInfo = MutableStateFlow(TuiDebugInfo())
    val _debugScrollOffset = MutableStateFlow(0)

    // --- File Browser ---
    val _fileBrowserPath = MutableStateFlow("")
    val _fileBrowserEntries = MutableStateFlow<List<TuiFileEntry>>(emptyList())
    val _fileBrowserSelectedIndex = MutableStateFlow(0)
    val _fileBrowserShowHidden = MutableStateFlow(false)

    // --- File/Content Viewer ---
    val _fileViewerVisible = MutableStateFlow(false)
    val _fileViewerPath = MutableStateFlow("")
    val _fileViewerContent = MutableStateFlow("")
    val _fileViewerScrollOffset = MutableStateFlow(0)
    val _fileViewerShowLineNumbers = MutableStateFlow(true)
    val _fileViewerAllowAddContext = MutableStateFlow(true)

    // --- Help ---
    val _helpScrollOffset = MutableStateFlow(0)

    /** LogSink that feeds core log messages into the Logs tab. */
    val tuiLogSink = TuiLogSink(_logs)

    private val apiLogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private var ragJob: kotlinx.coroutines.Job? = null

    // ========================================================================
    // RAG operations
    // ========================================================================

    fun ragReindex() {
        ragJob = scope.launch {
            val r = getRouter() ?: return@launch
            try {
                _ragIndexingStatus.value = "Indexing..."
                _ragIndexingProgress.value = 0.0
                r.ragRouter.indexProjectForRag(onProgress = { progress ->
                    val pct = if (progress.totalFiles > 0) progress.processedFiles.toDouble() / progress.totalFiles else 0.0
                    _ragIndexingProgress.value = pct
                    _ragIndexingStatus.value = progress.message
                })
                _ragIndexingStatus.value = "Indexing complete"
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                logger.warn(e) { "RAG reindex failed" }
                _ragIndexingStatus.value = "Error: ${e.message}"
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    fun ragGenerateEmbeddings() {
        ragJob = scope.launch {
            val r = getRouter() ?: return@launch
            try {
                _ragIndexingStatus.value = "Generating embeddings..."
                _ragIndexingProgress.value = 0.0
                r.ragRouter.generateEmbeddings(onProgress = { progress ->
                    _ragIndexingProgress.value = progress.progressPercent / 100.0
                    _ragIndexingStatus.value = progress.statusMessage
                })
                _ragIndexingStatus.value = "Embeddings complete"
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                logger.warn(e) { "Embedding generation failed" }
                _ragIndexingStatus.value = "Error: ${e.message}"
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    fun ragSearch(query: String) {
        _ragSearchQuery.value = query
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                val results = r.ragRouter.searchRag(query, topK = 5)
                // Populate RAG tab state
                _ragSearchResults.value = results.map { result ->
                    val score = String.format("%.2f", result.similarity)
                    val lines = if (result.startLine != null) ":${result.startLine}-${result.endLine}" else ""
                    val preview = result.content.take(120).replace("\n", " ")
                    "[$score] ${result.filePath}$lines: $preview"
                }
                // Also show in chat
                val resultText = if (results.isEmpty()) {
                    "No results for: $query"
                } else {
                    buildString {
                        appendLine("RAG search results for: $query")
                        appendLine()
                        for ((i, result) in results.withIndex()) {
                            appendLine("${i + 1}. ${result.filePath}:${result.startLine ?: ""}")
                            appendLine("   Score: ${String.format("%.3f", result.similarity)}")
                            appendLine("   ${result.content.take(120)}...")
                            appendLine()
                        }
                    }
                }
                addSystemMessageFn(resultText)
            } catch (e: Exception) {
                logger.warn(e) { "RAG search failed" }
                _ragSearchResults.value = listOf("Search error: ${e.message}")
                addSystemMessageFn("RAG search error: ${e.message}")
            }
        }
    }

    fun ragFileUp() { _ragSelectedFileIndex.update { (it - 1).coerceAtLeast(0) } }
    fun ragFileDown() { _ragSelectedFileIndex.update { it + 1 } }

    fun ragOpenSelectedFile() {
        val file = _ragIndexedFiles.value.getOrNull(_ragSelectedFileIndex.value) ?: return
        val fullPath = projectPath.resolve(file.filePath).toString()
        scope.launch(Dispatchers.IO) {
            try {
                val f = java.io.File(fullPath)
                if (!f.exists()) {
                    addSystemMessageFn("File not found: $fullPath")
                    return@launch
                }
                val content = f.readText()
                openFileViewer(fullPath, content)
            } catch (e: Exception) {
                addSystemMessageFn("Failed to read file: ${e.message}")
            }
        }
    }

    fun ragViewSelectedChunks() {
        val file = _ragIndexedFiles.value.getOrNull(_ragSelectedFileIndex.value) ?: return
        ragViewChunks(file.filePath)
    }

    fun ragViewChunks(filePath: String) {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                val chunks = r.ragRouter.getRagChunksForFile(filePath)
                if (chunks.isEmpty()) {
                    addSystemMessageFn("No chunks for: $filePath")
                    return@launch
                }
                val text = buildString {
                    for ((i, chunk) in chunks.withIndex()) {
                        appendLine("--- Chunk ${i + 1} (lines ${chunk.startLine ?: "?"}–${chunk.endLine ?: "?"}) ---")
                        appendLine(chunk.content)
                        appendLine()
                    }
                }
                openContentViewer(
                    title = "$filePath (${chunks.size} chunks)",
                    content = text,
                    showLineNumbers = true,
                    allowAddContext = true
                )
            } catch (e: Exception) {
                addSystemMessageFn("Failed to load chunks: ${e.message}")
            }
        }
    }

    fun ragStopIndexing() {
        ragJob?.cancel()
        ragJob = null
        _ragIndexingStatus.value = "Stopped"
        _ragIndexingProgress.value = -1.0
    }

    fun ragClearIndex() {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                r.ragRouter.clearRagIndex()
                _ragIndexingStatus.value = "Index cleared"
                refreshRagStats(r)
                addSystemMessageFn("RAG index cleared.")
            } catch (e: Exception) {
                logger.warn(e) { "RAG clear index failed" }
                addSystemMessageFn("Error clearing RAG index: ${e.message}")
            }
        }
    }

    internal fun refreshRagStats(r: CoreApiRouter) {
        scope.launch(Dispatchers.IO) {
            try {
                // Try to get full project context (has token breakdown per section)
                val tid = getTaskId()
                if (tid != null) {
                    try {
                        val ctx = r.projectContextRouter.getProjectContext(tid)
                        val totalTokens = ctx.totalEstimatedTokens.coerceAtLeast(1)
                        val contextLimit = 128_000 // default context window

                        // Extract section content from LLM prompt using <SECTION_TAG>...</SECTION_TAG> markers
                        val llmPrompt = ctx.llmContextPrompt ?: ""

                        val sections = ctx.contextSectionTokens.entries
                            .sortedByDescending { it.value.tokens }
                            .mapIndexed { _, (key, info) ->
                                // key is like "project_overview", tag is like "PROJECT_CONTEXT"
                                val sectionContent = extractSectionContent(llmPrompt, key)
                                TuiContextSection(
                                    name = info.name,
                                    category = categorizeSection(key),
                                    tokensUsed = info.tokens,
                                    tokensMax = contextLimit,
                                    percentage = info.percentage,
                                    colorIndex = sectionColorMap[key] ?: (key.hashCode() and 0x7FFFFFFF) % 10,
                                    content = sectionContent,
                                )
                            }
                            .toMutableList()

                        ctx.taskRequirementsPrompt
                            ?.takeIf { it.isNotBlank() }
                            ?.let { requirements ->
                                val alreadyPresent = sections.any { it.name == "Task Requirements (Sticky)" }
                                if (!alreadyPresent) {
                                    sections.add(
                                        TuiContextSection(
                                            name = "Task Requirements (Sticky)",
                                            category = "user",
                                            tokensUsed = (requirements.length / 4).coerceAtLeast(1),
                                            tokensMax = contextLimit,
                                            percentage = 0.0,
                                            colorIndex = sectionColorMap["task_requirements"] ?: 7,
                                            content = requirements
                                        )
                                    )
                                }
                            }

                        if (sections.isNotEmpty()) {
                            _contextSections.value = sections
                        }
                    } catch (e: Exception) {
                        logger.debug(e) { "Project context not available, falling back to RAG stats" }
                    }
                }

                // If no context sections were loaded, fall back to RAG stats
                if (_contextSections.value.isEmpty()) {
                    val stats = r.ragRouter.getRagStatistics()
                    _contextSections.value = listOf(
                        TuiContextSection(
                            name = "RAG Index",
                            category = "rag",
                            tokensUsed = stats.embeddingsCount,
                            tokensMax = stats.chunksCount,
                            colorIndex = 6
                        ),
                        TuiContextSection(
                            name = "Indexed Files",
                            category = "rag",
                            tokensUsed = stats.filesCount,
                            tokensMax = 0,
                            colorIndex = 6
                        )
                    )
                }

                // Always load indexed files for the RAG files table
                val files = r.ragRouter.getRagIndexedFiles()
                _ragIndexedFiles.value = files.map { f ->
                    TuiRagFile(
                        filePath = f.filePath,
                        chunks = f.chunksCount,
                        embeddings = f.embeddingsCount,
                        sizeBytes = f.fileSize
                    )
                }
            } catch (e: Exception) {
                logger.debug(e) { "RAG stats not available (indexing may not be configured)" }
            }
        }
    }

    // --- Documentation management ---

    fun docsAdd(url: String, depth: Int = 2) {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                val source = r.ragRouter.addDocumentationSource(url, depth)
                addSystemMessageFn("Added documentation source: $url (ID: ${source.id})")
            } catch (e: Exception) {
                addSystemMessageFn("Failed to add docs: ${e.message}")
            }
        }
    }

    fun docsDelete(docId: Int) {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                r.ragRouter.deleteDocumentationSource(docId)
                addSystemMessageFn("Deleted documentation source #$docId")
            } catch (e: Exception) {
                addSystemMessageFn("Failed to delete docs: ${e.message}")
            }
        }
    }

    fun docsReindex(docId: Int) {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                addSystemMessageFn("Indexing documentation #$docId...")
                r.ragRouter.indexDocumentation(docId).collect { progress ->
                    _ragIndexingStatus.value = progress.statusMessage
                    _ragIndexingProgress.value = progress.progressPercent / 100.0
                }
                addSystemMessageFn("Documentation #$docId indexed.")
                _ragIndexingProgress.value = -1.0
                refreshRagStats(r)
            } catch (e: Exception) {
                addSystemMessageFn("Indexing failed: ${e.message}")
                _ragIndexingProgress.value = -1.0
            }
        }
    }

    // ========================================================================
    // Context section navigation
    // ========================================================================

    fun contextSectionUp() { _selectedContextIndex.update { (it - 1).coerceAtLeast(0) } }
    fun contextSectionDown() { _selectedContextIndex.update { (it + 1).coerceAtMost((_contextSections.value.size - 1).coerceAtLeast(0)) } }

    fun toggleContextDetail() {
        _contextDetailVisible.update { !it }
        _contextDetailScrollOffset.value = 0
    }

    fun contextDetailScrollUp() {
        _contextDetailScrollOffset.update { (it - 1).coerceAtLeast(0) }
    }

    fun contextDetailScrollDown() {
        val section = _contextSections.value.getOrNull(_selectedContextIndex.value) ?: return
        val maxScroll = (section.content?.lines()?.size ?: 1) - 5
        _contextDetailScrollOffset.update { (it + 1).coerceAtMost(maxScroll.coerceAtLeast(0)) }
    }

    /** Section key -> color index mapping (matches plugin's ContextSectionColorPalette) */
    private val sectionColorMap = mapOf(
        "project_overview" to 0, "semantic_summary" to 0,
        "project_instructions" to 1, "project_structure" to 1,
        "technologies" to 2, "dependencies" to 2,
        "code_analysis" to 3, "framework_analysis" to 3,
        "current_task" to 4, "subtasks" to 4,
        "conversation_history" to 5, "conversation" to 5, "recent_work" to 5,
        "user_context" to 7, "user_requirements" to 7, "task_requirements" to 7,
        "key_components" to 8, "domain_analysis" to 8,
        "working_memory" to 9, "mcp_resources" to 9,
        "system_prompt" to 10, "system_messages" to 10,
        "messages_assistant" to 11, "assistant_messages" to 11,
        "messages_user" to 7, "messages_system" to 14, "messages_other" to 14,
        "navigation_map" to 12, "patterns" to 12, "architecture" to 0,
        "typescript_analysis" to 13, "html_analysis" to 13, "css_analysis" to 13,
        "context_injection_overhead" to 14, "request_overhead" to 14, "free_space" to 14,
        "tool_outputs" to 8,
    )

    private fun categorizeSection(key: String): String {
        return when {
            key.startsWith("project") || key == "semantic_summary" -> "project"
            key.startsWith("user") -> "user"
            key == "task_requirements" -> "user"
            key.startsWith("conversation") || key == "recent_work" -> "conversation"
            key.startsWith("mcp") || key.startsWith("tool") -> "tools"
            else -> "project"
        }
    }

    /**
     * Extract section content from the LLM prompt by looking for XML-like tags.
     * The prompt uses tags like <PROJECT_CONTEXT>...</PROJECT_CONTEXT>.
     * The key (e.g., "project_overview") maps to a tag (e.g., "PROJECT_CONTEXT").
     */
    private fun extractSectionContent(llmPrompt: String, sectionKey: String): String? {
        if (llmPrompt.isBlank()) return null

        // Map section keys to XML tag names used in the prompt
        val tagName = sectionKeyToTag[sectionKey] ?: sectionKey.uppercase()

        val openTag = "<$tagName>"
        val closeTag = "</$tagName>"
        val startIdx = llmPrompt.indexOf(openTag)
        if (startIdx < 0) return null
        val contentStart = startIdx + openTag.length
        val endIdx = llmPrompt.indexOf(closeTag, contentStart)
        if (endIdx < 0) return null

        return llmPrompt.substring(contentStart, endIdx).trim()
    }

    private val sectionKeyToTag = mapOf(
        "project_overview" to "PROJECT_CONTEXT",
        "project_instructions" to "PROJECT_INSTRUCTIONS",
        "task_requirements" to "TASK_REQUIREMENTS",
        "current_task" to "CURRENT_TASK",
        "user_requirements" to "USER_REQUIREMENTS",
        "user_context" to "USER_PROVIDED_CONTEXT",
        "working_memory" to "WORKING_MEMORY",
        "mcp_resources" to "MCP_RESOURCES",
        "conversation" to "CONVERSATION_HISTORY",
        "recent_work" to "RECENT_WORK",
        "subtasks" to "SUBTASKS_STATUS",
        "key_components" to "KEY_COMPONENTS",
        "dependencies" to "PROJECT_DEPENDENCIES",
        "architecture" to "PROJECT_ARCHITECTURE",
        "framework_analysis" to "FRAMEWORK_ANALYSIS",
        "typescript_analysis" to "TYPESCRIPT_ANALYSIS",
        "html_analysis" to "HTML_ANALYSIS",
        "css_analysis" to "CSS_ANALYSIS",
        "patterns" to "PATTERNS",
        "navigation_map" to "NAVIGATION_MAP",
        "domain_analysis" to "DOMAIN_ANALYSIS",
        "semantic_summary" to "SEMANTIC_SUMMARY",
        "context_stability" to "CONTEXT_STABILITY",
    )

    // ========================================================================
    // Logs view methods
    // ========================================================================

    fun toggleLogPause() {
        _logsPaused.update { !it }
    }

    fun logUp() {
        _selectedLogIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun logDown() {
        _selectedLogIndex.update { (it + 1).coerceAtMost((_logs.value.size - 1).coerceAtLeast(0)) }
    }

    fun toggleLogDetail() {
        _logDetailVisible.update { !it }
    }

    fun cycleLogFilter() {
        val levels = listOf(null, "DEBUG", "INFO", "WARN", "ERROR")
        val currentIdx = levels.indexOf(_logsFilter.value)
        _logsFilter.value = levels[(currentIdx + 1) % levels.size]
    }

    fun openLogDetailViewer() {
        val logs = if (_logsFilter.value != null) {
            _logs.value.filter { it.level == _logsFilter.value }
        } else _logs.value
        val log = logs.getOrNull(_selectedLogIndex.value) ?: return
        val content = buildString {
            appendLine("Timestamp: ${log.timestamp}")
            appendLine("Level:     ${log.level}")
            appendLine()
            appendLine("Message:")
            appendLine(log.message)
        }
        openContentViewer(title = "Log Detail [${log.level}] ${log.timestamp}", content = content)
    }

    // ========================================================================
    // API Logs
    // ========================================================================

    fun cycleApiLogsFilter() {
        val providers = _apiLogs.value.map { it.provider }.distinct().sorted()
        val current = _apiLogsFilter.value
        _apiLogsFilter.value = if (current == null && providers.isNotEmpty()) {
            providers.first()
        } else if (current != null) {
            val idx = providers.indexOf(current)
            if (idx >= 0 && idx < providers.size - 1) providers[idx + 1] else null
        } else null
    }

    fun apiLogUp() {
        val max = _apiLogs.value.size
        if (max > 0) _selectedApiLogIndex.value = (_selectedApiLogIndex.value - 1).coerceIn(0, max - 1)
        _apiLogDetailVisible.value = false
    }

    fun apiLogDown() {
        val max = _apiLogs.value.size
        if (max > 0) _selectedApiLogIndex.value = (_selectedApiLogIndex.value + 1).coerceIn(0, max - 1)
        _apiLogDetailVisible.value = false
    }

    fun toggleApiLogDetail() {
        _apiLogDetailVisible.value = !_apiLogDetailVisible.value
    }

    fun openApiLogDetailViewer() {
        val logs = if (_apiLogsFilter.value != null) {
            _apiLogs.value.filter { it.provider == _apiLogsFilter.value }
        } else _apiLogs.value
        val log = logs.getOrNull(_selectedApiLogIndex.value) ?: return
        val content = buildString {
            appendLine("Time:       ${log.timestamp}")
            appendLine("Provider:   ${log.provider}")
            appendLine("Model:      ${log.model}")
            appendLine("Endpoint:   ${log.endpoint}")
            appendLine("Source:     ${log.source ?: "-"}")
            appendLine("HTTP:       ${log.httpStatus ?: "-"}")
            appendLine()
            appendLine("=== Metrics ===")
            appendLine("Tokens:     ${log.tokensIn} in / ${log.tokensOut} out (${log.tokensIn + log.tokensOut} total)")
            appendLine("Cost:       \$${String.format("%.6f", log.costUsd)}")
            appendLine("Latency:    ${log.latencyMs}ms")
            if (log.taskId != null) appendLine("Task:       ${log.taskId}")
            if (log.subtaskId != null) appendLine("Subtask:    ${log.subtaskId}")
            if (log.errorType != null || log.errorMessage != null) {
                appendLine()
                appendLine("=== Error ===")
                if (log.errorType != null) appendLine("Type:       ${log.errorType}")
                if (log.errorMessage != null) appendLine(log.errorMessage)
            }
            if (log.requestPayload.isNotBlank()) {
                appendLine()
                appendLine("=== Request (${log.requestPayload.length} chars) ===")
                appendLine(prettyPrintJson(log.requestPayload))
            }
            if (log.responsePayload.isNotBlank()) {
                appendLine()
                appendLine("=== Response (${log.responsePayload.length} chars) ===")
                appendLine(prettyPrintJson(log.responsePayload))
            }
        }
        openContentViewer(title = "API Log: ${log.provider}/${log.model} ${log.timestamp}", content = content)
    }

    internal fun refreshApiLogs(r: CoreApiRouter) {
        try {
            val logs = r.apiLogsRouter.getRecentApiLogs(100)
            _apiLogs.value = logs.map { log ->
                TuiApiLogEntry(
                    id = log.id,
                    timestamp = apiLogTimeFormatter.format(Instant.ofEpochMilli(log.createdAt)),
                    provider = log.provider,
                    model = log.model,
                    tokensIn = log.inputTokens.toLong(),
                    tokensOut = log.outputTokens.toLong(),
                    costUsd = log.costUsd,
                    latencyMs = log.latencyMs,
                    httpStatus = log.httpStatus,
                    source = log.requestSource,
                    errorType = log.errorType,
                    errorMessage = log.errorMessage,
                    endpoint = log.endpoint,
                    requestPayload = log.requestPayload.take(2000),
                    responsePayload = (log.responsePayload ?: "").take(2000),
                    taskId = log.taskId,
                    subtaskId = log.subtaskId
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh API logs" }
        }
    }

    fun apiLogDetailScrollUp() {
        _apiLogDetailScrollOffset.update { (it - 1).coerceAtLeast(0) }
    }

    fun apiLogDetailScrollDown() {
        _apiLogDetailScrollOffset.update { it + 1 }
    }

    fun resetApiLogDetailScroll() {
        _apiLogDetailScrollOffset.value = 0
    }

    // ========================================================================
    // Debug
    // ========================================================================

    fun debugScrollUp() {
        _debugScrollOffset.update { (it - 1).coerceAtLeast(0) }
    }

    fun debugScrollDown() {
        _debugScrollOffset.update { it + 1 }
    }

    internal fun refreshDebugState(r: CoreApiRouter) {
        try {
            val stats = r.apiLogsRouter.getApiLogStatistics()
            val taskId = getTaskId()
            val task = taskId?.let {
                try { r.taskRouter.getTask(it) } catch (_: Exception) { null }
            }
            val subtaskCount = taskId?.let {
                try { r.subtaskRouter.getSubtasks(it).subtasks.size } catch (_: Exception) { 0 }
            } ?: 0

            _debugInfo.update {
                it.copy(
                    tokensIn = task?.tokensIn?.toLong() ?: it.tokensIn,
                    tokensOut = task?.tokensOut?.toLong() ?: it.tokensOut,
                    costUsd = task?.costUsd ?: it.costUsd,
                    subtaskCount = subtaskCount,
                    projectRoot = projectPath.toAbsolutePath().toString(),
                    sessionCreatedAt = task?.createdAt ?: 0,
                    lastUpdate = System.currentTimeMillis(),
                    totalApiCalls = stats.totalCalls,
                    globalTokensIn = stats.totalInputTokens,
                    globalTokensOut = stats.totalOutputTokens,
                    globalCost = stats.totalCost,
                    avgLatencyMs = stats.avgLatencyMs,
                    errorCount = stats.errorCount,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh debug state" }
        }
    }

    // ========================================================================
    // File Browser
    // ========================================================================

    fun initFileBrowser() {
        _fileBrowserPath.value = projectPath.toAbsolutePath().toString()
        refreshFileBrowser()
    }

    private fun refreshFileBrowser() {
        val dir = File(_fileBrowserPath.value)
        if (!dir.isDirectory) return
        val showHidden = _fileBrowserShowHidden.value
        val entries = mutableListOf<TuiFileEntry>()

        // Parent directory entry (unless at filesystem root)
        if (dir.parentFile != null) {
            entries.add(TuiFileEntry(name = "..", isDirectory = true))
        }

        try {
            val children = dir.listFiles()?.toList() ?: emptyList()
            val filtered = if (showHidden) children else children.filter { !it.name.startsWith(".") }
            val sorted = filtered.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })

            for (file in sorted) {
                entries.add(TuiFileEntry(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    lastModified = file.lastModified(),
                    isSymlink = Files.isSymbolicLink(file.toPath())
                ))
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to list directory: ${_fileBrowserPath.value}" }
        }

        _fileBrowserEntries.value = entries
        _fileBrowserSelectedIndex.value = 0
    }

    fun fileBrowserUp() {
        _fileBrowserSelectedIndex.update { (it - 1).coerceAtLeast(0) }
    }

    fun fileBrowserDown() {
        _fileBrowserSelectedIndex.update { (it + 1).coerceAtMost((_fileBrowserEntries.value.size - 1).coerceAtLeast(0)) }
    }

    fun fileBrowserEnter() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return

        if (entry.isDirectory) {
            val currentPath = _fileBrowserPath.value
            val newPath = if (entry.name == "..") {
                File(currentPath).parent ?: currentPath
            } else {
                File(currentPath, entry.name).absolutePath
            }
            _fileBrowserPath.value = newPath
            refreshFileBrowser()
        } else {
            // Open file in modal viewer overlay instead of injecting into chat
            fileBrowserOpenInViewer(entry)
        }
    }

    fun fileBrowserGoUp() {
        val parent = File(_fileBrowserPath.value).parent
        if (parent != null) {
            _fileBrowserPath.value = parent
            refreshFileBrowser()
        }
    }

    fun fileBrowserToggleHidden() {
        _fileBrowserShowHidden.update { !it }
        refreshFileBrowser()
    }

    fun fileBrowserAddAsContext() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val relativePath = try {
            projectPath.toAbsolutePath().relativize(java.nio.file.Paths.get(fullPath)).toString()
        } catch (_: Exception) { fullPath }

        val ref = if (entry.isDirectory) "@folder:$relativePath" else "@file:$relativePath"
        insertContextFn(ref)
        addSystemMessageFn("Added context: $ref")
    }

    fun fileBrowserOpenExternal() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        scope.launch(Dispatchers.IO) {
            try {
                val resolved = java.nio.file.Paths.get(fullPath).toRealPath()
                if (!java.nio.file.Files.exists(resolved)) {
                    addSystemMessageFn("File not found: $fullPath")
                    return@launch
                }
                val editor = System.getenv("EDITOR") ?: System.getenv("VISUAL") ?: "vi"
                val pb = ProcessBuilder(editor, resolved.toString())
                pb.inheritIO()
                val proc = pb.start()
                proc.waitFor()
                addSystemMessageFn("Closed editor for: $fullPath")
            } catch (e: Exception) {
                addSystemMessageFn("Failed to open editor: ${e.message}")
            }
        }
    }

    fun fileBrowserShowInfo() {
        val entries = _fileBrowserEntries.value
        val idx = _fileBrowserSelectedIndex.value
        val entry = entries.getOrNull(idx) ?: return
        if (entry.name == "..") return

        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val file = File(fullPath)
        val info = buildString {
            appendLine("File Info: ${entry.name}")
            appendLine("  Path: $fullPath")
            appendLine("  Type: ${if (entry.isDirectory) "Directory" else "File"}")
            if (!entry.isDirectory) {
                appendLine("  Size: ${formatFileSize(entry.size)}")
            }
            if (entry.isSymlink) appendLine("  Symlink: yes")
            if (entry.lastModified > 0) {
                val instant = java.time.Instant.ofEpochMilli(entry.lastModified)
                val dt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                appendLine("  Modified: $dt")
            }
            if (file.isDirectory) {
                val count = file.listFiles()?.size ?: 0
                appendLine("  Contents: $count items")
            }
        }
        // Show info in viewer overlay for files, system message for directories
        if (!entry.isDirectory && file.isFile && file.length() <= 500_000) {
            try {
                openFileViewer(fullPath, file.readText())
            } catch (_: Exception) {
                addSystemMessageFn(info)
            }
        } else {
            addSystemMessageFn(info)
        }
    }

    private fun fileBrowserOpenInViewer(entry: TuiFileEntry) {
        val fullPath = File(_fileBrowserPath.value, entry.name).absolutePath
        val file = File(fullPath)

        if (!file.isFile) return
        if (file.length() > 500_000) {
            addSystemMessageFn("File too large to preview: ${entry.name} (${formatFileSize(file.length())}). Use [a] to add as context or [o] to open externally.")
            return
        }

        try {
            val content = file.readText()
            openFileViewer(fullPath, content)
        } catch (e: Exception) {
            addSystemMessageFn("Cannot read file: ${e.message}")
        }
    }

    fun fileBrowserRefresh() {
        refreshFileBrowser()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1fM", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.1fK", bytes / 1_024.0)
        else -> "${bytes}B"
    }

    // ========================================================================
    // File/Content Viewer
    // ========================================================================

    fun openFileViewer(path: String, content: String) {
        openContentViewer(title = path, content = content, showLineNumbers = true, allowAddContext = true)
    }

    /**
     * Generic content viewer — used for files, log details, API payloads, debug info.
     */
    fun openContentViewer(title: String, content: String, showLineNumbers: Boolean = false, allowAddContext: Boolean = false) {
        _fileViewerPath.value = title
        _fileViewerContent.value = content
        _fileViewerScrollOffset.value = 0
        _fileViewerShowLineNumbers.value = showLineNumbers
        _fileViewerAllowAddContext.value = allowAddContext
        _fileViewerVisible.value = true
    }

    fun closeFileViewer() {
        _fileViewerVisible.value = false
        _fileViewerContent.value = ""
        _fileViewerPath.value = ""
    }

    fun fileViewerScrollUp() {
        _fileViewerScrollOffset.update { (it - 1).coerceAtLeast(0) }
    }

    fun fileViewerScrollDown() {
        val maxScroll = (_fileViewerContent.value.lines().size - 5).coerceAtLeast(0)
        _fileViewerScrollOffset.update { (it + 1).coerceAtMost(maxScroll) }
    }

    fun fileViewerPageUp() {
        _fileViewerScrollOffset.update { (it - 20).coerceAtLeast(0) }
    }

    fun fileViewerPageDown() {
        val maxScroll = (_fileViewerContent.value.lines().size - 5).coerceAtLeast(0)
        _fileViewerScrollOffset.update { (it + 20).coerceAtMost(maxScroll) }
    }

    fun fileViewerAddAsContext() {
        val path = _fileViewerPath.value
        if (path.isBlank()) return
        val relativePath = try {
            projectPath.toAbsolutePath().relativize(java.nio.file.Paths.get(path)).toString()
        } catch (_: Exception) { path }

        val ref = "@file:$relativePath"
        insertContextFn(ref)
        closeFileViewer()
        addSystemMessageFn("Added context: $ref")
    }

    fun fileViewerCopyToClipboard() {
        val content = _fileViewerContent.value
        if (content.isBlank()) return
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(content), null)
            addSystemMessageFn("Content copied to clipboard.")
        } catch (e: Exception) {
            addSystemMessageFn("Copy failed: ${e.message}")
        }
    }

    // ========================================================================
    // Help
    // ========================================================================

    fun helpScrollUp() { _helpScrollOffset.update { (it - 1).coerceAtLeast(0) } }
    fun helpScrollDown() { _helpScrollOffset.update { it + 1 } }
    fun helpPageUp() { _helpScrollOffset.update { (it - 10).coerceAtLeast(0) } }
    fun helpPageDown() { _helpScrollOffset.update { it + 10 } }

    // ========================================================================
    // Auto-refresh (mirrors DebugPanel's 30s timer from the IntelliJ plugin)
    // ========================================================================

    fun startAutoRefresh(r: CoreApiRouter) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000) // 30s like plugin's DebugPanel
                try {
                    refreshApiLogs(r)
                    refreshDebugState(r)
                    refreshRagStats(r)
                } catch (e: Exception) {
                    logger.warn(e) { "Auto-refresh failed" }
                }
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Simple JSON pretty-printer for content viewer */
    private fun prettyPrintJson(json: String): String {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val sb = StringBuilder()
            var indent = 0
            var inString = false
            var escaped = false
            for (c in trimmed) {
                if (escaped) { sb.append(c); escaped = false; continue }
                if (c == '\\' && inString) { sb.append(c); escaped = true; continue }
                if (c == '"') { inString = !inString; sb.append(c); continue }
                if (inString) { sb.append(c); continue }
                when (c) {
                    '{', '[' -> { sb.append(c); indent++; sb.append('\n'); sb.append("  ".repeat(indent)) }
                    '}', ']' -> { indent = (indent - 1).coerceAtLeast(0); sb.append('\n'); sb.append("  ".repeat(indent)); sb.append(c) }
                    ',' -> { sb.append(c); sb.append('\n'); sb.append("  ".repeat(indent)) }
                    ':' -> sb.append(": ")
                    ' ', '\n', '\r', '\t' -> {}
                    else -> sb.append(c)
                }
            }
            sb.toString()
        } catch (_: Exception) { json }
    }
}
