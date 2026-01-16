package pl.jclab.refio.ui.components.toolbar

import pl.jclab.refio.services.logging.dualLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.core.CoreHealthState
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.SystemMonitor
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*

/**
 * Custom context usage bar with colored sections similar to TokenUsageVisualizationPanel.
 * Shows context sections in different colors to visualize what consumes context.
 */
class ContextUsageBar : JPanel() {
    var percentage: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            repaint()
        }

    var currentTokens: Int = 0
    var maxTokens: Int = 0

    // Section data for colored visualization
    data class SectionInfo(val name: String, val tokens: Int, val percentage: Double, val color: Color)
    private var sections: List<SectionInfo> = emptyList()

    // Color palette for sections (matching TokenUsageVisualizationPanel)
    private val sectionColors = mapOf(
        "project_overview" to Color(0x4A90D9),     // Blue
        "dependencies" to Color(0x7B68EE),         // Medium slate blue
        "code_analysis" to Color(0x9370DB),        // Medium purple
        "current_task" to Color(0xDA70D6),         // Orchid
        "subtasks" to Color(0xFF69B4),             // Hot pink
        "conversation" to Color(0xF08080),         // Light coral
        "rag_fragments" to Color(0xFFB347),        // Orange
        "user_context" to Color(0x98FB98),         // Pale green
        "tool_outputs" to Color(0x87CEEB),         // Sky blue
        "recent_work" to Color(0xDDA0DD),          // Plum
        "free_space" to Color(0xD3D3D3)            // Light gray
    )

    private val colorBackground = Color(0xE0E0E0)

    init {
        preferredSize = Dimension(140, 14)
        minimumSize = Dimension(100, 12)
        toolTipText = "Context window usage"
        isOpaque = false
    }

    /**
     * Update sections data for colored visualization
     */
    fun updateSections(sectionTokens: Map<String, pl.jclab.refio.core.api.ContextSectionTokenInfo>) {
        sections = sectionTokens.map { (key, info) ->
            SectionInfo(
                name = info.name,
                tokens = info.tokens,
                percentage = info.percentage,
                color = sectionColors[key] ?: JBColor.GRAY
            )
        }.filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width - 2
        val h = height - 2
        val x = 1
        val y = 1

        // Background (represents free/unused context)
        val isDark = LCATheme.isDark
        g2.color = if (isDark) Color(0x3C3F41) else colorBackground
        g2.fillRoundRect(x, y, w, h, 6, 6)

        // Calculate used width based on overall percentage (relative to context limit)
        val usedWidth = (percentage / 100.0 * w).toInt().coerceIn(0, w)

        // Draw colored sections within the used portion
        if (usedWidth > 0 && sections.isNotEmpty()) {
            // Calculate total tokens for proportional distribution
            val totalSectionTokens = sections.sumOf { it.tokens }.coerceAtLeast(1)
            var currentX = x

            sections.forEach { section ->
                // Proportional width within the used area
                val sectionWidth = ((section.tokens.toDouble() / totalSectionTokens) * usedWidth).toInt().coerceAtLeast(1)
                if (currentX + sectionWidth <= x + usedWidth) {
                    g2.color = section.color
                    g2.fillRect(currentX, y, sectionWidth, h)
                    currentX += sectionWidth
                }
            }

            // Add subtle gradient overlay for visual depth
            val gradient = GradientPaint(
                x.toFloat(), y.toFloat(), Color(255, 255, 255, 40),
                x.toFloat(), (y + h / 2).toFloat(), Color(255, 255, 255, 0)
            )
            g2.paint = gradient
            g2.fillRect(x, y, usedWidth, h / 2)
        } else if (percentage > 0) {
            // Fallback: single color based on percentage (when no section data)
            val fillWidth = (percentage / 100.0 * w).toInt().coerceAtLeast(4)
            g2.color = when {
                percentage < 50 -> Color(0x4CAF50)   // Green
                percentage < 75 -> Color(0xFFC107)   // Yellow
                percentage < 90 -> Color(0xFF9800)   // Orange
                else -> Color(0xF44336)              // Red
            }
            g2.fillRoundRect(x, y, fillWidth, h, 6, 6)
        }

        // Border
        g2.color = LCATheme.borderColor
        g2.drawRoundRect(x, y, w, h, 6, 6)

        // Percentage text inside bar if there's enough space
        if (w > 60) {
            g2.color = LCATheme.labelForeground
            g2.font = Font("SansSerif", Font.BOLD, 9)
            val text = "$percentage%"
            val fm = g2.fontMetrics
            val textX = x + (w - fm.stringWidth(text)) / 2
            val textY = y + (h + fm.ascent - fm.descent) / 2
            g2.drawString(text, textX, textY)
        }
    }

    override fun getToolTipText(): String {
        val sectionInfo = if (sections.isNotEmpty()) {
            sections.take(3).joinToString(", ") { "${it.name}: ${formatTokens(it.tokens)}" }
        } else ""
        return "Context: ${formatTokens(currentTokens)} / ${formatTokens(maxTokens)} ($percentage%)${if (sectionInfo.isNotEmpty()) "\n$sectionInfo" else ""}"
    }

    private fun formatTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}

