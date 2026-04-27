package pl.jclab.refio.ui.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ToolDefinitionInfo
import pl.jclab.refio.core.tools.security.CommandRule
import pl.jclab.refio.core.tools.security.CommandRuleDefaults
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.event.TableModelEvent

private val logger = dualLogger("ToolsSettingsPanel")

/**
 * Tools Settings Panel
 * Zarządza uprawnieniami narzędzi z automatycznym zapisywaniem do DB
 */
class ToolsSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiRouter?
) : JBPanel<ToolsSettingsPanel>(BorderLayout()) {

    private lateinit var toolsTable: JBTable
    private lateinit var commandRulesTable: JBTable
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flaga blokująca auto-save podczas ładowania
    private var isLoadingPermissions = false

    init {
        border = LCATheme.createSettingsBorder("Tools")

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = LCATheme.paddedBorder(8, 0, 0, 0)
            add(createToolsTable())
            add(Box.createVerticalStrut(12))
            add(createCommandRulesPanel())
        }

        val scrollPane = com.intellij.ui.components.JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        loadToolDefinitions()
    }

    private fun createToolsTable(): JComponent {
        val columnNames = arrayOf("Tool Name", "Description", "Plan Mode", "Agent Mode")

        toolsTable = JBTable(object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 2 || column == 3
            }

            override fun getColumnClass(column: Int): Class<*> {
                return String::class.java
            }
        }).apply {
            model.addTableModelListener { event ->
                if (event.type == TableModelEvent.UPDATE) {
                    val row = event.firstRow
                    val column = event.column

                    if (column == 2 || column == 3) {
                        onPermissionChanged(row)
                    }
                }
            }
        }

        val planModeColumn = toolsTable.columnModel.getColumn(2)
        val planModeCombo = JComboBox(arrayOf("On", "Ask", "Off"))
        planModeColumn.cellEditor = DefaultCellEditor(planModeCombo)

        val agentModeColumn = toolsTable.columnModel.getColumn(3)
        val agentModeCombo = JComboBox(arrayOf("On", "Ask", "Off"))
        agentModeColumn.cellEditor = DefaultCellEditor(agentModeCombo)

        // Flexible layout: fix mode columns, let Description stretch.
        toolsTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        toolsTable.columnModel.getColumn(0).apply {
            minWidth = 120
            preferredWidth = 160
        }
        toolsTable.columnModel.getColumn(1).apply {
            minWidth = 160
            preferredWidth = 280
        }
        planModeColumn.apply {
            minWidth = 80
            maxWidth = 110
            preferredWidth = 90
        }
        agentModeColumn.apply {
            minWidth = 80
            maxWidth = 110
            preferredWidth = 90
        }

        val modeRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

                val displayValue = value?.toString()?.trim()?.lowercase()
                when (displayValue) {
                    "off" -> foreground = JBColor.RED
                    "ask" -> foreground = JBColor.ORANGE
                    "on" -> foreground = Color(0, 150, 0)
                }

                return component
            }
        }
        planModeColumn.cellRenderer = modeRenderer
        agentModeColumn.cellRenderer = modeRenderer

        toolsTable.setShowGrid(true)
        toolsTable.gridColor = JBColor.LIGHT_GRAY
        toolsTable.rowHeight = 28

        return JScrollPane(toolsTable).apply {
            border = LCATheme.customLineBorder(LCATheme.grayColor, 1)
            preferredSize = Dimension(0, 250)
            minimumSize = Dimension(0, 150)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun createCommandRulesPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createSettingsBorder("Terminal Command Rules")
            preferredSize = Dimension(0, 310)
        }

        val columns = arrayOf("Pattern (regex)", "Action", "Description")
        commandRulesTable = JBTable(object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }).apply {
            rowHeight = 26
            setShowGrid(true)
            gridColor = JBColor.LIGHT_GRAY
        }

        // Flexible layout: Action column fixed, Pattern and Description share the rest.
        commandRulesTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        commandRulesTable.columnModel.getColumn(0).apply {
            minWidth = 140
            preferredWidth = 220
        }
        commandRulesTable.columnModel.getColumn(1).apply {
            minWidth = 80
            maxWidth = 110
            preferredWidth = 90
        }
        commandRulesTable.columnModel.getColumn(2).apply {
            minWidth = 120
            preferredWidth = 180
        }

        val actionCombo = JComboBox(arrayOf("ALLOW", "BLOCK", "ASK"))
        commandRulesTable.columnModel.getColumn(1).cellEditor = DefaultCellEditor(actionCombo)

        val actionRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                when (value?.toString()?.uppercase()) {
                    "BLOCK" -> foreground = JBColor.RED
                    "ASK" -> foreground = JBColor.ORANGE
                    "ALLOW" -> foreground = Color(0, 150, 0)
                }
                return c
            }
        }
        commandRulesTable.columnModel.getColumn(1).cellRenderer = actionRenderer

        populateCommandRulesTable(CommandRuleDefaults.DEFAULT_RULES)

        val addButton = JButton("Add Rule").apply {
            addActionListener {
                val model = commandRulesTable.model as DefaultTableModel
                model.addRow(arrayOf("^new_command(\\s+.*)?$", "ASK", "New rule"))
                commandRulesTable.changeSelection(model.rowCount - 1, 0, false, false)
            }
        }
        val removeButton = JButton("Remove Selected").apply {
            addActionListener {
                val model = commandRulesTable.model as DefaultTableModel
                commandRulesTable.selectedRows.sortedDescending().forEach { row ->
                    if (row in 0 until model.rowCount) model.removeRow(row)
                }
            }
        }
        val resetButton = JButton("Reset Defaults").apply {
            addActionListener { populateCommandRulesTable(CommandRuleDefaults.DEFAULT_RULES) }
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(addButton)
            add(removeButton)
            add(resetButton)
        }

        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JScrollPane(commandRulesTable).apply {
                border = LCATheme.customLineBorder(LCATheme.grayColor, 1)
            }, BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        panel.add(centerPanel, BorderLayout.CENTER)

        return panel
    }

    private fun populateCommandRulesTable(rules: List<CommandRule>) {
        val model = commandRulesTable.model as DefaultTableModel
        model.rowCount = 0
        rules.forEach { rule ->
            model.addRow(arrayOf(rule.pattern, rule.action.name, rule.description))
        }
    }

    private fun loadToolDefinitions() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available – tools table will be empty" }
            return
        }

        coroutineScope.launch {
            try {
                val definitions = coreApiClient.toolRouter.getAvailableToolDefinitions()
                SwingUtilities.invokeLater {
                    populateTableFromBackend(definitions)
                    loadToolPermissions()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load tool definitions from backend" }
            }
        }
    }

    private fun populateTableFromBackend(definitions: List<ToolDefinitionInfo>) {
        val tableModel = toolsTable.model as DefaultTableModel
        tableModel.rowCount = 0

        definitions.forEach { tool ->
            tableModel.addRow(arrayOf(
                toDisplayToolName(tool.name),
                tool.description,
                normalizePermissionValue(tool.defaultPlanMode),
                normalizePermissionValue(tool.defaultAgentMode)
            ))
        }
    }

    private fun loadToolPermissions() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading tool permissions from backend" }

                val response = coreApiClient.toolRouter.getToolPermissions(null)
                val permissions = response.tools.associate { tool ->
                    tool.toolName to (tool.planMode to tool.agentMode)
                }

                applyPermissions(permissions)
            } catch (e: Exception) {
                logger.error(e) { "Failed to load tool permissions" }
            }
        }
    }

    private fun applyPermissions(permissions: Map<String, Pair<String, String>>) {
        SwingUtilities.invokeLater {
            isLoadingPermissions = true
            try {
                val tableModel = toolsTable.model as DefaultTableModel

                for (row in 0 until tableModel.rowCount) {
                    val displayToolName = tableModel.getValueAt(row, 0) as String
                    val toolName = toInternalToolName(displayToolName)
                    val (planMode, agentMode) = permissions[toolName] ?: continue

                    val normalizedPlanMode = normalizePermissionValue(planMode)
                    val normalizedAgentMode = normalizePermissionValue(agentMode)

                    tableModel.setValueAt(normalizedPlanMode, row, 2)
                    tableModel.setValueAt(normalizedAgentMode, row, 3)
                }
            } finally {
                isLoadingPermissions = false
            }
        }
    }

    private fun onPermissionChanged(row: Int) {
        if (isLoadingPermissions) {
            logger.debug { "Ignoring permission change during loading (row=$row)" }
            return
        }

        val tableModel = toolsTable.model as DefaultTableModel
        val displayToolName = tableModel.getValueAt(row, 0) as String
        val toolName = toInternalToolName(displayToolName)
        val planMode = tableModel.getValueAt(row, 2) as String
        val agentMode = tableModel.getValueAt(row, 3) as String

        logger.debug { "Permission changed: $toolName -> plan=$planMode, agent=$agentMode" }

        coroutineScope.launch {
            try {
                coreApiClient?.toolRouter?.setToolPermission(
                    toolName = toolName,
                    request = pl.jclab.refio.core.models.api.SetToolPermissionRequest(
                        planMode = planMode.uppercase(),
                        agentMode = agentMode.uppercase()
                    )
                )

                logger.info { "Saved permission for $toolName" }

                SwingUtilities.invokeLater {
                    onSettingChanged("tools", "permission_$toolName", "$planMode,$agentMode")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save permission for $toolName" }

                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ToolsSettingsPanel,
                        "Nie udało się zapisać ustawienia: ${e.message}",
                        "Błąd",
                        JOptionPane.ERROR_MESSAGE
                    )

                    loadToolPermissions()
                }
            }
        }
    }

    /**
     * Reload ustawień (wywoływane przy "Reset to Defaults")
     */
    fun reload() {
        logger.info { "Reloading tool permissions" }
        loadToolDefinitions()
    }

    private fun toDisplayToolName(internalToolName: String): String {
        return if (internalToolName == "invoke_subagent") "subagent" else internalToolName
    }

    private fun toInternalToolName(displayToolName: String): String {
        return if (displayToolName == "subagent") "invoke_subagent" else displayToolName
    }

    private fun normalizePermissionValue(value: String?): String {
        val normalized = value?.trim()?.uppercase() ?: return "Off"
        return when (normalized) {
            "ON" -> "On"
            "ASK" -> "Ask"
            "OFF" -> "Off"
            else -> {
                logger.warn { "Unknown permission value: $value, defaulting to Off" }
                "Off"
            }
        }
    }
}
