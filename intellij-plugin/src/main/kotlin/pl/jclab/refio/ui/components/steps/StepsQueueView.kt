package pl.jclab.refio.ui.components.steps

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.common.PromptDialog
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Steps Queue View - execution steps of the active session.
 *
 * A plan is shown as a summary header plus one row per step, so a seven-step run fits on screen
 * and the outcome (how many passed, how long it took, where the time went) is readable without
 * opening anything. Selecting a failed step reveals its error under the list; the full payload
 * stays behind the details dialog.
 */
class StepsQueueView(private val project: Project) : JBPanel<StepsQueueView>(BorderLayout()), Disposable {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val logger = dualLogger("StepsQueueView")

    private val planSummary = PlanSummaryPanel()
    private val listModel = CollectionListModel<StepRowView>()
    private val stepList: JBList<StepRowView>
    private val detailPanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(true, "refio.exec.split", 0.7f)

    private val subtasksById = mutableMapOf<String, SubtaskResponse>()

    // Execution control buttons (for enabling/disabling)
    private lateinit var replanBtn: JButton
    private lateinit var cancelAllBtn: JButton

    init {
        stepList = JBList(listModel).apply {
            cellRenderer = StepListRenderer()
            fixedCellHeight = JBUI.scale(26)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No steps planned"
            emptyText.appendSecondaryText(
                "Use Plan or Agent mode to create an execution plan",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                null
            )
        }

        stepList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showDetailFor(stepList.selectedValue)
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = stepList.selectedValue ?: return false
                openDetailsDialog(row)
                return true
            }
        }.installOn(stepList)

        PopupHandler.installPopupMenu(stepList, contextActions(), "Refio.Steps.Popup")

        detailPanel.apply {
            background = LCATheme.backgroundColor
            border = JBUI.Borders.empty(6, 8)
            isVisible = false
        }

        splitter.firstComponent = JBScrollPane(stepList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        splitter.secondComponent = null

        val header = JPanel(BorderLayout()).apply {
            add(planSummary, BorderLayout.NORTH)
            add(createExecutionToolbar(), BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        planSummary.update(emptyList())

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

        // Auto-refresh subtasks when the panel becomes visible in the hierarchy — switching
        // screens on the rail doesn't re-run init(), so without this the StateFlow shows whatever
        // was last cached from a prior session tick.
        addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent?) {
                if (sessionManager.activeSession.value == null) return
                cs.launch {
                    try {
                        sessionManager.refreshSubtasks()
                    } catch (e: Exception) {
                        logger.error(e) { "Auto-refresh on panel show failed" }
                    }
                }
            }
            override fun ancestorRemoved(event: AncestorEvent?) {}
            override fun ancestorMoved(event: AncestorEvent?) {}
        })

        // Auto-refresh on step state changes. AgentEventBus.ToolCalled fires when a tool
        // finishes (subtask PENDING → SUCCESS/FAILED) and TurnEnded covers the case where a
        // whole turn batch just completed. Pulling the fresh list into the StateFlow keeps the
        // view updated without waiting for the user to click Refresh.
        cs.launch {
            sessionManager.apiRouter.agentEventBus.events.collect { event ->
                val triggersRefresh = event is pl.jclab.refio.core.agents.events.AgentEvent.ToolCalled ||
                    event is pl.jclab.refio.core.agents.events.AgentEvent.TurnEnded
                if (!triggersRefresh) return@collect
                val activeSessionId = sessionManager.activeSession.value?.id ?: return@collect
                if (event.sessionId != activeSessionId) return@collect
                try {
                    sessionManager.refreshSubtasks()
                } catch (e: Exception) {
                    logger.debug { "Auto-refresh on tool event failed: ${e.message}" }
                }
            }
        }
    }

    private fun updateSteps(subtasks: List<SubtaskResponse>) {
        logger.debug { "updateSteps called with ${subtasks.size} subtasks" }

        SwingUtilities.invokeLater {
            val sorted = subtasks.sortedBy { it.orderIndex }

            subtasksById.clear()
            sorted.forEach { subtasksById[it.id] = it }

            val rows = sorted.mapIndexed { index, subtask -> StepRowView.from(subtask, index + 1) }

            val selectedId = stepList.selectedValue?.id
            listModel.replaceAll(rows)
            planSummary.update(rows)

            val restored = rows.indexOfFirst { it.id == selectedId }
            if (restored >= 0) {
                stepList.selectedIndex = restored
            } else {
                showDetailFor(null)
            }
        }
    }

    /**
     * A failed step explains itself under the list. `JBList` cannot host variable-height rows
     * sensibly, so the detail lives in the lower half of a splitter rather than inline.
     */
    private fun showDetailFor(row: StepRowView?) {
        val error = row?.takeIf { it.state == StepRowView.State.FAILED }?.errorMessage

        detailPanel.removeAll()

        if (row == null || error == null) {
            detailPanel.isVisible = false
            splitter.secondComponent = null
            return
        }

        val message = JBTextArea(error).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create(Font.MONOSPACED, 11)
            foreground = LCATheme.errorColor
            background = LCATheme.backgroundColor
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(HyperlinkLabel("Show details").apply {
                addHyperlinkListener { openDetailsDialog(row) }
            })
            add(HyperlinkLabel("Copy error").apply {
                addHyperlinkListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(error), null)
                }
            })
        }

        detailPanel.add(JBScrollPane(message).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        detailPanel.add(actions, BorderLayout.SOUTH)
        detailPanel.isVisible = true
        splitter.secondComponent = detailPanel
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun contextActions(): ActionGroup = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Approve Step", "Approve the selected step", AllIcons.Actions.Commit) {
            override fun actionPerformed(e: AnActionEvent) {
                val row = stepList.selectedValue ?: return
                cs.launch {
                    try {
                        sessionManager.approveSubtask(row.id)
                    } catch (ex: Exception) {
                        logger.error(ex) { "Failed to approve step" }
                    }
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = stepList.selectedValue?.canApprove == true
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        add(object : DumbAwareAction("Skip Step", "Skip the selected step", AllIcons.Actions.Forward) {
            override fun actionPerformed(e: AnActionEvent) {
                val row = stepList.selectedValue ?: return
                cs.launch {
                    try {
                        sessionManager.skipSubtask(row.id)
                    } catch (ex: Exception) {
                        logger.error(ex) { "Failed to skip step" }
                    }
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = stepList.selectedValue?.canSkip == true
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        addSeparator()

        add(object : DumbAwareAction("Show Details", "Open the full step payload", AllIcons.Actions.Preview) {
            override fun actionPerformed(e: AnActionEvent) {
                stepList.selectedValue?.let { openDetailsDialog(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = stepList.selectedValue != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })
    }

    private fun openDetailsDialog(row: StepRowView) {
        val subtask = subtasksById[row.id] ?: return
        StepDetailsDialog(project, subtask, row.number, this::buildStepDetailsText).show()
    }

    private fun buildStepDetailsText(subtask: SubtaskResponse, stepNumber: Int): String {
        val completedAt = subtask.completedAt ?: subtask.finishedAt
        return buildString {
            appendLine("Step $stepNumber")
            appendLine()
            appendLine("kind: ${subtask.kind}")
            appendLine("status: ${subtask.status}")
            appendLine("description: ${subtask.description}")
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
            appendLine("input_tokens: ${subtask.tokensIn}")
            appendLine("output_tokens: ${subtask.tokensOut}")
            appendLine("cost_usd: ${String.format("%.4f", subtask.costUsd)}")
            appendLine("latency_ms: ${subtask.latencyMs}")
            appendLine()
            appendLine("created_at: ${formatTimestamp(subtask.createdAt)}")
            appendLine("updated_at: ${formatTimestamp(subtask.updatedAt)}")
            appendLine("started_at: ${formatTimestamp(subtask.startedAt)}")
            appendLine("completed_at: ${formatTimestamp(completedAt)}")
            appendLine("finished_at: ${formatTimestamp(subtask.finishedAt)}")
        }
    }

    private fun prettyJsonOrRaw(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val parsed = com.google.gson.JsonParser.parseString(raw)
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed)
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return ""
        return timestampFormatter.format(Instant.ofEpochMilli(timestamp))
    }

    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /**
     * Toolbar above the list: refresh, re-plan, and the destructive clear kept apart on the right.
     */
    private fun createExecutionToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(LCATheme.borderColor)
        }
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)))

        val refreshBtn = JButton("Refresh", AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh steps list"
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

        replanBtn = JButton("Re-plan").apply {
            toolTipText = "Delete remaining steps and generate new plan"
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

        cancelAllBtn = JButton("Clear").apply {
            toolTipText = "Delete all remaining steps"
            foreground = LCATheme.redColor
            isEnabled = false
            addActionListener {
                logger.info { "Clear steps clicked" }
                val confirmed = MessageDialogBuilder
                    .yesNo("Confirm Deletion", "Delete all remaining steps?")
                    .asWarning()
                    .ask(this@StepsQueueView)

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
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(3)))
        rightPanel.add(cancelAllBtn)

        toolbar.add(panel, BorderLayout.WEST)
        toolbar.add(rightPanel, BorderLayout.EAST)

        return toolbar
    }

    override fun dispose() {
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
    private val subtask: SubtaskResponse,
    private val stepNumber: Int,
    private val detailsProvider: (SubtaskResponse, Int) -> String
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

        subtask.paramsJson?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createPayloadSection("Params JSON", it))
        }

        subtask.stepPlanJson?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createPayloadSection("Step Plan JSON", it))
        }

        contentPanel.add(createTextSection("Summary", subtask.summary ?: subtask.resultSummary))
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        contentPanel.add(createTextSection("Result", subtask.result))

        subtask.errorMessage?.takeIf { it.isNotBlank() }?.let {
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
            contentPanel.add(createTextSection("Error", it, isError = true))
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
            addField(this, gbc, "Cost (USD):", if (subtask.costUsd > 0.0) String.format("$%.6f", subtask.costUsd) else "-")
            gbc.gridy++
            addField(this, gbc, "Latency:", if (subtask.latencyMs > 0) "${formatNumber(subtask.latencyMs)} ms" else "-")
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


