package pl.jclab.refio.ui.components.toolbar

import pl.jclab.refio.services.logging.dualLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.core.CoreHealthState
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.ui.theme.ContextSectionColorPalette
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
 * Enhanced Status Bar showing system metrics in a single row.
 *
 * Layout (1 row):
 * Core status (dot), Execution status, Context bar, Requests session/global,
 * Tokens In session/global, Tokens Out session/global, Cost session/global
 */
class StatusBar(private val project: Project) : JBPanel<StatusBar>(BorderLayout()) {

    private val logger = dualLogger("StatusBar")

    // Services
    private val cs = CoroutineScope(SupervisorJob())
    private val coreManager = CoreConnectionManager.getInstance()
    private val sessionManager = SessionManager.getInstance(project)
    private val globalMetrics = GlobalMetrics

    // Row 1 labels
    private val coreHealthLabel: JBLabel
    private val executionStatusLabel: JBLabel
    private val contextFillBar: ContextUsageBar
    private val contextPercentLabel: JBLabel
    private val requestsLabel: JBLabel     // "Req: sessionReq/globalReq"
    private val tokensInLabel: JBLabel     // "⬇️ sessionIn/globalIn"
    private val tokensOutLabel: JBLabel    // "⬆️ sessionOut/globalOut"
    private val costLabel: JBLabel         // "$sessionCost/$globalCost"

    // State tracking
    private var sessionRequests = 0
    private var prevSessionTokensIn = 0
    private var sessionTokensIn = 0
    private var sessionTokensOut = 0
    private var sessionCostUsd = 0.0
    private var globalRequests = 0L
    private var globalTokensIn = 0L
    private var globalTokensOut = 0L
    private var globalCostUsd = 0.0

