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
import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
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
        preferredSize = JBUI.size(80, 10)
        minimumSize = JBUI.size(44, 8)
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

        // The percentage is printed next to the bar by the status bar, not inside it.
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
 * Status bar under the panel body.
 *
 * Shows engine state, context fill and the session counters that fit a 22 px strip: request
 * count, tokens in/out and cost, all abbreviated. The exact numbers, cache reads and global
 * totals live in the tooltip behind the trailing "more" icon.
 *
 * Engine flows push far more often than a human can read, so updates are buffered and repainted
 * at most four times a second.
 */
class StatusBar(private val project: Project) : JBPanel<StatusBar>(BorderLayout()), Disposable {

    enum class Level { MINIMAL, NORMAL }

    private val logger = dualLogger("StatusBar")

    // Services
    private val cs = CoroutineScope(SupervisorJob())
    private val coreManager = CoreConnectionManager.getInstance()
    private val sessionManager = SessionManager.getInstance(project)
    private val globalMetrics = GlobalMetrics

    private val coreHealthLabel = JBLabel("●").apply {
        foreground = LCATheme.errorColor
        toolTipText = "Core initialization failed - check logs"
    }
    private val stateLabel = JBLabel("Idle").apply {
        font = JBUI.Fonts.smallFont()
        foreground = LCATheme.neutralColor
    }
    private val contextLabel = JBLabel("Context").apply {
        font = JBUI.Fonts.smallFont()
        foreground = LCATheme.descriptionForeground
    }
    private val contextFillBar = ContextUsageBar()
    private val contextTokensLabel = JBLabel().apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        foreground = LCATheme.descriptionForeground
        toolTipText = "Context tokens used / model context window"
    }
    private val contextPercentLabel = JBLabel("0%").apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
    }
    private val requestsLabel = JBLabel("0r").apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        foreground = LCATheme.descriptionForeground
        toolTipText = "LLM requests in this session"
    }
    private val tokensLabel = JBLabel("0/0").apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        foreground = LCATheme.descriptionForeground
        toolTipText = "Session tokens in / out"
    }
    private val costLabel = JBLabel("\$0.00").apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        toolTipText = "Session cost"
    }
    private val moreLabel = JBLabel(AllIcons.Actions.More)

    private val flushAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

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
    private var contextTokens = 0
    private var contextLimit = 0
    private var stateText = "Idle"
    private var stateColor: Color = LCATheme.neutralColor

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(LCATheme.borderColor),
            JBUI.Borders.empty(0, 8)
        )
        preferredSize = Dimension(0, JBUI.scale(22))
        minimumSize = Dimension(0, JBUI.scale(20))

        val left = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(coreHealthLabel)
            add(stateLabel)
            add(contextLabel)
            add(contextFillBar)
            add(contextTokensLabel)
            add(contextPercentLabel)
        }

        val right = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(requestsLabel)
            add(tokensLabel)
            add(costLabel)
            add(moreLabel)
        }

        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)

        startMonitoring()
        flush()
    }

    /**
     * Collapses the bar to what still fits a narrow dock: the health dot, context percentage
     * and cost. Driven by the panel-wide width listener, not by this component.
     */
    fun setLevel(level: Level) {
        val full = level == Level.NORMAL
        stateLabel.isVisible = full
        contextLabel.isVisible = full
        contextFillBar.isVisible = full
        contextTokensLabel.isVisible = full
        requestsLabel.isVisible = full
        tokensLabel.isVisible = full
        moreLabel.isVisible = full
        revalidate()
        repaint()
    }

    private fun startMonitoring() {
        cs.launch {
            coreManager.healthState.collect { health ->
                SwingUtilities.invokeLater { updateCoreHealth(health.state) }
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
                        contextTokens = 0
                        contextLimit = 0
                        scheduleFlush()
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

    /** Buffers a repaint; a burst of engine events collapses into one update per tick. */
    private fun scheduleFlush() {
        if (flushAlarm.isDisposed) return
        if (flushAlarm.isEmpty) flushAlarm.addRequest({ flush() }, FLUSH_INTERVAL_MS)
    }

    private fun flush() {
        stateLabel.text = stateText
        stateLabel.foreground = stateColor

        val percentage = if (contextLimit > 0) (contextTokens * 100.0 / contextLimit).toInt() else 0
        contextFillBar.percentage = percentage
        contextFillBar.currentTokens = contextTokens
        contextFillBar.maxTokens = contextLimit
        contextTokensLabel.text = StatusBarFormat.contextFill(contextTokens, contextLimit)
        contextPercentLabel.text = "$percentage%"
        contextPercentLabel.foreground = when {
            percentage < 75 -> LCATheme.successColor
            percentage < 90 -> LCATheme.warningColor
            else -> LCATheme.errorColor
        }

        requestsLabel.text = "${formatLargeNumber(sessionRequests.toLong())}r"
        tokensLabel.text = "${formatLargeNumber(sessionTokensIn.toLong())}/${formatLargeNumber(sessionTokensOut.toLong())}"
        costLabel.text = "\$${formatCostShort(sessionCostUsd)}"

        installMetricsTooltip()
    }

    /**
     * The metrics that used to crowd the bar. Reinstalled on each flush so the numbers stay
     * current; installing it more often than that would be wasted work.
     */
    private fun installMetricsTooltip() {
        HelpTooltip()
            .setTitle("Session metrics")
            .setDescription(
                "Requests: $sessionRequests session / ${formatLargeNumber(globalRequests)} global<br>" +
                    "Tokens in: ${formatLargeNumber(sessionTokensIn.toLong())} / ${formatLargeNumber(globalTokensIn)} global<br>" +
                    "Tokens out: ${formatLargeNumber(sessionTokensOut.toLong())} / ${formatLargeNumber(globalTokensOut)} global<br>" +
                    "Cached input: ${formatLargeNumber(sessionCachedTokens.toLong())}<br>" +
                    "Context: ${formatTokens(contextTokens)} / ${formatTokens(contextLimit)}<br>" +
                    "Cost: \$${formatCostShort(sessionCostUsd)} session / \$${formatCostShort(globalCostUsd)} total"
            )
            .installOn(moreLabel)
    }

    /**
     * Update core health indicator (dot color only)
     */
    private fun updateCoreHealth(state: CoreHealthState) {
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
     * Update execution status text based on current operation.
     * Single source of truth for execution state display.
     */
    private fun updateCurrentOperation(operation: OperationInfo) {
        stateText = describeOperation(operation)

        stateColor = when (operation) {
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

        scheduleFlush()
    }

    private fun describeOperation(operation: OperationInfo): String = when (operation) {
        is OperationInfo.Idle -> "Idle"
        is OperationInfo.ChatRequest -> "Chat Request"
        is OperationInfo.PlanningRequest -> "Planning"
        is OperationInfo.PlanningStep -> "Planning ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.ExecutingStep -> "Step ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.StepPlanning -> "Step planning ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.StepExecuting -> "Step execute ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.StepSummarizing -> "Step summarize ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.StepReasoning -> "Step reasoning ${operation.stepNumber}/${operation.totalSteps}"
        is OperationInfo.ExecutingTool -> operation.toolName
        is OperationInfo.Orchestration -> {
            val stepInfo = if (operation.stepNumber != null && operation.totalSteps != null) {
                " ${operation.stepNumber}/${operation.totalSteps}"
            } else ""
            "Orchestration: ${operation.phase}$stepInfo"
        }
        else -> operation.toString()
    }

    private fun updateGlobalMetrics(metrics: GlobalMetrics.MetricsSnapshot) {
        globalRequests = metrics.totalRequests.toLong()
        globalTokensIn = metrics.totalTokensIn
        globalTokensOut = metrics.totalTokensOut
        globalCostUsd = metrics.totalCostUsd
        scheduleFlush()
    }

    private fun updateSessionTokens(tokensIn: Int, tokensOut: Int, cachedTokens: Int = 0) {
        if (tokensIn > prevSessionTokensIn) {
            sessionRequests++
            prevSessionTokensIn = tokensIn
        }
        sessionTokensIn = tokensIn
        sessionTokensOut = tokensOut
        sessionCachedTokens = cachedTokens
        scheduleFlush()
    }

    private fun updateSessionCost(costUsd: Double) {
        sessionCostUsd = costUsd
        scheduleFlush()
    }

    private fun updateContextFill(current: Int, max: Int) {
        contextTokens = current
        contextLimit = max
        scheduleFlush()
    }

    private fun formatCostShort(costUsd: Double): String = StatusBarFormat.cost(costUsd)

    private fun formatLargeNumber(num: Long): String = StatusBarFormat.count(num)

    private fun formatTokens(tokens: Int): String = StatusBarFormat.count(tokens.toLong())

    fun updateMetrics(messages: List<Message>) {
        val totals = calculateTotalMetrics(messages)
        SwingUtilities.invokeLater {
            sessionTokensIn = totals.inputTokens
            sessionTokensOut = totals.outputTokens
            sessionCostUsd = totals.costUsd
            scheduleFlush()
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

    override fun dispose() {
        flushAlarm.cancelAllRequests()
        cs.cancel()
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 250
    }
}

/**
 * Number formatting for the status bar and its tooltip.
 *
 * Kept apart from the component so the rules that decide how a metric reads in a 22 px strip
 * can be checked without a UI.
 */
object StatusBarFormat {

    /** Shortens counts so a metric never widens the bar past the panel. */
    fun count(num: Long): String = when {
        num >= 1_000_000_000 -> String.format("%.1fB", num / 1_000_000_000.0)
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
        else -> num.toString()
    }

    /** Session cost, in the user's locale (a comma decimal separator is expected in PL). */
    fun cost(usd: Double): String = String.format("%.2f", usd)

    /**
     * Context fill as "used / window", e.g. `47K/128K`.
     *
     * A percentage alone does not say how much room is left in absolute terms, and the same
     * percentage means something very different on a 32K and on a 1M window. Whole thousands are
     * enough to judge that and keep the width stable while the number grows; an unknown window
     * (no model resolved yet) leaves the metric out rather than printing a misleading limit.
     */
    fun contextFill(used: Int, limit: Int): String =
        if (limit <= 0) "" else "${tokensShort(used)}/${tokensShort(limit)}"

    // Truncated, not rounded: a 32768-token window has to read "32K" the way the user knows it,
    // and a shown limit must never claim more room than the model actually has.
    private fun tokensShort(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }
}
