package pl.jclab.refio.ui.components.debug

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
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
    private val recentLogsArea = JTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 11)
        lineWrap = false
        rows = 8
    }

    // Tool Usage Analytics
    private val toolUsageArea = JTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 11)
        lineWrap = false
        rows = 8
    }

    // Model Usage Analytics
    private val modelUsageArea = JTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 11)
        lineWrap = false
        rows = 8
    }

    init {
        // Title panel
        val includeDebugLogsCheckbox = JCheckBox("Include DEBUG", false).apply {
            font = font.deriveFont(10f)
            toolTipText = "Include DEBUG-level log entries in the export"
        }
        val maxLogEntriesSpinner = JSpinner(SpinnerNumberModel(200, 50, 1000, 50)).apply {
            toolTipText = "Max number of log entries to include"
            preferredSize = Dimension(70, preferredSize.height)
        }
        val copySessionDebugButton = JButton("Copy Session Debug").apply {
            font = font.deriveFont(10f)
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
                add(JLabel("Logs:").apply { font = font.deriveFont(10f) })
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
            val logsScrollPane = JBScrollPane(recentLogsArea).apply {
                preferredSize = Dimension(Int.MAX_VALUE, 200)
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            add(logsScrollPane)

            // Tool Usage Analytics
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Tool Usage Analytics"))
            val toolUsageScrollPane = JBScrollPane(toolUsageArea).apply {
                preferredSize = Dimension(Int.MAX_VALUE, 200)
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            add(toolUsageScrollPane)

            // Model Usage Analytics
            add(Box.createVerticalStrut(10))
            add(createSectionHeader("Model Usage Analytics"))
            val modelUsageScrollPane = JBScrollPane(modelUsageArea).apply {
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

        // Auto-refresh every 30 seconds
        Timer(30000) {
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
                    pl.jclab.refio.services.core.CoreHealthState.CONNECTED -> "✅ CONNECTED"
                    pl.jclab.refio.services.core.CoreHealthState.DEGRADED -> "⚠️ DEGRADED"
                    pl.jclab.refio.services.core.CoreHealthState.DISCONNECTED -> "❌ DISCONNECTED"
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

                // Recent API Logs - formatted as table
                if (recentLogs.isNotEmpty()) {
                    val logsText = buildString {
                        // Header
                        append(String.format("%-8s %-12s %-15s %-15s %6s %6s %6s %-10s %s\n",
                            "Time", "Source", "Provider", "Model", "In", "Out", "Lat", "Cost", "Status"))
                        append("─".repeat(110))
                        append("\n")

                        // Rows
                        recentLogs.forEach { log ->
                            val time = SimpleDateFormat("HH:mm:ss").format(Date(log.createdAt))
                            val requestSource = log.requestSource
                            val source = if (requestSource != null) {
                                if (requestSource.length > 12) requestSource.take(9) + "..." else requestSource
                            } else "-"
                            val provider = if (log.provider.length > 15) log.provider.take(12) + "..." else log.provider
                            val model = if (log.model.length > 15) log.model.take(12) + "..." else log.model
                            val tokensIn = formatTokens(log.inputTokens)
                            val tokensOut = formatTokens(log.outputTokens)
                            val latency = "${log.latencyMs}ms"
                            val cost = String.format("$%.4f", log.costUsd)
                            val errorMessage = log.errorMessage
                            val httpStatus = log.httpStatus
                            val status = when {
                                errorMessage != null -> "❌ ${log.errorType ?: "Error"}".take(20)
                                httpStatus != null && httpStatus in 200..299 -> "✓"
                                httpStatus != null -> "HTTP $httpStatus"
                                else -> "?"
                            }

                            append(String.format("%-8s %-12s %-15s %-15s %6s %6s %6s %-10s %s\n",
                                time, source, provider, model, tokensIn, tokensOut, latency, cost, status))

                            // If error, show error message on next line
                            if (errorMessage != null && errorMessage.isNotEmpty()) {
                                val errorMsg = errorMessage.take(80)
                                append(String.format("  └─ %s\n", errorMsg))
                            }
                        }
                    }
                    recentLogsArea.text = logsText
                    recentLogsArea.caretPosition = 0 // Scroll to top
                } else {
                    recentLogsArea.text = "No API logs yet"
                }

                // Tool Usage Analytics
                val toolRows = pl.jclab.refio.core.services.monitoring.ToolUsageStats.snapshot()
                if (toolRows.isNotEmpty()) {
                    val toolText = buildString {
                        append(String.format("%-22s %6s %8s %9s %9s  %s\n",
                            "Tool", "Count", "Success", "Avg", "Max", "Last Error"))
                        append("─".repeat(110))
                        append("\n")
                        toolRows.forEach { row ->
                            val name = if (row.toolName.length > 22) row.toolName.take(19) + "..." else row.toolName
                            val successPct = String.format("%.0f%%", row.successRate * 100)
                            val avg = formatMs(row.avgDurationMs)
                            val max = formatMs(row.maxDurationMs)
                            val err = row.lastError?.replace('\n', ' ')?.take(60) ?: "-"
                            append(String.format("%-22s %6d %8s %9s %9s  %s\n",
                                name, row.count, successPct, avg, max, err))
                        }
                    }
                    toolUsageArea.text = toolText
                    toolUsageArea.caretPosition = 0
                } else {
                    toolUsageArea.text = "No tool invocations recorded yet"
                }

                // Model Usage Analytics
                val modelRows = pl.jclab.refio.core.services.monitoring.ModelUsageStats.snapshot()
                if (modelRows.isNotEmpty()) {
                    val modelText = buildString {
                        append(String.format("%-12s %-26s %6s %10s %10s %10s %9s %9s\n",
                            "Provider", "Model", "Calls", "Tokens In", "Tokens Out", "Cost", "Avg", "Max"))
                        append("─".repeat(110))
                        append("\n")
                        modelRows.forEach { row ->
                            val provider = if (row.provider.length > 12) row.provider.take(9) + "..." else row.provider
                            val modelName = if (row.model.length > 26) row.model.take(23) + "..." else row.model
                            val cost = String.format("$%.4f", row.costUsd)
                            val avg = formatMs(row.avgDurationMs)
                            val max = formatMs(row.maxDurationMs)
                            append(String.format("%-12s %-26s %6d %10s %10s %10s %9s %9s\n",
                                provider, modelName, row.calls,
                                formatNumber(row.tokensIn), formatNumber(row.tokensOut),
                                cost, avg, max))
                        }
                    }
                    modelUsageArea.text = modelText
                    modelUsageArea.caretPosition = 0
                } else {
                    modelUsageArea.text = "No LLM calls recorded yet"
                }

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
            val report = buildSessionDebugReport(includeDebugLogs, maxLogEntries)
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

    fun dispose() {
        cs.cancel()
    }
}