package pl.jclab.refio.ui.components.debug

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.JBTable
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.LogLevel
import pl.jclab.refio.services.logging.PluginLogger
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Debug panel showing internal plugin state
 */
class DebugPanel(private val project: Project) : JBPanel<DebugPanel>(BorderLayout()) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val coreManager = CoreConnectionManager.getInstance()
    private val apiLogRepository = ApiLogRepository()

    /** Provider for agent execution text (trace + graph + events). Set from RefioMainPanel. */
    var agentTraceProvider: (() -> String)? = null

    private val statePanel: JPanel
    private val refreshButton: JButton
    private var autoRefreshTimer: Timer? = null

    // State labels
    private val sessionIdLabel = JLabel("-")
    private val sessionModeLabel = JLabel("-")
    private val sessionModelLabel = JLabel("-")
    private val sessionStatusLabel = JLabel("-")
    private val sessionCreatedAtLabel = JLabel("-")
    private val sessionTokensInLabel = JLabel("-")
    private val sessionTokensOutLabel = JLabel("-")
    private val sessionCostLabel = JLabel("-")
    private val messagesCountLabel = JLabel("-")
    private val subtasksCountLabel = JLabel("-")
    private val selectedModelLabel = JLabel("-")
    private val coreHealthLabel = JLabel("-")
    private val coreUrlLabel = JLabel("-")
    private val coreLatencyLabel = JLabel("-")
    private val projectRootLabel = JLabel("-")
    private val databasePathLabel = JLabel("-")
    private val lastUpdateLabel = JLabel("-")

    // LLM Statistics
    private val totalApiCallsLabel = JLabel("-")
    private val totalTokensInLabel = JLabel("-")
    private val totalTokensOutLabel = JLabel("-")
    private val totalCostGlobalLabel = JLabel("-")
    private val avgLatencyLabel = JLabel("-")
    private val errorCountLabel = JLabel("-")

    // Recent API Logs
    private val recentLogsModel = ApiLogsTableModel()
    private val recentLogsTable = JBTable(recentLogsModel).apply {
        emptyText.text = "No API logs yet"
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setDefaultRenderer(Any::class.java, TooltipCellRenderer())
        columnModel.getColumn(0).preferredWidth = 70   // Time
        columnModel.getColumn(1).preferredWidth = 100  // Source
        columnModel.getColumn(2).preferredWidth = 110  // Provider
        columnModel.getColumn(3).preferredWidth = 130  // Model
        columnModel.getColumn(4).preferredWidth = 50   // In
        columnModel.getColumn(5).preferredWidth = 50   // Out
        columnModel.getColumn(6).preferredWidth = 60   // Lat
        columnModel.getColumn(7).preferredWidth = 80   // Cost
        columnModel.getColumn(8).preferredWidth = 120  // Status
    }

    // Tool Usage Analytics
    private val toolUsageModel = ToolUsageTableModel()
    private val toolUsageTable = JBTable(toolUsageModel).apply {
        emptyText.text = "No tool invocations recorded yet"
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setDefaultRenderer(Any::class.java, TooltipCellRenderer())
        columnModel.getColumn(0).preferredWidth = 180  // Tool
        columnModel.getColumn(1).preferredWidth = 60   // Count
        columnModel.getColumn(2).preferredWidth = 70   // Success
        columnModel.getColumn(3).preferredWidth = 80   // Avg
        columnModel.getColumn(4).preferredWidth = 80   // Max
        columnModel.getColumn(5).preferredWidth = 300  // Last Error
    }

    // Model Usage Analytics
    private val modelUsageModel = ModelUsageTableModel()
    private val modelUsageTable = JBTable(modelUsageModel).apply {
        emptyText.text = "No LLM calls recorded yet"
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setDefaultRenderer(Any::class.java, TooltipCellRenderer())
        columnModel.getColumn(0).preferredWidth = 100  // Provider
        columnModel.getColumn(1).preferredWidth = 220  // Model
        columnModel.getColumn(2).preferredWidth = 60   // Calls
        columnModel.getColumn(3).preferredWidth = 90   // Tokens In
        columnModel.getColumn(4).preferredWidth = 90   // Tokens Out
        columnModel.getColumn(5).preferredWidth = 90   // Cost
        columnModel.getColumn(6).preferredWidth = 80   // Avg
        columnModel.getColumn(7).preferredWidth = 80   // Max
    }

    init {
        // Title panel
        val includeDebugLogsCheckbox = JCheckBox("Include DEBUG", false).apply {
            font = font.deriveFont(JBUIScale.scale(10f))
            toolTipText = "Include DEBUG-level log entries in the export"
        }
        val maxLogEntriesSpinner = JSpinner(SpinnerNumberModel(200, 50, 1000, 50)).apply {
            toolTipText = "Max number of log entries to include"
            preferredSize = Dimension(70, preferredSize.height)
        }
        val copySessionDebugButton = JButton("Copy Session Debug").apply {
            font = font.deriveFont(JBUIScale.scale(10f))
            toolTipText = "Copy comprehensive session debug info to clipboard (for LLM analysis)"
            addActionListener {
                val includeDebug = includeDebugLogsCheckbox.isSelected
                val maxEntries = maxLogEntriesSpinner.value as Int
                copySessionDebugToClipboard(includeDebug, maxEntries)
            }
        }

        val titlePanel = JPanel(BorderLayout()).apply {
            add(JLabel("Plugin Internal State").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.WEST)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(JLabel("Logs:").apply { font = font.deriveFont(JBUIScale.scale(10f)) })
                add(maxLogEntriesSpinner)
                add(includeDebugLogsCheckbox)
                add(copySessionDebugButton)
                add(Box.createHorizontalStrut(8))
                refreshButton = JButton("Refresh").apply {
                    addActionListener { refreshState() }
                }
                add(refreshButton)
            }
            add(buttonsPanel, BorderLayout.EAST)
        }

        // State panel with BoxLayout
        statePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            // Session section
            add(createSectionHeader("Active Session"))
            add(createStateRow("Session ID:", sessionIdLabel))
            add(createStateRow("Mode:", sessionModeLabel))
            add(createStateRow("Session Model:", sessionModelLabel))
            add(createStateRow("Status:", sessionStatusLabel))
            add(createStateRow("Created At:", sessionCreatedAtLabel))
            add(createStateRow("Tokens In:", sessionTokensInLabel))
            add(createStateRow("Tokens Out:", sessionTokensOutLabel))
            add(createStateRow("Cost (USD):", sessionCostLabel))

            // Messages & Subtasks section
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Conversation State"))
            add(createStateRow("Messages:", messagesCountLabel))
            add(createStateRow("Subtasks:", subtasksCountLabel))
            add(createStateRow("Selected Model:", selectedModelLabel))

            // Core connection section
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Core Connection"))
            add(createStateRow("Health:", coreHealthLabel))
            add(createStateRow("URL:", coreUrlLabel))
            add(createStateRow("Latency:", coreLatencyLabel))

            // LLM Statistics
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("LLM Statistics (Global)"))
            add(createStateRow("Total API Calls:", totalApiCallsLabel))
            add(createStateRow("Total Tokens In:", totalTokensInLabel))
            add(createStateRow("Total Tokens Out:", totalTokensOutLabel))
            add(createStateRow("Total Cost:", totalCostGlobalLabel))
            add(createStateRow("Avg Latency:", avgLatencyLabel))
            add(createStateRow("Error Count:", errorCountLabel))

            // Recent API Logs
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Recent API Logs (Last 10)"))
            val logsScrollPane = JBScrollPane(recentLogsTable).apply {
                preferredSize = Dimension(Int.MAX_VALUE, 200)
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            add(logsScrollPane)

            // Tool Usage Analytics
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Tool Usage Analytics"))
            val toolUsageScrollPane = JBScrollPane(toolUsageTable).apply {
                preferredSize = Dimension(Int.MAX_VALUE, 200)
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            add(toolUsageScrollPane)

            // Model Usage Analytics
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Model Usage Analytics"))
            val modelUsageScrollPane = JBScrollPane(modelUsageTable).apply {
                preferredSize = Dimension(Int.MAX_VALUE, 200)
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            add(modelUsageScrollPane)

            // Last update
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Debug Info"))
            add(createStateRow("Project Root:", projectRootLabel))
            add(createStateRow("Database Path:", databasePathLabel))
            add(createStateRow("Last Update:", lastUpdateLabel))

            // Push everything to the top
            add(Box.createVerticalGlue())
        }

        val scrollPane = JBScrollPane(statePanel)
        add(titlePanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Auto-refresh every 30 seconds; stopped in dispose() so the panel does not
        // keep querying the DB after the tool window is closed.
        autoRefreshTimer = Timer(30000) {
            refreshState()
        }.apply {
            isRepeats = true
            start()
        }

        // Initial refresh
        refreshState()
    }

    private fun createSectionHeader(title: String): JPanel {
        return JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            border = BorderFactory.createEmptyBorder(5, 0, 5, 0)

            add(JLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 13f)
            }, BorderLayout.WEST)
        }
    }

    private fun createStateRow(label: String, valueLabel: JLabel): JPanel {
        return JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 25)
            border = BorderFactory.createEmptyBorder(2, 10, 2, 0)

            val keyLabel = JLabel(label).apply {
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(150, preferredSize.height)
            }

            valueLabel.font = Font("Monospaced", Font.PLAIN, 12)

            add(keyLabel, BorderLayout.WEST)
            add(valueLabel, BorderLayout.CENTER)
        }
    }

    private fun refreshState() {
        cs.launch {
            val session = sessionManager.activeSession.value
            val messages = sessionManager.messages.value
            val subtasks = sessionManager.subtasks.value
            val selectedModel = sessionManager.selectedModel.value
            val health = coreManager.healthState.value

            // Fetch API logs and statistics
            val recentLogs = try {
                apiLogRepository.getRecentLogs(10)
            } catch (e: Exception) {
                emptyList()
            }

            val statistics = try {
                apiLogRepository.getGlobalStatistics()
            } catch (e: Exception) {
                null
            }

            SwingUtilities.invokeLater {
                // Session info
                if (session != null) {
                    sessionIdLabel.text = session.id
                    sessionModeLabel.text = session.mode.name
                    sessionModelLabel.text = session.model ?: "-"
                    sessionStatusLabel.text = session.status.name
                    sessionCreatedAtLabel.text = formatTimestamp(session.createdAt)
                    sessionTokensInLabel.text = session.tokensIn.toString()
                    sessionTokensOutLabel.text = session.tokensOut.toString()
                    sessionCostLabel.text = String.format("%.6f", session.costUsd)
                } else {
                    sessionIdLabel.text = "No active session"
                    sessionModeLabel.text = "-"
                    sessionModelLabel.text = "-"
                    sessionStatusLabel.text = "-"
                    sessionCreatedAtLabel.text = "-"
                    sessionTokensInLabel.text = "-"
                    sessionTokensOutLabel.text = "-"
                    sessionCostLabel.text = "-"
                }

                // Conversation state
                messagesCountLabel.text = messages.size.toString()
                subtasksCountLabel.text = subtasks.size.toString()
                selectedModelLabel.text = selectedModel

                // Core connection
                coreHealthLabel.text = when (health.state) {
                    pl.jclab.refio.services.core.CoreHealthState.CONNECTED -> "OK CONNECTED"
                    pl.jclab.refio.services.core.CoreHealthState.DEGRADED -> "WARN DEGRADED"
                    pl.jclab.refio.services.core.CoreHealthState.DISCONNECTED -> "ERR DISCONNECTED"
                }
                coreUrlLabel.text = "In-process (embedded Kotlin core)"
                coreLatencyLabel.text = "< 1ms"

                // LLM Statistics
                if (statistics != null) {
                    totalApiCallsLabel.text = statistics.totalCalls.toString()
                    totalTokensInLabel.text = formatNumber(statistics.totalInputTokens)
                    totalTokensOutLabel.text = formatNumber(statistics.totalOutputTokens)
                    totalCostGlobalLabel.text = String.format("$%.6f", statistics.totalCost)
                    avgLatencyLabel.text = "${statistics.avgLatencyMs}ms"
                    errorCountLabel.text = "${statistics.errorCount} (${if (statistics.totalCalls > 0) String.format("%.1f", statistics.errorCount * 100.0 / statistics.totalCalls) else "0.0"}%)"
                } else {
                    totalApiCallsLabel.text = "-"
                    totalTokensInLabel.text = "-"
                    totalTokensOutLabel.text = "-"
                    totalCostGlobalLabel.text = "-"
                    avgLatencyLabel.text = "-"
                    errorCountLabel.text = "-"
                }

                // Recent API Logs
                recentLogsModel.updateRows(recentLogs)

                // Tool Usage Analytics
                toolUsageModel.updateRows(pl.jclab.refio.core.services.monitoring.ToolUsageStats.snapshot())

                // Model Usage Analytics
                modelUsageModel.updateRows(pl.jclab.refio.core.services.monitoring.ModelUsageStats.snapshot())

                // Debug info
                projectRootLabel.text = project.basePath ?: "Unknown"
                databasePathLabel.text = coreManager.getDatabasePath()
                lastUpdateLabel.text = formatTimestamp(System.currentTimeMillis())
            }
        }
    }

    /**
     * Build and copy a comprehensive session debug report to clipboard.
     * Groups data from all panels into a single markdown document suitable for LLM analysis.
     */
    private fun copySessionDebugToClipboard(includeDebugLogs: Boolean, maxLogEntries: Int) {
        cs.launch {
            // The report carries full prompts, tool outputs and logs - redact secrets
            // (API keys, tokens) before anything leaves the plugin via the clipboard.
            val report = pl.jclab.refio.core.security.SecureLogger.redact(
                buildSessionDebugReport(includeDebugLogs, maxLogEntries)
            )
            SwingUtilities.invokeLater {
                val sel = StringSelection(report)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
            }
        }
    }

    private suspend fun buildSessionDebugReport(includeDebugLogs: Boolean, maxLogEntries: Int): String = buildString {
        val session = sessionManager.activeSession.value
        val messages = sessionManager.messages.value
        val subtasks = sessionManager.subtasks.value
        val selectedModel = sessionManager.selectedModel.value
        val health = coreManager.healthState.value
        val timeFmt = SimpleDateFormat("HH:mm:ss")

        appendLine("# Session Debug Report")
        appendLine()
        appendLine("Generated: ${formatTimestamp(System.currentTimeMillis())}")
        appendLine()

        // ── 1. Session Info ──
        appendLine("## Session Info")
        appendLine()
        if (session != null) {
            appendLine("| Key | Value |")
            appendLine("|-----|-------|")
            appendLine("| Session ID | `${session.id}` |")
            appendLine("| Mode | ${session.mode.name} |")
            appendLine("| Model | ${session.model ?: "-"} |")
            appendLine("| Selected Model | $selectedModel |")
            appendLine("| Status | ${session.status.name} |")
            appendLine("| Created | ${formatTimestamp(session.createdAt)} |")
            appendLine("| Tokens In/Out | ${session.tokensIn} / ${session.tokensOut} |")
            appendLine("| Cost | ${"$%.6f".format(session.costUsd)} |")
            appendLine("| Messages | ${messages.size} |")
            appendLine("| Subtasks | ${subtasks.size} |")
            appendLine("| Execution Mode | ${session.executionMode} |")
            appendLine("| Thinking | ${session.thinkingEnabled} |")
            val healthText = when (health.state) {
                pl.jclab.refio.services.core.CoreHealthState.CONNECTED -> "CONNECTED"
                pl.jclab.refio.services.core.CoreHealthState.DEGRADED -> "DEGRADED"
                pl.jclab.refio.services.core.CoreHealthState.DISCONNECTED -> "DISCONNECTED"
            }
            appendLine("| Core Health | $healthText |")
            appendLine("| Project Root | ${project.basePath ?: "?"} |")
        } else {
            appendLine("No active session")
        }
        appendLine()

        // ── 2. Conversation (full content) ──
        appendLine("## Conversation (${messages.size} messages)")
        appendLine()
        if (messages.isNotEmpty()) {
            var i = 0
            while (i < messages.size) {
                val msg = messages[i]
                val time = timeFmt.format(Date(msg.createdAt))
                val role = msg.role.uppercase()
                val agentTag = msg.agentName?.let { " [$it d${msg.agentDepth ?: 0}]" } ?: ""

                when {
                    // Tool call from assistant + tool result = merge into one block
                    msg.role == "assistant" && msg.toolCallInfo != null -> {
                        val info = msg.toolCallInfo!!
                        val params = info.parameters.entries
                            .sortedBy { it.key }
                            .joinToString(", ") { "${it.key}=${it.value}" }
                        val statusStr = info.status.name
                        val resultStr = info.result?.summary ?: ""

                        // Check if next message is the tool result
                        val nextMsg = messages.getOrNull(i + 1)
                        val toolResultPreview = if (nextMsg?.role == "tool" && nextMsg.toolCallId == info.toolCallId) {
                            i++ // skip the tool result message, we merge it here
                            val resultContent = nextMsg.content.trim()
                            if (resultContent.isNotBlank()) {
                                resultContent
                            } else {
                                nextMsg.toolStreamContent?.trim() ?: ""
                            }
                        } else null

                        appendLine("**[$time] TOOL$agentTag** `${info.toolName}` ($params) -> $statusStr")
                        if (resultStr.isNotBlank()) appendLine("  Result: $resultStr")
                        if (!toolResultPreview.isNullOrBlank()) {
                            appendLine("  Output:")
                            appendLine("  ```")
                            toolResultPreview.lines().forEach { appendLine("  $it") }
                            appendLine("  ```")
                        }
                        appendLine()
                    }
                    // Standalone tool result (not merged above)
                    msg.role == "tool" -> {
                        val toolName = msg.toolCallInfo?.toolName ?: "?"
                        val content = msg.content.trim().ifBlank { msg.toolStreamContent?.trim() ?: "" }
                        appendLine("**[$time] TOOL-RESULT** `$toolName`:")
                        if (content.isNotBlank()) {
                            appendLine("```")
                            appendLine(content)
                            appendLine("```")
                        }
                        appendLine()
                    }
                    // System message — show full
                    msg.role == "system" -> {
                        val content = msg.content.trim()
                        appendLine("**[$time] SYSTEM** $content")
                        appendLine()
                    }
                    // User / Assistant — show full
                    else -> {
                        val content = msg.content.trim()
                        val metricsStr = msg.metrics?.let { m ->
                            " *(${m.model} | ${m.inputTokens}/${m.outputTokens}t | ${"$%.4f".format(m.costUsd)} | ${m.latencyMs}ms)*"
                        } ?: ""

                        appendLine("**[$time] $role$agentTag**$metricsStr")
                        if (content.isNotBlank()) {
                            appendLine(content)
                        }
                        appendLine()
                    }
                }
                i++
            }
        } else {
            appendLine("(no messages)")
        }
        appendLine()

        // ── 3. Subtasks ──
        if (subtasks.isNotEmpty()) {
            appendLine("## Subtasks (${subtasks.size})")
            appendLine()
            appendLine("| # | Kind | Status | Description | Tokens | Duration |")
            appendLine("|---|------|--------|-------------|--------|----------|")
            subtasks.forEach { st ->
                val desc = (st.description ?: st.resultSummary) ?: "-"
                val tokens = if (st.tokensIn != null || st.tokensOut != null) {
                    "${st.tokensIn ?: 0}/${st.tokensOut ?: 0}"
                } else "-"
                val dur = st.latencyMs?.let { "${it}ms" } ?: "-"
                appendLine("| ${st.orderIndex} | ${st.kind} | ${st.status} | $desc | $tokens | $dur |")
            }
            appendLine()
        }

        // ── 4. Context ──
        appendLine("## Context")
        appendLine()
        val snapshot = sessionManager.lastPromptSnapshot?.value
        if (snapshot != null) {
            appendLine("- **Iteration:** ${snapshot.iteration}")
            appendLine("- **Tokens:** system=${snapshot.systemPromptTokens}, messages=${snapshot.messagesTokens}, total=${snapshot.totalTokens}")
            appendLine("- **Tools (${snapshot.toolCount}):** ${snapshot.toolNames.joinToString(", ")}")
            appendLine()
            val trace = snapshot.contextTrace
            appendLine("### Context Sections (budget: ${trace.totalBudget}, used: ${trace.totalUsed})")
            appendLine()
            appendLine("| Section | Priority | Tokens | Status |")
            appendLine("|---------|----------|--------|--------|")
            trace.sections.forEach { s ->
                val tokens = s.actualTokens ?: s.estimatedTokens
                val status = if (s.included) "included" else "dropped (${s.dropReason ?: "?"})"
                appendLine("| ${s.section} | ${s.priority} | $tokens | $status |")
            }
            appendLine()
        } else {
            // Fallback: use contextSectionTokens StateFlow if available
            val sectionTokens = sessionManager.contextSectionTokens.value
            val totalTokens = sessionManager.totalEstimatedTokens.value
            if (sectionTokens.isNotEmpty()) {
                appendLine("*(No prompt snapshot — showing last known context section tokens)*")
                appendLine()
                appendLine("- **Total estimated tokens:** $totalTokens")
                appendLine()
                appendLine("| Section | Tokens | Chars | % |")
                appendLine("|---------|--------|-------|---|")
                sectionTokens.entries.sortedByDescending { it.value.tokens }.forEach { (key, info) ->
                    appendLine("| ${info.name} | ${info.tokens} | ${info.chars} | ${"%.1f".format(info.percentage)}% |")
                }
                appendLine()
            } else {
                appendLine("(no context data available)")
                appendLine()
            }
        }

        // Context token usage visualization (ASCII bar chart)
        val vizTokens = snapshot?.sectionTokens?.takeIf { it.isNotEmpty() }
            ?: sessionManager.contextSectionTokens.value.takeIf { it.isNotEmpty() }
        if (vizTokens != null) {
            appendLine("### Context Usage")
            appendLine()
            appendLine("```")
            val sorted = vizTokens.entries.sortedByDescending { it.value.tokens }
            val maxTokens = sorted.firstOrNull()?.value?.tokens ?: 1
            val barWidth = 40
            sorted.forEach { (_, info) ->
                val filled = ((info.tokens.toDouble() / maxTokens) * barWidth).toInt().coerceAtLeast(if (info.tokens > 0) 1 else 0)
                val bar = "█".repeat(filled) + "░".repeat(barWidth - filled)
                val pct = "%5.1f%%".format(info.percentage)
                val tokens = "%6d".format(info.tokens)
                appendLine("$bar $tokens tok $pct  ${info.name}")
            }
            appendLine("```")
            appendLine()
        }

        // Fetch and include the full LLM context prompt
        if (session != null) {
            try {
                val contextResponse = sessionManager.apiRouter.projectContextRouter.getProjectContext(session.id)

                contextResponse.nativeToolsDecision?.let { decision ->
                    if (decision.isNotBlank()) {
                        appendLine("### Native Tools Decision")
                        appendLine()
                        appendLine("Next-turn tool-call routing (native function-calling vs JSON-in-text):")
                        appendLine()
                        appendLine("```")
                        appendLine(decision)
                        appendLine("```")
                        appendLine()
                    }
                }

                contextResponse.activeLlmRequestPrompt?.let { prompt ->
                    if (prompt.isNotBlank()) {
                        appendLine("### Active LLM Request Prompt")
                        appendLine()
                        appendLine("```")
                        appendLine(prompt)
                        appendLine("```")
                        appendLine()
                    }
                }

                contextResponse.recentWorkPrompt?.let { rw ->
                    if (rw.isNotBlank()) {
                        appendLine("### Recent Work Section")
                        appendLine()
                        appendLine("```")
                        appendLine(rw)
                        appendLine("```")
                        appendLine()
                    }
                }

                contextResponse.taskRequirementsPrompt?.let { tr ->
                    if (tr.isNotBlank()) {
                        appendLine("### Task Requirements Section")
                        appendLine()
                        appendLine("```")
                        appendLine(tr)
                        appendLine("```")
                        appendLine()
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLine("*(Failed to fetch full context: ${e.message})*")
                appendLine()
            }
        }

        // ── 5. Agents Graph & Trace ──
        appendLine("## Agents")
        appendLine()
        val agentText = agentTraceProvider?.invoke()
        if (!agentText.isNullOrBlank()) {
            appendLine(agentText)
        } else {
            appendLine("(no agent trace data)")
        }
        appendLine()

        // ── 6. API Logs (session-scoped) ──
        val sessionApiLogs = if (session != null) {
            try { apiLogRepository.findByTaskId(session.id) } catch (_: Exception) { emptyList() }
        } else emptyList()
        val globalApiLogs = try { apiLogRepository.getRecentLogs(10) } catch (_: Exception) { emptyList() }

        if (sessionApiLogs.isNotEmpty()) {
            appendLine("## Session API Logs (${sessionApiLogs.size} calls)")
            appendLine()
            appendLine("| Time | Source | Model | Tokens In | Tokens Out | Latency | Cost | Status |")
            appendLine("|------|--------|-------|-----------|------------|---------|------|--------|")
            sessionApiLogs.forEach { log ->
                val time = timeFmt.format(Date(log.createdAt))
                val source = log.requestSource ?: "-"
                val status = formatApiLogStatus(log)
                appendLine("| $time | $source | ${log.model} | ${log.inputTokens} | ${log.outputTokens} | ${log.latencyMs}ms | ${"$%.4f".format(log.costUsd)} | $status |")
            }
            appendLine()
        }

        // Also show recent global logs if different from session logs
        if (globalApiLogs.isNotEmpty()) {
            val sessionLogIds = sessionApiLogs.map { it.id }.toSet()
            val extraGlobal = globalApiLogs.filter { it.id !in sessionLogIds }
            if (extraGlobal.isNotEmpty()) {
                appendLine("## Recent Global API Logs (non-session)")
                appendLine()
                appendLine("| Time | Source | Model | Tokens In | Tokens Out | Latency | Cost | Status |")
                appendLine("|------|--------|-------|-----------|------------|---------|------|--------|")
                extraGlobal.forEach { log ->
                    val time = timeFmt.format(Date(log.createdAt))
                    val source = log.requestSource ?: "-"
                    val status = formatApiLogStatus(log)
                    appendLine("| $time | $source | ${log.model} | ${log.inputTokens} | ${log.outputTokens} | ${log.latencyMs}ms | ${"$%.4f".format(log.costUsd)} | $status |")
                }
                appendLine()
            }
        }

        // ── 7. Debug Logs ──
        appendLine("## Debug Logs (last $maxLogEntries${if (!includeDebugLogs) ", excluding DEBUG" else ""})")
        appendLine()
        val allLogs = PluginLogger.getInstance().logEntries.value
        // If session exists, try to show only logs from session start time
        val sessionStartMs = session?.createdAt ?: 0L
        val sessionLogs = if (sessionStartMs > 0) {
            allLogs.filter { it.timestamp >= sessionStartMs }
        } else allLogs
        val filteredLogs = if (includeDebugLogs) sessionLogs else sessionLogs.filter { it.level != LogLevel.DEBUG }
        val logs = filteredLogs.takeLast(maxLogEntries)
        if (logs.isNotEmpty()) {
            appendLine("```")
            logs.forEach { entry ->
                appendLine(entry.format())
            }
            appendLine("```")
            if (filteredLogs.size > maxLogEntries) {
                appendLine()
                appendLine("*(${filteredLogs.size - maxLogEntries} older entries omitted)*")
            }
        } else {
            appendLine("(no log entries)")
        }
        appendLine()

        // ── 8. Statistics ──
        appendLine("## Statistics")
        appendLine()
        val statistics = try {
            apiLogRepository.getGlobalStatistics()
        } catch (_: Exception) {
            null
        }
        if (statistics != null) {
            appendLine("### LLM Statistics (Global)")
            appendLine()
            appendLine("- **Total API Calls:** ${statistics.totalCalls}")
            appendLine("- **Total Tokens In:** ${formatNumber(statistics.totalInputTokens)}")
            appendLine("- **Total Tokens Out:** ${formatNumber(statistics.totalOutputTokens)}")
            appendLine("- **Total Cost:** ${"$%.6f".format(statistics.totalCost)}")
            appendLine("- **Avg Latency:** ${statistics.avgLatencyMs}ms")
            appendLine("- **Errors:** ${statistics.errorCount}")
            appendLine()
        }

        val toolRows = pl.jclab.refio.core.services.monitoring.ToolUsageStats.snapshot()
        if (toolRows.isNotEmpty()) {
            appendLine("### Tool Usage")
            appendLine()
            appendLine("| Tool | Count | Success | Avg | Max |")
            appendLine("|------|-------|---------|-----|-----|")
            toolRows.forEach { row ->
                val successPct = "%.0f%%".format(row.successRate * 100)
                appendLine("| ${row.toolName} | ${row.count} | $successPct | ${formatMs(row.avgDurationMs)} | ${formatMs(row.maxDurationMs)} |")
            }
            appendLine()
        }

        val modelRows = pl.jclab.refio.core.services.monitoring.ModelUsageStats.snapshot()
        if (modelRows.isNotEmpty()) {
            appendLine("### Model Usage")
            appendLine()
            appendLine("| Provider | Model | Calls | Tokens In | Tokens Out | Cost | Avg | Max |")
            appendLine("|----------|-------|-------|-----------|------------|------|-----|-----|")
            modelRows.forEach { row ->
                appendLine("| ${row.provider} | ${row.model} | ${row.calls} | ${formatNumber(row.tokensIn)} | ${formatNumber(row.tokensOut)} | ${"$%.4f".format(row.costUsd)} | ${formatMs(row.avgDurationMs)} | ${formatMs(row.maxDurationMs)} |")
            }
            appendLine()
        }
    }

    private fun formatApiLogStatus(log: pl.jclab.refio.core.db.ApiLog): String {
        val errorMessage = log.errorMessage
        val httpStatus = log.httpStatus
        return when {
            errorMessage != null -> "ERR: ${(log.errorType ?: "Error").take(15)}"
            httpStatus != null && httpStatus in 200..299 -> "OK"
            httpStatus != null -> "HTTP $httpStatus"
            else -> "?"
        }
    }

    private fun formatNumber(num: Long): String {
        return when {
            num >= 1_000_000 -> String.format("%.2fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.2fK", num / 1_000.0)
            else -> num.toString()
        }
    }

    private fun formatTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
    }

    private fun formatMs(ms: Long): String = when {
        ms <= 0 -> "-"
        ms < 1000 -> "${ms}ms"
        else -> String.format("%.2fs", ms / 1000.0)
    }

    /**
     * Table model for the Recent API Logs table. Holds the raw ApiLog rows and
     * formats each cell on demand; full values are exposed via the tooltip renderer
     * so long provider/model/source strings size instead of being hard-truncated.
     */
    private inner class ApiLogsTableModel : AbstractTableModel() {
        private var rows: List<pl.jclab.refio.core.db.ApiLog> = emptyList()
        private val columns = arrayOf("Time", "Source", "Provider", "Model", "In", "Out", "Lat", "Cost", "Status")

        fun updateRows(newRows: List<pl.jclab.refio.core.db.ApiLog>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val log = rows[rowIndex]
            return when (columnIndex) {
                0 -> SimpleDateFormat("HH:mm:ss").format(Date(log.createdAt))
                1 -> log.requestSource ?: "-"
                2 -> log.provider
                3 -> log.model
                4 -> formatTokens(log.inputTokens)
                5 -> formatTokens(log.outputTokens)
                6 -> "${log.latencyMs}ms"
                7 -> String.format("$%.4f", log.costUsd)
                8 -> statusText(log)
                else -> ""
            }
        }

        private fun statusText(log: pl.jclab.refio.core.db.ApiLog): String {
            val errorMessage = log.errorMessage
            val httpStatus = log.httpStatus
            return when {
                // Full error text stays available via the cell tooltip.
                errorMessage != null -> "ERR ${log.errorType ?: "Error"}${if (errorMessage.isNotEmpty()) ": ${errorMessage.replace('\n', ' ')}" else ""}"
                httpStatus != null && httpStatus in 200..299 -> "OK"
                httpStatus != null -> "HTTP $httpStatus"
                else -> "?"
            }
        }
    }

    /**
     * Table model for the Tool Usage Analytics table.
     */
    private inner class ToolUsageTableModel : AbstractTableModel() {
        private var rows: List<pl.jclab.refio.core.services.monitoring.ToolUsageStats.Row> = emptyList()
        private val columns = arrayOf("Tool", "Count", "Success", "Avg", "Max", "Last Error")

        fun updateRows(newRows: List<pl.jclab.refio.core.services.monitoring.ToolUsageStats.Row>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.toolName
                1 -> row.count
                2 -> String.format("%.0f%%", row.successRate * 100)
                3 -> formatMs(row.avgDurationMs)
                4 -> formatMs(row.maxDurationMs)
                5 -> row.lastError?.replace('\n', ' ') ?: "-"
                else -> ""
            }
        }
    }

    /**
     * Table model for the Model Usage Analytics table.
     */
    private inner class ModelUsageTableModel : AbstractTableModel() {
        private var rows: List<pl.jclab.refio.core.services.monitoring.ModelUsageStats.Row> = emptyList()
        private val columns = arrayOf("Provider", "Model", "Calls", "Tokens In", "Tokens Out", "Cost", "Avg", "Max")

        fun updateRows(newRows: List<pl.jclab.refio.core.services.monitoring.ModelUsageStats.Row>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.provider
                1 -> row.model
                2 -> row.calls
                3 -> formatNumber(row.tokensIn)
                4 -> formatNumber(row.tokensOut)
                5 -> String.format("$%.4f", row.costUsd)
                6 -> formatMs(row.avgDurationMs)
                7 -> formatMs(row.maxDurationMs)
                else -> ""
            }
        }
    }

    /**
     * Renders each cell as text and exposes the full (untruncated) value as an
     * HTML-escaped tooltip, so columns can size while long values stay readable
     * on hover instead of being cut off.
     */
    private class TooltipCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val text = value?.toString()
            toolTipText = if (!text.isNullOrEmpty()) {
                val escaped = text
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;")
                "<html>$escaped</html>"
            } else {
                null
            }
            return component
        }
    }

    fun dispose() {
        autoRefreshTimer?.stop()
        autoRefreshTimer = null
        cs.cancel()
    }
}