package pl.jclab.refio.ui.components.steps

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.SubtaskDto
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.common.PromptDialog
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

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
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private val stepsPanel: JPanel
    private val scrollPane: JBScrollPane
    private val emptyStateLabel: JLabel
    private val executionToolbar: JPanel

    // Execution control buttons (for enabling/disabling)
    private lateinit var replanBtn: JButton
    private lateinit var cancelAllBtn: JButton

    init {
        border = LCATheme.paddedBorder(4)

        // Buttons toolbar: Add Step | Resume | Re-plan | Delete All
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

        // Layout: Buttons (fixed) | Steps (scrollable)
        add(executionToolbar, BorderLayout.NORTH)
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

        // Enable/disable buttons: active when session exists and not currently generating
        cs.launch {
            kotlinx.coroutines.flow.combine(
                sessionManager.activeSession,
                sessionManager.isGenerating
            ) { session, generating ->
                session != null && !generating
            }.collect { shouldEnable ->
                SwingUtilities.invokeLater {
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
            val header = createCompactStepHeader(subtask, stepNumber)
            add(header)

            // Section: Metrics (show for PLANNED, RUNNING, SUCCESS, FAILED - whenever we have data)
            val metricsSection = createMetricsSection(subtask)
            if (metricsSection != null) {
                add(metricsSection)
            }
        }
    }


    private fun createCompactStepHeader(subtask: SubtaskDto, stepNumber: Int): JPanel {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = LCATheme.paddedBorder(6, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to open full step details"

            val leftFixedPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                preferredSize = Dimension(72, 24)
                minimumSize = Dimension(72, 24)
                add(JBLabel("Step $stepNumber:").apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                })
            }

            val fullDesc = subtask.description ?: subtask.kind
            val descLabel = JBLabel(fullDesc).apply {
                font = font.deriveFont(11f)
                toolTipText = fullDesc
                preferredSize = Dimension(10, 20)
            }

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false

                val startedAt = subtask.startedAt
                val finishedAt = subtask.completedAt ?: subtask.finishedAt
                if (startedAt != null && finishedAt != null) {
                    val executionMs = finishedAt - startedAt
                    add(JBLabel(formatTime(executionMs)).apply {
                        font = font.deriveFont(10f)
                        foreground = LCATheme.grayColor
                    })
                }
            }

            add(leftFixedPanel, BorderLayout.WEST)
            add(descLabel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)

            installHeaderClickHandler(this, subtask, stepNumber)
            installHeaderClickHandler(leftFixedPanel, subtask, stepNumber)
            installHeaderClickHandler(descLabel, subtask, stepNumber)
            installHeaderClickHandler(rightPanel, subtask, stepNumber)
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
        val startedAtMs = subtask.startedAt
        val finishedAtMs = subtask.completedAt ?: subtask.finishedAt
        val executionMs = if (startedAtMs != null && finishedAtMs != null) {
            finishedAtMs - startedAtMs
        } else null

        if (subtask.model == null && executionMs == null && subtask.latencyMs == null) return null

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            add(Box.createVerticalStrut(4))

            // Compact metrics in flow layout (chips style)
            val metricsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                isOpaque = false
                border = LCATheme.paddedBorder(0, 0, 0, 12)

                // Model chip
                val subtaskModel = subtask.model
                if (subtaskModel != null) {
                    val modelText = if (subtask.provider != null) {
                        "$subtaskModel (${subtask.provider})"
                    } else {
                        subtaskModel
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

    private fun formatCost(cost: Double?): String? {
        if (cost == null) return null
        return "$${String.format("%.4f", cost)}"
    }

    private fun formatTimestamp(timestamp: Long?): String? {
        return timestamp?.let { timestampFormatter.format(Instant.ofEpochMilli(it)) }
    }

    private fun prettyJsonOrRaw(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val gson = pl.jclab.refio.core.utils.GsonInstance.gson
            gson.toJson(gson.fromJson(raw, Any::class.java))
        } catch (_: Exception) {
            raw
        }
    }

    private fun installHeaderClickHandler(component: JComponent, subtask: SubtaskDto, stepNumber: Int) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    showStepDetailsDialog(subtask, stepNumber)
                }
            }
        })
    }

    private fun showStepDetailsDialog(subtask: SubtaskDto, stepNumber: Int) {
        StepDetailsDialog(project, subtask, stepNumber, this::buildStepDetailsText).show()
    }

    private fun buildStepDetailsText(subtask: SubtaskDto, stepNumber: Int): String {
        val completedAt = subtask.completedAt ?: subtask.finishedAt
        return buildString {
            appendLine("Step $stepNumber")
            appendLine()
            appendLine("kind: ${subtask.kind}")
            appendLine("status: ${subtask.status}")
            appendLine("description: ${subtask.description.orEmpty()}")
            appendLine()
            appendLine("params_json:")
            appendLine(prettyJsonOrRaw(subtask.paramsJson).orEmpty())
            appendLine()
            appendLine("step_plan_json:")
            appendLine(prettyJsonOrRaw(subtask.stepPlanJson).orEmpty())
            appendLine()
            appendLine("summary:")
            appendLine(subtask.summary.orEmpty())
            appendLine()
            appendLine("requires_approval: ${subtask.requiresApproval}")
            appendLine("approval_status: ${subtask.approvalStatus}")
            appendLine()
            appendLine("result:")
            appendLine(subtask.result.orEmpty())
            appendLine()
            appendLine("error_message:")
            appendLine(subtask.errorMessage.orEmpty())
            appendLine()
            appendLine("llm_model: ${subtask.model.orEmpty()}")
            appendLine("llm_provider: ${subtask.provider.orEmpty()}")
            appendLine("input_tokens: ${subtask.tokensIn?.toString().orEmpty()}")
            appendLine("output_tokens: ${subtask.tokensOut?.toString().orEmpty()}")
            appendLine("cost_usd: ${formatCost(subtask.costUsd).orEmpty()}")
            appendLine("latency_ms: ${subtask.latencyMs?.toString().orEmpty()}")
            appendLine()
            appendLine("created_at: ${formatTimestamp(subtask.createdAt).orEmpty()}")
            appendLine("updated_at: ${formatTimestamp(subtask.updatedAt).orEmpty()}")
            appendLine("started_at: ${formatTimestamp(subtask.startedAt).orEmpty()}")
            appendLine("completed_at: ${formatTimestamp(completedAt).orEmpty()}")
            appendLine("finished_at: ${formatTimestamp(subtask.finishedAt).orEmpty()}")
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
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, LCATheme.borderColor)
        }

        // Refresh button — always active
        val refreshBtn = JButton("⟳ Refresh").apply {
            toolTipText = "Refresh steps list"
            preferredSize = Dimension(100, 28)
            isEnabled = true
            addActionListener {
                logger.info { "Refresh steps clicked" }
                cs.launch {
                    try {
                        sessionManager.refreshSubtasks()
                        logger.info { "Steps list refreshed" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to refresh steps" }
                    }
                }
            }
        }
        panel.add(refreshBtn)

        // Re-plan button — delete pending first, then ask LLM for new plan
        replanBtn = JButton("Re-plan").apply {
            toolTipText = "Delete remaining steps and generate new plan"
            preferredSize = Dimension(90, 28)
            isEnabled = false
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
                            sessionManager.cancelAllPendingSteps()
                            logger.info { "Deleted pending subtasks before replan" }
                            sessionManager.sendMessage("Create a new plan: $prompt")
                            logger.info { "Replan request sent" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to replan" }
                        }
                    }
                }
            }
        }
        panel.add(replanBtn)

        // Delete All button — deterministic delete
        cancelAllBtn = JButton("Delete All").apply {
            toolTipText = "Delete all remaining steps"
            preferredSize = Dimension(100, 28)
            foreground = LCATheme.redColor
            isEnabled = false
            addActionListener {
                logger.info { "Delete all clicked" }
                val confirmed = JOptionPane.showConfirmDialog(
                    this@StepsQueueView,
                    "Are you sure you want to delete all remaining steps?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.YES_OPTION

                if (confirmed) {
                    cs.launch {
                        try {
                            sessionManager.cancelAllPendingSteps()
                            logger.info { "Deleted all pending steps" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to delete steps" }
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

private class StepDetailsDialog(
    project: Project,
    private val subtask: SubtaskDto,
    private val stepNumber: Int,
    private val detailsProvider: (SubtaskDto, Int) -> String
) : DialogWrapper(project, true) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        title = "Step $stepNumber Details"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(LCATheme.margin)
            preferredSize = Dimension(900, 720)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        contentPanel.add(createMetadataSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createTimingSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createMetricsSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createTextSection("Description", subtask.description))
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createTextSection("Summary", subtask.summary ?: subtask.resultSummary))
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createTextSection("Result", subtask.result))

        subtask.errorMessage?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createTextSection("Error", it, isError = true))
        }

        subtask.paramsJson?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createPayloadSection("Params JSON", it))
        }

        subtask.stepPlanJson?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createPayloadSection("Step Plan JSON", it))
        }

        panel.add(JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
        }, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        val copyAction = object : DialogWrapperAction("Copy to Clipboard") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(detailsProvider(subtask, stepNumber)),
                    null
                )
            }
        }
        val saveAction = object : DialogWrapperAction("Save to File") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                saveToFile()
            }
        }
        return arrayOf(copyAction, saveAction, okAction)
    }

    private fun createMetadataSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Step Information")
            val gbc = createConstraints()

            addField(this, gbc, "Step:", stepNumber.toString())
            gbc.gridy++
            addField(this, gbc, "ID:", subtask.id)
            gbc.gridy++
            addField(this, gbc, "Task ID:", subtask.taskId)
            gbc.gridy++
            addField(this, gbc, "Order:", subtask.orderIndex.toString())
            gbc.gridy++
            addField(this, gbc, "Kind:", subtask.kind)
            gbc.gridy++
            addField(this, gbc, "Status:", subtask.status)
            gbc.gridy++
            addField(this, gbc, "Requires Approval:", subtask.requiresApproval.toString())
            gbc.gridy++
            addField(this, gbc, "Approval Status:", subtask.approvalStatus)
        }
    }

    private fun createTimingSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Timestamps")
            val gbc = createConstraints()

            addField(this, gbc, "Created At:", formatTimestamp(subtask.createdAt))
            gbc.gridy++
            addField(this, gbc, "Updated At:", formatTimestamp(subtask.updatedAt))
            gbc.gridy++
            addField(this, gbc, "Started At:", formatTimestamp(subtask.startedAt))
            gbc.gridy++
            addField(this, gbc, "Completed At:", formatTimestamp(subtask.completedAt ?: subtask.finishedAt))
            gbc.gridy++
            addField(this, gbc, "Finished At:", formatTimestamp(subtask.finishedAt))
        }
    }

    private fun createMetricsSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Metrics")
            val gbc = createConstraints()

            addField(this, gbc, "LLM Model:", subtask.model ?: "-")
            gbc.gridy++
            addField(this, gbc, "LLM Provider:", subtask.provider ?: "-")
            gbc.gridy++
            addField(this, gbc, "Input Tokens:", formatNumber(subtask.tokensIn))
            gbc.gridy++
            addField(this, gbc, "Output Tokens:", formatNumber(subtask.tokensOut))
            gbc.gridy++
            addField(this, gbc, "Cost (USD):", subtask.costUsd?.let { String.format("$%.6f", it) } ?: "-")
            gbc.gridy++
            addField(this, gbc, "Latency:", subtask.latencyMs?.let { "${formatNumber(it)} ms" } ?: "-")
        }
    }

    private fun createTextSection(title: String, content: String?, isError: Boolean = false): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createTitledBorder(title)

            val textArea = JTextArea(content?.ifBlank { "-" } ?: "-").apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = 4
                font = LCATheme.bodyFont
                background = LCATheme.editorBackground
                border = LCATheme.paddedBorder(LCATheme.padding)
                if (isError) {
                    foreground = LCATheme.errorColor
                }
            }

            add(textArea, BorderLayout.CENTER)
        }
    }

    private fun createPayloadSection(title: String, payload: String): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createTitledBorder(title)

            val textArea = JTextArea(formatJson(payload)).apply {
                isEditable = false
                lineWrap = false
                font = LCATheme.monoFont
                background = LCATheme.editorBackground
                border = LCATheme.paddedBorder(LCATheme.padding)
                caretPosition = 0
            }

            add(JBScrollPane(textArea).apply {
                preferredSize = Dimension(820, 180)
                border = BorderFactory.createLineBorder(LCATheme.borderColor, 1)
            }, BorderLayout.CENTER)
        }
    }

    private fun createConstraints(): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = LCATheme.insetsMedium
            fill = GridBagConstraints.HORIZONTAL
        }
    }

    private fun addField(panel: JPanel, gbc: GridBagConstraints, label: String, value: String) {
        gbc.gridx = 0
        gbc.weightx = 0.3
        panel.add(JBLabel(label).apply {
            font = LCATheme.headerFont
        }, gbc)

        gbc.gridx = 1
        gbc.weightx = 0.7
        panel.add(JBLabel(value).apply {
            font = LCATheme.monoFont
        }, gbc)
    }

    private fun formatNumber(number: Int?): String {
        return number?.let { String.format("%,d", it) } ?: "-"
    }

    private fun formatTimestamp(timestamp: Long?): String {
        return timestamp?.let { dateFormatter.format(Instant.ofEpochMilli(it)) } ?: "-"
    }

    private fun formatJson(json: String): String {
        return try {
            val gson = pl.jclab.refio.core.utils.GsonInstance.gson
            gson.toJson(gson.fromJson(json, Any::class.java))
        } catch (_: Exception) {
            json
        }
    }

    private fun saveToFile() {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save Step Details"
            selectedFile = File("step-$stepNumber-${subtask.id}.txt")
            fileFilter = FileNameExtensionFilter("Text Files", "txt")
        }

        if (fileChooser.showSaveDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                fileChooser.selectedFile.writeText(detailsProvider(subtask, stepNumber))
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to save file:\n${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
}


