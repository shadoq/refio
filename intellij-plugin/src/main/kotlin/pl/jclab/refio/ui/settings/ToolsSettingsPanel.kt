package pl.jclab.refio.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
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
        val toolsTableComponent = createToolsTable()
        val commandRulesComponent = createCommandRulesPanel()

        val form = settingsForm {
            group("Tool permissions") {
                row {
                    cell(toolsTableComponent).align(Align.FILL).resizableColumn()
                }.resizableRow()
                    .rowComment("Per-mode access: On runs without asking, Ask requires approval, Off hides the tool")
            }
            group("Terminal command rules") {
                row {
                    cell(commandRulesComponent).align(Align.FILL).resizableColumn()
                }.resizableRow()
                    .rowComment("First matching pattern decides: ALLOW runs, ASK prompts, BLOCK refuses")
            }
        }

        add(settingsScrollPane(form), BorderLayout.CENTER)

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

        // Minimums stay small enough that all four columns survive dock width; Description takes
        // whatever is left over when there is more room.
        toolsTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        toolsTable.fitColumns(
            ColumnWidth(min = 70, preferred = 150),
            ColumnWidth(min = 70, preferred = 260),
            ColumnWidth(min = 46, preferred = 66, max = 110),
            ColumnWidth(min = 46, preferred = 66, max = 110)
        )
        toolsTable.showFullValueOnHover()

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
            preferredSize = Dimension(0, JBUI.scale(250))
            minimumSize = Dimension(0, JBUI.scale(150))
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun createCommandRulesPanel(): JComponent {
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
        commandRulesTable.fitColumns(
            ColumnWidth(min = 80, preferred = 200),
            ColumnWidth(min = 56, preferred = 76, max = 110),
            ColumnWidth(min = 70, preferred = 170)
        )
        commandRulesTable.showFullValueOnHover()

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

        // Add/remove/reset live in the table's own toolbar, so they scale and theme with the IDE
        // instead of being a hand-placed button row.
        return ToolbarDecorator.createDecorator(commandRulesTable)
            .setAddAction {
                val model = commandRulesTable.model as DefaultTableModel
                model.addRow(arrayOf("^new_command(\\s+.*)?$", "ASK", "New rule"))
                commandRulesTable.changeSelection(model.rowCount - 1, 0, false, false)
            }
            .setRemoveAction {
                val model = commandRulesTable.model as DefaultTableModel
                commandRulesTable.selectedRows.sortedDescending().forEach { row ->
                    if (row in 0 until model.rowCount) model.removeRow(row)
                }
            }
            .disableUpDownActions()
            .addExtraAction(object : DumbAwareAction("Reset Defaults", null, AllIcons.Actions.Rollback) {
                override fun actionPerformed(e: AnActionEvent) {
                    populateCommandRulesTable(CommandRuleDefaults.DEFAULT_RULES)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            .createPanel()
            .apply { preferredSize = Dimension(0, JBUI.scale(260)) }
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