/**
 * Enhanced Status Bar showing comprehensive system metrics
 *
 * Layout (3 rows):
 * Row 1: Core status (dot), Context size, Session In/Out, Session cost
 * Row 2: Execution status, Progress bar, CPU, RAM
 * Row 3: Global metrics (requests, tokens in/out, total cost)
 */
class StatusBar(private val project: Project) : JBPanel<StatusBar>(BorderLayout()) {

    private val logger = dualLogger("StatusBar")

    // Services
    private val cs = CoroutineScope(SupervisorJob())
    private val coreManager = CoreConnectionManager.getInstance()
    private val sessionManager = SessionManager.getInstance(project)
    private val globalMetrics = GlobalMetrics
    private val systemMonitor = SystemMonitor

    // Row 1: Core Status (dot), Context Size, Session In/Out, Session Cost
    private val coreHealthLabel: JBLabel
    private val contextFillBar: ContextUsageBar
    private val contextPercentLabel: JBLabel
    private val sessionTokensLabel: JBLabel
    private val sessionCostLabel: JBLabel
    private val busyIndicator: JProgressBar

    // Row 2: Execution Status, Progress, System Resources
    private val executionStatusLabel: JBLabel
    private val executionProgressBar: JProgressBar
    private val cpuLabel: JBLabel
    private val ramLabel: JBLabel

    // Row 3: Global Metrics (all operations)
    private val totalRequestsLabel: JBLabel
    private val globalTokensInLabel: JBLabel
    private val globalTokensOutLabel: JBLabel
    private val globalCostLabel: JBLabel

    // Panels for advanced view toggle
    private lateinit var row2Panel: JPanel
    private lateinit var row3Panel: JPanel
    private lateinit var mainPanel: JPanel

    init {
        // Row 1: Core Status (dot), Context Size, Session In/Out, Session Cost
        val row1Panel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
            coreHealthLabel = JBLabel("●").apply {
                font = font.deriveFont(Font.BOLD, 16f)
                foreground = LCATheme.errorColor
                toolTipText = "Core initialization failed - check logs"
            }
            busyIndicator = JProgressBar().apply {
                isIndeterminate = true
                isStringPainted = false
                preferredSize = Dimension(60, 8)
                isVisible = false
                toolTipText = "Operation in progress"
            }
            contextFillBar = ContextUsageBar()
            contextPercentLabel = JBLabel("0% (0/0)").apply {
                foreground = LCATheme.neutralColor
            }
            sessionTokensLabel = JBLabel("⬇️0 / ⬆️0").apply {
                toolTipText = "Current session tokens (input / output)"
            }
            sessionCostLabel = JBLabel("$0.00").apply {
                toolTipText = "Current session cost"
            }

            add(coreHealthLabel)
            add(busyIndicator)
            add(createSeparator())
            add(contextFillBar)
            add(contextPercentLabel)
            add(createSeparator())
            add(sessionTokensLabel)
            add(createSeparator())
            add(sessionCostLabel)
        }

        // Row 2: Execution Status, Progress, System Resources
        row2Panel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
            executionStatusLabel = JBLabel("Idle").apply {
                foreground = LCATheme.neutralColor
            }
            executionProgressBar = JProgressBar(0, 100).apply {
                value = 0
                isStringPainted = false
                preferredSize = Dimension(100, 12)
                isVisible = false // Hidden when idle
            }
            cpuLabel = JBLabel("⚡ CPU: 0%").apply {
                toolTipText = "Plugin CPU usage"
            }
            ramLabel = JBLabel("💾 RAM: 0/0 MB").apply {
                toolTipText = "Plugin memory usage"
            }