    init {
        val row1Panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            coreHealthLabel = JBLabel("●").apply {
                font = font.deriveFont(Font.BOLD, 16f)
                foreground = LCATheme.errorColor
                toolTipText = "Core initialization failed - check logs"
            }
            executionStatusLabel = JBLabel("Idle").apply {
                foreground = LCATheme.neutralColor
            }
            contextFillBar = ContextUsageBar()
            contextPercentLabel = JBLabel("(0/0)").apply {
                foreground = LCATheme.neutralColor
            }
            requestsLabel = JBLabel("Rq:0/0").apply {
                toolTipText = "Requests: session / global"
            }
            tokensInLabel = JBLabel("⬇️0/0").apply {
                toolTipText = "Input tokens: session / global"
            }
            tokensOutLabel = JBLabel("⬆️0/0").apply {
                toolTipText = "Output tokens: session / global"
            }
            costLabel = JBLabel("\$0/\$0").apply {
                toolTipText = "Cost USD: session / global"
            }

            add(coreHealthLabel)
            add(executionStatusLabel)
            add(createSeparator())
            add(contextFillBar)
            add(contextPercentLabel)
            add(createSeparator())
            add(requestsLabel)
            add(tokensInLabel)
            add(tokensOutLabel)
            add(createSeparator())
            add(costLabel)
        }

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(row1Panel)
        }

        add(mainPanel, BorderLayout.CENTER)

        startMonitoring()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                revalidate()
                repaint()
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

    private fun startMonitoring() {
        cs.launch {
            coreManager.healthState.collect { health ->
                SwingUtilities.invokeLater { updateCoreHealth(health.state, health.latencyMs) }
            }
        }

        cs.launch {
            globalMetrics.metrics.collect { metrics ->
                SwingUtilities.invokeLater { updateGlobalMetrics(metrics) }
            }
        }

        cs.launch {
            globalMetrics.currentOperation.collect { operation ->
                SwingUtilities.invokeLater { updateCurrentOperation(operation) }
            }
        }

        cs.launch {
            sessionManager.activeSession.collect { session ->
                SwingUtilities.invokeLater {
                    session?.let {
                        updateSessionTokens(it.tokensIn, it.tokensOut)
                        updateSessionCost(it.costUsd)
                    } ?: run {
                        sessionRequests = 0
                        prevSessionTokensIn = 0
                        sessionTokensIn = 0
                        sessionTokensOut = 0
                        sessionCostUsd = 0.0
                        contextFillBar.percentage = 0
                        contextFillBar.currentTokens = 0
                        contextFillBar.maxTokens = 0
                        contextPercentLabel.text = "(0/0)"
                        refreshCombinedLabels()
                    }
                }
            }
        }

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
    @Suppress("UNUSED_PARAMETER")
    private fun updateCoreHealth(state: CoreHealthState, _latencyMs: Int?) {
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
     * Update execution status label based on current operation.
     * Single source of truth for execution state display.
     */
    private fun updateCurrentOperation(operation: OperationInfo) {

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
            is OperationInfo.Orchestration -> LCATheme.descriptionForeground
            else -> LCATheme.descriptionForeground
        }

        executionStatusLabel.repaint()
    }

    private fun updateGlobalMetrics(metrics: GlobalMetrics.MetricsSnapshot) {
        globalRequests = metrics.totalRequests.toLong()
        globalTokensIn = metrics.totalTokensIn
        globalTokensOut = metrics.totalTokensOut
        globalCostUsd = metrics.totalCostUsd
        refreshCombinedLabels()
    }

    private fun updateSessionTokens(tokensIn: Int, tokensOut: Int) {
        if (tokensIn > prevSessionTokensIn) {
            sessionRequests++
            prevSessionTokensIn = tokensIn
        }
        sessionTokensIn = tokensIn
        sessionTokensOut = tokensOut
        refreshCombinedLabels()
    }

    private fun updateSessionCost(costUsd: Double) {
        sessionCostUsd = costUsd
        refreshCombinedLabels()
    }

    private fun updateContextFill(current: Int, max: Int) {
        val percentage = if (max > 0) (current * 100.0 / max).toInt() else 0
        contextFillBar.percentage = percentage
        contextFillBar.currentTokens = current
        contextFillBar.maxTokens = max
        contextPercentLabel.text = "(${formatTokens(current)}/${formatTokens(max)})"
        contextPercentLabel.foreground = when {
            percentage < 50 -> LCATheme.successColor
            percentage < 75 -> LCATheme.warningColor
            percentage < 90 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }
    }

    private fun refreshCombinedLabels() {
        requestsLabel.text = "Rq:$sessionRequests/${formatLargeNumber(globalRequests)}"
        requestsLabel.toolTipText = "Requests: $sessionRequests session / $globalRequests global"

        tokensInLabel.text = "⬇️${formatLargeNumber(sessionTokensIn.toLong())}/${formatLargeNumber(globalTokensIn)}"
        tokensInLabel.toolTipText = "Input tokens: $sessionTokensIn session / $globalTokensIn global"

        tokensOutLabel.text = "⬆️${formatLargeNumber(sessionTokensOut.toLong())}/${formatLargeNumber(globalTokensOut)}"
        tokensOutLabel.toolTipText = "Output tokens: $sessionTokensOut session / $globalTokensOut global"

        val sessionCostStr = formatCostShort(sessionCostUsd)
        val globalCostStr = formatCostShort(globalCostUsd)
        costLabel.text = "\$$sessionCostStr/\$$globalCostStr"
        costLabel.toolTipText = "Cost: \$$sessionCostStr session / \$$globalCostStr global"

        requestsLabel.repaint()
        tokensInLabel.repaint()
        tokensOutLabel.repaint()
        costLabel.repaint()
    }

    private fun formatCostShort(costUsd: Double): String = String.format("%.2f", costUsd)

    private fun formatLargeNumber(num: Long): String = when {
        num >= 1_000_000_000 -> String.format("%.1fB", num / 1_000_000_000.0)
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
        else -> num.toString()
    }

    private fun formatTokens(tokens: Int): String =
        if (tokens >= 1000) String.format("%.1fK", tokens / 1000.0) else tokens.toString()

    fun updateMetrics(messages: List<Message>) {
        val totals = calculateTotalMetrics(messages)
        SwingUtilities.invokeLater {
            sessionTokensIn = totals.inputTokens
            sessionTokensOut = totals.outputTokens
            sessionCostUsd = totals.costUsd
            refreshCombinedLabels()
        }
    }

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

    @Suppress("UNUSED_PARAMETER")
    fun setAdvancedViewEnabled(_enabled: Boolean) {
        // Single-row layout - no advanced view
        logger.debug { "Advanced view toggle ignored (single-row layout)" }
    }

    fun dispose() {
        cs.cancel()
    }
}
