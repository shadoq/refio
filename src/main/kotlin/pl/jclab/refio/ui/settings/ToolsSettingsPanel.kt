package pl.jclab.refio.ui.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.services.logging.dualLogger
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
    private val coreApiClient: CoreApiClient?
) : JBPanel<ToolsSettingsPanel>(BorderLayout()) {

    private lateinit var toolsTable: JBTable
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flaga blokująca auto-save podczas ładowania
    private var isLoadingPermissions = false

    // Definicje narzędzi (nazwa, opis, domyślne ustawienia)
    private val toolDefinitions = listOf(
        ToolDefinition("read_file", "Read file contents from the project", "On", "On"),
        ToolDefinition("read_directory", "List directory contents", "On", "On"),
        ToolDefinition("file_search", "Search for files by name or pattern", "On", "On"),
        ToolDefinition("grep_search", "Search for content within files", "On", "On"),
        ToolDefinition("view_diff", "View differences between file versions", "On", "On"),
        ToolDefinition("create_new_file", "Create a new file in the project", "Off", "On"),
        ToolDefinition("code_editing", "Edit existing file with simple search-replace", "Off", "On"),
        ToolDefinition("advance_code_editing", "Edit file using LLM-assisted generation", "Off", "On"),
        ToolDefinition("multi_line_editor", "Edit file parts using LLM-assisted generation", "Off", "On"),
        ToolDefinition("multi_edit", "Edit multiple files in a single operation", "Off", "On"),
        ToolDefinition("run_terminal_command", "Execute terminal commands", "Off", "On")
    )

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                "Tools"
            ),
            LCATheme.paddedBorder(16)
        )

        // Main content
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8, 0, 0, 0)
            add(createToolsTable(), BorderLayout.CENTER)
        }

        add(contentPanel, BorderLayout.CENTER)

        // Załaduj aktualne ustawienia z DB
        loadToolPermissions()
    }

    private fun createToolsTable(): JComponent {
        val columnNames = arrayOf("Tool Name", "Description", "Plan Mode", "Agent Mode")

        toolsTable = JBTable(object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 2 || column == 3 // Plan Mode and Agent Mode columns
            }

            override fun getColumnClass(column: Int): Class<*> {
                return String::class.java
            }
        }).apply {
            // Auto-save przy zmianie
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

        // Populate with initial data
        populateTable()

        // Set up Plan Mode column with combo box editor
        val planModeColumn = toolsTable.columnModel.getColumn(2)
        val planModeCombo = JComboBox(arrayOf("On", "Off"))
        planModeColumn.cellEditor = DefaultCellEditor(planModeCombo)

        // Set up Agent Mode column with combo box editor
        val agentModeColumn = toolsTable.columnModel.getColumn(3)
        val agentModeCombo = JComboBox(arrayOf("On", "Off"))
        agentModeColumn.cellEditor = DefaultCellEditor(agentModeCombo)

        // Set column widths
        toolsTable.columnModel.getColumn(0).preferredWidth = 180 // Tool Name
        toolsTable.columnModel.getColumn(1).preferredWidth = 320 // Description
        toolsTable.columnModel.getColumn(2).preferredWidth = 90  // Plan Mode
        toolsTable.columnModel.getColumn(3).preferredWidth = 90  // Agent Mode

        // Custom renderer for mode columns
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
                    "ask", "on" -> foreground = Color(0, 150, 0)
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
            preferredSize = Dimension(700, 350)
        }
    }

    private fun populateTable() {
        val tableModel = toolsTable.model as DefaultTableModel
        tableModel.rowCount = 0

        toolDefinitions.forEach { tool ->
            tableModel.addRow(arrayOf(
                tool.name,
                tool.description,
                tool.defaultPlanMode,
                tool.defaultAgentMode
            ))
        }
    }

    /**
     * Ładuje uprawnienia narzędzi z backendu
     */
    private fun loadToolPermissions() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading tool permissions from backend" }

                val permissions = coreApiClient.getToolPermissions()

                // applyPermissions already uses SwingUtilities.invokeLater internally
                applyPermissions(permissions)
            } catch (e: Exception) {
                logger.error(e) { "Failed to load tool permissions" }
            }
        }
    }

    /**
     * Aplikuje załadowane uprawnienia do tabeli
     */
    private fun applyPermissions(permissions: Map<String, Pair<String, String>>) {
        SwingUtilities.invokeLater {
            // Blokuj auto-save podczas ładowania
            isLoadingPermissions = true
            try {
                val tableModel = toolsTable.model as DefaultTableModel

                for (row in 0 until tableModel.rowCount) {
                    val toolName = tableModel.getValueAt(row, 0) as String
                    val (planMode, agentMode) = permissions[toolName] ?: continue

                    val normalizedPlanMode = normalizePermissionValue(planMode)
                    val normalizedAgentMode = normalizePermissionValue(agentMode)

                    tableModel.setValueAt(normalizedPlanMode, row, 2)
                    tableModel.setValueAt(normalizedAgentMode, row, 3)
                }
            } finally {
                // Zawsze odblokuj po zakończeniu
                isLoadingPermissions = false
            }
        }
    }

    /**
     * Callback wywoływany gdy zmieni się uprawnienie w tabeli
     */
    private fun onPermissionChanged(row: Int) {
        // Ignoruj zmiany podczas ładowania (aby uniknąć zapętlenia)
        if (isLoadingPermissions) {
            logger.debug { "Ignoring permission change during loading (row=$row)" }
            return
        }

        val tableModel = toolsTable.model as DefaultTableModel
        val toolName = tableModel.getValueAt(row, 0) as String
        val planMode = tableModel.getValueAt(row, 2) as String
        val agentMode = tableModel.getValueAt(row, 3) as String

        logger.debug { "Permission changed: $toolName -> plan=$planMode, agent=$agentMode" }

        // Auto-save do backendu
        coroutineScope.launch {
            try {
                coreApiClient?.setToolPermission(
                    toolName = toolName,
                    planMode = planMode.uppercase(),
                    agentMode = agentMode.uppercase()
                )

                logger.info { "Saved permission for $toolName" }

                // Powiadom SettingsView o zmianie
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

                    // Reload table to restore previous values
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
        loadToolPermissions()
    }

    private fun normalizePermissionValue(value: String?): String {
        val normalized = value?.trim()?.uppercase() ?: return "Off"
        return when (normalized) {
            "ON", "ASK" -> "On"
            "OFF" -> "Off"
            else -> {
                logger.warn { "Unknown permission value: $value, defaulting to Off" }
                "Off"
            }
        }
    }

    private data class ToolDefinition(
        val name: String,
        val description: String,
        val defaultPlanMode: String,
        val defaultAgentMode: String
    )
}
