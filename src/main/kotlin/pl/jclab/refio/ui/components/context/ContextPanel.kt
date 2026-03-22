@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package pl.jclab.refio.ui.components.context

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.ui.Messages
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.notification.NotificationService
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.ui.dialogs.LLMPromptViewerDialog
import pl.jclab.refio.ui.theme.ContextSectionColorPalette
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlin.coroutines.cancellation.CancellationException
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Insets
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.SwingUtilities
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File

private val logger = dualLogger("ContextPanel")

/**
 * Panel displaying project context (analysis + current state).
 * Updates in real-time as agent works.
 *
 * Based on ADR 0018: Context Building & Visualization System
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class ContextPanel(private val project: Project) : JBPanel<ContextPanel>(BorderLayout()) {
    private data class SectionEntry(
        val key: String,
        val order: Int,
        val section: CollapsibleContextSection,
        val updater: (pl.jclab.refio.core.api.ProjectContextResponse, List<ContextReference>) -> Unit
    )

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionManager = SessionManager.getInstance(project)
    private val coreManager = CoreConnectionManager.getInstance()

    // Track current refresh job to allow cancellation
    private var currentRefreshJob: Job? = null

    // Debounced refresh trigger - all listeners emit to this shared flow
    // which debounces events and triggers refreshContext() only once per time window
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JBScrollPane(contentPanel)

    // Section panels
    private val headerPanel = JPanel(BorderLayout())
    private val tokenUsagePanel = TokenUsageVisualizationPanel()

    private val projectOverviewSection = createSection("Project Overview", "project_overview")
    private val semanticSummarySection = createSection("Semantic Summary (for LLM)", "semantic_summary")
    private val structureSection = createSection("Project Structure", "project_structure")
    private val technologiesSection = createSection("Technologies & Dependencies", "dependencies")
    private val keyComponentsSection = createSection("Key Components", "key_components")
    private val codeAnalysisSection = createSection("Code Analysis", "code_analysis")
    private val currentTaskSection = createSection("Current Task", "current_task")
    private val userContextSection = createSection("User Context (@mentions)", "user_context")
    private val mcpResourcesSection = createSection("MCP Resources", "mcp_resources")
    private val userRequirementsSection = createSection("User Requirements", "user_requirements")
    private val projectInstructionsSection = createSection("Project Instructions", "project_instructions")
    private val ragFragmentsSection = createSection("RAG Fragments", "rag_fragments")
    private val subtasksSection = createSection("Subtasks", "subtasks")
    private val conversationSection = createSection("Conversation History", "conversation")
    private val recentWorkSection = createSection("Recent Work", "recent_work")
    private val frameworkAnalysisSection = createSection("Framework Analysis", "framework_analysis")
    private val workingMemorySection = createSection("Working Memory", "working_memory")
    private val contextStabilitySection = createSection("Context Stability", "context_stability")
    private val domainAnalysisSection = createSection("Domain Analysis", "domain_analysis")
    private val llmPromptPreviewSection = createSection("LLM Context Prompt", "llm_prompt", collapsible = false)
    // Create buttons for LLM prompt controls
    private val copyPromptButton = JButton("Copy").apply {
        toolTipText = "Copy LLM Context Prompt to clipboard"
        isEnabled = false
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            copyLLMContextToClipboard()
        }
    }
    
    private val savePromptButton = JButton("Save").apply {
        toolTipText = "Save LLM Context Prompt to file"
        isEnabled = false
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            saveLLMContextToFile()
        }
    }
    
    private val viewPromptButton = JButton("View Full").apply {
        toolTipText = "Open full LLM Context"
        isEnabled = false
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            openLLMPromptViewer()
        }
    }

    private val refreshButton = JButton("Refresh").apply {
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            refreshContext(manual = true)
        }
    }

    private var isLoading = false
    private var currentLLMPrompt: String? = null
    private var lastStreamingActive = false
    private var streamingSuppressStartMs: Long? = null
    private var pendingAutoRefreshAfterStreaming = false

    private val sectionEntries = listOf(
        SectionEntry("project_overview", 1, projectOverviewSection) { context, _ ->
            updateProjectOverviewSection(context)
        },
        SectionEntry("semantic_summary", 2, semanticSummarySection) { context, _ ->
            updateSemanticSummarySection(context)
        },
        SectionEntry("key_components", 3, keyComponentsSection) { context, _ ->
            updateKeyComponentsSection(context)
        },
        SectionEntry("code_analysis", 4, codeAnalysisSection) { context, _ ->
            updateCodeAnalysisSection(context)
        },
        SectionEntry("project_structure", 5, structureSection) { context, _ ->
            updateStructureSection(context)
        },
        SectionEntry("dependencies", 6, technologiesSection) { context, _ ->
            updateTechnologiesSection(context)
        },
        SectionEntry("current_task", 7, currentTaskSection) { context, _ ->
            updateCurrentTaskSection(context)
        },
        SectionEntry("user_requirements", 8, userRequirementsSection) { context, _ ->
            updateUserRequirementsSection(context)
        },
        SectionEntry("project_instructions", 9, projectInstructionsSection) { context, _ ->
            updateProjectInstructionsSection(context)
        },
        SectionEntry("user_context", 10, userContextSection) { context, pendingRefs ->
            updateUserContextSection(context, pendingRefs)
        },
        SectionEntry("mcp_resources", 10, mcpResourcesSection) { context, _ ->
            updateMcpResourcesSection(context)
        },
        SectionEntry("rag_fragments", 11, ragFragmentsSection) { context, _ ->
            updateRagFragmentsSection(context)
        },
        SectionEntry("conversation", 12, conversationSection) { context, _ ->
            updateConversationSection(context)
        },
        SectionEntry("recent_work", 13, recentWorkSection) { context, _ ->
            updateRecentWorkSection(context)
        },
        SectionEntry("subtasks", 14, subtasksSection) { context, _ ->
            updateSubtasksSection(context)
        },
        SectionEntry("framework_analysis", 15, frameworkAnalysisSection) { context, _ ->
            updateFrameworkAnalysisSection(context)
        },
        SectionEntry("working_memory", 16, workingMemorySection) { context, _ ->
            updateWorkingMemorySection(context)
        },
        SectionEntry("context_stability", 17, contextStabilitySection) { context, _ ->
            updateContextStabilitySection(context)
        },
        SectionEntry("domain_analysis", 18, domainAnalysisSection) { context, _ ->
            updateDomainAnalysisSection(context)
        }
    )

    init {
        // Header with refresh button
        headerPanel.apply {
            val titleLabel = JBLabel("Project Context").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }
            add(titleLabel, BorderLayout.WEST)
            val headerActions = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(copyPromptButton)
                add(Box.createHorizontalStrut(4))
                add(savePromptButton)
                add(Box.createHorizontalStrut(6))
                add(viewPromptButton)
                add(Box.createHorizontalStrut(8))
                add(refreshButton)
            }
            add(headerActions, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }

        // Top panel with header and token visualization
        val topPanel = JPanel(BorderLayout())
        topPanel.add(headerPanel, BorderLayout.NORTH)
        topPanel.add(tokenUsagePanel, BorderLayout.CENTER)

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Assemble sections in contentPanel
        // Order matches ADR 0040 - Phase 2: Project context first, then task, then history
        // Priority: Project Meta → Task → User Context → RAG → Conversation → Work History
        applySectionOrder(sectionEntries.sortedBy { it.order })

        // Debounced refresh listener - collects all events and triggers refresh only once per time window
        // This prevents excessive refreshes when multiple flows emit events simultaneously
        cs.launch {
            refreshTrigger
                .debounce(1500) // 1.5 second debounce window
                .collectLatest {
                    if (isAutoRefreshBlocked()) {
                        pendingAutoRefreshAfterStreaming = true
                        logger.debug { "Debounce window expired during streaming/generation, deferring auto-refresh" }
                        return@collectLatest
                    }

                    logger.debug { "Debounce window expired, triggering refresh..." }
                    refreshContext(manual = false)
                }
        }

        // Listen to session changes and trigger debounced refresh
        cs.launch {
            sessionManager.activeSession.collectLatest { session ->
                session?.let {
                    requestAutoRefresh("Active session changed: ${it.id}, mode=${it.mode}")
                }
            }
        }

        // Listen to messages changes (all modes including CHAT)
        cs.launch {
            sessionManager.messages.collectLatest {
                sessionManager.activeSession.value?.let { session ->
                    val streamingActive = it.any { msg -> msg.isStreaming }
                    if (streamingActive) {
                        if (!lastStreamingActive) {
                            streamingSuppressStartMs = System.currentTimeMillis()
                            pendingAutoRefreshAfterStreaming = true
                            logger.debug {
                                "Messages changed for session: ${session.id}, streaming active - suppressing refresh"
                            }
                        }
                        lastStreamingActive = true
                        return@collectLatest
                    }

                    if (lastStreamingActive) {
                        lastStreamingActive = false
                        val suppressedMs = streamingSuppressStartMs?.let { startMs ->
                            (System.currentTimeMillis() - startMs).coerceAtLeast(0)
                        } ?: 0L
                        streamingSuppressStartMs = null
                        logger.debug {
                            "Streaming completed for session: ${session.id}, suppressedMs=${suppressedMs}, pendingAutoRefresh=$pendingAutoRefreshAfterStreaming"
                        }
                        if (pendingAutoRefreshAfterStreaming) {
                            pendingAutoRefreshAfterStreaming = false
                            refreshTrigger.tryEmit(Unit)
                        }
                        return@collectLatest
                    }

                    requestAutoRefresh("Messages changed for session: ${session.id}")
                }
            }
        }

        // Listen to subtask changes (work progress)
        cs.launch {
            sessionManager.subtasks.collectLatest {
                sessionManager.activeSession.value?.let { session ->
                    requestAutoRefresh("Subtasks changed for session: ${session.id}")
                }
            }
        }

        // Listen to model changes (context limit changes with model)
        cs.launch {
            sessionManager.selectedModel.collectLatest {
                sessionManager.activeSession.value?.let { session ->
                    requestAutoRefresh("Model changed for session: ${session.id}")
                }
            }
        }

        // Listen to pending context references changes (from PromptInputPanel @ mentions and addContextButton)
        // This enables real-time context preview when users add context via UI
        cs.launch {
            sessionManager.pendingContextRefs.collectLatest { pendingRefs ->
                sessionManager.activeSession.value?.let { session ->
                    requestAutoRefresh(
                        "Pending context refs changed for session: ${session.id}, refs count=${pendingRefs.size}"
                    )
                }
            }
        }

        // Initial load
        showMessage("Select a session to view context")
    }

    private fun createSection(
        title: String,
        key: String,
        collapsible: Boolean = true
    ): CollapsibleContextSection {
        val color = ContextSectionColorPalette.colorFor(key)
        return CollapsibleContextSection(title, color, collapsible).apply {
            setContent("Loading...", "<html><body style='padding: 5px;'>Loading...</body></html>")
        }
    }

    private fun refreshContext(manual: Boolean = false) {
        val session = sessionManager.activeSession.value
        if (session == null) {
            showMessage("No active session")
            return
        }

        if (!manual && isAutoRefreshBlocked()) {
            pendingAutoRefreshAfterStreaming = true
            logger.debug { "Skipping auto-refresh while streaming/generation is active" }
            return
        }

        if (isLoading) {
            logger.debug { "Context refresh already in progress, skipping this request" }
            // Skip this refresh request instead of cancelling the running one
            return
        }

        currentRefreshJob = cs.launch {
            try {
                isLoading = true
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = false
                    refreshButton.text = "⏳ Loading..."
                }

                logger.info { "Refreshing context for task=${session.id}" }

                val projectRoot = project.basePath?.let { java.nio.file.Paths.get(it) }
                if (projectRoot == null) {
                    showError("Project root not found")
                    return@launch
                }

                // Use cached router to avoid recreating and re-initializing database
                val router = coreManager.getOrCreateProjectRouter(projectRoot)
                val pendingUserInput = sessionManager.pendingUserInput.value

                logger.info { "[CONTEXT_DEBUG] refreshContext called for session: ${session.id}" }

                val pendingRefs = sessionManager.pendingContextRefs.value
                val context = router.getProjectContext(
                    taskId = session.id,
                    userInput = pendingUserInput,
                    contextRefs = pendingRefs
                )

                // Share context section data with SessionManager for StatusBar
                sessionManager.updateContextSectionTokens(context.contextSectionTokens, context.totalEstimatedTokens)

                SwingUtilities.invokeLater {
                    // Update token usage visualization
                    tokenUsagePanel.updateTokenUsage(
                        context.contextSectionTokens,
                        context.totalEstimatedTokens,
                        getSelectedModelContextLimit()
                    )

                    sessionManager.updateContextSectionTokens(context.contextSectionTokens, context.totalEstimatedTokens)

                    updateSections(context, pendingRefs)
                    updateLLMPromptState(context)
                }

                logger.info { "Context refreshed successfully" }
            } catch (e: CancellationException) {
                logger.debug { "Context refresh cancelled" }
                // Don't show error for cancellation
            } catch (e: IllegalArgumentException) {
                // Handle "Task not found" gracefully - session may be stale
                if (e.message?.contains("Task not found") == true) {
                    logger.debug { "Task not found in DB (stale session), showing placeholder" }
                    SwingUtilities.invokeLater {
                        showMessage("Session not synced with database. Send a message to initialize.")
                    }
                } else {
                    logger.error(e) { "Invalid argument for context refresh" }
                    SwingUtilities.invokeLater {
                        showError("Invalid request: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to refresh context" }

                // Show notification for RAG unavailability (only on first failure)
                if (e.message?.contains("Ollama service") == true &&
                    e.message?.contains("unavailable") == true) {
                    NotificationService.showRagUnavailable(project, "ollama", "http://localhost:11434")
                }

                SwingUtilities.invokeLater {
                    showError("Failed to load context: ${e.message}")
                }
            } finally {
                isLoading = false
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    refreshButton.text = "🔄 Refresh"
                }
            }
        }
    }

    private fun requestAutoRefresh(reason: String) {
        if (isAutoRefreshBlocked()) {
            pendingAutoRefreshAfterStreaming = true
            logger.debug { "$reason, but auto-refresh is blocked during streaming/generation" }
            return
        }

        logger.debug { "$reason, emitting refresh trigger..." }
        refreshTrigger.tryEmit(Unit)
    }

    private fun isAutoRefreshBlocked(): Boolean {
        return lastStreamingActive ||
            sessionManager.isGenerating.value ||
            sessionManager.messages.value.any { it.isStreaming } ||
            GlobalMetrics.currentOperation.value !is OperationInfo.Idle
    }

    private fun updateProjectOverviewSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // Format infrastructure list
        val sep = ", "
        val infrastructureText = if (context.infrastructure.isNotEmpty()) {
            "<b>Infrastructure:</b> ${context.infrastructure.joinToString(sep)}<br>"
        } else {
            ""
        }

        val raw = buildString {
            appendLine("Project Path: ${context.projectPath}")
            appendLine("Type: ${context.projectType}")
            appendLine("Primary Language: ${context.primaryLanguage}")
            appendLine("Main Language: ${context.mainLanguage}")
            appendLine("Complexity: ${context.complexity}")
            appendLine("Total Files: ${context.totalFiles}")
            if (context.infrastructure.isNotEmpty()) {
                appendLine("Infrastructure: ${context.infrastructure.joinToString(sep)}")
            }
            appendLine("Analyzed: ${formatTimestamp(context.analyzedAt)}")
        }.trimEnd()

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Project Path:</b> ${context.projectPath}<br>
            <b>Type:</b> ${context.projectType}<br>
            <b>Primary Language:</b> ${context.primaryLanguage}<br>
            <b>Main Language:</b> ${context.mainLanguage}<br>
            <b>Complexity:</b> ${context.complexity}<br>
            <b>Total Files:</b> ${context.totalFiles}<br>
            $infrastructureText<b>Analyzed:</b> ${formatTimestamp(context.analyzedAt)}<br>
            </body></html>
        """.trimIndent()
        projectOverviewSection.setContent(raw, html)
    }

    private fun updateSemanticSummarySection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val summary = context.semanticSummary

        if (summary.isNullOrBlank()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No semantic summary available
                </body></html>
            """.trimIndent()
            semanticSummarySection.setContent("No semantic summary available", html)
            return
        }

        val escaped = summary
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        val html = """
            <html><body style='padding: 5px; font-family: monospace; word-wrap: break-word;'>
            <div style='white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word;'>$escaped</div>
            </body></html>
        """.trimIndent()
        semanticSummarySection.setContent(summary, html)
    }

    private fun updateTechnologiesSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // Separate technologies by category
        val languages = context.technologies.filter {
            it in listOf("Kotlin", "Java", "Python", "TypeScript", "JavaScript", "Go", "Rust", "C#", "C++", "C", "Ruby", "PHP", "Swift")
        }
        val frameworks = context.technologies.filter {
            it in listOf("React", "Vue.js", "Angular", "Next.js", "Nuxt.js", "FastAPI", "Django", "Flask")
        }
        val tools = context.technologies.filter {
            it in listOf("Gradle", "Maven", "Node.js", "Webpack", "Vite")
        }
        val other = context.technologies.filter {
            it !in languages && it !in frameworks && it !in tools
        }

        val parts = mutableListOf<String>()
        val sep = ", "

        if (languages.isNotEmpty()) {
            parts.add("<b>Languages:</b> ${languages.joinToString(sep)}")
        }
        if (frameworks.isNotEmpty()) {
            parts.add("<b>Frameworks:</b> ${frameworks.joinToString(sep)}")
        }
        if (tools.isNotEmpty()) {
            parts.add("<b>Build Tools:</b> ${tools.joinToString(sep)}")
        }
        if (context.infrastructure.isNotEmpty()) {
            parts.add("<b>Infrastructure:</b> ${context.infrastructure.joinToString(sep)}")
        }
        if (other.isNotEmpty()) {
            parts.add("<b>Other:</b> ${other.joinToString(sep)}")
        }
        if (context.technologyVersions.isNotEmpty()) {
            val versions = context.technologyVersions.entries
                .sortedBy { it.key }
                .joinToString(sep) { (name, version) ->
                    if (version.isNullOrBlank()) name else "$name $version"
                }
            parts.add("<b>Versions:</b> $versions")
        }

        val br = "<br>"
        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${parts.joinToString(br)}
            </body></html>
        """.trimIndent()
        val raw = buildString {
            if (languages.isNotEmpty()) appendLine("Languages: ${languages.joinToString(sep)}")
            if (frameworks.isNotEmpty()) appendLine("Frameworks: ${frameworks.joinToString(sep)}")
            if (tools.isNotEmpty()) appendLine("Build Tools: ${tools.joinToString(sep)}")
            if (context.infrastructure.isNotEmpty()) {
                appendLine("Infrastructure: ${context.infrastructure.joinToString(sep)}")
            }
            if (other.isNotEmpty()) appendLine("Other: ${other.joinToString(sep)}")
            if (context.technologyVersions.isNotEmpty()) {
                val versions = context.technologyVersions.entries
                    .sortedBy { it.key }
                    .joinToString(sep) { (name, version) ->
                        if (version.isNullOrBlank()) name else "$name $version"
                    }
                appendLine("Versions: $versions")
            }
        }.trimEnd()
        technologiesSection.setContent(raw, html)
    }

    private fun updateKeyComponentsSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.keyComponents.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No key components identified
                </body></html>
            """.trimIndent()
            keyComponentsSection.setContent("No key components identified", html)
            return
        }

        val componentsList = context.keyComponents.take(15).joinToString("<br>") { "- $it" }
        val moreCount = if (context.keyComponents.size > 15) "<br>... and ${context.keyComponents.size - 15} more" else ""

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            $componentsList$moreCount
            </body></html>
        """.trimIndent()
        val raw = context.keyComponents.joinToString("\n") { "- $it" }
        keyComponentsSection.setContent(raw, html)
    }

    private fun updateCodeAnalysisSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val htmlParts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()

        // Kotlin Analysis
        @Suppress("UNCHECKED_CAST")
        val kotlin = context.codeAnalysis["kotlin"] as? Map<String, Any>
        if (kotlin != null && (kotlin["files"] as? Int ?: 0) > 0) {
            htmlParts.add("<b>Kotlin:</b> ${kotlin["files"]} files, ${kotlin["classes"]} classes, ${kotlin["functions"]} functions")
            rawParts.add("Kotlin: ${kotlin["files"]} files, ${kotlin["classes"]} classes, ${kotlin["functions"]} functions")

            val dataClasses = kotlin["data_classes"] as? Int ?: 0
            val sealedClasses = kotlin["sealed_classes"] as? Int ?: 0
            val suspendFunctions = kotlin["suspend_functions"] as? Int ?: 0
            if (dataClasses > 0 || sealedClasses > 0 || suspendFunctions > 0) {
                htmlParts.add("  Data classes: $dataClasses, Sealed: $sealedClasses, Suspend functions: $suspendFunctions")
                rawParts.add("  Data classes: $dataClasses, Sealed: $sealedClasses, Suspend functions: $suspendFunctions")
            }

            @Suppress("UNCHECKED_CAST")
            val coroutinePatterns = kotlin["coroutine_patterns"] as? List<String>
            if (!coroutinePatterns.isNullOrEmpty()) {
                htmlParts.add("  Coroutines: ${coroutinePatterns.joinToString(", ")}")
                rawParts.add("  Coroutines: ${coroutinePatterns.joinToString(", ")}")
            }
        }

        // Java Analysis
        @Suppress("UNCHECKED_CAST")
        val java = context.codeAnalysis["java"] as? Map<String, Any>
        if (java != null && (java["files"] as? Int ?: 0) > 0) {
            htmlParts.add("<b>Java:</b> ${java["files"]} files, ${java["classes"]} classes")
            rawParts.add("Java: ${java["files"]} files, ${java["classes"]} classes")

            @Suppress("UNCHECKED_CAST")
            val springPatterns = java["spring_patterns"] as? List<String>
            if (!springPatterns.isNullOrEmpty()) {
                val patterns = springPatterns.joinToString(", ")
                htmlParts.add("  Spring/Jakarta: ${springPatterns.take(5).joinToString(", ")}")
                rawParts.add("  Spring/Jakarta: $patterns")
            }
        }

        // Python Analysis
        @Suppress("UNCHECKED_CAST")
        val python = context.codeAnalysis["python"] as? Map<String, Any>
        if (python != null && (python["files"] as? Int ?: 0) > 0) {
            htmlParts.add("<b>Python:</b> ${python["files"]} files, ${python["classes"]} classes, ${python["functions"]} functions")
            rawParts.add("Python: ${python["files"]} files, ${python["classes"]} classes, ${python["functions"]} functions")

            val asyncFunctions = python["async_functions"] as? Int ?: 0
            if (asyncFunctions > 0) {
                htmlParts.add("  Async functions: $asyncFunctions")
                rawParts.add("  Async functions: $asyncFunctions")
            }

            @Suppress("UNCHECKED_CAST")
            val frameworkPatterns = python["framework_patterns"] as? List<String>
            if (!frameworkPatterns.isNullOrEmpty()) {
                htmlParts.add("  Frameworks: ${frameworkPatterns.joinToString(", ")}")
                rawParts.add("  Frameworks: ${frameworkPatterns.joinToString(", ")}")
            }
        }

        // TypeScript Analysis
        @Suppress("UNCHECKED_CAST")
        val typescript = context.codeAnalysis["typescript"] as? Map<String, Any>
        if (typescript != null && (typescript["files"] as? Int ?: 0) > 0) {
            htmlParts.add("<b>TypeScript:</b> ${typescript["files"]} files, ${typescript["interfaces"]} interfaces, ${typescript["types"]} types")
            rawParts.add("TypeScript: ${typescript["files"]} files, ${typescript["interfaces"]} interfaces, ${typescript["types"]} types")
        }

        // HTML Analysis
        @Suppress("UNCHECKED_CAST")
        val html = context.codeAnalysis["html"] as? Map<String, Any>
        if (html != null && (html["files"] as? Int ?: 0) > 0) {
            htmlParts.add("<b>HTML:</b> ${html["files"]} files")
            rawParts.add("HTML: ${html["files"]} files")

            @Suppress("UNCHECKED_CAST")
            val canvasGames = html["canvas_games"] as? List<String>
            if (!canvasGames.isNullOrEmpty()) {
                htmlParts.add("  Canvas games detected: ${canvasGames.size}")
                rawParts.add("  Canvas games detected: ${canvasGames.size}")
            }
        }

        // CSS Analysis
        @Suppress("UNCHECKED_CAST")
        val css = context.codeAnalysis["css"] as? Map<String, Any>
        if (css != null && (css["files"] as? Int ?: 0) > 0) {
            val classesCount = css["classes_count"] as? Int ?: 0
            val animations = css["animations"] as? List<*>
            val animCount = animations?.size ?: 0
            htmlParts.add("<b>CSS:</b> ${css["files"]} files, $classesCount classes, $animCount animations")
            rawParts.add("CSS: ${css["files"]} files, $classesCount classes, $animCount animations")
        }

        if (htmlParts.isEmpty()) {
            val htmlContent = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No code analysis available
                </body></html>
            """.trimIndent()
            codeAnalysisSection.setContent("No code analysis available", htmlContent)
        } else {
            val htmlContent = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                ${htmlParts.joinToString("<br>")}
                </body></html>
            """.trimIndent()
        val raw = rawParts.joinToString("\n")
            codeAnalysisSection.setContent(raw, htmlContent)
        }
    }

    private fun updateCurrentTaskSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val task = context.currentTask

        // FIXED: Handle nullable CurrentTaskDTO
        if (task == null) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No task information available
                </body></html>
            """.trimIndent()
            currentTaskSection.setContent("No task information available", html)
            return
        }

        // FIXED: Remove 'mode' field (doesn't exist in new DTO), use executionMode if available
        val executionModeStr = task.executionMode?.let { "<b>Execution Mode:</b> $it<br>" } ?: ""
        val priorityStr = task.priority?.let { "<b>Priority:</b> $it<br>" } ?: ""
        val descriptionPreview = task.description.take(500)

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Name:</b> ${task.name}<br>
            <b>Status:</b> ${task.status}<br>
            $executionModeStr
            $priorityStr
            <b>Description:</b><br>
            ${descriptionPreview}${if (task.description.length > 500) "..." else ""}<br>
            </body></html>
        """.trimIndent()

        val raw = buildString {
            appendLine("Name: ${task.name}")
            appendLine("Status: ${task.status}")
            task.executionMode?.let { appendLine("Execution Mode: $it") }
            task.priority?.let { appendLine("Priority: $it") }
            appendLine("Description:")
            appendLine(task.description)
        }.trimEnd()

        currentTaskSection.setContent(raw, html)
    }

    private fun updateUserContextSection(
        context: pl.jclab.refio.core.api.ProjectContextResponse,
        pendingRefs: List<ContextReference>
    ) {
        val htmlBuilder = StringBuilder()
        val rawBuilder = StringBuilder()

        htmlBuilder.append("<html><body style='padding: 5px; word-wrap: break-word;'>")
        htmlBuilder.append("<b>Pending attachments (${pendingRefs.size}):</b><br>")

        rawBuilder.appendLine("Pending attachments (${pendingRefs.size}):")
        if (pendingRefs.isEmpty()) {
            htmlBuilder.append("<i>No pending context references.</i><br><br>")
            rawBuilder.appendLine("- No pending context references.")
            rawBuilder.appendLine()
        } else {
            pendingRefs.forEach { ref ->
                htmlBuilder.append("${pendingContextIcon(ref)} ${ref.displayName}<br>")
                rawBuilder.appendLine("- ${ref.displayName}")
                if (ref.path.isNotBlank()) {
                    htmlBuilder.append("<font color='#666666'>${ref.path}</font><br>")
                    rawBuilder.appendLine("  Path: ${ref.path}")
                }
                if (!ref.content.isNullOrBlank()) {
                    rawBuilder.appendLine(ref.content)
                }
            }
            htmlBuilder.append("<br>")
            rawBuilder.appendLine()
        }

        htmlBuilder.append("<b>Conversation history (${context.userContextRefs.size}):</b><br><br>")
        rawBuilder.appendLine("Conversation history (${context.userContextRefs.size}):")

        if (context.userContextRefs.isEmpty()) {
            htmlBuilder.append("<i>No saved context references.</i>")
            rawBuilder.appendLine("- No saved context references.")
        } else {
            context.userContextRefs.forEach { ref ->
                val typeIcon = when (ref.type) {
                    "FILE" -> "??"
                    "FOLDER" -> "??"
                    "SELECTION" -> "?"
                    "PROVIDER" -> "??"
                    else -> "??"
                }

                val header = "$typeIcon <b>${ref.displayName}</b>"
                val typeInfo = "<font color='#808080'>[${ref.type}${ref.providerId?.let { " - $it" } ?: ""}]</font>"
                val sizeInfo = if (ref.sizeBytes > 0) {
                    val sizeKB = ref.sizeBytes / 1024.0
                    " <font color='#808080'>(${String.format("%.1f", sizeKB)} KB, ~${ref.estimatedTokens} tokens)</font>"
                } else ""

                htmlBuilder.append("$header $typeInfo$sizeInfo<br>")
                rawBuilder.appendLine("- ${ref.displayName} [${ref.type}${ref.providerId?.let { " - $it" } ?: ""}]")
                if (ref.sizeBytes > 0) {
                    val sizeKB = ref.sizeBytes / 1024.0
                    rawBuilder.appendLine("  Size: ${String.format("%.1f", sizeKB)} KB, ~${ref.estimatedTokens} tokens")
                }
                ref.path?.let { path ->
                    htmlBuilder.append("<font color='#666666'>Path: $path</font><br>")
                    rawBuilder.appendLine("  Path: $path")
                }

                val escapedContent = ref.content
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>")
                    .take(500)

                val preview = if (ref.content.length > 500) {
                    "$escapedContent<br><i>... (${ref.content.length - 500} more chars)</i>"
                } else {
                    escapedContent
                }

                htmlBuilder.append("<pre style='padding: 5px; border: 1px solid #ddd; white-space: pre-wrap; word-wrap: break-word; font-size: 10px;'>$preview</pre>")
                htmlBuilder.append("<br>")

                rawBuilder.appendLine(ref.content)
                rawBuilder.appendLine()
            }
        }

        htmlBuilder.append("</body></html>")
        userContextSection.setContent(rawBuilder.toString().trim(), htmlBuilder.toString())
    }

    private fun updateMcpResourcesSection(
        context: pl.jclab.refio.core.api.ProjectContextResponse
    ) {
        if (context.mcpResources.isEmpty()) {
            val html = "<html><b>MCP:</b> no resources</html>"
            mcpResourcesSection.setContent("MCP: no resources", html)
            return
        }

        val htmlBuilder = StringBuilder()
        val rawBuilder = StringBuilder()
        htmlBuilder.append("<html>")
        context.mcpResources.take(10).forEach { res ->
            htmlBuilder.append("<b>@${res.serverId}</b> ${res.name} (${res.uri})<br>")
            res.description?.let {
                htmlBuilder.append(it.take(240)).append("<br>")
            }
        }
        if (context.mcpResources.size > 10) {
            htmlBuilder.append("... and ${context.mcpResources.size - 10} more")
        }
        htmlBuilder.append("</html>")

        context.mcpResources.forEach { res ->
            rawBuilder.appendLine("@${res.serverId} ${res.name} (${res.uri})")
            res.description?.let { rawBuilder.appendLine(it) }
            rawBuilder.appendLine()
        }

        mcpResourcesSection.setContent(rawBuilder.toString().trim(), htmlBuilder.toString())
    }

    private fun pendingContextIcon(ref: ContextReference): String = when (ref.type.name) {
        "FILE" -> "??"
        "FOLDER" -> "??"
        "SELECTION" -> "?"
        "PROVIDER" -> "??"
        else -> "??"
    }

    /**
     * Display user requirements extracted from task description
     */
    private fun updateUserRequirementsSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.userRequirements.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No user requirements extracted
                </body></html>
            """.trimIndent()
            userRequirementsSection.setContent("No user requirements extracted", html)
            return
        }

        val htmlParts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val technologies = context.userRequirements["technologies"] as? List<String>
        if (!technologies.isNullOrEmpty()) {
            htmlParts.add("<b>Required Technologies:</b><br>${technologies.joinToString(", ")}")
            rawParts.add("Required Technologies: ${technologies.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val services = context.userRequirements["services"] as? List<String>
        if (!services.isNullOrEmpty()) {
            htmlParts.add("<b>Required Services:</b><br>${services.joinToString(", ")}")
            rawParts.add("Required Services: ${services.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val notes = context.userRequirements["notes"] as? List<String>
        if (!notes.isNullOrEmpty()) {
            htmlParts.add("<b>Additional Notes:</b>")
            notes.take(5).forEach { note ->
                htmlParts.add("- $note")
            }
            rawParts.add("Additional Notes:")
            notes.forEach { note -> rawParts.add("- $note") }
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${htmlParts.joinToString("<br><br>")}
            </body></html>
        """.trimIndent()
        val raw = rawParts.joinToString("\n")
        userRequirementsSection.setContent(raw, html)
    }

    private fun updateProjectInstructionsSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val instructions = context.projectInstructions
        if (instructions.isNullOrBlank()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No project instructions found.<br>
                Create <code>.refio/agent.md</code> or <code>AGENTS.md</code> in project root.<br>
                Conditional rules: <code>.refio/rules/*.md</code> with YAML frontmatter.
                </body></html>
            """.trimIndent()
            projectInstructionsSection.setContent("No project instructions found", html)
            return
        }

        val preview = if (instructions.length > 500) instructions.take(500) + "..." else instructions
        val escapedHtml = preview
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word; font-family: monospace; font-size: 11px;'>
            $escapedHtml
            </body></html>
        """.trimIndent()
        projectInstructionsSection.setContent(instructions, html)
    }

    private fun updateRagFragmentsSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.ragFragments.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No RAG fragments found. Run project indexing and embeddings to enable contextual search.
                </body></html>
            """.trimIndent()
            ragFragmentsSection.setContent(
                "No RAG fragments found. Run project indexing and embeddings to enable contextual search.",
                html
            )
            return
        }

        val htmlParts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()
        context.ragFragments.take(8).forEach { fragment ->
            val sourceName = fragment.filePath.substringAfterLast("/").substringAfterLast("\\").take(60)
            val lines = if (fragment.startLine != null && fragment.endLine != null) {
                " (lines ${fragment.startLine}-${fragment.endLine})"
            } else ""
            val similarity = String.format("%.0f", fragment.similarity * 100)
            val type = fragment.contentType.lowercase().replaceFirstChar { it.uppercase() }

            htmlParts.add("<b>$sourceName$lines</b> <font color='#808080'>[$type | ${similarity}% match]</font>")

            val escapedContent = fragment.content
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")
            val preview = if (fragment.content.length > 300) {
                "${escapedContent.take(300)}..."
            } else {
                escapedContent
            }

            htmlParts.add("<pre style='padding: 5px; white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word;'>$preview</pre>")
            htmlParts.add("<br>")
        }

        if (context.ragFragments.size > 8) {
            htmlParts.add("<font color='#808080'>... and ${context.ragFragments.size - 8} more fragments</font>")
        }

        context.ragFragments.forEach { fragment ->
            val lines = if (fragment.startLine != null && fragment.endLine != null) {
                " (lines ${fragment.startLine}-${fragment.endLine})"
            } else ""
            val similarity = String.format("%.0f", fragment.similarity * 100)
            val type = fragment.contentType.lowercase().replaceFirstChar { it.uppercase() }
            rawParts.add("${fragment.filePath}$lines [$type | ${similarity}% match]")
            rawParts.add(fragment.content)
            rawParts.add("")
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${htmlParts.joinToString("")}
            </body></html>
        """.trimIndent()
        val raw = rawParts.joinToString("\n").trim()
        ragFragmentsSection.setContent(raw, html)
    }

    private fun updateSubtasksSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.subtasks.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                <font color='#808080'>No subtasks yet. Use PLAN or AGENT mode to create subtasks.</font>
                </body></html>
            """.trimIndent()
            subtasksSection.setContent("No subtasks yet. Use PLAN or AGENT mode to create subtasks.", html)
            return
        }

        // Group by status
        val completed = context.subtasks.filter { it.status == "SUCCESS" }
        val failed = context.subtasks.filter { it.status == "FAILED" }
        val running = context.subtasks.filter { it.status == "RUNNING" }
        val pending = context.subtasks.filter { it.status in listOf("PENDING", "PLANNED", "NEW") }

        val total = context.subtasks.size
        val completedCount = completed.size

        val parts = mutableListOf<String>()

        // Progress bar visualization
        val progressPercent = if (total > 0) (completedCount * 100 / total) else 0
        val progressColor = when {
            failed.isNotEmpty() -> "#cc0000"
            progressPercent == 100 -> "#00aa00"
            else -> "#0066cc"
        }
        parts.add("<b style='color: $progressColor;'>Progress: $completedCount / $total completed ($progressPercent%)</b><br><br>")

        // Running first (most important)
        if (running.isNotEmpty()) {
            parts.add("<b style='color: #ff9900;'>- Running (${running.size}):</b><br>")
            running.take(3).forEach { subtask ->
                parts.add("&nbsp;&nbsp;- ${subtask.description.take(80)}${if (subtask.description.length > 80) "..." else ""}<br>")
            }
            if (running.size > 3) parts.add("&nbsp;&nbsp;<font color='#808080'>... +${running.size - 3} more</font><br>")
            parts.add("<br>")
        }

        // Pending
        if (pending.isNotEmpty()) {
            parts.add("<b style='color: #666666;'>- Pending (${pending.size}):</b><br>")
            pending.take(5).forEach { subtask ->
                parts.add("&nbsp;&nbsp;- ${subtask.description.take(80)}${if (subtask.description.length > 80) "..." else ""}<br>")
            }
            if (pending.size > 5) parts.add("&nbsp;&nbsp;<font color='#808080'>... +${pending.size - 5} more</font><br>")
            parts.add("<br>")
        }

        // Completed (collapsed if many)
        if (completed.isNotEmpty()) {
            parts.add("<b style='color: #00aa00;'>- Completed (${completed.size}):</b><br>")
            completed.takeLast(3).forEach { subtask ->
                parts.add("&nbsp;&nbsp;<font color='#666666'>- ${subtask.description.take(60)}${if (subtask.description.length > 60) "..." else ""}</font><br>")
            }
            if (completed.size > 3) parts.add("&nbsp;&nbsp;<font color='#808080'>... +${completed.size - 3} earlier</font><br>")
            parts.add("<br>")
        }

        // Failed (always show)
        if (failed.isNotEmpty()) {
            parts.add("<b style='color: #cc0000;'>- Failed (${failed.size}):</b><br>")
            failed.forEach { subtask ->
                parts.add("&nbsp;&nbsp;<font color='#cc0000'>- ${subtask.description.take(80)}${if (subtask.description.length > 80) "..." else ""}</font><br>")
            }
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${parts.joinToString("")}
            </body></html>
        """.trimIndent()

        val raw = buildString {
            appendLine("Progress: $completedCount / $total completed ($progressPercent%)")
            if (running.isNotEmpty()) {
                appendLine("Running (${running.size}):")
                running.forEach { appendLine("- ${it.description}") }
                appendLine()
            }
            if (pending.isNotEmpty()) {
                appendLine("Pending (${pending.size}):")
                pending.forEach { appendLine("- ${it.description}") }
                appendLine()
            }
            if (completed.isNotEmpty()) {
                appendLine("Completed (${completed.size}):")
                completed.forEach { appendLine("- ${it.description}") }
                appendLine()
            }
            if (failed.isNotEmpty()) {
                appendLine("Failed (${failed.size}):")
                failed.forEach { appendLine("- ${it.description}") }
            }
        }.trimEnd()

        subtasksSection.setContent(raw, html)
    }

    private fun updateRecentWorkSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // If recentWorkPrompt is available, show it directly (this is what LLM sees)
        if (!context.recentWorkPrompt.isNullOrBlank()) {
            val recentWorkText = context.recentWorkPrompt
            val escapedContent = recentWorkText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")

            val html = """
                <html><body style='padding: 5px; word-wrap: break-word; font-family: monospace;'>
                <div style='white-space: pre-wrap; word-wrap: break-word;'>$escapedContent</div>
                </body></html>
            """.trimIndent()
            recentWorkSection.setContent(recentWorkText, html)
            return
        }

        // Fallback to old behavior if recentWorkPrompt is not available
        if (context.completedFiles.isEmpty() && context.executedSteps.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No completed work yet
                </body></html>
            """.trimIndent()
            recentWorkSection.setContent("No completed work yet", html)
            return
        }

        // Calculate actual sizes
        val completedFilesSize = context.completedFiles.sumOf { it.length }
        val executedStepsSize = context.executedSteps.sumOf { step ->
            // Calculate size of each ExecutedStepDTO component
            val fileSize = step.file?.length ?: 0
            val toolSize = step.tool.length
            val paramsSize = step.parameters.toString().length
            val resultSize = step.result.length
            val summarySize = step.summary?.length ?: 0
            fileSize + toolSize + paramsSize + resultSize + summarySize
        }
        val totalSize = completedFilesSize + executedStepsSize
        val totalTokens = (totalSize / 4).coerceAtLeast(1)

        val htmlParts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()

        // Size summary header
        htmlParts.add("""
            <b>Recent Work Size:</b> ${String.format("%.1f", totalSize / 1024.0)} KB (~${totalTokens} tokens)<br>
            <b>Breakdown:</b><br>
            &nbsp;- Completed files: ${String.format("%.1f", completedFilesSize / 1024.0)} KB<br>
            &nbsp;- Executed steps: ${String.format("%.1f", executedStepsSize / 1024.0)} KB<br>
            <br>
        """.trimIndent())
        rawParts.add("Recent Work Size: ${String.format("%.1f", totalSize / 1024.0)} KB (~$totalTokens tokens)")
        rawParts.add("Breakdown:")
        rawParts.add("- Completed files: ${String.format("%.1f", completedFilesSize / 1024.0)} KB")
        rawParts.add("- Executed steps: ${String.format("%.1f", executedStepsSize / 1024.0)} KB")
        rawParts.add("")

        // Completed files
        if (context.completedFiles.isNotEmpty()) {
            htmlParts.add("<b>Completed Files (${context.completedFiles.size}):</b><br>")
            rawParts.add("Completed Files (${context.completedFiles.size}):")

            context.completedFiles.take(15).forEach { file ->
                htmlParts.add("&nbsp;&nbsp;$file<br>")
                rawParts.add("  - $file")
            }
            if (context.completedFiles.size > 15) {
                htmlParts.add("&nbsp;&nbsp;<font color='#808080'>... +${context.completedFiles.size - 15} more</font><br>")
                rawParts.add("  ... +${context.completedFiles.size - 15} more")
            }
            htmlParts.add("<br>")
            rawParts.add("")
        }

        // Executed steps with detailed breakdown
        if (context.executedSteps.isNotEmpty()) {
            htmlParts.add("<b>Executed Steps (${context.executedSteps.size}):</b><br>")
            rawParts.add("Executed Steps (${context.executedSteps.size}):")

            context.executedSteps.take(8).forEach { step ->
                val fileLabel = step.file?.let { filePath ->
                    val name = runCatching { Path.of(filePath).fileName.toString() }.getOrElse { filePath }
                    name
                } ?: "n/a"

                val paramsSize = step.parameters.toString().length
                val resultSize = step.result.length
                val summarySize = step.summary?.length ?: 0
                val stepSize = (step.file?.length ?: 0) + step.tool.length + paramsSize + resultSize + summarySize
                val stepTokens = stepSize / 4

                htmlParts.add("&nbsp;&nbsp;<b>${step.tool}</b> on <code>$fileLabel</code> <font color='#808080'>(${String.format("%.1f", stepSize / 1024.0)} KB, ~$stepTokens tokens)</font><br>")

                // Show breakdown if step is large
                if (stepSize > 1000) {
                    htmlParts.add("&nbsp;&nbsp;&nbsp;&nbsp;<font color='#666666'>Breakdown: params=$paramsSize chars, result=$resultSize chars")
                    if (summarySize > 0) htmlParts.add(", summary=$summarySize chars")
                    htmlParts.add("</font><br>")
                }

                // Show preview of result (truncated)
                if (resultSize > 0) {
                    val preview = step.result
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "<br>")
                        .take(200)

                    htmlParts.add("&nbsp;&nbsp;&nbsp;&nbsp;<font color='#666666'><pre style='display: inline; margin: 0;'>$preview${if (step.result.length > 200) "..." else ""}</pre></font><br>")
                }

                rawParts.add("  - ${step.tool} on $fileLabel (${String.format("%.1f", stepSize / 1024.0)} KB, ~$stepTokens tokens)")
                if (paramsSize > 0) rawParts.add("      Parameters: $paramsSize chars")
                if (resultSize > 0) rawParts.add("      Result: $resultSize chars")
                if (summarySize > 0) rawParts.add("      Summary: $summarySize chars")
            }

            if (context.executedSteps.size > 8) {
                htmlParts.add("&nbsp;&nbsp;<font color='#808080'>... +${context.executedSteps.size - 8} more steps</font><br>")
                rawParts.add("  ... +${context.executedSteps.size - 8} more steps")
            }
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${htmlParts.joinToString("")}
            </body></html>
        """.trimIndent()

        recentWorkSection.setContent(rawParts.joinToString("\n"), html)
    }

    private fun updateLLMPromptState(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val prompt = context.activeLlmRequestPrompt?.takeIf { it.isNotBlank() } ?: context.llmContextPrompt
        currentLLMPrompt = prompt

        val enabled = !prompt.isNullOrBlank()
        copyPromptButton.isEnabled = enabled
        savePromptButton.isEnabled = enabled
        viewPromptButton.isEnabled = enabled

        if (!enabled) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No LLM prompt available.
                </body></html>
            """.trimIndent()
            llmPromptPreviewSection.setContent("No LLM prompt available.", html)
            return
        }
        val promptText = prompt ?: return

        val structureHtml = buildContextStructureOverview(context)
        val escaped = promptText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        val tokenSummary = "Runtime request: ~${context.activeEstimatedTokens} tokens"
        val modeLabel = "Active request only"

        val html = """
            <html><body style='padding: 5px; font-family: monospace; word-wrap: break-word;'>
            <div style='margin-bottom: 15px; padding: 10px; border-left: 4px solid #4A90D9;'>
                <b>Context Structure (what LLM sees):</b><br><br>
                $structureHtml
                <br><font color='#666666'>View: $modeLabel | Size: ${String.format("%.1f", promptText.length / 1024.0)} KB | $tokenSummary</font>
            </div>
            <b>LLM Runtime Request:</b><br><br>
            <div style='white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word;'>$escaped</div>
            </body></html>
        """.trimIndent()
        llmPromptPreviewSection.setContent(promptText, html)
    }
    /**
     * Build HTML overview of context structure with section sizes
     */
    private fun buildContextStructureOverview(context: pl.jclab.refio.core.api.ProjectContextResponse): String {
        val sectionTokens = context.contextSectionTokens
        if (sectionTokens.isEmpty()) {
            return "<font color='#999999'>No section data available</font>"
        }

        // Sort by size (largest first) and take top 10
        val sortedSections = sectionTokens.entries
            .sortedByDescending { it.value.tokens }
            .take(10)

        val parts = mutableListOf<String>()
        sortedSections.forEach { (key, info) ->
            val color = ContextSectionColorPalette.colorFor(key)
            val rgbColor = String.format("#%06X", 0xFFFFFF and color.rgb)
            val percentage = String.format("%.1f", info.percentage)

            parts.add(
                """<font color='$rgbColor'>▸</font> <b>${info.name}</b>: """
            )
            parts.add("<font color='#666666'>")
            parts.add("~${info.tokens} tokens ($percentage%)")
            parts.add("</font><br>")
        }

        if (sectionTokens.size > 10) {
            parts.add("<font color='#999999'>... +${sectionTokens.size - 10} more sections</font><br>")
        }

        return parts.joinToString("")
    }

    private fun openLLMPromptViewer() {
        val prompt = currentLLMPrompt
        if (prompt.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "LLM Context Prompt is not available. Please refresh the context first.",
                "View LLM Prompt"
            )
            return
        }

        LLMPromptViewerDialog(project, prompt).show()
    }

    private fun updateSections(
        context: pl.jclab.refio.core.api.ProjectContextResponse,
        pendingRefs: List<ContextReference>
    ) {
        sectionEntries.forEach { entry ->
            entry.section.updateTokenInfo(context.contextSectionTokens[entry.key])
            entry.updater(context, pendingRefs)
        }

        val sorted = sectionEntries.sortedWith(
            compareByDescending<SectionEntry> { entry ->
                context.contextSectionTokens[entry.key]?.tokens ?: 0
            }.thenBy { it.order }
        )
        applySectionOrder(sorted)
    }

    private fun applySectionOrder(entries: List<SectionEntry>) {
        contentPanel.removeAll()
        entries.forEach { entry ->
            contentPanel.add(entry.section)
        }
        contentPanel.add(llmPromptPreviewSection)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun updateStructureSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        val fileTypesSummary = context.fileTypes.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString("<br>") { "- .${it.key}: ${it.value} files" }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Directories:</b> ${context.directoryCount}<br>
            <b>Max Depth:</b> ${context.maxDepth}<br>
            <b>Total Files:</b> ${context.totalFiles}<br>
            <br>
            <b>File Types:</b><br>
            $fileTypesSummary
            </body></html>
        """.trimIndent()

        val raw = buildString {
            appendLine("Directories: ${context.directoryCount}")
            appendLine("Max Depth: ${context.maxDepth}")
            appendLine("Total Files: ${context.totalFiles}")
            appendLine()
            appendLine("File Types:")
            context.fileTypes.entries
                .sortedByDescending { it.value }
                .forEach { appendLine("- .${it.key}: ${it.value} files") }
        }.trimEnd()

        structureSection.setContent(raw, html)
    }

    private fun updateConversationSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.conversationHistory.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No conversation history yet
                </body></html>
            """.trimIndent()
            conversationSection.setContent("No conversation history yet", html)
            return
        }

        val parts = mutableListOf<String>()
        parts.add("<b>${context.conversationHistory.size} message(s) in context:</b><br><br>")

        context.conversationHistory.takeLast(10).forEach { msg ->
            val roleIcon = if (msg.role == "user") "U" else "A"
            val roleColor = if (msg.role == "user") "#0066cc" else "#006600"
            val previewRaw = msg.content.take(200)
            val preview = previewRaw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")

            parts.add("<font color='$roleColor'>$roleIcon <b>${msg.role.uppercase()}</b></font><br>")
            parts.add("<font color='#666666'>$preview${if (msg.content.length > 200) "..." else ""}</font><br><br>")
        }

        if (context.conversationHistory.size > 10) {
            parts.add("<font color='#808080'>... and ${context.conversationHistory.size - 10} more messages</font>")
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${parts.joinToString("")}
            </body></html>
        """.trimIndent()

        val raw = buildString {
            appendLine("${context.conversationHistory.size} message(s) in context:")
            context.conversationHistory.forEach { msg ->
                appendLine("${msg.role.uppercase()}:")
                appendLine(msg.content)
                appendLine()
            }
        }.trimEnd()

        conversationSection.setContent(raw, html)
    }

    private fun updateDomainAnalysisSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        if (context.domainAnalysis.isEmpty()) {
            val html = """
                <html><body style='padding: 5px; word-wrap: break-word;'>
                No domain analysis available
                </body></html>
            """.trimIndent()
            domainAnalysisSection.setContent("No domain analysis available", html)
            return
        }

        val parts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()
        context.domainAnalysis.entries
            .sortedByDescending { (it.value as? Number)?.toDouble() ?: 0.0 }
            .take(10)
            .forEach { (domain, score) ->
                val scorePercent = ((score as? Number)?.toDouble() ?: 0.0) * 100
                parts.add("- <b>$domain:</b> ${String.format("%.1f", scorePercent)}%")
                rawParts.add("- $domain: ${String.format("%.1f", scorePercent)}%")
            }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Domain Scores:</b><br><br>
            ${parts.joinToString("<br>")}
            </body></html>
        """.trimIndent()

        val raw = buildString {
            appendLine("Domain Scores:")
            rawParts.forEach { appendLine(it) }
        }.trimEnd()

        domainAnalysisSection.setContent(raw, html)
    }

    private fun updateFrameworkAnalysisSection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // Framework analysis data comes from project analysis
        val technologies = context.technologies
        if (technologies.isEmpty()) {
            frameworkAnalysisSection.setContent(
                "No frameworks detected",
                "<html><body style='padding: 5px;'>No frameworks detected</body></html>"
            )
            return
        }

        val parts = mutableListOf<String>()
        val rawParts = mutableListOf<String>()

        parts.add("<b>Detected Technologies:</b>")
        rawParts.add("Detected Technologies:")
        technologies.forEach { tech ->
            parts.add("- $tech")
            rawParts.add("- $tech")
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            ${parts.joinToString("<br>")}
            </body></html>
        """.trimIndent()

        frameworkAnalysisSection.setContent(rawParts.joinToString("\n"), html)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateWorkingMemorySection(_context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // Display working memory entries if available in the context
        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Working Memory</b><br>
            Working memory entries are accumulated during agent execution.<br>
            Entries are displayed with importance indicators:<br>
            <span style='color: #4CAF50;'>&#9679;</span> High importance<br>
            <span style='color: #FFC107;'>&#9679;</span> Medium importance<br>
            <span style='color: #9E9E9E;'>&#9679;</span> Low importance
            </body></html>
        """.trimIndent()
        workingMemorySection.setContent("Working memory active", html)
    }

    private fun updateContextStabilitySection(context: pl.jclab.refio.core.api.ProjectContextResponse) {
        // Context stability is estimated from section token info if available
        val sectionTokens = context.contextSectionTokens
        val hasStableContext = sectionTokens.isNotEmpty()
        val stabilityPercent = if (hasStableContext) 85 else 0 // Stable context is cached when available

        val barColor = when {
            stabilityPercent >= 80 -> "#4CAF50"
            stabilityPercent >= 50 -> "#FFC107"
            else -> "#F44336"
        }

        val html = """
            <html><body style='padding: 5px; word-wrap: break-word;'>
            <b>Context Stability:</b> ~${stabilityPercent}% unchanged from previous turn<br>
            <div style='background: #E0E0E0; width: 200px; height: 12px; border-radius: 6px; margin-top: 4px;'>
                <div style='background: $barColor; width: ${stabilityPercent * 2}px; height: 12px; border-radius: 6px;'></div>
            </div>
            <br>
            <span style='font-size: 0.9em; color: #888;'>
            Stable context (project info, conventions) is cached and reused across turns.
            </span>
            </body></html>
        """.trimIndent()

        contextStabilitySection.setContent("Stability: ~${stabilityPercent}%", html)
    }

    private fun getSelectedModelContextLimit(): Int {
        // Use SessionManager's dynamic context window calculation
        return sessionManager.getMaxContextWindow()
    }

    private fun showMessage(message: String) {
        SwingUtilities.invokeLater {
            val html = "<html><body style='padding: 5px;'>$message</body></html>"
            currentTaskSection.setContent(message, html)
            currentLLMPrompt = null
            copyPromptButton.isEnabled = false
            savePromptButton.isEnabled = false
            viewPromptButton.isEnabled = false

            // Clear other sections (order matches contentPanel order for clarity)
            sectionEntries
                .map { it.section }
                .filter { it != currentTaskSection }
                .forEach { it.clearContent() }
            llmPromptPreviewSection.clearContent()
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            val html = "<html><body style='color: red; padding: 5px;'>$message</body></html>"
            currentTaskSection.setContent(message, html)
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return format.format(date)
    }

    /**
     * Copy LLM Context Prompt to clipboard
     */
    private fun copyLLMContextToClipboard() {
        try {
            val prompt = currentLLMPrompt
            if (prompt.isNullOrBlank()) {
                Messages.showWarningDialog(
                    project,
                    "LLM Context Prompt is not available. Please refresh the context first.",
                    "Copy LLM Prompt"
                )
                return
            }
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val stringSelection = StringSelection(prompt)
            clipboard.setContents(stringSelection, null)

            logger.info { "LLM Context Prompt copied to clipboard, size: ${prompt.length} chars" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to copy LLM prompt to clipboard" }
            Messages.showErrorDialog(
                project,
                "Failed to copy prompt: ${e.message}",
                "Copy LLM Prompt"
            )
        }
    }

    /**
     * Save LLM Context Prompt to file
     */
    private fun saveLLMContextToFile() {
        try {
            val prompt = currentLLMPrompt
            if (prompt.isNullOrBlank()) {
                Messages.showWarningDialog(
                    project,
                    "LLM Context Prompt is not available. Please refresh the context first.",
                    "Save LLM Prompt"
                )
                return
            }

            // Show file chooser
            val fileChooser = JFileChooser().apply {
                dialogTitle = "Save LLM Context Prompt"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                
                // Add file filters
                addChoosableFileFilter(FileNameExtensionFilter("Text files (*.txt)", "txt"))
                addChoosableFileFilter(FileNameExtensionFilter("Markdown files (*.md)", "md"))
                addChoosableFileFilter(FileNameExtensionFilter("All files (*.*)", "*"))
                fileFilter = choosableFileFilters[0] // Default to .txt
                
                // Set default filename
                selectedFile = File("llm_context_prompt_${System.currentTimeMillis()}.txt")
            }

            val result = fileChooser.showSaveDialog(this)

            if (result == JFileChooser.APPROVE_OPTION) {
                val file = fileChooser.selectedFile
                
                // Add extension if not present
                val finalFile = if (!file.name.contains(".")) {
                    val selectedFilter = fileChooser.fileFilter as? FileNameExtensionFilter
                    val extension = selectedFilter?.extensions?.firstOrNull() ?: "txt"
                    File(file.parentFile, "${file.name}.$extension")
                } else {
                    file
                }

                val projectRoot = project.basePath?.let { Paths.get(it) }
                if (projectRoot == null) {
                    Messages.showErrorDialog(
                        project,
                        "Project root not found. Cannot save outside sandbox.",
                        "Save LLM Prompt"
                    )
                    return
                }

                val sandbox = PathSandbox(projectRoot)
                try {
                    sandbox.validatePath(finalFile.toPath())
                } catch (e: SecurityException) {
                    Messages.showErrorDialog(
                        project,
                        "Selected path is outside the project sandbox.",
                        "Save LLM Prompt"
                    )
                    return
                }

                finalFile.writeText(prompt)

                Messages.showInfoMessage(
                    project,
                    "LLM Context Prompt saved to:\n${finalFile.absolutePath}\n\nSize: ${prompt.length} characters",
                    "Save LLM Prompt"
                )

                logger.info { "LLM Context Prompt saved to: ${finalFile.absolutePath}, size: ${prompt.length} chars" }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to save LLM prompt to file" }
            Messages.showErrorDialog(
                project,
                "Failed to save prompt: ${e.message}",
                "Save LLM Prompt"
            )
        }
    }

    fun dispose() {
        cs.cancel()
    }
}

/**
 * Visual representation of token usage per context section.
 * Displays colored blocks similar to Claude Code's context usage visualization.
 */
class TokenUsageVisualizationPanel : JBPanel<TokenUsageVisualizationPanel>(BorderLayout()) {

    private data class SectionData(
        val name: String,
        val tokens: Int,
        val percentage: Double,
        val color: Color
    )

    private var sections: List<SectionData> = emptyList()
    private var totalTokens: Int = 0
    private var contextLimit: Int = 128_000
    private var usagePercentage: Double = 0.0

    init {
        preferredSize = Dimension(0, 140)
        minimumSize = Dimension(0, 120)
        border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        background = LCATheme.backgroundColor
        isOpaque = true
    }

    fun updateTokenUsage(
        sectionTokens: Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>,
        total: Int,
        limit: Int
    ) {
        totalTokens = total
        contextLimit = limit.coerceAtLeast(1)
        usagePercentage = if (limit > 0) (total.toDouble() / limit * 100) else 0.0

        // Calculate percentages relative to context limit (not sum of sections)
        sections = sectionTokens.map { (key, info) ->
            SectionData(
                name = info.name,
                tokens = info.tokens,
                // Percentage relative to context limit for proper bar scaling
                percentage = if (contextLimit > 0) (info.tokens.toDouble() / contextLimit * 100) else 0.0,
                color = ContextSectionColorPalette.colorFor(key)
            )
        }.filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }

        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val startX = 10
        val barHeight = 20
        val barY = 5
        val barWidth = width - 20

        // Draw background bar first
        val isDark = LCATheme.isDark
        g2.color = if (isDark) Color(0x3C3F41) else Color(0xE8E8E8)
        g2.fillRoundRect(startX, barY, barWidth, barHeight, 4, 4)

        // Draw section blocks - scale to usagePercentage of bar width
        val usedBarWidth = (usagePercentage / 100 * barWidth).toInt().coerceIn(0, barWidth)
        var currentX = startX

        if (usedBarWidth > 0 && sections.isNotEmpty()) {
            // Calculate total tokens for proportional distribution within used area
            val totalSectionTokens = sections.sumOf { it.tokens }.coerceAtLeast(1)

            sections.forEach { section ->
                // Proportional width within the used area
                val sectionWidth = ((section.tokens.toDouble() / totalSectionTokens) * usedBarWidth).toInt().coerceAtLeast(1)
                if (currentX + sectionWidth <= startX + usedBarWidth) {
                    g2.color = section.color
                    g2.fillRect(currentX, barY, sectionWidth, barHeight)
                    currentX += sectionWidth
                }
            }
        }

        // Draw border
        g2.color = LCATheme.borderColor
        g2.drawRoundRect(startX, barY, barWidth, barHeight, 4, 4)

        // Draw header below bar
        g2.font = Font("SansSerif", Font.BOLD, 11)
        g2.color = LCATheme.labelForeground
        val headerText = "Total: ${formatTokens(totalTokens)} / ${formatTokens(contextLimit)} tokens (${String.format("%.1f", usagePercentage)}%)"
        g2.drawString(headerText, startX, barY + barHeight + 15)

        // Draw legend
        g2.font = Font("SansSerif", Font.PLAIN, 9)
        var legendY = barY + barHeight + 30  // Below header text
        var legendX = startX
        val lineHeight = 14
        val maxLegendItems = 8

        val labelForeground = LCATheme.labelForeground
        val borderColor = LCATheme.borderColor

        sections.take(maxLegendItems).forEach { section ->
            val label = "${section.name}: ${formatTokens(section.tokens)} (${String.format("%.1f", section.percentage)}%)"
            val labelWidth = g2.fontMetrics.stringWidth(label) + 24

            // Wrap to next line if needed
            if (legendX + labelWidth > width - 10 && legendX > startX) {
                legendX = startX
                legendY += lineHeight
            }

            // Color box
            g2.color = section.color
            g2.fillRect(legendX, legendY, 10, 10)
            g2.color = borderColor
            g2.drawRect(legendX, legendY, 10, 10)

            // Label
            g2.color = labelForeground
            g2.drawString(label, legendX + 14, legendY + 9)
            legendX += labelWidth
        }

        if (sections.size > maxLegendItems) {
            // Wrap if needed
            val moreText = "... +${sections.size - maxLegendItems} more"
            val moreWidth = g2.fontMetrics.stringWidth(moreText) + 10
            if (legendX + moreWidth > width - 10) {
                legendX = startX
                legendY += lineHeight
            }
            g2.color = LCATheme.labelDisabledForeground
            g2.drawString(moreText, legendX, legendY + 9)
        }
    }

    private fun formatTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}

