package pl.jclab.refio.ui.components.debug

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Dimension
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
        val titlePanel = JPanel(BorderLayout()).apply {
            add(JLabel("Plugin Internal State").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.WEST)

            refreshButton = JButton("🔄 Refresh").apply {
                addActionListener { refreshState() }
            }
            add(refreshButton, BorderLayout.EAST)
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