package pl.jclab.refio.ui.components.steps

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.SubtaskDto
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.common.PromptDialog
import kotlinx.coroutines.*
import java.awt.*
import javax.swing.*

/**
 * Steps Queue View - displays subtasks (steps) for active session
 *
 * Features:
 * - Read-only list of subtasks
 * - Status badges (new, pending, running, success, failed)
 * - Collapsible step details with expand/collapse
 * - Detailed tool parameters and execution results
 * - Per-step metrics (model, tokens, cost, time)
 * - Error messages for failed steps
 */
class StepsQueueView(private val project: Project) : JBPanel<StepsQueueView>(BorderLayout()) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val logger = dualLogger("StepsQueueView")

    private val stepsPanel: JPanel
    private val scrollPane: JBScrollPane
    private val emptyStateLabel: JLabel
    private val executionToolbar: JPanel

    // Execution control buttons (for enabling/disabling)
    private lateinit var resumeBtn: JButton
    private lateinit var replanBtn: JButton
    private lateinit var cancelAllBtn: JButton

    // Track which steps are expanded (by subtask ID)
    private val expandedSteps = mutableSetOf<String>()

    init {
        border = LCATheme.paddedBorder(8)

        // Header with title and Add Step button
        val headerPanel = JPanel(BorderLayout()).apply {
            val titleLabel = JBLabel("Steps Queue").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }
            add(titleLabel, BorderLayout.WEST)

            val addStepBtn = JButton("+ Add Step").apply {
                toolTipText = "Add new step to the queue"
                preferredSize = Dimension(100, 24)
                addActionListener {
                    showAddStepDialog()
                }
            }
            add(addStepBtn, BorderLayout.EAST)
        }

        // Execution state toolbar (Resume/Re-plan/Cancel All)
        executionToolbar = createExecutionToolbar()

        // Steps list
        stepsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = LCATheme.backgroundColor
        }

        scrollPane = JBScrollPane(stepsPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Empty state
        emptyStateLabel = JLabel(
            "<html><div style='text-align: center; color: gray; font-style: italic;'>" +
                    "No steps planned<br>" +
                    "Use Plan/Agent mode to create execution plan" +
                    "</div></html>"
        ).apply {
            horizontalAlignment = SwingConstants.CENTER
            border = LCATheme.paddedBorder(20)
        }

        // Layout: Header | Toolbar | Steps
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerPanel)
            add(executionToolbar)
        }

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Initially show empty state
        showEmptyState()

        // Observe subtasks
        cs.launch {
            sessionManager.subtasks.collect { subtasks ->
                logger.debug { "Received ${subtasks.size} subtasks from Flow" }
                updateSteps(subtasks)
            }
        }

        // Observe execution state to enable/disable toolbar buttons
        cs.launch {
            kotlinx.coroutines.flow.combine(
                stepExecutionService.isExecuting,
                sessionManager.subtasks
            ) { isExecuting, subtasks ->
                // Enable buttons only when:
                // 1. Not currently executing
                // 2. There are pending/planned steps remaining
                // 3. At least one step was already executed (meaning execution started and stopped)
                val hasPendingSteps = subtasks.any { it.status in listOf("PENDING", "PLANNED") }
                val hasExecutedSteps = subtasks.any { it.status in listOf("SUCCESS", "FAILED", "RUNNING") }
                !isExecuting && hasPendingSteps && hasExecutedSteps
            }.collect { shouldEnable ->
                SwingUtilities.invokeLater {
                    resumeBtn.isEnabled = shouldEnable
                    replanBtn.isEnabled = shouldEnable
                    cancelAllBtn.isEnabled = shouldEnable
                }
            }
        }
    }

    private fun showEmptyState() {
        stepsPanel.removeAll()
        stepsPanel.add(emptyStateLabel)
        stepsPanel.revalidate()
        stepsPanel.repaint()
    }

    private fun updateSteps(subtasks: List<SubtaskDto>) {
        logger.debug { "updateSteps called with ${subtasks.size} subtasks" }

        SwingUtilities.invokeLater {
            stepsPanel.removeAll()

            if (subtasks.isEmpty()) {
                logger.debug { "Showing empty state" }
                showEmptyState()
                return@invokeLater
            }

            logger.debug { "Rendering ${subtasks.size} subtasks" }

            // Sort by orderIndex to show steps in execution order
            val sortedSubtasks = subtasks.sortedBy { it.orderIndex }
            logger.debug { "Sorted ${sortedSubtasks.size} subtasks by orderIndex" }

            // Render each step in order
            sortedSubtasks.forEachIndexed { index, subtask ->
                val stepNumber = index + 1
                logger.debug { "Rendering step $stepNumber: ${subtask.description?.take(50)} [${subtask.status}]" }

                val itemPanel = createStepItem(subtask, stepNumber)
                stepsPanel.add(itemPanel)

                // Add small separator between steps
                if (index < sortedSubtasks.size - 1) {
                    stepsPanel.add(Box.createVerticalStrut(4))
                }
            }

            stepsPanel.revalidate()
            stepsPanel.repaint()
            logger.debug { "UI updated successfully" }
        }
    }

    private fun createStepItem(subtask: SubtaskDto, stepNumber: Int): JPanel {
        val isExpanded = subtask.id in expandedSteps

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = getBackgroundColorForStatus(subtask.status)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LCATheme.borderColor, 1),
                LCATheme.emptyBorder()
            )

            // Action buttons (compact)
            val buttonsAndStatus = createCompactActions(subtask, stepNumber)
            // Status badge
            buttonsAndStatus.add(createStatusBadge(subtask.status))
            add(buttonsAndStatus)

            // Header (always visible)
            val header = createStepHeader(subtask, stepNumber, isExpanded)
            add(header)

            // Details (collapsible)
            if (isExpanded) {
                val details = createStepDetails(subtask)
                add(details)
            }

            // Section: Metrics (show for PLANNED, RUNNING, SUCCESS, FAILED - whenever we have data)
            val metricsSection = createMetricsSection(subtask)
            if (metricsSection != null) {
                add(metricsSection)
            }
        }
    }

    private fun createStepHeader(subtask: SubtaskDto, stepNumber: Int, isExpanded: Boolean): JPanel {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = LCATheme.paddedBorder(6, 8)

            // Left: expand icon + step number (fixed width)
            val leftFixedPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                preferredSize = Dimension(80, 24)
                minimumSize = Dimension(80, 24)

                // Expand/collapse icon
                val expandIcon = JBLabel(if (isExpanded) "▼" else "▶").apply {
                    font = font.deriveFont(10f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                            toggleExpand(subtask.id)
                        }
                    })
                }
                add(expandIcon)

                // Step label
                add(JBLabel("Step $stepNumber:").apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                })
            }

            // Center: description (takes remaining space, truncates with ellipsis)
            val fullDesc = subtask.description ?: subtask.kind
            val descLabel = JBLabel(fullDesc).apply {
                font = font.deriveFont(11f)
                toolTipText = fullDesc
                // Enable text truncation with ellipsis
                preferredSize = Dimension(10, 20) // Will expand to fill available space
            }

            // Right: actions + status + time (fixed width panel)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false

                // Execution time
                if (subtask.startedAt != null && subtask.finishedAt != null) {
                    val executionMs = subtask.finishedAt - subtask.startedAt
                    add(JBLabel(formatTime(executionMs)).apply {
                        font = font.deriveFont(10f)
                        foreground = LCATheme.grayColor
                    })
                }
            }

            add(leftFixedPanel, BorderLayout.WEST)
            add(descLabel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    /**
     * Create compact action buttons for header (icons only)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun createCompactActions(subtask: SubtaskDto, _stepNumber: Int): JPanel {

        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))

        // Run button
        if (subtask.status in listOf("PENDING", "PLANNED", "PENDING_APPROVAL")) {
            panel.add(createCompactButton("▶", "Run step") {
                cs.launch {
                    try {
                        if (subtask.status == "PENDING_APPROVAL") {
                            sessionManager.approveSubtask(subtask.id)
                        } else {
                            sessionManager.executeSubtaskById(subtask.id)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to execute step" }
                    }
                }
            })
        }

        // Skip button
        if (subtask.status in listOf("PENDING_APPROVAL", "PENDING", "PLANNED")) {
            panel.add(createCompactButton("⏭", "Skip step") {
                cs.launch {
                    try {
                        sessionManager.skipSubtask(subtask.id)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to skip step" }
                    }
                }
            })
        }

        return panel
    }

    /**
     * Create compact icon button (20x20)
     */
    private fun createCompactButton(icon: String, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(20, 20)
            font = font.deriveFont(11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    /**
     * Create action buttons for a step
     */
    private fun createStepActions(subtask: SubtaskDto, stepNumber: Int): JPanel {
        return JPanel(FlowLayout(FlowLayout.CENTER, 2, 0)).apply {
            isOpaque = false

            // Run button (only for PENDING/PLANNED steps)
            if (subtask.status in listOf("PENDING", "PLANNED", "PENDING_APPROVAL")) {
                add(createActionButton("▶", "Run this step") {
                    cs.launch {
                        try {
                            // For PENDING_APPROVAL steps, use approveSubtask
                            // For other steps, use executeSubtaskById
                            if (subtask.status == "PENDING_APPROVAL") {
                                sessionManager.approveSubtask(subtask.id)
                            } else {
                                sessionManager.executeSubtaskById(subtask.id)
                            }
                            logger.info { "Executed step: ${subtask.id}" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to execute step" }
                        }
                    }
                })
            }

            // Skip button (only for PENDING_APPROVAL/PENDING/PLANNED steps)
            if (subtask.status in listOf("PENDING_APPROVAL", "PENDING", "PLANNED")) {
                add(createActionButton("⏭", "Skip this step") {
                    cs.launch {
                        try {
                            sessionManager.skipSubtask(subtask.id)
                            logger.info { "Skipped step: ${subtask.id}" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to skip step" }
                        }
                    }
                })
            }

            // Move Up button (only if not first step and not completed)
            if (stepNumber > 1 && subtask.status !in listOf("SUCCESS", "FAILED", "RUNNING")) {
                add(createActionButton("↑", "Move step up") {
                    cs.launch {
                        try {
                            sessionManager.moveStepUp(subtask.id)
                            logger.info { "Moved step up: ${subtask.id}" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to move step up" }
                        }
                    }
                })
            }

            // Move Down button (only if not last step and not completed)
            val totalSteps = sessionManager.subtasks.value.size
            if (stepNumber < totalSteps && subtask.status !in listOf("SUCCESS", "FAILED", "RUNNING")) {
                add(createActionButton("↓", "Move step down") {
                    cs.launch {
                        try {
                            sessionManager.moveStepDown(subtask.id)
                            logger.info { "Moved step down: ${subtask.id}" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to move step down" }
                        }
                    }
                })
            }

            // Delete button (only for PENDING/PLANNED steps)
            if (subtask.status in listOf("PENDING", "PLANNED")) {
                add(createActionButton("✖", "Delete this step", isDestructive = true) {
                    val confirmed = JOptionPane.showConfirmDialog(
                        this@StepsQueueView,
                        "Are you sure you want to delete this step?",
                        "Confirm Deletion",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                    ) == JOptionPane.YES_OPTION

                    if (confirmed) {
                        cs.launch {
                            try {
                                sessionManager.deleteStep(subtask.id)
                                logger.info { "Deleted step: ${subtask.id}" }
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to delete step" }
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * Create small action button
     */
    private fun createActionButton(
        icon: String,
        tooltip: String,
        isDestructive: Boolean = false,
        action: () -> Unit
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(24, 24)
            font = font.deriveFont(12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (isDestructive) {
                foreground = LCATheme.redColor
            }
            addActionListener {
                action()
            }
        }
    }

    private fun createStepDetails(subtask: SubtaskDto): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = LCATheme.paddedBorder(8, 32, 8, 8)  // Indent details

            // Section: Description
            if (!subtask.description.isNullOrBlank()) {
                add(createSection("📋 Description:", subtask.description))
                add(Box.createVerticalStrut(8))
            }

            // Section: Tools Planned
            if (subtask.status in listOf("PLANNED", "RUNNING", "SUCCESS", "FAILED")) {
                val toolsSection = createToolsSection(subtask)
                if (toolsSection != null) {
                    add(toolsSection)
                    add(Box.createVerticalStrut(8))
                }
            }

            // Section: Execution Summary
            // Note: resultSummary doesn't exist in SubtaskDto, skipping for now
            // TODO: Add result_summary field to SubtaskDto when backend supports it

            // Section: Error Message
            if (subtask.status == "FAILED" && !subtask.errorMessage.isNullOrBlank()) {
                add(createSection("❌ Error:", subtask.errorMessage))
                add(Box.createVerticalStrut(8))
            }
        }
    }

    private fun toggleExpand(subtaskId: String) {
        if (subtaskId in expandedSteps) {
            expandedSteps.remove(subtaskId)
        } else {
            expandedSteps.add(subtaskId)
        }
        // Trigger UI refresh
        SwingUtilities.invokeLater {
            val subtasks = sessionManager.subtasks.value
            updateSteps(subtasks)
        }
    }


    private fun createSection(title: String, content: String): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false

            val titleLabel = JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = LCATheme.grayColor
            }

            // Use JTextArea for proper text wrapping instead of HTML label
            val contentArea = JTextArea(content).apply {
                font = LCATheme.bodyFont.deriveFont(11f)
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                border = LCATheme.paddedBorder(4, 0, 0, 0)
                background = null
            }

            add(titleLabel, BorderLayout.NORTH)
            add(contentArea, BorderLayout.CENTER)
        }
    }

    private fun createToolsSection(subtask: SubtaskDto): JPanel? {
        val tools = parseToolsFromSubtask(subtask) ?: return null

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            val titleLabel = JBLabel("🔧 Tools Planned:").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = LCATheme.grayColor
            }
            add(titleLabel)
            add(Box.createVerticalStrut(4))

            tools.forEach { tool ->
                val toolPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = LCATheme.paddedBorder(0, 0, 0, 16)

                    val toolLabel = JBLabel("• ${tool.name}").apply {
                        font = font.deriveFont(Font.BOLD, 11f)
                    }
                    add(toolLabel, BorderLayout.NORTH)

                    if (tool.args.isNotEmpty()) {
                        val argsPanel = JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                            border = LCATheme.paddedBorder(0, 0, 0, 16)

                            tool.args.forEach { (key, value) ->
                                val argLabel = JBLabel("- $key: ${formatArgValue(value)}").apply {
                                    font = font.deriveFont(10f)
                                    foreground = LCATheme.grayColor
                                }
                                add(argLabel)
                            }
                        }
                        add(argsPanel, BorderLayout.CENTER)
                    }
                }
                add(toolPanel)
                add(Box.createVerticalStrut(4))
            }
        }
    }

    private fun createMetricsSection(subtask: SubtaskDto): JPanel? {
        // Calculate execution time from timestamps
        val executionMs = if (subtask.startedAt != null && subtask.finishedAt != null) {
            subtask.finishedAt - subtask.startedAt
        } else null

        if (subtask.model == null && executionMs == null) return null

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            val titleLabel = JBLabel("📊 Metrics:").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = LCATheme.grayColor
            }
            add(titleLabel)
            add(Box.createVerticalStrut(4))

            // Compact metrics in flow layout (chips style)
            val metricsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                isOpaque = false
                border = LCATheme.paddedBorder(0, 0, 0, 12)

                // Model chip
                if (subtask.model != null) {
                    val modelText = if (subtask.provider != null) {
                        "${subtask.model} (${subtask.provider})"
                    } else {
                        subtask.model
                    }
                    add(createMetricChip("🤖", modelText))
                }

                // Time chip
                if (executionMs != null) {
                    add(createMetricChip("⏱", formatTime(executionMs)))
                }

                // Tokens chip
                if (subtask.tokensIn != null && subtask.tokensOut != null) {
                    add(createMetricChip("📊", "${subtask.tokensIn}/${subtask.tokensOut}"))
                }

                // Cost chip
                if (subtask.costUsd != null) {
                    add(createMetricChip("💰", "$${String.format("%.4f", subtask.costUsd)}"))
                }
            }
            add(metricsRow)
        }
    }

    /**
     * Create compact metric chip (icon + value)
     */
    private fun createMetricChip(icon: String, value: String): JLabel {
        return JLabel("$icon $value").apply {
            font = font.deriveFont(10f)
            foreground = LCATheme.grayColor
            background = LCATheme.systemBubbleBackground
            isOpaque = true
            border = LCATheme.paddedBorder(2, 6)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolsFromSubtask(subtask: SubtaskDto): List<ToolInfo>? {
        try {
            // Try step_plan_json first (from prepare endpoint)
            subtask.stepPlanJson?.let { json ->
                val gson = pl.jclab.refio.core.utils.GsonInstance.gson
                val plan = gson.fromJson(json, Map::class.java)
                val tools = plan["tools"] as? List<Map<String, Any>>
                if (!tools.isNullOrEmpty()) {
                    return tools.map { tool ->
                        ToolInfo(
                            name = tool["name"] as? String ?: "unknown",
                            args = tool["args"] as? Map<String, Any> ?: emptyMap()
                        )
                    }
                }
            }

            // Fallback to params_json
            subtask.paramsJson?.let { json ->
                val gson = pl.jclab.refio.core.utils.GsonInstance.gson
                val params = gson.fromJson(json, Map::class.java)
                val tools = params["tools"] as? List<Map<String, Any>>
                if (!tools.isNullOrEmpty()) {
                    return tools.map { tool ->
                        ToolInfo(
                            name = tool["name"] as? String ?: "unknown",
                            args = tool["args"] as? Map<String, Any> ?: emptyMap()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug { "Failed to parse tools: ${e.message}" }
        }
        return null
    }

    private fun formatArgValue(value: Any?): String {
        return when (value) {
            is String -> if (value.length > 50) "${value.take(47)}..." else value
            is Map<*, *> -> "{...}"
            is List<*> -> "[...] (${value.size} items)"
            else -> value.toString()
        }
    }

    private fun formatTime(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.2fs", ms / 1000.0)
            else -> {
                val minutes = ms / 60_000
                val seconds = (ms % 60_000) / 1000
                "${minutes}m ${seconds}s"
            }
        }
    }

    private fun getBackgroundColorForStatus(status: String): Color {
        return when (status) {
            "PENDING_APPROVAL", "PLANNED" -> LCATheme.stepPendingBackground
            "RUNNING" -> LCATheme.stepRunningBackground
            "SUCCESS" -> LCATheme.stepSuccessBackground
            "FAILED" -> LCATheme.stepFailedBackground
            else -> LCATheme.backgroundColor
        }
    }

    private fun createStatusBadge(status: String): JLabel {
        val (text, bgColor) = when (status.uppercase()) {
            "NEW" -> "NEW" to LCATheme.stepNewBackground
            "PENDING" -> "PENDING" to LCATheme.stepPendingBackground
            "PLANNED" -> "PLANNED" to LCATheme.stepPendingBackground
            "RUNNING" -> "RUNNING" to LCATheme.stepRunningBackground
            "SUCCESS" -> "SUCCESS" to LCATheme.stepSuccessBackground
            "FAILED" -> "FAILED" to LCATheme.stepFailedBackground
            "SKIPPED" -> "SKIPPED" to LCATheme.stepSkippedBackground
            "CANCELED" -> "CANCELED" to LCATheme.stepCanceledBackground
            else -> status.uppercase() to LCATheme.grayColor
        }

        return JLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = LCATheme.whiteColor
            background = bgColor
            isOpaque = true
            border = LCATheme.paddedBorder(2, 6)
        }
    }


    /**
     * Create execution toolbar with Resume/Re-plan/Cancel All buttons
     */
    private fun createExecutionToolbar(): JPanel {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, LCATheme.borderColor),
                LCATheme.paddedBorder(6)
            )
        }

        // Resume button
        resumeBtn = JButton("▶ Resume").apply {
            toolTipText = "Continue execution from where it stopped"
            preferredSize = Dimension(110, 28)
            isEnabled = false  // Initially disabled
            addActionListener {
                logger.info { "Resume execution clicked" }
                cs.launch {
                    try {
                        sessionManager.sendMessage("Continue execution of the previous task.")
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to continue execution via AgentTurnLoop" }
                    }
                }
            }
        }
        panel.add(resumeBtn)

        // Re-plan button
        replanBtn = JButton("🔄 Re-plan").apply {
            toolTipText = "Delete remaining steps and generate new plan"
            preferredSize = Dimension(110, 28)
            isEnabled = false  // Initially disabled
            addActionListener {
                logger.info { "Re-plan clicked" }
                val prompt = PromptDialog.showAndGet(
                    title = "Re-plan",
                    label = "Enter prompt for new plan:",
                    defaultText = ""
                )
                if (prompt != null) {
                    cs.launch {
                        try {
                            sessionManager.sendMessage("Replan the remaining work: $prompt")
                            logger.info { "Replan request sent via AgentTurnLoop" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to send replan request via AgentTurnLoop" }
                        }
                    }
                }
            }
        }
        panel.add(replanBtn)

        // Cancel All button
        cancelAllBtn = JButton("✖ Cancel All").apply {
            toolTipText = "Delete all remaining steps without creating new plan"
            preferredSize = Dimension(120, 28)
            foreground = LCATheme.redColor
            isEnabled = false  // Initially disabled
            addActionListener {
                logger.info { "Cancel all clicked" }
                val confirmed = JOptionPane.showConfirmDialog(
                    this@StepsQueueView,
                    "Are you sure you want to cancel all remaining steps?",
                    "Confirm Cancellation",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.YES_OPTION

                if (confirmed) {
                    cs.launch {
                        try {
                            sessionManager.cancelAllPendingSteps()
                            logger.info { "Cancelled all pending steps" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to cancel steps" }
                        }
                    }
                }
            }
        }
        panel.add(cancelAllBtn)

        return panel
    }

    /**
     * Show dialog to add new step
     */
    private fun showAddStepDialog() {
        val description = PromptDialog.showAndGet(
            title = "Add New Step",
            label = "Enter step description:",
            defaultText = ""
        )

        if (description != null && description.isNotBlank()) {
            cs.launch {
                try {
                    sessionManager.sendMessage("Add step: $description")
                    logger.info { "Add step request sent via AgentTurnLoop" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send add step request via AgentTurnLoop" }
                }
            }
        }
    }

    fun dispose() {
        cs.cancel()
    }
}

/**
 * Data class representing a tool with its arguments
 */
data class ToolInfo(
    val name: String,
    val args: Map<String, Any>
)
