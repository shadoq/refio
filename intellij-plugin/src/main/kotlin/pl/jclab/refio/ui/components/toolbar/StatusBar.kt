package pl.jclab.refio.ui.components.toolbar

import pl.jclab.refio.core.logging.dualLogger
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
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
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

    private val colorBackground = JBColor(Color(0xE0E0E0), Color(0x3C3F41))

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
        g2.color = colorBackground
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
                percentage < 50 -> JBColor(Color(0x2E7D32), Color(0x4CAF50))   // Green
                percentage < 75 -> JBColor(Color(0xB88A00), Color(0xFFC107))   // Yellow
                percentage < 90 -> JBColor(Color(0xC77700), Color(0xFF9800))   // Orange
                else -> JBColor(Color(0xC62828), Color(0xF44336))              // Red
            }
            g2.fillRoundRect(x, y, fillWidth, h, 6, 6)
        }

        // Border
        g2.color = LCATheme.borderColor
        g2.drawRoundRect(x, y, w, h, 6, 6)

        // Percentage text inside bar if there's enough space
        if (w > 60) {
            g2.color = LCATheme.labelForeground
            g2.font = font.deriveFont(Font.BOLD, JBUIScale.scale(9f))
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
    private val tokensInLabel: JBLabel     // "in sessionIn/globalIn"
    private val cachedLabel: JBLabel       // "💾 sessionCached" (cache-read input tokens)
    private val tokensOutLabel: JBLabel    // "out sessionOut/globalOut"
    private val costLabel: JBLabel         // "$sessionCost/$globalCost"

    // Lowest-priority groups are hidden first when the bar does not fit the panel width.
    private val hideableGroups: MutableList<List<JComponent>> = mutableListOf()
    private val row1Panel: JPanel

    // State tracking
    private var sessionRequests = 0
    private var prevSessionTokensIn = 0
    private var sessionTokensIn = 0
    private var sessionTokensOut = 0
    private var sessionCachedTokens = 0
    private var sessionCostUsd = 0.0
    private var globalRequests = 0L
    private var globalTokensIn = 0L
    private var globalTokensOut = 0L
    private var globalCostUsd = 0.0

    init {
        row1Panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
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
            tokensInLabel = JBLabel("↓0/0").apply {
                toolTipText = "Input tokens: session / global"
            }
            cachedLabel = JBLabel("").apply {
                toolTipText = "Cache-read input tokens this session (subset of input)"
                foreground = LCATheme.neutralColor
            }
            tokensOutLabel = JBLabel("↑0/0").apply {
                toolTipText = "Output tokens: session / global"
            }
            costLabel = JBLabel("\$0/\$0").apply {
                toolTipText = "Cost USD: session / global"
            }

            val sep1 = createSeparator()
            val sep2 = createSeparator()
            val sep3 = createSeparator()

            add(coreHealthLabel)
            add(executionStatusLabel)
            add(sep1)
            add(contextFillBar)
            add(contextPercentLabel)
            add(sep2)
            add(requestsLabel)
            add(tokensInLabel)
            add(cachedLabel)
            add(tokensOutLabel)
            add(sep3)
            add(costLabel)

            // Hidden first when space runs out: cost, then requests, then the context
            // token counts (the bar itself and core/execution status always stay).
            hideableGroups.add(listOf(sep3, costLabel))
            hideableGroups.add(listOf(requestsLabel))
            hideableGroups.add(listOf(contextPercentLabel))
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
                applyPriorityVisibility()
                revalidate()
                repaint()
            }
        })
    }

    /**
     * Hide the lowest-priority groups when the single-row content does not fit
     * the current width, so the row never wraps or clips important segments.
     */
    private fun applyPriorityVisibility() {
        if (width <= 0) return
        hideableGroups.flatten().forEach { it.isVisible = true }
        var hiddenIndex = 0
        while (row1Panel.preferredSize.width > width && hiddenIndex < hideableGroups.size) {
            hideableGroups[hiddenIndex].forEach { it.isVisible = false }
            hiddenIndex++
        }
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
                        updateSessionTokens(it.tokensIn, it.tokensOut, it.cachedTokens)
                        updateSessionCost(it.costUsd)
                    } ?: run {
                        sessionRequests = 0
                        prevSessionTokensIn = 0
                        sessionTokensIn = 0
                        sessionTokensOut = 0
                        sessionCachedTokens = 0
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

        // Drive context-fill bar from totalEstimatedTokens and the cached maxContextWindow
        // StateFlow. SessionManager refreshes maxContextWindow off-EDT on session/model
        // change, so we get model-switch updates here without doing any DB work.
        cs.launch {
            kotlinx.coroutines.flow.combine(
                sessionManager.totalEstimatedTokens,
                sessionManager.maxContextWindow,
                sessionManager.activeSession
            ) { total, max, session -> Triple(total, max, session) }
                .collect { (total, max, session) ->
                    if (session != null) {
                        SwingUtilities.invokeLater {
                            updateContextFill(total, max)
                            logger.debug { "Context fill updated: $total/$max tokens" }
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

    private fun updateSessionTokens(tokensIn: Int, tokensOut: Int, cachedTokens: Int = 0) {
        if (tokensIn > prevSessionTokensIn) {
            sessionRequests++
            prevSessionTokensIn = tokensIn
        }
        sessionTokensIn = tokensIn
        sessionTokensOut = tokensOut
        sessionCachedTokens = cachedTokens
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

        tokensInLabel.text = "↓${formatLargeNumber(sessionTokensIn.toLong())}/${formatLargeNumber(globalTokensIn)}"
        tokensInLabel.toolTipText = "Input tokens: $sessionTokensIn session / $globalTokensIn global"

        cachedLabel.text = if (sessionCachedTokens > 0) "💾${formatLargeNumber(sessionCachedTokens.toLong())}" else ""
        cachedLabel.toolTipText = "Cache-read input tokens this session: $sessionCachedTokens (of $sessionTokensIn input)"
        cachedLabel.repaint()

        tokensOutLabel.text = "↑${formatLargeNumber(sessionTokensOut.toLong())}/${formatLargeNumber(globalTokensOut)}"
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

    fun dispose() {
        cs.cancel()
    }
}