            add(JLabel("Status:"))
            add(executionStatusLabel)
            add(executionProgressBar)
            add(createSeparator())
            add(cpuLabel)
            add(createSeparator())
            add(ramLabel)
        }

        // Row 3: Global Metrics (all operations)
        row3Panel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
            totalRequestsLabel = JBLabel("📊 Requests: 0 (0✓ / 0✗)").apply {
                toolTipText = "Total requests (successful / failed)"
                font = font.deriveFont(Font.BOLD)
            }
            globalTokensInLabel = JBLabel("⬇️ In: 0").apply {
                toolTipText = "Total input tokens (all operations)"
            }
            globalTokensOutLabel = JBLabel("⬆️ Out: 0").apply {
                toolTipText = "Total output tokens (all operations)"
            }
            globalCostLabel = JBLabel("💰 Total: $0.00").apply {
                toolTipText = "Total cost (all operations)"
                font = font.deriveFont(Font.BOLD)
            }

            add(totalRequestsLabel)
            add(createSeparator())
            add(globalTokensInLabel)
            add(createSeparator())
            add(globalTokensOutLabel)
            add(createSeparator())
            add(globalCostLabel)
        }

        mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

            add(row1Panel)
            add(Box.createVerticalStrut(2))
            add(row2Panel)
            add(Box.createVerticalStrut(2))
            add(row3Panel)
        }

        add(mainPanel, BorderLayout.CENTER)

        // Initially hide rows 2 and 3 (advanced view disabled by default)
        setAdvancedViewEnabled(false)

        // Start monitoring
        startMonitoring()

        // Add component listener for responsive behavior
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateOverflowState()
            }
        })
    }

    /**
     * Create visual separator
     */
    private fun createSeparator(): JLabel {
        return JLabel("|").apply {
            foreground = LCATheme.neutralColor
        }
    }

    /**
     * Start monitoring core health, system metrics, and session data
     */
    private fun startMonitoring() {
        // Monitor core health
        cs.launch {
            coreManager.healthState.collect { health ->
                SwingUtilities.invokeLater {
                    updateCoreHealth(health.state, health.latencyMs)
                }
            }
        }

        // Monitor global metrics
        cs.launch {
            globalMetrics.metrics.collect { metrics ->
                SwingUtilities.invokeLater {
                    updateGlobalMetrics(metrics)
                }
            }
        }

        // Monitor system resources (CPU, RAM)
        cs.launch {
            systemMonitor.metrics.collect { metrics ->
                SwingUtilities.invokeLater {
                    updateSystemMetrics(metrics)
                }
            }
        }

        // Monitor current operation
        cs.launch {
            globalMetrics.currentOperation.collect { operation ->
                SwingUtilities.invokeLater {
                    updateCurrentOperation(operation)
                }
            }
        }

        // Monitor session
        cs.launch {
            sessionManager.activeSession.collect { session ->
                SwingUtilities.invokeLater {
                    session?.let {
                        updateSessionTokens(it.tokensIn, it.tokensOut)
                        updateSessionCost(it.costUsd)
                    } ?: run {
                        // No active session - clear session metrics
                        sessionTokensLabel.text = "⬇️0 / ⬆️0"
                        sessionCostLabel.text = "$0.00"
                        contextFillBar.percentage = 0
                        contextFillBar.currentTokens = 0
                        contextFillBar.maxTokens = 0
                        contextPercentLabel.text = "(0/0)"
                    }
                }
            }
        }

        // Monitor total estimated tokens from ContextService (single source of truth)
        // This is updated by ContextPanel after each context refresh
        cs.launch {
            sessionManager.totalEstimatedTokens.collect { totalTokens ->
                val session = sessionManager.activeSession.value
                if (session != null) {
                    SwingUtilities.invokeLater {
                        val max = sessionManager.getMaxContextWindow()
                        updateContextFill(totalTokens, max)
                        logger.debug { "Total estimated tokens changed: $totalTokens/$max tokens" }
                    }
                }
            }
        }

        // Monitor selected model changes - update context window limit
        cs.launch {
            sessionManager.selectedModel.collect { modelString ->
                val session = sessionManager.activeSession.value
                if (session != null) {
                    SwingUtilities.invokeLater {
                        val current = sessionManager.totalEstimatedTokens.value
                        val max = sessionManager.getMaxContextWindow()
                        updateContextFill(current, max)
                        logger.debug { "Model changed to '$modelString': Context limit updated to $max tokens" }
                    }
                }
            }
        }

        // Monitor context section tokens (from ContextPanel) - update colored sections in bar
        cs.launch {
            sessionManager.contextSectionTokens.collect { sections ->
                SwingUtilities.invokeLater {
                    contextFillBar.updateSections(sections)
                    logger.debug { "Context sections updated: ${sections.size} sections" }
                }
            }
        }
    }

    /**
     * Update core health indicator (dot color only)
     */
    private fun updateCoreHealth(state: CoreHealthState, latencyMs: Int?) {
        when (state) {
            CoreHealthState.CONNECTED -> {
                coreHealthLabel.foreground = LCATheme.successColor
                coreHealthLabel.toolTipText = "Core: Connected (in-process, < 1ms)"
            }
            CoreHealthState.DEGRADED -> {
                coreHealthLabel.foreground = LCATheme.warningColor
                coreHealthLabel.toolTipText = "Core: Performance degraded"
            }
            CoreHealthState.DISCONNECTED -> {
                coreHealthLabel.foreground = LCATheme.errorColor
                coreHealthLabel.toolTipText = "Core: Initialization failed - check logs"
            }
        }
        coreHealthLabel.repaint()
    }

    /**
     * Update execution status label and progress bar based on current operation.
     * Single source of truth for execution state display.
     */
    private fun updateCurrentOperation(operation: OperationInfo) {
        busyIndicator.isVisible = operation !is OperationInfo.Idle

        // Update label text based on operation
        executionStatusLabel.text = when (operation) {
            is OperationInfo.Idle -> "Idle"
            is OperationInfo.ChatRequest -> "Chat Request"
            is OperationInfo.PlanningRequest -> "Planning"
            is OperationInfo.PlanningStep ->
                "Planning ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.ExecutingStep ->
                "Step ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.StepPlanning ->
                "Step planning ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.StepExecuting ->
                "Step execute ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.StepSummarizing ->
                "Step summarize ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.StepReasoning ->
                "Step reasoning ${operation.stepNumber}/${operation.totalSteps}"
            is OperationInfo.ExecutingTool ->
                "Tool: ${operation.toolName}"
            is OperationInfo.ContextBuilding ->
                "Context build: ${operation.phase}"
            is OperationInfo.Orchestration -> {
                val stepInfo = if (operation.stepNumber != null && operation.totalSteps != null) {
                    " ${operation.stepNumber}/${operation.totalSteps}"
                } else ""
                "Orchestration: ${operation.phase}$stepInfo"
            }
            else -> operation.toString()
        }

        executionStatusLabel.toolTipText = "Current operation: ${operation}"

        // Color code based on operation type
        executionStatusLabel.foreground = when (operation) {
            is OperationInfo.Idle -> LCATheme.neutralColor
            is OperationInfo.ChatRequest,
            is OperationInfo.PlanningRequest -> LCATheme.infoColor
            is OperationInfo.ExecutingStep,
            is OperationInfo.ExecutingTool,
            is OperationInfo.StepExecuting -> LCATheme.successColor
            is OperationInfo.PlanningStep -> LCATheme.warningColor
            is OperationInfo.StepPlanning -> LCATheme.warningColor
            is OperationInfo.StepSummarizing,
            is OperationInfo.StepReasoning -> LCATheme.infoColor
            is OperationInfo.ContextBuilding,
            is OperationInfo.Orchestration -> LCATheme.descriptionForeground
            else -> LCATheme.descriptionForeground
        }

        // Update progress bar based on operation type
        fun setDeterminateProgress(percentage: Int) {
            executionProgressBar.isIndeterminate = false
            executionProgressBar.value = percentage
            executionProgressBar.isVisible = true
        }

        when (operation) {
            is OperationInfo.ExecutingStep -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.PlanningStep -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.StepPlanning -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.StepExecuting -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.StepSummarizing -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.StepReasoning -> {
                val percentage = if (operation.totalSteps > 0) {
                    (operation.stepNumber * 100 / operation.totalSteps)
                } else 0
                setDeterminateProgress(percentage)
            }
            is OperationInfo.Idle -> {
                executionProgressBar.isIndeterminate = false
                executionProgressBar.value = 0
                executionProgressBar.isVisible = false
            }
            else -> {
                executionProgressBar.isIndeterminate = true
                executionProgressBar.value = 0
                executionProgressBar.isVisible = true
            }
        }

        executionStatusLabel.repaint()
        executionProgressBar.repaint()
        busyIndicator.repaint()
    }


    /**
     * Update system metrics (CPU, RAM)
     */
    private fun updateSystemMetrics(metrics: SystemMonitor.SystemMetrics) {
        // CPU
        val cpuPercent = String.format("%.1f", metrics.cpuUsagePercent)
        cpuLabel.text = "⚡ CPU: $cpuPercent%"
        cpuLabel.toolTipText = "Plugin CPU usage: $cpuPercent% (${metrics.availableProcessors} cores)"

        // Color code CPU usage
        cpuLabel.foreground = when {
            metrics.cpuUsagePercent < 30 -> LCATheme.successColor
            metrics.cpuUsagePercent < 60 -> LCATheme.warningColor
            metrics.cpuUsagePercent < 80 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }

        // RAM
        ramLabel.text = "💾 RAM: ${metrics.memoryUsedMb}/${metrics.memoryTotalMb} MB"
        ramLabel.toolTipText = String.format(
            "Memory: %d MB used / %d MB total (%.1f%%)",
            metrics.memoryUsedMb,
            metrics.memoryTotalMb,
            metrics.memoryUsagePercent
        )

        // Color code RAM usage
        ramLabel.foreground = when {
            metrics.memoryUsagePercent < 60 -> LCATheme.successColor
            metrics.memoryUsagePercent < 80 -> LCATheme.warningColor
            metrics.memoryUsagePercent < 90 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }

        cpuLabel.repaint()
        ramLabel.repaint()
    }

    /**
     * Update global metrics (all operations)
     */
    private fun updateGlobalMetrics(metrics: GlobalMetrics.MetricsSnapshot) {
        // Total requests
        totalRequestsLabel.text = "📊 Requests: ${metrics.totalRequests} " +
            "(${metrics.successfulRequests}✓ / ${metrics.failedRequests}✗)"
        totalRequestsLabel.toolTipText = "Total: ${metrics.totalRequests}, " +
            "Success: ${metrics.successfulRequests}, " +
            "Failed: ${metrics.failedRequests}"

        // Global tokens in
        globalTokensInLabel.text = "⬇️ In: ${formatLargeNumber(metrics.totalTokensIn)}"
        globalTokensInLabel.toolTipText = "Total input tokens: ${metrics.totalTokensIn}"

        // Global tokens out
        globalTokensOutLabel.text = "⬆️ Out: ${formatLargeNumber(metrics.totalTokensOut)}"
        globalTokensOutLabel.toolTipText = "Total output tokens: ${metrics.totalTokensOut}"

        // Total cost
        globalCostLabel.text = String.format("💰 Total: $%.2f", metrics.totalCostUsd)
        globalCostLabel.toolTipText = String.format("Total cost: $%.2f USD", metrics.totalCostUsd)

        // Color code cost
        globalCostLabel.foreground = when {
            metrics.totalCostUsd < 1.0 -> LCATheme.successColor
            metrics.totalCostUsd < 10.0 -> LCATheme.warningColor
            metrics.totalCostUsd < 50.0 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }

        totalRequestsLabel.repaint()
        globalTokensInLabel.repaint()
        globalTokensOutLabel.repaint()
        globalCostLabel.repaint()
    }

    /**
     * Format large numbers (K, M, B)
     */
    private fun formatLargeNumber(num: Long): String {
        return when {
            num >= 1_000_000_000 -> String.format("%.1fB", num / 1_000_000_000.0)
            num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
            else -> num.toString()
        }
    }

    /**
     * Update session tokens (separate in/out)
     */
    private fun updateSessionTokens(tokensIn: Int, tokensOut: Int) {
        val inFormatted = formatLargeNumber(tokensIn.toLong())
        val outFormatted = formatLargeNumber(tokensOut.toLong())
        val total = tokensIn + tokensOut

        sessionTokensLabel.text = "⬇️$inFormatted / ⬆️$outFormatted"
        sessionTokensLabel.toolTipText = "Input: $tokensIn, Output: $tokensOut, Total: $total"
        sessionTokensLabel.repaint()
    }

    /**
     * Update context fill indicator using custom ContextUsageBar
     */
    private fun updateContextFill(current: Int, max: Int) {
        val percentage = if (max > 0) (current * 100.0 / max).toInt() else 0

        // Update custom context bar
        contextFillBar.percentage = percentage
        contextFillBar.currentTokens = current
        contextFillBar.maxTokens = max

        contextPercentLabel.text = "(${formatTokens(current)}/${formatTokens(max)})"

        // Color code the label to match bar
        contextPercentLabel.foreground = when {
            percentage < 50 -> LCATheme.successColor
            percentage < 75 -> LCATheme.warningColor
            percentage < 90 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }
    }

    /**
     * Update session cost
     */
    private fun updateSessionCost(costUsd: Double) {
        val formatted = formatCostLabel(costUsd).replace("💵 Session: ", "")
        sessionCostLabel.text = formatted
        sessionCostLabel.toolTipText = String.format("Current session cost: $%.2f USD", costUsd)
        sessionCostLabel.repaint()
    }

    /**
     * Format tokens with K suffix
     */
    private fun formatTokens(tokens: Int): String {
        return if (tokens >= 1000) {
            String.format("%.1fK", tokens / 1000.0)
        } else {
            tokens.toString()
        }
    }

    /**
     * Update overflow state (for responsive design)
     */
    private fun updateOverflowState() {
        // Could implement responsive behavior here if needed
        // For now, just trigger revalidate
        revalidate()
        repaint()
    }

    /**
     * Update metrics display with session totals (US-027)
     * Calculates and displays total metrics from all messages
     */
    fun updateMetrics(messages: List<Message>) {
        val totals = calculateTotalMetrics(messages)

        SwingUtilities.invokeLater {
            sessionTokensLabel.text = "⬇️${formatLargeNumber(totals.inputTokens.toLong())} / ⬆️${formatLargeNumber(totals.outputTokens.toLong())}"
            sessionTokensLabel.toolTipText = "Input: ${totals.inputTokens}, Output: ${totals.outputTokens}, Total: ${totals.totalTokens}"

            sessionCostLabel.text = formatCostLabel(totals.costUsd).replace("💵 Session: ", "")
            sessionCostLabel.toolTipText = String.format("Current session cost: $%.4f USD", totals.costUsd)
        }
    }

    /**
     * Calculate total metrics from all messages (US-027)
     */
    private fun calculateTotalMetrics(messages: List<Message>): MessageMetrics {
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalCostUsd = 0.0

        messages.forEach { message ->
            message.metrics?.let { metrics ->
                totalInputTokens += metrics.inputTokens
                totalOutputTokens += metrics.outputTokens
                totalCostUsd += metrics.costUsd
            }
        }

        return MessageMetrics(
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            totalTokens = totalInputTokens + totalOutputTokens,
            costUsd = totalCostUsd
        )
    }

    /**
     * Format cost label with proper precision
     */
    private fun formatCostLabel(costUsd: Double): String {
        return when {
            costUsd < 0.0001 -> String.format("💵 Session: $%.6f", costUsd)
            costUsd < 0.01 -> String.format("💵 Session: $%.4f", costUsd)
            else -> String.format("💵 Session: $%.2f", costUsd)
        }
    }

    /**
     * Enable or disable advanced view (show/hide rows 2 and 3)
     */
    fun setAdvancedViewEnabled(enabled: Boolean) {
        SwingUtilities.invokeLater {
            row2Panel.isVisible = enabled
            row3Panel.isVisible = enabled

            mainPanel.revalidate()
            mainPanel.repaint()

            logger.info { "Advanced view ${if (enabled) "enabled" else "disabled"}" }
        }
    }

    /**
     * Dispose resources
     */
    fun dispose() {
        cs.cancel()
    }
}
