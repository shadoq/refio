package pl.jclab.refio.ui.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.core.api.ToolDefinitionInfo
import pl.jclab.refio.core.tools.security.AllowedCommand
import pl.jclab.refio.core.tools.security.CommandWhitelistConfig
import pl.jclab.refio.core.tools.security.CommandWhitelistDefaults
import pl.jclab.refio.core.tools.security.WhitelistMode
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
    private lateinit var whitelistEnabledCheckbox: JCheckBox
    private lateinit var whitelistModeCombo: JComboBox<String>
    private lateinit var whitelistTable: JBTable
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flaga blokująca auto-save podczas ładowania
    private var isLoadingPermissions = false
    private var isLoadingWhitelist = false

    // Definicje narzędzi (nazwa, opis, domyślne ustawienia)
    private val toolDefinitions = listOf(
        ToolDefinition("read_file", "read_file", "Read file contents from the project", "On", "On"),
        ToolDefinition("read_directory", "read_directory", "List directory contents", "On", "On"),
        ToolDefinition("file_search", "file_search", "Search for files by name or pattern", "On", "On"),
        ToolDefinition("grep_search", "grep_search", "Search for content within files", "On", "On"),
        ToolDefinition("view_diff", "view_diff", "View differences between file versions", "On", "On"),
        ToolDefinition("create_new_file", "create_new_file", "Create a new file in the project", "Off", "On"),
        ToolDefinition("code_editing", "code_editing", "Edit existing file with simple search-replace", "Off", "On"),
        ToolDefinition("advance_code_editing", "advance_code_editing", "Edit file using LLM-assisted generation", "Off", "On"),
        ToolDefinition("multi_line_editor", "multi_line_editor", "Edit file parts using LLM-assisted generation", "Off", "On"),
        ToolDefinition("multi_edit", "multi_edit", "Edit multiple files in a single operation", "Off", "On"),
        ToolDefinition("run_terminal_command", "run_terminal_command", "Execute terminal commands", "Off", "On"),
        ToolDefinition("http_request", "http_request", "Make HTTP requests to external APIs (GET, POST, PUT, DELETE)", "Off", "On"),
        ToolDefinition("run_code", "run_code", "Execute inline code snippets (Python, JavaScript, Kotlin)", "Off", "Off"),
        ToolDefinition("invoke_subagent", "subagent", "Invoke a specialized subagent to solve part of the task", "On", "On")
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
            add(createTerminalWhitelistPanel(), BorderLayout.SOUTH)
        }

        add(contentPanel, BorderLayout.CENTER)

        // Załaduj aktualne ustawienia z DB
        loadToolDefinitions()
        loadTerminalWhitelist()
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

    private fun createTerminalWhitelistPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    LCATheme.customLineBorder(LCATheme.borderColor, 1),
                    "Terminal Command Whitelist"
                ),
                LCATheme.paddedBorder(8)
            )
            preferredSize = Dimension(700, 310)
        }

        whitelistEnabledCheckbox = JCheckBox("Enabled", true)
        whitelistModeCombo = JComboBox(arrayOf(
            WhitelistMode.WHITELIST_ONLY.name,
            WhitelistMode.WHITELIST_PLUS_DENY.name
        ))

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(whitelistEnabledCheckbox)
            add(JLabel("Mode:"))
            add(whitelistModeCombo)
        }

        val whitelistColumns = arrayOf(
            "Enabled",
            "Program",
            "Aliases (comma-separated)",
            "Allowed Subcommands",
            "Blocked Subcommands",
            "Blocked Flags",
            "Blocked Arg Patterns",
            "Max Args",
            "Confirm"
        )

        whitelistTable = JBTable(object : DefaultTableModel(whitelistColumns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true

            override fun getColumnClass(column: Int): Class<*> {
                return when (column) {
                    0, 8 -> Boolean::class.java
                    else -> String::class.java
                }
            }
        }).apply {
            rowHeight = 26
            setShowGrid(true)
            gridColor = JBColor.LIGHT_GRAY
        }

        whitelistTable.columnModel.getColumn(0).preferredWidth = 60
        whitelistTable.columnModel.getColumn(1).preferredWidth = 120
        whitelistTable.columnModel.getColumn(2).preferredWidth = 150
        whitelistTable.columnModel.getColumn(3).preferredWidth = 170
        whitelistTable.columnModel.getColumn(4).preferredWidth = 170
        whitelistTable.columnModel.getColumn(5).preferredWidth = 140
        whitelistTable.columnModel.getColumn(6).preferredWidth = 170
        whitelistTable.columnModel.getColumn(7).preferredWidth = 70
        whitelistTable.columnModel.getColumn(8).preferredWidth = 70

        populateWhitelistTable(CommandWhitelistDefaults.DEFAULT_COMMANDS)

        val addButton = JButton("Add Command").apply {
            addActionListener { addEmptyWhitelistRow() }
        }
        val removeButton = JButton("Remove Selected").apply {
            addActionListener { removeSelectedWhitelistRow() }
        }
        val saveButton = JButton("Save Whitelist").apply {
            addActionListener { saveTerminalWhitelist() }
        }
        val resetButton = JButton("Reset to Defaults").apply {
            addActionListener { resetTerminalWhitelistToDefaults() }
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(addButton)
            add(removeButton)
            add(saveButton)
            add(resetButton)
        }

        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(whitelistTable).apply {
                border = LCATheme.customLineBorder(LCATheme.grayColor, 1)
            }, BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        panel.add(centerPanel, BorderLayout.CENTER)

        return panel
    }

    private fun populateWhitelistTable(commands: List<AllowedCommand>) {
        val model = whitelistTable.model as DefaultTableModel
        model.rowCount = 0

        commands.forEach { command ->
            model.addRow(arrayOf(
                true,
                command.program,
                command.aliases.joinToString(", "),
                command.allowedSubcommands.joinToString(", "),
                command.blockedSubcommands.joinToString(", "),
                command.blockedFlags.joinToString(", "),
                command.blockedArgPatterns.joinToString(", "),
                command.maxArgs.toString(),
                command.requireConfirmation
            ))
        }
    }

    private fun addEmptyWhitelistRow() {
        val model = whitelistTable.model as DefaultTableModel
        model.addRow(arrayOf(true, "", "", "", "", "", "", "50", false))
        val row = model.rowCount - 1
        whitelistTable.changeSelection(row, 1, false, false)
    }

    private fun removeSelectedWhitelistRow() {
        val model = whitelistTable.model as DefaultTableModel
        val selectedRows = whitelistTable.selectedRows
        if (selectedRows.isEmpty()) {
            return
        }

        selectedRows.sortedDescending().forEach { row ->
            if (row in 0 until model.rowCount) {
                model.removeRow(row)
            }
        }
    }

    private fun populateTable() {
        val tableModel = toolsTable.model as DefaultTableModel
        tableModel.rowCount = 0

        toolDefinitions.forEach { tool ->
            tableModel.addRow(arrayOf(
                tool.displayName,
                tool.description,
                tool.defaultPlanMode,
                tool.defaultAgentMode
            ))
        }
    }

    /**
     * Ładuje uprawnienia narzędzi z backendu
     */
    private fun loadToolDefinitions() {
        if (coreApiClient == null) {
            populateTable()
            loadToolPermissions()
            return
        }

        coroutineScope.launch {
            try {
                val definitions = coreApiClient.getAvailableToolDefinitions()
                SwingUtilities.invokeLater {
                    if (definitions.isNotEmpty()) {
                        populateTableFromBackend(definitions)
                    } else {
                        populateTable()
                    }
                    loadToolPermissions()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load tool definitions from backend, using defaults" }
                SwingUtilities.invokeLater {
                    populateTable()
                    loadToolPermissions()
                }
            }
        }
    }

    private fun populateTableFromBackend(definitions: List<ToolDefinitionInfo>) {
        val tableModel = toolsTable.model as DefaultTableModel
        tableModel.rowCount = 0

        definitions.forEach { tool ->
            val defaultPlan = if (tool.defaultPlanMode.uppercase() == "ON") "On" else "Off"
            val defaultAgent = if (tool.defaultAgentMode.uppercase() == "ON") "On" else "Off"
            tableModel.addRow(arrayOf(toDisplayToolName(tool.name), tool.description, defaultPlan, defaultAgent))
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
                    val displayToolName = tableModel.getValueAt(row, 0) as String
                    val toolName = toInternalToolName(displayToolName)
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
    private fun loadTerminalWhitelist() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using terminal whitelist defaults" }
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading terminal whitelist from backend" }
                val config = coreApiClient.getTerminalWhitelistConfig()
                applyTerminalWhitelistConfig(config)
            } catch (e: Exception) {
                logger.error(e) { "Failed to load terminal whitelist" }
            }
        }
    }

    private fun applyTerminalWhitelistConfig(config: CommandWhitelistConfig) {
        SwingUtilities.invokeLater {
            isLoadingWhitelist = true
            try {
                whitelistEnabledCheckbox.isSelected = config.enabled
                whitelistModeCombo.selectedItem = config.mode.name
                populateWhitelistTable(config.allowedCommands)
            } finally {
                isLoadingWhitelist = false
            }
        }
    }

    private fun saveTerminalWhitelist() {
        if (isLoadingWhitelist) return
        if (coreApiClient == null) {
            JOptionPane.showMessageDialog(this, "CoreApiClient is not available", "Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        val config = try {
            buildTerminalWhitelistConfigFromUi()
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(
                this,
                e.message,
                "Invalid whitelist configuration",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        coroutineScope.launch {
            try {
                coreApiClient.setTerminalWhitelistConfig(config, "app")
                logger.info { "Terminal whitelist saved (${config.allowedCommands.size} commands)" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save terminal whitelist" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ToolsSettingsPanel,
                        "Nie udaĹ‚o siÄ™ zapisaÄ‡ whitelisty: ${e.message}",
                        "BĹ‚Ä…d",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun resetTerminalWhitelistToDefaults() {
        whitelistEnabledCheckbox.isSelected = true
        whitelistModeCombo.selectedItem = WhitelistMode.WHITELIST_ONLY.name
        populateWhitelistTable(CommandWhitelistDefaults.DEFAULT_COMMANDS)
        saveTerminalWhitelist()
    }

    private fun buildTerminalWhitelistConfigFromUi(): CommandWhitelistConfig {
        val modeName = whitelistModeCombo.selectedItem?.toString()
            ?: throw IllegalArgumentException("Whitelist mode is required")
        val mode = try {
            WhitelistMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown whitelist mode: $modeName")
        }

        return CommandWhitelistConfig(
            enabled = whitelistEnabledCheckbox.isSelected,
            mode = mode,
            allowedCommands = collectWhitelistCommandsFromTable(),
            globalBlockedPatterns = CommandWhitelistDefaults.DEFAULT_BLOCKED_PATTERNS
        )
    }

    private fun collectWhitelistCommandsFromTable(): List<AllowedCommand> {
        val model = whitelistTable.model as DefaultTableModel
        val commands = mutableListOf<AllowedCommand>()

        for (row in 0 until model.rowCount) {
            val enabled = (model.getValueAt(row, 0) as? Boolean) ?: false
            if (!enabled) continue

            val program = model.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (program.isBlank()) {
                throw IllegalArgumentException("Program is required for enabled row ${row + 1}")
            }

            val maxArgsRaw = model.getValueAt(row, 7)?.toString()?.trim().orEmpty()
            val maxArgs = if (maxArgsRaw.isBlank()) {
                50
            } else {
                maxArgsRaw.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid maxArgs in row ${row + 1}: '$maxArgsRaw'")
            }

            val requireConfirmation = (model.getValueAt(row, 8) as? Boolean) ?: false

            commands += AllowedCommand(
                program = program,
                aliases = parseCsvList(model.getValueAt(row, 2)?.toString()),
                allowedSubcommands = parseCsvList(model.getValueAt(row, 3)?.toString()),
                blockedSubcommands = parseCsvList(model.getValueAt(row, 4)?.toString()),
                blockedFlags = parseCsvList(model.getValueAt(row, 5)?.toString()),
                blockedArgPatterns = parseCsvList(model.getValueAt(row, 6)?.toString()),
                maxArgs = maxArgs,
                requireConfirmation = requireConfirmation
            )
        }

        return commands
    }

    private fun parseCsvList(raw: String?): List<String> {
        return raw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun onPermissionChanged(row: Int) {
        // Ignoruj zmiany podczas ładowania (aby uniknąć zapętlenia)
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
        loadToolDefinitions()
        loadTerminalWhitelist()
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
        val displayName: String,
        val description: String,
        val defaultPlanMode: String,
        val defaultAgentMode: String
    )
}
